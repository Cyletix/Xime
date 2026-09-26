package com.kingzcheung.xime.settings

import android.content.Context
import java.io.File

/** 日语词库与上游默认方案子模块独立分发，兼容用户从市场更新同名 jaroomaji 文件。 */
object JapaneseSchemas {
    val ids = listOf("japanese", "japanese_kana")
    private const val ASSET_DIRECTORY = "rime_japanese"
    private const val ADDED = "japanese_schemas_added_v1"

    // Only byte-equivalent old bundled schemas are upgraded. User/market edits remain intact.
    private val previousBuiltinHashes = setOf(
        "518801269741b9e89c52067d4efea8ec4dc70e32ad882150d4e7cf7d8c9ac0e2",
        "e6dca8f663e4e717e191d8db0a62fcc6cbbdec6c04eee7dbe890d004cf8c46fd",
        "5aa36ea5440316d1cfefff59596c40cbd538e8ee54157e0b0ba0f9acb034731c",
        "5ed788330a4ff1d47388c25e2d1cdd8d03d5a7f276a3be2b7d7960e4f16c60ee",
        "d5b97431c3b6c9fdaa44b76e63e8e7f44fc54546dac054c63cc42a5f0161c532",
        "bc86ebc64769ecab245e24cad05d4c4770fa41524b0f223ea986aa23fe54b8bc",
        "1e3d4a61263b7f9150f575d55cb9200cb19ca1118648d729946f579316f673d2",
        "d4ed8bae780066e88d4d296fb5885d8c3208e3e898c9988794506fe78c392827",
        "d1112911e39779237b3aa45138e0f254a6110dcaa4b0c5407904967639454a0a",
        "1a01463d08d9ebc82970f33198c3436e9bc4ff511e724f3d7abbc33764ef0afc",
        "fdc4da0139ec7f99bba2195a53ca3a86eff305b7223472c683b701cf6991ae55",
        "bd5dad3644aaa81c7ff10c4bd90d07506018b5f873f0b82ad07ae5525e6d037b",
        "41496872839f5a53d0814c3d13645daf6c605c645c119867cb108401c9a770ba",
        "8f70d7e3ae055f040e67a23746798a13ba51195185156d9aa108215d46d8f427",
        "6e2f68b4754d1a2044b567dae828800c4084d8d0aaaa54eb7d5f11701f939a15",
        "535f00e7e2f08a9a1445f623219afdd87b71bddbfd4d665d83d1793ea282e90f",
        "c5602acc778686861f200d397259d0c93cacc2cbfc98391d33c10471c6cbbbd8",
    )

    internal fun isPreviousBuiltinSchema(text: String): Boolean {
        val normalized = text.replace("\r\n", "\n")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return hash in previousBuiltinHashes
    }

    @Synchronized
    fun installAssets(context: Context, target: File) {
        target.mkdirs()
        for (name in context.assets.list(ASSET_DIRECTORY).orEmpty()) {
            val processor = name in setOf("cyime_japanese_romaji.lua", "cyime_japanese_completion.lua")
            if (!name.endsWith(".yaml") && !processor) continue
            val destination = File(target, if (processor) "lua/$name" else name)
            if (destination.exists()) {
                if ((!name.endsWith(".schema.yaml") && !processor) || destination.length() > 128 * 1024 ||
                    !isPreviousBuiltinSchema(destination.readText())) continue
            }
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, "$name.installing")
            try {
                context.assets.open("$ASSET_DIRECTORY/$name").use { input ->
                    temporary.outputStream().use { input.copyTo(it) }
                }
                java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }
    }

    /** 升级时仅追加本轮新方案一次，不重新启用用户已禁用的其它内置方案。 */
    fun addOnFirstUpgrade(context: Context, enabled: List<String>): List<String> {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (prefs.getBoolean(ADDED, false)) return enabled
        val updated = addMissing(enabled)
        if (updated != enabled) SchemaManager.setEnabledSchemas(context, updated)
        prefs.edit().putBoolean(ADDED, true).apply()
        return updated
    }

    internal fun addMissing(enabled: List<String>): List<String> = enabled + ids.filterNot { it in enabled }
}
