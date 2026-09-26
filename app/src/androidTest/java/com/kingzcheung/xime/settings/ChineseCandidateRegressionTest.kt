package com.kingzcheung.xime.settings

import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class ChineseCandidateRegressionTest {
    @Test fun inspectRecallAndLearning() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        val engine = RimeEngine.getInstance()
        engine.initialize(user, shared)
        assertTrue(RimeConfigHelper.ensureDeployment(context))
        assertTrue(engine.ensureSession())
        for ((schema, inputs) in listOf(
            "t9_pinyin" to listOf("3", "33", "64824", "72628542628", "9682437363358"),
            "rime_ice" to listOf("d", "de", "paobuliaobu", "zoubierende lu".replace(" ", "")))) {
            assertTrue(engine.switchSchema(schema))
            engine.setOption("ascii_mode", false)
            for (input in inputs) {
                if (schema == "t9_pinyin") {
                    engine.clearQueuedT9Composition()
                    input.forEach { engine.processQueuedT9Key(it.code) }
                } else {
                    engine.clearQueuedComposition()
                    engine.setInput(input)
                }
                val candidates = engine.getAllCandidates(100)
                android.util.Log.i("CandidateProbe", "$schema $input: " + candidates.take(25).joinToString { "${it.text}[${it.comment}]" })
                if (input == "3") {
                    assertTrue("Single 3 must recall 的: $candidates", candidates.take(20).any { it.text == "的" })
                    assertTrue("Full e remains valid", candidates.any { it.comment == "e" })
                    assertTrue("f initial remains valid", candidates.any { it.comment.startsWith("f") })
                }
                if (input == "33") assertTrue("的 remains available alongside learned candidates", candidates.any { it.text == "的" })
                if (input == "64824") {
                    assertTrue(candidates.any { it.text == "牛逼" })
                    assertTrue(candidates.any { it.text == "你太" })
                    assertTrue(engine.t9SelectPinyinDirect("niu", 3))
                    engine.t9FlushRimeInput()
                    val locked = engine.getAllCandidates(100)
                    assertTrue("locked niu still recalls 牛逼", locked.any { it.text == "牛逼" })
                    assertFalse("locked niu excludes ni tai", locked.any { it.text == "你太" })
                }
            }
        }
        engine.clearQueuedComposition()
        assertTrue(engine.switchSchema("t9_pinyin"))
        engine.clearQueuedT9Composition()
        engine.processQueuedT9Key('3'.code)
        val before = engine.getAllCandidates(100).indexOfFirst { it.text == "德" }
        assertTrue(before >= 0)
        var learned = 0
        try {
            repeat(8) {
                engine.clearQueuedT9Composition()
                engine.processQueuedT9Key('3'.code)
                engine.getAllCandidates(100)
                assertTrue(engine.t9SelectCandidate("de", "德", 1))
                assertTrue(engine.t9Memorize("德", "de")); learned++
            }
            engine.clearQueuedT9Composition(); engine.processQueuedT9Key('3'.code)
            val after = engine.getAllCandidates(100).indexOfFirst { it.text == "德" }
            assertTrue("Learning promotes 德 or retains its existing first place ($before -> $after)",
                if (before == 0) after == 0 else after in 0 until before)
        } finally { repeat(learned) { engine.t9Forget("德", "de") } }
        engine.clearQueuedT9Composition()
    }

    @Test fun learnedSingleKeySurvivesEngineRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        val engine = RimeEngine.getInstance()
        engine.initialize(user, shared)
        assertTrue(RimeConfigHelper.ensureDeployment(context)); assertTrue(engine.ensureSession())
        assertTrue(engine.switchSchema("t9_pinyin")); engine.setOption("ascii_mode", false)
        var learned = 0
        try {
            repeat(8) { assertTrue(engine.t9Memorize("德", "de")); learned++ }
            engine.clearQueuedT9Composition(); engine.processQueuedT9Key('3'.code)
            val rank = engine.getAllCandidates(100).indexOfFirst { it.text == "德" }
            assertTrue(rank >= 0)
            engine.destroy()
            engine.initialize(user, shared); assertTrue(engine.ensureSession())
            assertTrue(engine.switchSchema("t9_pinyin")); engine.setOption("ascii_mode", false)
            engine.clearQueuedT9Composition(); engine.processQueuedT9Key('3'.code)
            assertEquals("Learned rank survives engine shutdown and reopening user dictionaries", rank,
                engine.getAllCandidates(100).indexOfFirst { it.text == "德" })
        } finally {
            repeat(learned) { engine.t9Forget("德", "de") }
            engine.clearQueuedT9Composition()
        }
    }
}
