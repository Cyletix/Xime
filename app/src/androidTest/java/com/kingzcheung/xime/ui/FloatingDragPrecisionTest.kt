package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.keyboard.FloatingKeyboardContainer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class FloatingDragPrecisionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun subpixelDragReachesTheBottomAndCancellationStillFinishesTheDrag() {
        var density = 1f
        var docks = 0
        var finishes = 0
        rule.setContent {
            density = LocalDensity.current.density
            var y by remember { mutableIntStateOf(60) }
            var accumulatedY by remember { mutableFloatStateOf(60f) }
            MaterialTheme {
                Box(Modifier.size(340.dp, 328.dp)) {
                    FloatingKeyboardContainer(true, 0.85f, offsetX = 0, offsetY = y,
                        availableHeightDp = 800, contentHeightDp = 300,
                        onDrag = { _, dy ->
                            accumulatedY = (accumulatedY + dy).coerceAtLeast(0f)
                            y = accumulatedY.roundToInt()
                        }, onDragEnd = { finishes++ }, onDock = { docks++ }) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center)
            moveBy(Offset(24f * density, 0f))
            repeat(200) { moveBy(Offset(0f, 0.3f * density)) }
        }
        rule.waitUntil(2_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ready"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { cancel() }
        rule.onNodeWithTag("floating-dock-preview").assertDoesNotExist()
        rule.onNodeWithTag("floating-drag-bar").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, finishes); assertEquals(0, docks) }
    }
}
