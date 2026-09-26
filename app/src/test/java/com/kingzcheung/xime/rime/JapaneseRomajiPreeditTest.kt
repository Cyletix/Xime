package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlScalar
import java.io.File

/**
 * 日语未拼完罗马音的显示判定（纯字符串规则，不需要引擎）。
 *
 * 依据随包方案 `japanese.schema.yaml` 的 preedit_format：末尾是元音即为完整假名；
 * 末尾剩辅音串说明还在等后续元音，此时方案的单字母回显规则
 * （`xform/n/ん/`、`xform/k/っ/` …，方案文件 506-530 行）会把它显示成 っ / ん。
 */
class JapaneseRomajiPreeditTest {
    @Test fun rebuildingInputMatchesContextualKeyProcessing() {
        mapOf("yoy" to "yoy", "kitte" to "kixtsute", "gakkou" to "gaxtsukou",
            "nk" to "nnk", "nnk" to "nnk", "nky" to "nnky", "ny" to "ny",
            "KATTA" to "KAXTSUTA", "kixtsute" to "kixtsute").forEach { (input, expected) ->
            assertEquals(input, expected, canonicalJapaneseRomaji(input))
            assertEquals("idempotent $input", expected, canonicalJapaneseRomaji(expected))
        }
    }

    @Test fun bundledSchemasDisplayAndDeleteTheSameUnits() {
        listOf("japanese", "jaroomaji").forEach { schema ->
            val root = Yaml.default.parseToYamlNode(
                File("src/main/assets/rime_japanese/$schema.schema.yaml").readText()) as YamlMap
            val translator = root.entries.entries.single { it.key.content == "translator" }.value as YamlMap
            val formats = (translator.entries.entries.single { it.key.content == "preedit_format" }.value as YamlList)
                .items.map { (it as YamlScalar).content.split('/') }
            fun display(input: String): String {
                val echo = formats.fold(input) { text, rule ->
                    assertEquals("xform", rule[0])
                    Regex(rule[1]).replace(text, rule[2])
                }
                return japanesePreedit(input, echo)
            }
            mapOf("k" to "k", "n" to "n", "sh" to "sh", "ky" to "ky",
                "kash" to "かsh", "nk" to "んk", "kk" to "っk", "ny" to "ny",
                "nn" to "ん", "KATAK" to "カタK").forEach { (input, expected) ->
                assertEquals("$schema: $input", expected, display(input))
            }
            mapOf("nka" to "ん", "kakka" to "かっ", "kk" to "っ", "nk" to "ん",
                "kash" to "かs", "kya" to "", "KANPA" to "カン").forEach { (input, expected) ->
                assertEquals("$schema: delete $input", expected, display(deleteJapaneseRomaji(input)))
            }
        }
    }

    @Test fun deletionPreservesEarlierNasalAndSokuon() {
        val cases = mapOf("nka" to "nn", "nsha" to "nn", "nnka" to "nn",
            "nnya" to "nn", "kakka" to "kaxtsu", "kk" to "xtsu", "nk" to "nn",
            "ny" to "n", "kash" to "kas", "KANPA" to "KANN", "kaxtsu" to "ka")
        cases.forEach { (input, expected) -> assertEquals(input, expected, deleteJapaneseRomaji(input)) }
        var input = "kanka"
        listOf("kann", "ka", "").forEach { expected ->
            input = deleteJapaneseRomaji(input)
            assertEquals(expected, input)
        }
    }


    @Test
    fun `以元音结尾的输入已经拼完`() {
        listOf("a", "ka", "kya", "xtsu", "kakina", "KATA", "ka-").forEach {
            assertNull("$it 已是完整假名，不该改写", pendingRomajiTail(it))
        }
    }

    @Test
    fun `末尾辅音串才是要按原字母显示的待拼部分`() {
        assertEquals("k", pendingRomajiTail("k"))
        assertEquals("n", pendingRomajiTail("n"))
        assertEquals("k", pendingRomajiTail("kak"))
        // 多字母声母整体显示：旧实现只把最后一个字母当尾部，屏幕上会出现 っh
        assertEquals("sh", pendingRomajiTail("sh"))
        assertEquals("ky", pendingRomajiTail("ky"))
        assertEquals("ts", pendingRomajiTail("ts"))
        assertEquals("K", pendingRomajiTail("KATAK"))
        // n 后面接辅音时这个 n 已经读成 ん，只剩后面的辅音待拼
        assertEquals("k", pendingRomajiTail("nk"))
        assertEquals("k", pendingRomajiTail("nnk"))
        // 双写辅音的第一个是促音 っ，只有最后一个还在等元音
        assertEquals("k", pendingRomajiTail("kk"))
        // 空格是方案的分隔符：尾部只看分隔符之后的字母
        assertEquals("k", pendingRomajiTail("ka k"))
    }

    @Test
    fun `nn 是撥音，算拼完`() {
        assertNull(pendingRomajiTail("nn"))
        assertNull(pendingRomajiTail("kann"))
        assertNull(pendingRomajiTail("NN"))
        // んか / っか 都已成音；末尾单独的 n 才是待拼（可能在等第二个 n）
        assertNull(pendingRomajiTail("nka"))
        assertNull(pendingRomajiTail("kka"))
        assertEquals("n", pendingRomajiTail("nnn"))
    }

