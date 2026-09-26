package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.rime.RimeCandidate
import kotlin.concurrent.withLock

/** 使用原方案的实际回显确定假名边界，不复制罗马音规则，大小写保持原样。 */
internal fun RimeEngine.japaneseReading(input: String): String {
    setInput(input)
    return getComposition().preedit.filterNot { it.isWhitespace() || it == '\'' }
}

/**
 * 读音长度探测边界（仅供转换预览的切换范围使用）。
 *
 * 不要再用于退格删除单位：ん 与 っ 都是单字符，长度比较判不出假名边界，会把整串一次删光
 * （2026-09-25 复现）。删除单位已改为同源的 [com.kingzcheung.xime.rime.romajiDeleteStart]；
 * 显示边界同理，走纯字符串的 [com.kingzcheung.xime.rime.japanesePreedit]（在 UI 出口归一化），
 * 不再用引擎探测"是否拼完"——探测会把单字母的促音/拨音兜底回显当成已拼完。
 */
internal fun RimeEngine.previousJapaneseBoundary(input: String): Int {
    if (input.isEmpty()) return 0
    RimeEngine.rimeLock.lock()
    try {
        val reading = japaneseReading(input)
        for (end in input.lastIndex downTo 0) {
            val shorter = japaneseReading(input.take(end))
            if (shorter.length < reading.length && reading.startsWith(shorter)) return end
        }
        return 0
    } finally { setInput(input); RimeEngine.rimeLock.unlock() }
}

internal class JapaneseConversion(private val engine: RimeEngine, val input: String) {
    private val boundaries = RimeEngine.rimeLock.withLock { buildList {
        add(0)
        var end = input.length
        val reversed = mutableListOf(end)
        while (end > 0) { end = engine.previousJapaneseBoundary(input.take(end)); if (end > 0) reversed += end }
        addAll(reversed.reversed())
        engine.setInput(input)
    } }
    private var rangeIndex = boundaries.lastIndex
    var candidateIndex = 0
        private set
    var candidates: Array<RimeCandidate> = emptyArray()
        private set
    var preview = ""
        private set

    fun cycle() { candidateIndex++; refresh() }
    fun moveRange(steps: Int) {
        rangeIndex = (rangeIndex + steps).coerceIn(1, boundaries.lastIndex)
        candidateIndex = 0
        refresh()
    }
    fun choose(index: Int) { candidateIndex = index.coerceAtLeast(0); refresh() }
    fun refresh() {
        val end = boundaries[rangeIndex]
        RimeEngine.rimeLock.lock()
        try {
            val suffix = engine.japaneseReading(input.drop(end))
            engine.setInput(input.take(end))
            candidates = engine.getAllCandidates(200)
            candidateIndex = if (candidates.isEmpty()) 0 else candidateIndex.mod(candidates.size)
            if (candidates.isNotEmpty()) engine.highlightCandidate(candidateIndex)
            preview = engine.conversionPreview().ifEmpty { engine.getComposition().preedit }.replace(" ", "") + suffix
        } finally { engine.setInput(input); RimeEngine.rimeLock.unlock() }
    }
}
