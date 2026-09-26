package com.kingzcheung.xime.settings

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundledRimeSyncTest {
    @get:Rule val folder = TemporaryFolder()
    private fun manifest(text: String, name: String = "core.dict.yaml"): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$hash\t${text.toByteArray().size}\tbundled/$name\t$name\n"
    }
    @Test fun replacesSameSizeOldDictionaryBacksUpAndInvalidatesOnlyCompiledCache() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        File(target, "core.dict.yaml").writeText("old")
        File(target, "build").mkdir(); File(target, "build/core.table.bin").writeText("cache")
        listOf("personal.dict.yaml", "core.custom.yaml", "core.userdb").forEach { File(target, it).writeText("personal") }
        assertTrue(BundledRimeSync.install(target, backups, manifest("new")) { "new".byteInputStream() })
        assertEquals("new", File(target, "core.dict.yaml").readText())
        assertEquals("old", backups.walkTopDown().single { it.isFile }.readText())
        assertFalse(File(target, "build").exists())
        listOf("personal.dict.yaml", "core.custom.yaml", "core.userdb").forEach { assertEquals("personal", File(target, it).readText()) }
        assertFalse(BundledRimeSync.install(target, backups, manifest("new")) { error("Must not reopen large assets") })
    }
    @Test fun interruptedUpgradeRetriesWithoutOverwritingOriginalBackup() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        File(target, "a.schema.yaml").writeText("old")
        val list = manifest("one", "a.schema.yaml") + manifest("two", "b.dict.yaml")
        try { BundledRimeSync.install(target, backups, list) { if (it.endsWith("b.dict.yaml")) error("interruption") else "one".byteInputStream() }; fail() } catch (_: IllegalStateException) { }
        assertFalse(File(target, ".cyime-bundled-revision").exists())
        assertTrue(BundledRimeSync.install(target, backups, list) { if (it.endsWith("b.dict.yaml")) "two".byteInputStream() else error("already updated") })
        assertEquals("old", backups.walkTopDown().single { it.isFile }.readText())
    }
    @Test fun corruptAssetDoesNotReplaceExistingDictionaryOrCache() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        File(target, "core.dict.yaml").writeText("old"); File(target, "build").mkdir()
        try { BundledRimeSync.install(target, backups, manifest("new")) { "bad".byteInputStream() }; fail() } catch (_: IllegalStateException) { }
        assertEquals("old", File(target, "core.dict.yaml").readText()); assertTrue(File(target, "build").exists())
    }
    @Test fun missingFileRepairedEvenWithCurrentRevision() {
        val target = folder.newFolder("rime"); val backups = folder.newFolder("backup")
        BundledRimeSync.install(target, backups, manifest("new")) { "new".byteInputStream() }
        File(target, "core.dict.yaml").delete()
        assertTrue(BundledRimeSync.install(target, backups, manifest("new")) { "new".byteInputStream() })
    }
}