    @Test
    fun `空输入与非字母结尾不改写`() {
        assertNull(pendingRomajiTail(""))
        assertNull(pendingRomajiTail("ka "))
        assertNull(pendingRomajiTail("ka."))
    }

    /**
     * 「尾部回显字符数 == 待拼字母数」是 japanesePreedit 的前提：方案 preedit_format
     * 给每个未成音的字母都准备了单字符兜底规则。这里按 26 键真实回显锁定，不靠默契。
     */
    @Test
    fun `显示串等于引擎回显去掉尾部再接上按下的字母`() {
        assertEquals("k", japanesePreedit("k", "っ"))
        assertEquals("sh", japanesePreedit("sh", "っっ"))
        assertEquals("か", japanesePreedit("ka", "か"))
        assertEquals("かk", japanesePreedit("kak", "かっ"))
        assertEquals("かn", japanesePreedit("kan", "かん"))
        assertEquals("ん", japanesePreedit("nn", "ん"))
        assertEquals("かっか", japanesePreedit("kakka", "かっか"))
        assertEquals("カタK", japanesePreedit("KATAK", "カタッ"))
        assertEquals("かきな", japanesePreedit("kakina", "かきな"))
        // 回显长度不足以容纳尾部（方案改动或异常回显）：退回只显示按下的字母
        assertEquals("sh", japanesePreedit("sh", "っ"))
    }

    @Test
    fun `退格与显示同源：未拼完只删一个字母`() {
        assertEquals(0, romajiDeleteStart("k"))
        assertEquals(2, romajiDeleteStart("kak"))
        assertEquals(1, romajiDeleteStart("sh"))
        assertEquals(3, romajiDeleteStart("kash"))
        assertEquals(4, romajiDeleteStart("KATAK"))
    }

    @Test
    fun `退格与显示同源：已拼完成则整体删掉最后一个假名`() {
        assertEquals(0, romajiDeleteStart(""))
        assertEquals(0, romajiDeleteStart("ka"))
        assertEquals(0, romajiDeleteStart("kya"))
        assertEquals(2, romajiDeleteStart("kann"))
        assertEquals(2, romajiDeleteStart("kan"))
        assertEquals(0, romajiDeleteStart("nn"))
        assertEquals(2, romajiDeleteStart("ka-"))
        assertEquals(2, romajiDeleteStart("KATA"))
        // 促音属于上一个假名：kakka 一次只删 ka，留着 かっ
        assertEquals(3, romajiDeleteStart("kakka"))
        assertEquals(1, romajiDeleteStart("kka"))
        assertEquals(3, romajiDeleteStart("kakk"))
    }

    /**
     * n + 辅音：n 已读成 ん，只有后面的辅音待拼（`nk` → んk、`nsh` → んsh、`nky` → んky）；
     * n + y 未定音（可能是 にゃ/にゅ/にょ 的前缀），与主流日语输入法一致地保持待拼。
     */
    @Test
    fun `n 后接辅音时 n 已成音，只有后面的辅音待拼`() {
        assertEquals("k", pendingRomajiTail("nk"))
        assertEquals("sh", pendingRomajiTail("nsh"))
        assertEquals("ky", pendingRomajiTail("nky"))
        assertEquals("ny", pendingRomajiTail("ny"))
        assertEquals("んk", japanesePreedit("nk", "んっ"))
        assertEquals("んsh", japanesePreedit("nsh", "んっっ"))
        assertEquals("んky", japanesePreedit("nky", "んっっ"))
        assertEquals("ny", japanesePreedit("ny", "んっ"))
        assertEquals("んか", japanesePreedit("nka", "んか"))
    }

    /** 双字母声母整体按原字母显示（每个未成音字母在方案里各有一个兜底回显字符）。 */
    @Test
    fun `双字母声母整体显示按下的字母`() {
        listOf("sh", "ch", "ts", "ky", "ny").forEach {
            assertEquals(it, pendingRomajiTail(it))
        }
        assertEquals("sh", japanesePreedit("sh", "っっ"))
        assertEquals("ch", japanesePreedit("ch", "っっ"))
        assertEquals("ts", japanesePreedit("ts", "っっ"))
        assertEquals("ky", japanesePreedit("ky", "っっ"))
        assertEquals("かsh", japanesePreedit("kash", "かっっ"))
    }

    /** 大写罗马音 = 片假名：已拼完部分沿用回显，未成音尾部继续显示按下的（大写）字母。 */
    @Test
    fun `大写片假名路径未成音尾部保持大写`() {
        assertEquals("K", pendingRomajiTail("KATAK"))
        assertEquals("K", japanesePreedit("K", "ッ"))
        assertEquals("カ", japanesePreedit("KA", "カ"))
        assertEquals("カK", japanesePreedit("KAK", "カッ"))
        assertEquals("カタ", japanesePreedit("KATA", "カタ"))
        assertEquals("カタK", japanesePreedit("KATAK", "カタッ"))
    }
}
