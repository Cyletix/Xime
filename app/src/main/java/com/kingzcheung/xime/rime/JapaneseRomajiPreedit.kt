package com.kingzcheung.xime.rime

/** 罗马音里的元音：以元音结尾即已构成一个假名。 */
private const val ROMAJI_VOWELS = "aiueo"

/**
 * 末尾尚未成音的罗马音（需要按按下的原字母显示的部分）；null 表示末段已经拼完。
 *
 * 依据方案 `japanese.schema.yaml` / `jaroomaji.schema.yaml` 的 preedit_format 判定，
 * 不查引擎：把已经成音的部分交给引擎读音，只有「还在等后续元音的辅音」需要改回原字母。
 * 内置方案保留未拼完的原字母；此处也兼容仍使用旧版单字母兜底转换的自定义方案。
 *
 * 判定只依赖「罗马音里未成音的尾部必然以辅音结尾」这一条：
 * 末尾是元音/长音/分隔符就是完整假名（ka→か、kya→きゃ、xtsu→っ、ka-→かー）；
 * 末尾是辅音才可能没拼完，再从末尾回溯出整段辅音串，扣掉串内已经成音的部分
 * （`nn`→ん、双写辅音的第一个→促音 っ）。这样 `k`→k、`sh`→sh、`ka|k`→k，
 * 而 `nn`→完整、`nka`→完整（んか）、`kk`→只留最后一个 k（前面的 k 是促音）。
 *
 * 纯字符串判定、不依赖引擎状态：显示（[japanesePreedit]）与退格（[romajiDeleteStart]）
 * 共用这一套边界规则，任何线程、任何 UI 出口都能直接调用。
 */
internal fun pendingRomajiTail(input: String): String? {
    val last = input.lastOrNull() ?: return null
    if (!last.isLetter() || last.lowercaseChar() in ROMAJI_VOWELS) return null
    // 末尾是辅音（含 n）：回溯出「末尾整段辅音」再判其中哪些已经成音。
    var start = input.length
    while (start > 0) {
        val previous = input[start - 1]
        if (!previous.isLetter() || previous.lowercaseChar() in ROMAJI_VOWELS) break
        start--
    }
    return pendingTailInConsonants(input.substring(start))
}

/**
 * 从整段辅音里扣掉已经成音的部分，返回剩下的待拼尾部；null 表示整段都已成音。
 *
 * 只处理两种「辅音也能独立成音」的写法：
 * - `nn` → 撥音 ん；`n` + 非 y 的辅音 → n 已读成 ん（`nk` → んk、`nsh` → んsh）；
 * - 同一辅音双写 → 第一个是促音 っ（`kk` 表示 っ + 待拼 k，与主流日语输入法一致）。
 * `n` + `y` 未定音（可能是 にゃ/にゅ/にょ 的前缀），与其余辅音（`sh`、`ky`、`ts`…）
 * 一样算作待拼尾部。
 */
private fun pendingTailInConsonants(consonants: String): String? {
    var index = 0
    while (index < consonants.length) {
        val current = consonants[index].lowercaseChar()
        val next = consonants.getOrNull(index + 1)?.lowercaseChar()
        when {
            current == 'n' && next == 'n' -> index += 2
            current == 'n' && next == 'y' -> break
            current == 'n' && next != null -> index += 1
            next == current -> index += 1
            else -> break
        }
    }
    return if (index >= consonants.length) null else consonants.substring(index)
}

/**
 * 退格一次要删到的位置（与显示同源，避免删掉屏幕上没显示的内容）。
 *
 * - 末尾还有未成音的尾部 → 只删尾部最后一个字母（`kak` → 删 k）；
 * - 末尾已经成音 → 删掉最后一个完整假名，拗音与撥音整体删
 *   （`kya`、`kann`），双写促音只删后一个假名（`kakka` → 删 ka，保留 かっ）。
 */
internal fun romajiDeleteStart(input: String): Int {
    if (input.isEmpty()) return 0
    if (pendingRomajiTail(input) != null) return input.length - 1
    val last = input.last()
    // 长音符 / 分隔符 / 标点：单个字符即一个删除单位。
    if (!last.isLetter()) return input.length - 1
    // 末尾 nn → 撥音 ん 一个假名。
    if (last.lowercaseChar() == 'n' && input.length >= 2 &&
        input[input.length - 2].lowercaseChar() == 'n'
    ) return input.length - 2
    // 末尾元音：连同它前面的辅音群一起删（拗音 ky、sh 等）。
    var start = input.length - 1
    while (start > 0) {
        val previous = input[start - 1]
        if (!previous.isLetter() || previous.lowercaseChar() in ROMAJI_VOWELS) break
        start--
    }
    // 双写促音（kka）：第一个辅音属于上一个假名 っ，留给下一次删除。
    val consonants = input.substring(start, input.length - 1)
    val pending = pendingTailInConsonants(consonants)
    start += consonants.length - (pending?.length ?: 0)
    return start
}

/** Preserve already completed ん/っ when deleting the kana that supplied their context. */
internal fun deleteJapaneseRomaji(input: String): String {
    val end = romajiDeleteStart(input)
    val completedEnd = input.length - (pendingRomajiTail(input)?.length ?: 0)
    val completedPrefix = input.take(minOf(end, completedEnd))
    val contextualTail = pendingRomajiTail(completedPrefix).orEmpty()
    val stablePrefix = completedPrefix.dropLast(contextualTail.length) + contextualTail.map {
        when (it) { 'n' -> "nn"; 'N' -> "NN"; else -> if (it.isUpperCase()) "XTSU" else "xtsu" }
    }.joinToString("")
    return stablePrefix + input.substring(minOf(end, completedEnd), end)
}

/**
 * 显示串：已拼完部分沿用引擎回显，末尾尚未拼完的罗马音按按下的原字母显示。
 *
 * [preedit] 是引擎对同一个 [input] 给出的回显。内置方案尾部保留原字母；旧版自定义
 * 方案可能仍把每个字母转换成单个兜底假名。两者尾部字符数均等于待拼字母数：
 * 去掉这一截再接上原字母，就是「已拼完的假名 + 按下的字母」。
 * 「一字母一回显字符」这一假设由 JapaneseRomajiPreeditTest 锁定，不靠默契。
 *
 * 纯字符串运算，不改引擎状态（不 setInput）、不持锁，可在任意 UI 出口调用，含主线程刷新。
 */
internal fun japanesePreedit(input: String, preedit: String): String {
    val tail = pendingRomajiTail(input) ?: return preedit
    // 回显长度不足以容纳尾部（方案改动或异常回显）：直接显示按下的字母，
    // 宁可少显示已拼部分，也不能把 っ / ん 这类兜底回显当成真实假名显示出去。
    if (preedit.length < tail.length) return tail
    return preedit.dropLast(tail.length) + tail
}

/** Rebuilding composition must follow the same contextual rules as the key processor. */
internal fun canonicalJapaneseRomaji(input: String): String {
    var result = Regex("([bcdfghjkmprstvwzyq])(?=\\1)").replace(input, "xtsu")
    result = Regex("([BCDFGHJKMPRSTVWZYQ])(?=\\1)").replace(result, "XTSU")
    result = Regex("n+(?=[bcdfghjkmpqrstvwxz])").replace(result) {
        if (it.value.length % 2 == 1) it.value + "n" else it.value
    }
    return Regex("N+(?=[BCDFGHJKMPQRSTVWXZ])").replace(result) {
        if (it.value.length % 2 == 1) it.value + "N" else it.value
    }
}
