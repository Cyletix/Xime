package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeProcessResult
import com.kingzcheung.xime.rime.japanesePreedit
import com.kingzcheung.xime.rime.deleteJapaneseRomaji
import com.kingzcheung.xime.settings.JapaneseSchemas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 所有方法在按键队列串行调用；会话或原编码改变就作废旧转换预览。 */
internal class JapaneseInputController(private val service: XimeInputMethodService) {
    /**
     * 转换预览：非空表示 preedit 已是假名/汉字预览。
     * UI 出口可能在主线程经 [normalizePreedit] 读取，故用 volatile。
     */
    @Volatile private var conversion: JapaneseConversion? = null
    private var session = -1L
    private var schema = ""
    private val engine get() = service.rimeEngine
    /** 按键路径的判定：引擎是权威源（切方案/部署期间可能领先或落后 uiState 一帧）。 */
    private fun available(): Boolean = !engine.isAsciiMode() && (engine.getCurrentSchema() in JapaneseSchemas.ids || engine.getCurrentSchema() == "jaroomaji")

    /** 显示出口的判定：只读 uiState，不碰引擎——UI 每次刷新都会调用，不付出 JNI / 锁开销。 */
    private fun displayAvailable(): Boolean {
        val state = service.uiState.value
        return !state.isAsciiMode && (state.currentSchemaId in JapaneseSchemas.ids || state.currentSchemaId == "jaroomaji")
    }
    fun displayCandidates(input: String) = conversion?.takeIf { it.input == input && session == service.uiState.value.inputSessionId }?.candidates?.toList()

    /**
     * 显示层归一化：末尾尚未拼完的罗马音显示为按下的字母（引擎回显会给 っ / ん）。
     *
     * 纯字符串运算、不改引擎状态、不持锁，因此由两个 UI 出口统一调用
     * （updateUIWithResult / applyComposition），按键后的刷新路径同样生效。
     * 转换预览期间 preedit 是假名/汉字预览，跳过归一化；非日语方案原样返回。
     */
    fun normalizePreedit(input: String, preedit: String): String =
        if (conversion == null && preedit.isNotEmpty() && displayAvailable()) japanesePreedit(input, preedit) else preedit

    private fun current(): JapaneseConversion? {
        if (!available() || session != service.uiState.value.inputSessionId || schema != engine.getCurrentSchema() || conversion?.input != engine.getInput()) conversion = null
        return conversion
    }
    private fun start(): JapaneseConversion? {
        current()?.let { return it }
        val input = engine.getInput()
        if (input.isEmpty()) return null
        session = service.uiState.value.inputSessionId
        schema = engine.getCurrentSchema()
        return JapaneseConversion(engine, input).also { conversion = it; it.refresh() }
    }
    private suspend fun show() {
        val active = current()
        val result = engine.getProcessResult(true).let {
            // 转换预览显示假名本身；无转换时由 updateUIWithResult 出口做罗马音归一化（显示按下的字母）
            if (active == null) it
            else it.copy(preeditText = active.preview, candidates = active.candidates, hasNextPage = false, hasPrevPage = false)
        }
        val owner = service.uiState.value.inputSessionId
        withContext(Dispatchers.Main) { if (owner == service.uiState.value.inputSessionId) service.sessionController.updateUIWithResult(result) }
    }
    suspend fun cancel() { conversion = null; show() }
    suspend fun commit(index: Int? = null): Boolean {
        val active = current() ?: return false
        if (index != null) active.choose(index)
        val text = active.preview
        val owner = session
        conversion = null
        engine.clearComposition()
        withContext(Dispatchers.Main) {
            if (owner == service.uiState.value.inputSessionId) {
                service.commitText(text)
                service.sessionController.updateUIWithResult(engine.getProcessResult(true))
            }
        }
        return true
    }
    suspend fun prepareKana(modify: Boolean) { if (current() != null) { if (modify) cancel() else commit() } }
    suspend fun handleKey(key: String): Boolean {
        if (!available()) { conversion = null; return false }
        when (key) {
            "japanese_convert" -> { val old = current(); val active = start(); if (old != null) active?.cycle(); show(); return true }
            "japanese_undo" -> { cancel(); return true }
            "japanese_left", "japanese_right" -> {
                val steps = if (key.endsWith("left")) -1 else 1
                val active = start()
                if (active == null) service.schemaController.moveJapaneseCursorFromKeyQueue(steps)
                else { active.moveRange(steps); show() }
                return true
            }
            "delete", "clear_composition", "clear_all" -> conversion = null
            "space", "enter" -> if (commit()) return true
            else -> if (current() != null) commit()
        }
        return false
    }
    suspend fun deleteKana(): Boolean {
        if (!available()) return false
        val input = engine.getInput()
        if (input.isEmpty()) { conversion = null; return false }
        conversion = null
        // 删除单位与显示同源（romajiDeleteStart）：未拼完的罗马音只删一个字母，
        // 已拼完的假名整体删。不能再用引擎读音长度探测边界——ん 与 っ 等长时
        // 判不出假名边界，会把整串一次删光（2026-09-25 复现）。
        engine.setInput(deleteJapaneseRomaji(input))
        show()
        return true
    }
}
