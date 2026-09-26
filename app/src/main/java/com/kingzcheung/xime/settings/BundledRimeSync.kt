package com.kingzcheung.xime.settings

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Versioned app-owned assets only; personal dictionaries and user databases are never enumerated. */
internal object BundledRimeSync {
    private fun hash(input: InputStream): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    fun install(target: File, backups: File, manifest: String, open: (String) -> InputStream): Boolean {
        val revision = hash(manifest.byteInputStream())
        val stamp = File(target, ".cyime-bundled-revision")
        val entries = manifest.lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.toList()
        fun destination(relative: String): File {
            val result = File(target, relative).canonicalFile
            require(result.path.startsWith(target.canonicalPath + File.separator))
            require(!relative.endsWith(".custom.yaml") && !relative.contains("userdb"))
            return result
        }
        // No large dictionary hashing on ordinary starts. A new bundle revision always checks content.
        if (stamp.isFile && stamp.readText() == revision && entries.all {
                val file = destination(it[3]); file.isFile && file.length() == it[1].toLong()
            }) return false
        var changed = false
        for (entry in entries) {
            require(entry.size == 4)
            val (expected, size, asset, relative) = entry
            val file = destination(relative)
            if (file.isFile && file.length() == size.toLong() && hash(file.inputStream()) == expected) continue
            file.parentFile?.mkdirs()
            val pending = File(file.parentFile, "${file.name}.cyime-installing")
            try {
                open(asset).use { input -> pending.outputStream().use { input.copyTo(it) } }
                check(pending.length() == size.toLong() && hash(pending.inputStream()) == expected) { "Bundled asset checksum mismatch: $asset" }
                if (file.exists()) {
                    val backup = File(backups, "$revision/$relative")
                    require(backup.canonicalPath.startsWith(backups.canonicalPath + File.separator))
                    if (!backup.exists()) { backup.parentFile?.mkdirs(); file.copyTo(backup) }
                }
                // Invalidate before replacement, so interrupted upgrades cannot reuse compiled old tables.
                stamp.delete()
                val build = File(target, "build").canonicalFile
                require(build.path == target.canonicalPath + File.separator + "build")
                if (!changed && build.exists()) check(build.deleteRecursively()) { "Cannot invalidate Rime build cache" }
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                changed = true
            } finally { pending.delete() }
        }
        target.mkdirs()
        val pendingStamp = File(target, ".cyime-bundled-revision.tmp")
        pendingStamp.writeText(revision)
        Files.move(pendingStamp.toPath(), stamp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return changed
    }
}
