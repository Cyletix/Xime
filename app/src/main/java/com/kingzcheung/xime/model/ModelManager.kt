package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import com.kingzcheung.xime.settings.MarketVersionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

object ModelManager {

    private const val TAG = "ModelManager"
    private val downloads = ModelDownloadQueue(kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO))
    private val _installedRevision = MutableStateFlow(0L)
    val installedRevision: StateFlow<Long> = _installedRevision
    fun notifyInstalledModelsChanged() { _installedRevision.update { it + 1 } }

    /** Same readiness contract as the prediction loader, including installs before index loading. */
    fun isModelReady(context: Context, id: String): Boolean {
        ModelStorage.migrateLegacyForModel(context, id)
        if (id.startsWith("predictive-text")) {
            return predictionModelFilesReady(ModelStorage.getModelDir(context, id)) &&
                (getModel(id)?.let { isModelDownloaded(context, it) } ?: true)
        }
        return isModelDownloaded(context, id)
    }

    private var initialized = false
    private val _modelsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<ModelInfo>>(BuiltinModelCatalog.models)
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    private val installGuards = ConcurrentHashMap<String, ModelInstallGuard>()
    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates

    /** 随应用发布、离线可用的模型清单。 */
    val modelsFlow: kotlinx.coroutines.flow.StateFlow<List<ModelInfo>> = _modelsFlow

    fun initialize() {
        if (initialized) return
        initialized = true
        FileLogger.i(TAG, "ModelManager initialized")
    }

    /** 不可变快照，避免下载页刷新期间修改正在遍历的清单。 */
    private fun allModels(): List<ModelInfo> = _modelsFlow.value

    fun getAllModels(): List<ModelInfo> = allModels()

    fun getModel(id: String): ModelInfo? = allModels().find { it.id == id }

    fun getModelsByCategory(category: ModelCategory): List<ModelInfo> =
        allModels().filter { it.category == category }

    // Kept for existing callers; the built-in catalog is available offline from process start.
    suspend fun loadFromRemote(context: Context) {
        _modelsFlow.value = BuiltinModelCatalog.models
    }


    fun isModelDownloaded(context: Context, id: String): Boolean {
        val model = getModel(id) ?: return false
        return isModelDownloaded(context, model)
    }

    fun isModelDownloaded(context: Context, model: ModelInfo): Boolean {
        // 检测前先尝试迁移旧路径模型到统一目录，保证已下载的旧版可用
        ModelStorage.migrateLegacyForModel(context, model.id)
        val dir = getModelStorageDir(context, model) ?: return false
        if (!dir.exists()) return false

        return installedModelVersion(dir, model, MarketVersionStore.getModelVersion(context, model.id)) != null &&
            (model.category != ModelCategory.PREDICTION || predictionModelFilesReady(dir))
    }

    fun getModelStorageDir(context: Context, model: ModelInfo): File? {
        // 统一规则：所有模型一律存 filesDir/models/<id>/
        return ModelStorage.getModelDir(context, model.id)
    }

    fun getModelStorageDir(context: Context, id: String): File? {
        val model = getModel(id) ?: return null
        return getModelStorageDir(context, model)
    }

    fun getModelSizeOnDisk(context: Context, id: String): Long {
        val model = getModel(id) ?: return 0
        val dir = getModelStorageDir(context, model) ?: return 0
        if (!dir.exists()) return 0

        val version = installedModelVersion(dir, model, MarketVersionStore.getModelVersion(context, model.id)) ?: return 0
        return version.files.sumOf { fileInfo ->
            val file = findModelFile(dir, version, fileInfo.name)
            if (file.exists()) file.length() else 0
        }
    }

    fun getDownloadedModels(context: Context): List<ModelInfo> {
        return allModels().filter { isModelDownloaded(context, it) }
    }

    suspend fun downloadModel(
        context: Context,
        id: String,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val model = getModel(id)
        if (model == null) {
            onProgress(ModelDownloadState.Error("未知模型: $id"))
            return
        }
        downloadModel(context, model, onProgress)
    }

    fun downloadModelInBackground(context: Context, model: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit = {}, version: ModelVersion? = null,
        onlyIfDefaultPending: Boolean = false): kotlinx.coroutines.Deferred<Unit> {
        val app = context.applicationContext
        val key = "${model.id}:${(version ?: model.resolvedVersion())?.version.orEmpty()}"
        return downloads.start(key) { performDownload(app, model, onProgress, version, onlyIfDefaultPending) }
    }

    suspend fun downloadModel(context: Context, model: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit, version: ModelVersion? = null,
        onlyIfDefaultPending: Boolean = false) {
        downloadModelInBackground(context, model, onProgress, version, onlyIfDefaultPending).await()
    }

    private suspend fun performDownload(
        context: Context,
        model: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit,
        version: ModelVersion?,
        onlyIfDefaultPending: Boolean,
    ) {
        val guard = installGuards.getOrPut(model.id) { ModelInstallGuard() }
        val generation = guard.currentGeneration()
        val completionBeforeRequest = guard.completionRevision
        // 自动准备与市场点击同一模型时串行；仅合并排队期间已完成的同版本下载。
        downloadLocks.getOrPut(model.id) { Mutex() }.withLock {
            if (onlyIfDefaultPending && DefaultModelInstaller.isHandled(context, model.id)) {
                onProgress(ModelDownloadState.Idle)
                return@withLock
            }
            val target = version ?: model.resolvedVersion()
            val installed = MarketVersionStore.getModelVersion(context, model.id)
            if (guard.completionRevision != completionBeforeRequest && target != null &&
                installed == target.version && hasDownloadedModelFiles(ModelStorage.getModelDir(context, model.id), target)) {
                _downloadStates.update { it + (model.id to ModelDownloadState.Complete) }
                onProgress(ModelDownloadState.Complete)
                return@withLock
            }
            val starting = ModelDownloadState.Downloading(0f, 0, -1)
            _downloadStates.update { it + (model.id to starting) }
            onProgress(starting)
            try {
                ModelDownloader.downloadModel(context, model, { state ->
                    _downloadStates.update { it + (model.id to state) }
                    if (state is ModelDownloadState.Complete) notifyInstalledModelsChanged()
                    onProgress(state)
                }, version, install = { staging, destination ->
                    guard.install(generation, staging, destination,
                        isStillRequested = { !onlyIfDefaultPending || !DefaultModelInstaller.isHandled(context, model.id) }) {
                        if (target != null) MarketVersionStore.setModelVersion(context, model.id, target.version)
                    }
                })
            } catch (cancelled: CancellationException) {
                _downloadStates.update { states ->
                    if (states[model.id] is ModelDownloadState.Downloading)
                        states + (model.id to ModelDownloadState.Idle) else states
                }
                throw cancelled
            }
        }
    }

    fun deleteModel(context: Context, id: String): Boolean {
        val model = getModel(id) ?: return false
        return deleteModel(context, model)
    }

    fun deleteModel(context: Context, model: ModelInfo): Boolean {
        val guard = installGuards.getOrPut(model.id) { ModelInstallGuard() }
        return guard.delete {
            val dir = getModelStorageDir(context, model) ?: return@delete false
            val version = installedModelVersion(dir, model, MarketVersionStore.getModelVersion(context, model.id))
                ?: model.resolvedVersion()
            var success = true
            for (fileInfo in version?.files.orEmpty()) {
                val file = findModelFile(dir, version!!, fileInfo.name)
                if (file.exists() && !file.delete()) success = false
            }
            if (success) {
                MarketVersionStore.removeModelVersion(context, model.id)
                DefaultModelInstaller.markHandled(context, model.id)
                notifyInstalledModelsChanged()
                _downloadStates.update { it + (model.id to ModelDownloadState.Idle) }
            }
            success
        }
    }
}
