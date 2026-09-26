package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

class FixedKeyboardResizeTest {
    @get:Rule val rule = createComposeRule()
    @Test fun phoneCanNarrowAndDockOnEitherSide() = checkResize(360)
    @Test fun tabletCanNarrowAndDockOnEitherSide() = checkResize(900)

    private fun checkResize(width: Int) {
        var rect by mutableStateOf(ResizeRect(0f, 200f, width.toFloat(), 540f))
        var resizing by mutableStateOf(true)
        var geometry: ResizeGeometry? = null
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.size(width.dp, 540.dp)) {
                        FloatingKeyboardContainer(false, 1f, offsetX = 0, offsetY = 0,
                            contentHeightDp = 340, fixedWidthDp = geometry?.widthDp ?: 0,
                            fixedOffsetX = geometry?.horizontalOffsetDp ?: 0,
                            onDrag = { _, _ -> }, onDragEnd = {},
                            previewRect = if (resizing) rect else null) {
                            Box(Modifier.fillMaxSize().testTag("fixed-content"))
                        }
                        if (resizing) CompositionLocalProvider(LocalKeyboardResizePreviewState provides
                            KeyboardResizePreviewState(rect = rect, initialRect = rect, onRectChange = { rect = it },
                                bounds = ResizeRect(0f, 0f, width.toFloat(), 540f), fixedHeightRange = 180..500)) {
                            KeyboardResizeOverlay(340, 340, 0, false,
                                onHeightChange = {}, onBottomPaddingChange = {}, onOpacityChange = {}, onReset = {},
                                onGeometryChange = { geometry = it }, onConfirm = { _, _, floating, _ ->
                                    assertFalse(floating); resizing = false
                                }, onCancel = {})
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("keyboard-resize-frame", useUnmergedTree = true).performTouchInput {
            swipe(Offset(width - 3f, 365f), Offset(width - 83f, 365f), 350)
        }
        rule.runOnIdle {
            assertTrue("Fixed right edge must narrow", rect.width < width - 40)
            assertEquals(0f, rect.left, 1f)
            assertEquals(340f, rect.height, 1f)
        }
        rule.onNodeWithTag("keyboard-resize-fixed-move-bar", useUnmergedTree = true).performTouchInput { swipe(center, center + Offset(150f, 0f), 350) }
        rule.runOnIdle { assertEquals(width.toFloat(), rect.right, 1f) }
        rule.onNodeWithTag("keyboard-resize-fixed-move-bar", useUnmergedTree = true).performTouchInput { swipe(center, center - Offset(150f, 0f), 350) }
        rule.runOnIdle { assertEquals(0f, rect.left, 1f) }
        rule.onNodeWithTag("keyboard-resize-fixed-move-bar", useUnmergedTree = true).performTouchInput { swipe(center, center + Offset(150f, 0f), 350) }
        rule.onNodeWithContentDescription("确认").performClick()
        val rendered = rule.onNodeWithTag("fixed-keyboard-card").fetchSemanticsNode().boundsInRoot
        assertEquals(rect.width, rendered.width, 1f)
        assertEquals(rect.left, rendered.left, 1f)
        assertEquals(rect.right, rendered.right, 1f)
    }
}
