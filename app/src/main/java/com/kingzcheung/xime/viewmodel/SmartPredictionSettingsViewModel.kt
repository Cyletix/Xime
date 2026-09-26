package com.kingzcheung.xime.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.association.AssociationManager
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.model.ModelRuntime
import com.kingzcheung.xime.model.ModelStorage
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SmartPredictionUiState(
    val isEnabled: Boolean = false,
    val isInitialized: Boolean = false,
    val cacheSize: Int = 0,
    val isSaving: Boolean = false,
    val isLoading: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadStatus: String = "",
    val modelRepo: String = "",
    val hasModel: Boolean = false,
    val showRepoDialog: Boolean = false,
    val tempRepo: String = "",
    val toastMessage: String? = null
)

class SmartPredictionSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    
    private val _uiState = MutableStateFlow(SmartPredictionUiState(
        isEnabled = SettingsPreferences.isSmartPredictionEnabled(context),
        isInitialized = AssociationManager.isInitialized(),
        modelRepo = SettingsPreferences.getPredictionModelRepo(context)
    ))
    val uiState: StateFlow<SmartPredictionUiState> = _uiState.asStateFlow()
    
    init {
        checkModelState()
        loadCacheSize()
        validateModelState()
        loadRemoteModels()
        viewModelScope.launch {
            ModelManager.installedRevision.collect { checkModelState() }
        }
        viewModelScope.launch {
            ModelManager.downloadStates.collect { states ->
                val state = states[SettingsPreferences.getPredictionSelectedModel(context)]
                val progress = state as? com.kingzcheung.xime.model.ModelDownloadState.Downloading
                _uiState.update { it.copy(isDownloading = progress != null,
                    downloadProgress = progress?.progress ?: 0f,
                    downloadStatus = (state as? com.kingzcheung.xime.model.ModelDownloadState.Error)?.message.orEmpty()) }
            }
        }
    }

    private fun loadRemoteModels() {
        viewModelScope.launch {
            ModelManager.loadFromRemote(context)
        }
    }
    
    private fun checkModelState() {
        val modelId = SettingsPreferences.getPredictionSelectedModel(context)
        val hasModel = ModelManager.isModelReady(context, modelId)
        _uiState.update { it.copy(hasModel = hasModel) }
    }
    
    private fun loadCacheSize() {
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) {
                AssociationManager.getCacheSize()
            }
            _uiState.update { it.copy(cacheSize = size) }
        }
    }
    
    private fun validateModelState() {
        if (_uiState.value.isEnabled && !_uiState.value.hasModel) {
            _uiState.update { it.copy(isEnabled = false) }
            SettingsPreferences.setSmartPredictionEnabled(context, false)
            _uiState.update { it.copy(toastMessage = "模型文件不存在，已自动关闭智能联想") }
        }
    }
    
    fun selectModel(id: String) {
        SettingsPreferences.setPredictionSelectedModel(context, id)
        checkModelState()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AssociationManager.release() }
            _uiState.update { it.copy(isInitialized = false) }
            if (_uiState.value.isEnabled && _uiState.value.hasModel) loadModel()
        }
    }

    fun setEnabled(enabled: Boolean) {
        checkModelState()
        if (enabled && !_uiState.value.hasModel) {
            _uiState.update { it.copy(toastMessage = "请先下载模型文件") }
            return
        }
        
        SettingsPreferences.setSmartPredictionEnabled(context, enabled)
        _uiState.update { it.copy(isEnabled = enabled) }
        
        if (enabled && !_uiState.value.isInitialized && _uiState.value.hasModel) {
            loadModel()
            if (_uiState.value.isInitialized) {
                ModelRuntime.keepWarm("predictive_text")
            }
        } else if (!enabled && _uiState.value.isInitialized) {
            ModelRuntime.releaseWarm("predictive_text")
            releaseModel()
        }
    }
    
    private fun loadModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val success = withContext(Dispatchers.IO) {
                AssociationManager.initialize(context)
            }
            
            _uiState.update { it.copy(
                isInitialized = success,
                isLoading = false
            )}
            
            if (!success) {
                _uiState.update { it.copy(toastMessage = "模型加载失败，请检查模型文件") }
            }
        }
    }
    
    private fun releaseModel() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                AssociationManager.release()
            }
            _uiState.update { it.copy(isInitialized = false) }
        }
    }
    
    fun saveUserData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            
            withContext(Dispatchers.IO) {
                AssociationManager.saveUserData()
            }
            
            _uiState.update { it.copy(isSaving = false) }
        }
    }
    
    fun refreshCacheSize() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                AssociationManager.saveUserData()
            }
            val size = AssociationManager.getCacheSize()
            _uiState.update { it.copy(cacheSize = size) }
        }
    }
    
    fun downloadModelFiles() {
        val id = SettingsPreferences.getPredictionSelectedModel(context)
        val base = _uiState.value.modelRepo.trimEnd('/')
        val prefix = if (base.contains("modelscope.cn")) "$base/resolve/master" else base
        val model = ModelManager.getModel(id) ?: com.kingzcheung.xime.model.ModelInfo(
            id, "智能联想", "", com.kingzcheung.xime.model.ModelCategory.PREDICTION,
            versions = listOf(com.kingzcheung.xime.model.ModelVersion(version = "custom",
                files = listOf("vocab.json", "model_int8_dynamic.onnx").map {
                    com.kingzcheung.xime.model.ModelFile(it, "$prefix/$it")
                })))
        ModelManager.downloadModelInBackground(context, model)
    }

    fun deleteModel() {
        val modelId = SettingsPreferences.getPredictionSelectedModel(context)
        val modelDir = ModelStorage.getModelDir(context, modelId)
        val vocabFile = modelDir.resolve("vocab.json")
        val modelFile = modelDir.resolve("model_int8_dynamic.onnx")
        if (ModelManager.getModel(modelId) != null) ModelManager.deleteModel(context, modelId)
        else { vocabFile.delete(); modelFile.delete(); ModelManager.notifyInstalledModelsChanged() }
        
        SettingsPreferences.setSmartPredictionEnabled(context, false)
        
        viewModelScope.launch {
            if (_uiState.value.isInitialized) {
                withContext(Dispatchers.IO) {
                    AssociationManager.release()
                }
            }
            
            _uiState.update { it.copy(
                hasModel = false,
                isEnabled = false,
                isInitialized = false,
                toastMessage = "模型已删除"
            )}
        }
    }
    
    fun showRepoDialog() {
        _uiState.update { it.copy(
            showRepoDialog = true,
            tempRepo = it.modelRepo
        )}
    }
    
    fun hideRepoDialog() {
        _uiState.update { it.copy(showRepoDialog = false) }
    }
    
    fun setTempRepo(repo: String) {
        _uiState.update { it.copy(tempRepo = repo) }
    }
    
    fun saveRepo() {
        SettingsPreferences.setPredictionModelRepo(context, _uiState.value.tempRepo)
        _uiState.update { it.copy(
            modelRepo = it.tempRepo,
            showRepoDialog = false
        )}
    }
    
    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
    
    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}