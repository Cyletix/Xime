package com.kingzcheung.xime.settings

import android.content.Context

/** Language membership is independent of layout or encoding (e.g. 双拼 is Chinese). */
enum class InputLanguage(val id: String, val displayName: String) {
    CHINESE("zh", "中文"), JAPANESE("ja", "日语"), ENGLISH("en", "英文");

    companion object {
        fun forSchema(id: String): InputLanguage = when {
            id == InputModes.ENGLISH -> ENGLISH
            id == "jaroomaji" || id == "japanese" || id.startsWith("japanese_") -> JAPANESE
            else -> CHINESE
        }
    }
}

/** 英文始终存在；排序独立于方案的启用与部署，不改变市场方案。 */
object InputModes {
    const val ENGLISH = "__xime_english"
    private const val ORDER_KEY = "input_mode_order"
    val english = SchemaInfo(ENGLISH, "英文", "", "", "内置英文模式，始终启用", isDownloaded = true)

    fun available(schemas: List<SchemaInfo>, order: List<String> = emptyList()): List<SchemaInfo> {
        val available = schemas.map { it.schemaId }.toSet()
        val visible = schemas.filter { it.schemaId == ENGLISH || CyimeInputDefaults.visibleSchema(it.schemaId, available) }
            .map { it.copy(name = ChineseSchemas.displayName(it.schemaId, it.name)) }
        val unique = (visible + english).distinctBy { it.schemaId }
        val byId = unique.associateBy { it.schemaId }
        val canonicalOrder = order.flatMap { id ->
            if (id == ENGLISH) listOf(id) else CyimeInputDefaults.canonicalIds(listOf(id), available)
        }
        val ordered = canonicalOrder.distinct().mapNotNull { byId[it] }
        val used = ordered.mapTo(mutableSetOf()) { it.schemaId }
        return ordered + unique.filterNot { it.schemaId in used }
    }

    fun ordered(context: Context, schemas: List<SchemaInfo>): List<SchemaInfo> =
        available(schemas, SettingsPreferences.getPrefsPublic(context).getString(ORDER_KEY, "").orEmpty().lines())

    fun saveOrder(context: Context, ids: List<String>) {
        SettingsPreferences.getPrefsPublic(context).edit()
            .putString(ORDER_KEY, ids.distinct().joinToString("\n")).apply()
    }

    fun languageOf(modeId: String, schemas: List<SchemaInfo> = emptyList()): InputLanguage =
        schemas.firstOrNull { it.schemaId == modeId }?.language ?: InputLanguage.forSchema(modeId)

    fun inCurrentLanguage(schemas: List<SchemaInfo>, currentModeId: String): List<SchemaInfo> {
        val modes = available(schemas)
        val language = languageOf(currentModeId, modes)
        return modes.filter { it.language == language }
    }

    /** One entry per language, pointing to that language's last available mode. */
    fun languageChoices(schemas: List<SchemaInfo>, currentModeId: String,
        remembered: Map<InputLanguage, String>): List<SchemaInfo> {
        val modes = available(schemas)
        return InputLanguage.entries.mapNotNull { language ->
            val group = modes.filter { it.language == language }
            val chosen = group.firstOrNull { it.schemaId == currentModeId }
                ?: group.firstOrNull { it.schemaId == remembered[language] }
                ?: group.firstOrNull()
            chosen?.copy(name = language.displayName)
        }
    }

    fun rememberedModes(context: Context, schemas: List<SchemaInfo> = emptyList()): Map<InputLanguage, String> {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val previous = SettingsPreferences.getCurrentSchema(context)
        return InputLanguage.entries.associateWith { language ->
            prefs.getString("last_input_mode_${language.id}", null)
                ?: previous.takeIf { languageOf(it, schemas) == language }.orEmpty()
        }
    }

    fun rememberMode(context: Context, schemaId: String, language: InputLanguage = languageOf(schemaId)) {
        if (schemaId.isBlank() || schemaId == ENGLISH) return
        SettingsPreferences.getPrefsPublic(context).edit()
            .putString("last_input_mode_${language.id}", schemaId).apply()
    }

    /** Reordering one language must not move or discard the other languages' slots. */
    fun mergeOrder(current: List<String>, reordered: List<String>): List<String> {
        val replacements = reordered.distinct().filter { it in current }.iterator()
        val changed = reordered.toSet()
        return current.map { if (it in changed && replacements.hasNext()) replacements.next() else it }
    }

    fun selectedId(schemaId: String, isAsciiMode: Boolean): String = if (isAsciiMode) ENGLISH else schemaId
}
