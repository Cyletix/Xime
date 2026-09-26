import java.security.MessageDigest

val manifestOutput = layout.buildDirectory.dir("generated/rime-manifest")
val roots = listOf(
    "rime_ice" to layout.buildDirectory.dir("generated/chinese-assets/rime_ice").get().asFile,
    "rime_chinese" to file("src/main/assets/rime_chinese"),
    "rime_japanese" to layout.buildDirectory.dir("generated/japanese-assets/rime_japanese").get().asFile,
    "rime_japanese" to file("src/main/assets/rime_japanese"),
)
val prepareRimeManifest by tasks.registering {
    dependsOn("prepareChineseDictionaries", "prepareJapaneseDictionaries")
    inputs.files(roots.map { it.second })
    outputs.dir(manifestOutput)
    doLast {
        val entries = sortedMapOf<String, String>()
        for ((assetRoot, root) in roots) root.walkTopDown().filter { it.isFile }.forEach { source ->
            val relative = source.relativeTo(root).invariantSeparatorsPath
            if (relative.endsWith(".md") || relative.startsWith("LICENSE") || relative.endsWith(".custom.yaml")) return@forEach
            val target = if (assetRoot == "rime_japanese" && relative.endsWith(".lua")) "lua/$relative" else relative
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            entries[target] = "$hash\t${source.length()}\t$assetRoot/$relative\t$target"
        }
        val output = manifestOutput.get().asFile.apply { mkdirs() }
        File(output, "rime-bundled-manifest.tsv").writeText(entries.values.joinToString("\n", postfix = "\n"))
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareRimeManifest) }
