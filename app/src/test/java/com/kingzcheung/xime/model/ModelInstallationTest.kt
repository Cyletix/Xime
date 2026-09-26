package com.kingzcheung.xime.model

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelInstallationTest {
    @Test fun predictionNeedsBothNonemptyRuntimeFiles() {
        val dir = java.nio.file.Files.createTempDirectory("prediction-ready").toFile()
        try {
            File(dir, "vocab.json").writeText("{}")
            assertFalse(predictionModelFilesReady(dir))
            File(dir, "model_int8_dynamic.onnx").writeBytes(byteArrayOf())
            assertFalse(predictionModelFilesReady(dir))
            File(dir, "model_int8_dynamic.onnx").writeText("model")
            assertTrue(predictionModelFilesReady(dir))
        } finally { dir.deleteRecursively() }
    }

    @get:Rule val temporary = TemporaryFolder()

    @Test fun incompleteDownloadDoesNotReplaceInstalledModel() {
        val installed = temporary.newFolder("installed")
        File(installed, "weights.onnx").writeText("old model")
        val staging = temporary.newFolder("staging")
        File(staging, "weights.onnx").writeText("partial")
        assertThrows(IOException::class.java) {
            validateDownloadedModel(staging, ModelVersion(files = listOf(ModelFile("weights.onnx", "", sizeBytes = 99))))
        }
        assertEquals("old model", File(installed, "weights.onnx").readText())
    }

    @Test fun verifiedDownloadReplacesTheWholeModelTogether() {
        val installed = temporary.newFolder("installed")
        File(installed, "old.onnx").writeText("old")
        val staging = temporary.newFolder("staging")
        File(staging, "weights.onnx").writeText("abc")
        validateDownloadedModel(staging, ModelVersion(files = listOf(ModelFile("weights.onnx", "",
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sizeBytes = 3))))
        installDownloadedModel(staging, installed)
        assertEquals("abc", File(installed, "weights.onnx").readText())
        assertFalse(File(installed, "old.onnx").exists())
        assertFalse(staging.exists())
    }

    @Test fun incorrectMarketDigestIsRejected() {
        val file = temporary.newFile().apply { writeText("bad model") }
        assertThrows(IOException::class.java) { verifyModelDigest(file, "0".repeat(64)) }
    }

    @Test fun archivePathsCannotEscapeDestinationOnEitherPlatform() {
        val root = temporary.newFolder()
        listOf("../escape", "/etc/evil", "C:\\evil", "\\evil").forEach { name ->
            assertThrows(name, IOException::class.java) { modelDownloadFile(root, name) }
        }
        assertEquals(File(root, "sub/weights.onnx").canonicalFile, modelDownloadFile(root, "sub/weights.onnx"))
    }

    @Test fun originalMarketArchiveAndFileChecksumsAreRead() {
        val index = """
            models:
              - id: test
                name: Test
                versions:
                  - version: v1
                    archive:
                      url: https://example.invalid/model.tar.bz2
                      sha256: archive-digest
                    files:
                      - name: weights.onnx
                        sha256: file-digest
                        sizeBytes: 123
        """.trimIndent()
        val version = ModelIndexLoader.parseModelsIndex(index).single().resolvedVersion()!!
        assertEquals("archive-digest", version.sha256)
        assertEquals("file-digest", version.files.single().sha256)
        assertEquals(123L, version.files.single().sizeBytes)
    }

    @Test fun installedOlderMarketVersionUsesItsOwnFileManifest() {
        val installed = temporary.newFolder()
        File(installed, "weights.onnx").writeText("old")
        val old = ModelVersion(version = "v1", files = listOf(ModelFile("weights.onnx", "", sizeBytes = 3)))
        val latest = ModelVersion(version = "v2", files = listOf(ModelFile("weights.onnx", "", sizeBytes = 9)))
        val model = ModelInfo("test", "Test", "", ModelCategory.OTHER, versions = listOf(latest, old))
        assertEquals(old, installedModelVersion(installed, model, "v1"))
        assertEquals(old, installedModelVersion(installed, model, null))
        assertFalse(hasDownloadedModelFiles(installed, latest))
    }

    @Test fun deletingDuringDownloadPreventsTheStagedModelFromReappearing() {
        val installed = temporary.newFolder()
        val staging = temporary.newFolder()
        File(installed, "weights.onnx").writeText("old")
        File(staging, "weights.onnx").writeText("new")
        val guard = ModelInstallGuard()
        val generation = guard.currentGeneration()
        guard.delete { File(installed, "weights.onnx").delete() }
        var recorded = false
        assertFalse(guard.install(generation, staging, installed) { recorded = true })
        assertFalse(File(installed, "weights.onnx").exists())
        assertFalse(recorded)
        assertEquals(0L, guard.completionRevision)
    }

    @Test fun aNewExplicitDownloadAfterDeletionCanInstallAgain() {
        val installed = temporary.newFolder()
        val guard = ModelInstallGuard()
        guard.delete { true }
        val staging = temporary.newFolder()
        File(staging, "weights.onnx").writeText("repaired")
        assertTrue(guard.install(guard.currentGeneration(), staging, installed) {})
        assertEquals("repaired", File(installed, "weights.onnx").readText())
        assertEquals(1L, guard.completionRevision)
        val second = temporary.newFolder()
        File(second, "weights.onnx").writeText("reinstalled same version")
        assertTrue(guard.install(guard.currentGeneration(), second, installed) {})
        assertEquals(2L, guard.completionRevision)
    }

    @Test fun handledDefaultModelIsNotInstalledByALateAutomaticRequest() {
        val installed = temporary.newFolder()
        val staging = temporary.newFolder()
        File(staging, "weights.onnx").writeText("late")
        val guard = ModelInstallGuard()
        assertFalse(guard.install(guard.currentGeneration(), staging, installed,
            isStillRequested = { false }) {})
        assertFalse(File(installed, "weights.onnx").exists())
    }
}
