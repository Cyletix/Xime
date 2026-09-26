package com.kingzcheung.xime.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FloatingResizeGeometryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun floatingButtonActsImmediatelyAndControlsStayAboveNavigationAtMovedPosition() {
        var enabled = true
        var dx = 0
        rule.setContent {
            var floating by remember { mutableStateOf(true) }
            var x by remember { mutableStateOf(50) }
            var y by remember { mutableStateOf(90) }
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.size(640.dp, 320.dp).testTag("root")) {
                        FloatingKeyboardContainer(floating, 0.5f, 1f, 0.5f, x, y, 48, 700, 220, Color.Black,
                            onDrag = { a, b -> x += a.toInt(); y += b.toInt(); dx++ }, onDragEnd = {}) {
                            KeyboardResizeOverlay(288, 288, 0, floating,
                                onHeightChange = {}, onBottomPaddingChange = {}, onOpacityChange = {}, onReset = {},
                                onConfirm = { _, _, _, _ -> }, onCancel = {}, modifier = Modifier.fillMaxSize(),
                                onFloatingModeChange = { floating = it; enabled = it })
                        }
                    }
                }
            }
        }
        val before = rule.onNodeWithContentDescription("确认").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { down(center); moveBy(Offset(-30f, -40f)); up() }
        val after = rule.onNodeWithContentDescription("确认").fetchSemanticsNode().boundsInRoot
        assertTrue(dx > 0)
        assertTrue(after.left < before.left && after.top < before.top)
        assertTrue(after.bottom < 320f - 48f)
        rule.onNodeWithContentDescription("悬浮键盘").performClick()
        rule.runOnIdle { assertFalse(enabled) }
        rule.onNodeWithContentDescription("确认").assertIsDisplayed()
        rule.onNodeWithTag("floating-drag-bar").assertDoesNotExist()
    }

    @Test fun landscapeCannotShrinkBelowFourUsableKeyRows() {
        var height = 280
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply { screenWidthDp = 800; screenHeightDp = 360 }
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(1f)) {
                MaterialTheme { KeyboardResizeOverlay(280, 280, 0, true,
                    onHeightChange = { height = it }, onBottomPaddingChange = {}, onOpacityChange = {}, onReset = {},
                    onConfirm = { _, _, _, _ -> }, onCancel = {}, modifier = Modifier.size(320.dp, 280.dp).testTag("resize")) }
            }
        }
        rule.onNodeWithTag("resize").performTouchInput { down(Offset(width / 2f, 12f)); moveBy(Offset(0f, 240f)); up() }
        assertTrue("44dp工具栏外每行至少44dp", height >= 228)
        val slider = rule.onNodeWithTag("keyboard-opacity-slider").fetchSemanticsNode().boundsInRoot
        val confirm = rule.onNodeWithContentDescription("确认").fetchSemanticsNode().boundsInRoot
        assertTrue(slider.bottom <= confirm.top)
        assertTrue(floatingKeyboardWidth(800,360,260,300,false) >= FLOATING_RESIZE_MIN_WIDTH_DP)
        assertTrue(floatingKeyboardWidth(800,360,260,300,true) >= floatingKeyboardWidth(800,360,260,300,false))
    }
}
