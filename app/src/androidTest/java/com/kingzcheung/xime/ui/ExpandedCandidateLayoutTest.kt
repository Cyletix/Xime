package com.kingzcheung.xime.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExpandedCandidateLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun floatingCandidatesReflowByTheirViewportAndKeepSelectionIndices() {
        val width = mutableStateOf(400)
        val fontScale = mutableStateOf(1f)
        val landscape = mutableStateOf(true)
        val t9Rail = mutableStateOf(true)
        var selected = -1
        val words = listOf("那", "吗", "没", "哦", "面", "年", "女", "明", "你们", "哪里", "明天", "没有关系")
        val entries = List(80) { i -> CandidateEntry(words[i % words.size], "pinyin", 100 + i * 2) }
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 1800 // A tablet screen must not determine a floating panel's columns.
                orientation = if (landscape.value) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                MaterialTheme {
                    CandidatePage(
                        state = CandidatePageState(candidates = entries, backgroundColor = Color(0xFF211D29),
                            textColor = Color.White, matchT9Rail = t9Rail.value, railPinyinOptions = listOf("o", "m", "n")),
                        callbacks = CandidatePageCallbacks(onCandidateSelect = { selected = it.globalIndex }, onDelete = {}, onEnter = {}),
                        modifier = Modifier.size(width.value.dp, 300.dp).testTag("candidate-panel"))
                }
            }
        }
        fun firstRowCount(): Int {
            val nodes = rule.onAllNodes(hasTestTagPrefix("expanded-candidate:")).fetchSemanticsNodes()
            val centerY = rule.onNodeWithTag("expanded-candidate:100").fetchSemanticsNode().boundsInRoot.center.y
            return nodes.count { it.boundsInRoot.width > 0f && kotlin.math.abs(it.boundsInRoot.center.y - centerY) < 1f }
        }
        fun checkVisible() {
            rule.waitForIdle()
            val panel = rule.onNodeWithTag("candidate-panel").fetchSemanticsNode().boundsInRoot
            val rail = rule.onNodeWithTag("candidate-left-rail").fetchSemanticsNode().boundsInRoot
            val center = rule.onNodeWithTag("expanded-candidates").fetchSemanticsNode().boundsInRoot
            assertTrue("Side rail must not take half of a floating panel", rail.width < panel.width * .25f)
            assertTrue("Candidates must retain most of the panel", center.width > panel.width * .5f)
            val nodes = rule.onAllNodes(hasTestTagPrefix("expanded-candidate:")).fetchSemanticsNodes()
            assertTrue(nodes.isNotEmpty())
            nodes.forEach { node ->
                val tag = node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
                val results = mutableListOf<TextLayoutResult>()
                rule.onNodeWithTag(tag).onChildren().onFirst().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertTrue("$tag has readable text", results.isNotEmpty())
                results.forEach { assertFalse("$tag must not be squeezed into ellipsis", it.isLineEllipsized(0)) }
                assertTrue(node.boundsInRoot.left >= center.left - 1f)
                assertTrue(node.boundsInRoot.right <= center.right + 1f)
            }
        }
        checkVisible()
        val narrowCount = firstRowCount()
        rule.runOnIdle { width.value = 700 }
        checkVisible()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "candidate-wide.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        assertTrue("Widening the panel must fit more words: narrow=$narrowCount wide=${firstRowCount()}", firstRowCount() > narrowCount)
        rule.onNodeWithTag("expanded-candidate:106").assertExists()
        rule.onNodeWithTag("expanded-candidate:108").assertExists()
        rule.onNodeWithTag("expanded-candidate:110").assertExists()
        rule.runOnIdle { width.value = 320; fontScale.value = 1.5f; landscape.value = false }
        checkVisible()
        rule.onNodeWithTag("expanded-candidate:100").performTouchInput { click() }
        rule.runOnIdle { assertEquals(100, selected); t9Rail.value = false; fontScale.value = 1f }
        checkVisible()
        rule.onNodeWithTag("expanded-candidates").performScrollToNode(hasTestTag("expanded-candidate:258"))
        rule.onNodeWithTag("expanded-candidate:258").performTouchInput { click() }
        rule.runOnIdle { assertEquals("Scrolling must preserve the original global index", 258, selected) }
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
