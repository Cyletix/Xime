package com.kingzcheung.xime.model

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal fun predictionModelFilesReady(directory: File): Boolean =
    listOf("vocab.json", "model_int8_dynamic.onnx").all { name ->
        File(directory, name).let { it.isFile && it.length() > 0 }
    }

internal fun findModelFile(directory: File, version: ModelVersion, name: String): File {
    val direct = modelDownloadFile(directory, name)
    return if (direct.isFile || version.archiveUrl == null) direct else directory.walkTopDown()
        .firstOrNull { it.isFile && it.name == name } ?: direct
}

internal fun hasDownloadedModelFiles(directory: File, version: ModelVersion): Boolean =
    version.files.isNotEmpty() && version.files.all { info ->
        val file = findModelFile(directory, version, info.name)
        file.isFile && file.length() > 0 && (info.sizeBytes <= 0 || file.length() == info.sizeBytes)
    }

/** 优先按记录的市场版本检查，兼容早期尚未保存版本号的完整模型。 */
internal fun installedModelVersion(directory: File, model: ModelInfo, installedVersion: String?): ModelVersion? {
    val recorded = model.versions.firstOrNull { it.version == installedVersion }
    if (recorded != null && hasDownloadedModelFiles(directory, recorded)) return recorded
    return model.versions.firstOrNull { hasDownloadedModelFiles(directory, it) }
}

/** 只串行最终安装与删除，网络下载期间不占用此锁。 */
internal class ModelInstallGuard {
    private var deletionGeneration = 0L
    @Volatile var completionRevision = 0L
        private set

    @Synchronized fun currentGeneration(): Long = deletionGeneration

    @Synchronized fun install(
        generation: Long, staging: File, destination: File,
        isStillRequested: () -> Boolean = { true }, onInstalled: () -> Unit,
    ): Boolean {
        if (generation != deletionGeneration || !isStillRequested()) return false
        installDownloadedModel(staging, destination)
        completionRevision++
        onInstalled()
        return true
    }

    @Synchronized fun delete(action: () -> Boolean): Boolean {
        deletionGeneration++
        return action()
    }
}

internal fun modelDownloadFile(directory: File, name: String): File {
    val root = directory.canonicalFile
    val file = File(root, name).canonicalFile
    if (name.isBlank() || name.startsWith('/') || name.startsWith('\\') ||
        Regex("^[A-Za-z]:").containsMatchIn(name) || file == root || !file.toPath().startsWith(root.toPath())) {
        throw IOException("模型文件路径超出下载目录")
    }
    return file
}

internal fun verifyModelDigest(file: File, expected: String) {
    if (expected.isBlank()) return // 兼容尚未提供校验值的原版市场条目。
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        var length = input.read(buffer)
        while (length >= 0) {
            if (length > 0) digest.update(buffer, 0, length)
            length = input.read(buffer)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (!actual.equals(expected, ignoreCase = true)) throw IOException("模型校验失败：${file.name}")
}

internal fun validateDownloadedModel(directory: File, version: ModelVersion) {
    if (version.files.isEmpty()) throw IOException("模型没有文件清单")
    version.files.forEach { info ->
        val direct = modelDownloadFile(directory, info.name)
        val file = if (direct.isFile) direct else directory.walkTopDown()
            .firstOrNull { it.isFile && it.name == info.name } ?: direct
        if (!file.isFile || file.length() == 0L || (info.sizeBytes > 0 && file.length() != info.sizeBytes)) {
            throw IOException("模型文件不完整：${info.name}")
        }
        verifyModelDigest(file, info.sha256)
    }
}

/** 完整下载并校验后才替换，网络失败不会覆盖用户正在使用的市场模型。 */
internal fun installDownloadedModel(staging: File, destination: File) {
    destination.parentFile?.mkdirs()
    val backup = File(destination.parentFile, ".${destination.name}-${UUID.randomUUID()}.previous")
    val hadPrevious = destination.exists()
    if (hadPrevious && !destination.renameTo(backup)) throw IOException("无法保留原模型")
    if (!staging.renameTo(destination)) {
        if (hadPrevious && !backup.renameTo(destination)) {
            throw IOException("安装失败，原模型保留在 ${backup.name}")
        }
        throw IOException("无法安装模型")
    }
    if (hadPrevious) backup.deleteRecursively()
}
