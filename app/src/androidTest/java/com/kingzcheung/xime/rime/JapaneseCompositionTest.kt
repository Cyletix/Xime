package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.service.*
import com.kingzcheung.xime.settings.JapaneseSchemas
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JapaneseCompositionTest {
    @Test fun wholeKanaDeletionConversionRangeAndKatakanaUseTheInstalledEngine() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        val engine = RimeEngine.getInstance()
        engine.initialize(user, shared)
        assertTrue(RimeConfigHelper.ensureDeployment(context)); assertTrue(engine.ensureSession())
        val previous = engine.getCurrentSchema()
        try {
            JapaneseSchemas.ids.forEach { schema ->
                assertTrue(engine.switchSchema(schema)); engine.setOption("ascii_mode", false)
                for ((input, expected) in mapOf("na" to "", "ka" to "", "nn" to "", "xtu" to "", "kakina" to "kaki", "KATAKANA" to "KATAKA", "kitta" to "kixtsu")) {
                    engine.setInput(input)
                    val stored = engine.getInput()
                    assertEquals("重建编码与连续按键规则一致", canonicalJapaneseRomaji(input), stored)
                    assertEquals("$schema 删除整个假名: $input", expected, deleteJapaneseRomaji(stored))
                    assertEquals("删除边界查询不得更改引擎编码", stored, engine.getInput())
                }
                engine.setInput("KATAKA")
                assertEquals("片假名读音保持", "カタカ", engine.processJapaneseEnterIfComposing()!!.committedText)
                engine.setInput("naniwoshimasuka")
                val conversion = JapaneseConversion(engine, engine.getInput())
                conversion.refresh()
                assertTrue("整句: ${conversion.preview}", conversion.preview.contains("何"))
                conversion.moveRange(-4)
                assertTrue("只转换前3假名: ${conversion.preview}", conversion.preview.startsWith("何") && conversion.preview.endsWith("しますか"))
                val first = conversion.candidateIndex
                conversion.cycle()
                assertNotEquals(first, conversion.candidateIndex)
                assertEquals("预览不得提交", "", engine.commit())
                assertEquals("预览保留全部原编码", "naniwoshimasuka", engine.getInput())
                assertEquals("可撤回原假名", "なにをしますか", engine.japaneseReading(engine.getInput()))
            }
        } finally { engine.clearComposition(); if (previous.isNotBlank()) engine.switchSchema(previous) }
    }

    /**
     * 26 键罗马音只按下声母时，屏幕必须显示按下的字母，而不是原方案为促音/拨音
     * 准备的回显（k→っ、n→ん）；已拼完的假名仍沿用原方案回显。
     */
    @Test fun partialRomajiShowsPressedLettersWhileCompleteKanaKeepTheirEcho() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        val engine = RimeEngine.getInstance()
        engine.initialize(user, shared)
        assertTrue(RimeConfigHelper.ensureDeployment(context)); assertTrue(engine.ensureSession())
        val previous = engine.getCurrentSchema()
        try {
            JapaneseSchemas.ids.forEach { schema ->
                assertTrue(engine.switchSchema(schema)); engine.setOption("ascii_mode", false)
                assertEquals("$schema 单个声母显示按下的字母", "k", displayText(engine, "k"))
                assertEquals("$schema 单个 n 显示按下的字母", "n", displayText(engine, "n"))
                assertEquals("$schema nn 是完整假名，沿用回显", "ん", displayText(engine, "nn"))
                assertEquals("$schema ka 是完整假名，沿用回显", "か", displayText(engine, "ka"))
                assertEquals("$schema 已构成部分保留假名，尾部显示字母", "かk", displayText(engine, "kak"))
                assertEquals("$schema 完整假名不改写", "かん", displayText(engine, "kann"))
                assertEquals("$schema 以元音结尾不改写", "かきな", displayText(engine, "kakina"))
                // n + 辅音：n 已成 ん，只有后面的辅音待拼；n + y 未定音（引擎级锁定 2026-09-27）
                assertEquals("$schema nk 显示 んk", "んk", displayText(engine, "nk"))
                assertEquals("$schema nsh 显示 んsh", "んsh", displayText(engine, "nsh"))
                assertEquals("$schema nky 显示 んky", "んky", displayText(engine, "nky"))
                assertEquals("$schema ny 保持按下的字母", "ny", displayText(engine, "ny"))
                assertEquals("$schema nka 完整 んか", "んか", displayText(engine, "nka"))
                listOf("sh", "ch", "ts", "ky").forEach {
                    assertEquals("$schema $it 保持按下的字母", it, displayText(engine, it))
                }
                // 大写罗马音 = 片假名：已拼完沿用回显，未成音尾部保持按下的（大写）字母
                assertEquals("$schema 片假名 K", "K", displayText(engine, "K"))
                assertEquals("$schema 片假名 KA", "カ", displayText(engine, "KA"))
                assertEquals("$schema 片假名 KAK", "カK", displayText(engine, "KAK"))
                assertEquals("$schema 片假名 KATA", "カタ", displayText(engine, "KATA"))
                assertEquals("$schema 片假名 KATAK", "カタK", displayText(engine, "KATAK"))
                // 显示出口归一化不得改引擎状态；刷新（再取一次 composition）后仍显示按下的字母。
                // 回归 2026-09-27：曾经只在按键路径加工，刷新路径用引擎原回显，屏幕又打回 っ / ん。
                engine.setInput("kak")
                repeat(2) { assertEquals("$schema 刷新后仍显示按下的字母", "かk", displayText(engine, "kak")) }
                assertEquals("显示归一化不得更改编码", "kak", engine.getInput())
            }
        } finally { engine.clearComposition(); if (previous.isNotBlank()) engine.switchSchema(previous) }
    }

    /** 显示文本：引擎回显经显示出口归一化（与生产 updateUIWithResult/applyComposition 同一函数）。 */
    private fun displayText(engine: RimeEngine, input: String): String {
        engine.setInput(input)
        return japanesePreedit(input, engine.getComposition().preedit)
    }
}
