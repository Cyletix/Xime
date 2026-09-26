package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.kingzcheung.xime.ui.keyboard.FloatingKeyboardContainer
import com.kingzcheung.xime.ui.keyboard.KeyButton
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardResizeOverlay
import com.kingzcheung.xime.ui.keyboard.SwipeableKeyButton
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FloatingKeyboardUiTest {
    @get:Rule val rule = createComposeRule()
    private var density = 1f
    private var docks = 0

    private fun showFloating() {
        rule.setContent {
            density = LocalDensity.current.density
            MaterialTheme {
                var floating by remember { mutableStateOf(true) }
                Box(Modifier.size(340.dp, 328.dp)) {
                    FloatingKeyboardContainer(
                        isFloatingMode = floating, scaleFactor = 0.85f, fontScaleFactor = 1f,
                        contentHeightDp = 300, offsetX = 0, offsetY = 0, onDrag = { _, _ -> }, onDragEnd = {},
                        onDock = { docks++; floating = false },
                    ) { Box(Modifier.fillMaxSize().testTag("keyboard-content")) }
                }
            }
        }
    }

    @Test fun dragBarUsesOnlyOneShortBottomRow() {
        showFloating()
        val content = rule.onNodeWithTag("keyboard-content").fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag("floating-drag-bar").fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithTag("floating-keyboard-card").fetchSemanticsNode().boundsInRoot
        assertEquals(content.bottom, bar.top, 1f)
        assertEquals(card.bottom, bar.bottom, 1f)
        assertEquals(28f * density, bar.height, 1f)
        assertEquals(300f * density, content.height, 1f)
    }

    @Test fun edgePreviewExpandsThenReleaseRestoresWithoutLeavingTheDragRow() {
        showFloating()
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center); moveBy(Offset(24f * density, 0f))
        }
        rule.waitUntil(2_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "ready")).fetchSemanticsNodes().isNotEmpty()
        }
        val preview = rule.onNodeWithTag("floating-dock-preview").fetchSemanticsNode().boundsInRoot
        assertEquals(340f * density, preview.width, 1f)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { up() }
        rule.onNodeWithTag("floating-drag-bar").assertDoesNotExist()
        rule.onNodeWithTag("floating-dock-preview").assertDoesNotExist()
        assertEquals(328f * density, rule.onNodeWithTag("keyboard-content").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(1, docks)
    }

    @Test fun cancellingAnArmedDockKeepsTheFloatingKeyboard() {
        showFloating()
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center); moveBy(Offset(24f * density, 0f))
        }
        rule.waitUntil(2_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "ready")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { cancel() }
        rule.onNodeWithTag("floating-dock-preview").assertDoesNotExist()
        rule.onNodeWithTag("floating-drag-bar").assertIsDisplayed()
        assertEquals(0, docks)
    }

    @Test fun leavingTheEdgeAndReleasingBeforeRecompositionDoesNotDock() {
        showFloating()
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center); moveBy(Offset(24f * density, 0f))
        }
        rule.waitUntil(2_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "ready")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            moveBy(Offset(0f, -80f * density)); up()
        }
        rule.runOnIdle { assertEquals(0, docks) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("floating-drag-bar").assertIsDisplayed()
    }

    @Test fun leavingAndReturningInOneFrameRestartsTheHalfSecondHold() {
        showFloating()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center); moveBy(Offset(24f * density, 0f))
        }
        rule.mainClock.advanceTimeBy(400L)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            moveBy(Offset(0f, -80f * density))
            moveBy(Offset(0f, 80f * density))
        }
        rule.mainClock.advanceTimeBy(160L)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { up() }
        rule.runOnIdle { assertEquals(0, docks) }
    }

    @Test fun unspecifiedControlFontUsesReadableDefault() {
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(keyTextScale = 1f)) {
                KeyButton("符号", {}, Color.Gray, Color.White,
                    modifier = Modifier.size(64.dp, 48.dp), fontSize = TextUnit.Unspecified)
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("符号", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(16f, layouts.single().layoutInput.style.fontSize.value, 0.01f)
        val layout = layouts.single()
        assertFalse("textSize=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, constraints=${layout.layoutInput.constraints}, style=${layout.layoutInput.style}", layout.hasVisualOverflow)
    }

    @Test fun compressedKeyKeepsTheNumberHintInsideItsBounds() {
        rule.setContent {
            MaterialTheme {
                SwipeableKeyButton(text = "Q", onClick = {}, backgroundColor = Color.Gray,
                    textColor = Color.White, swipeText = "1", onSwipe = {},
                    modifier = Modifier.size(40.dp, 32.dp).testTag("short-key"))
            }
        }
        val key = rule.onNodeWithTag("short-key").fetchSemanticsNode().boundsInRoot
        val hint = rule.onNodeWithText("1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(hint.top >= key.top)
        assertTrue(hint.bottom <= key.bottom)
    }

    @Test fun resizeHasBottomCornerActionsAndAppliesOpacityOnlyOnConfirm() {
        var previewOpacity = 1f
        var savedOpacity = 1f
        var confirms = 0
        showResize(onOpacity = { previewOpacity = it }, onConfirm = { savedOpacity = it; confirms++ })
        rule.onNodeWithContentDescription("取消").assertDoesNotExist()
        val reset = rule.onNodeWithContentDescription("重置").fetchSemanticsNode().boundsInRoot
        val confirm = rule.onNodeWithContentDescription("确认").fetchSemanticsNode().boundsInRoot
        val slider = rule.onNodeWithTag("keyboard-opacity-slider").fetchSemanticsNode().boundsInRoot
        assertTrue(reset.center.x < confirm.center.x)
        assertEquals(reset.top, confirm.top, 1f)
        assertTrue(slider.bottom < confirm.top)
        val toggle = rule.onNodeWithContentDescription("悬浮键盘").fetchSemanticsNode().boundsInRoot
        assertEquals(reset.center.y, toggle.center.y, 1f)
        assertTrue(toggle.left > reset.right && toggle.right < confirm.left)
        rule.onNodeWithTag("keyboard-opacity-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }
        rule.runOnIdle { assertEquals(0.6f, previewOpacity, 0.01f); assertEquals(1f, savedOpacity, 0f) }
        rule.onNodeWithContentDescription("确认").performClick()
        rule.runOnIdle { assertEquals(0.6f, savedOpacity, 0.01f); assertEquals(1, confirms) }
    }

    @Test fun systemBackCancelsResizeWithoutConfirming() {
        var cancelled = false
        var confirmed = false
        showResize(onCancel = { cancelled = true }, onConfirm = { confirmed = true })
        Espresso.pressBack()
        rule.runOnIdle { assertTrue(cancelled); assertFalse(confirmed) }
    }

    @Test fun resetRestoresOpacityBeforeConfirmation() {
        var opacity = 0.5f
        showResize(initialOpacity = opacity, onOpacity = { opacity = it })
        rule.onNodeWithContentDescription("重置").performClick()
        rule.runOnIdle { assertEquals(1f, opacity, 0f) }
        rule.onNodeWithText("透明度 0%").assertIsDisplayed()
    }

    private fun showResize(initialOpacity: Float = 1f, onOpacity: (Float) -> Unit = {},
        onConfirm: (Float) -> Unit = {}, onCancel: () -> Unit = {}) {
        rule.setContent {
            MaterialTheme {
                KeyboardResizeOverlay(initialHeightDp = 320, defaultHeightDp = 300,
                    currentBottomPaddingDp = 0, isFloatingMode = true, initialOpacity = initialOpacity,
                    onHeightChange = {}, onBottomPaddingChange = {}, onOpacityChange = onOpacity,
                    onReset = {}, onConfirm = { _, _, _, opacity -> onConfirm(opacity) }, onCancel = onCancel,
                    modifier = Modifier.size(340.dp, 400.dp))
            }
        }
    }

    @Test fun restoredUnsafeOffsetCannotPutDragHandleBelowNavigationInset() {
        rule.setContent {
            density = LocalDensity.current.density
            Box(Modifier.size(340.dp, 400.dp).testTag("floating-window")) {
                FloatingKeyboardContainer(isFloatingMode = true, scaleFactor = 0.85f,
                    offsetX = 0, offsetY = -60, minOffsetY = 48, availableHeightDp = 800, contentHeightDp = 300,
                    onDrag = { _, _ -> }, onDragEnd = {}) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        val window = rule.onNodeWithTag("floating-window").fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag("floating-drag-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(48f * density, window.bottom - bar.bottom, 1f)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center); moveBy(Offset(0f, 100f * density)); up()
        }
        val after = rule.onNodeWithTag("floating-drag-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(bar.bottom, after.bottom, 1f)
    }
    @Test fun dockPreviewEndsAboveTheNavigationInset() {
        rule.setContent {
            density = LocalDensity.current.density
            Box(Modifier.size(340.dp, 328.dp).testTag("floating-window")) {
                FloatingKeyboardContainer(true, 0.85f, offsetX = 0, offsetY = 48, minOffsetY = 48,
                    availableHeightDp = 800, contentHeightDp = 300, onDrag = { _, _ -> }, onDragEnd = {}) { Box(Modifier.fillMaxSize()) }
            }
        }
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { down(center); moveBy(Offset(24f * density, 0f)) }
        rule.waitUntil(2000) { rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "ready")).fetchSemanticsNodes().isNotEmpty() }
        val bottom = rule.onNodeWithTag("floating-window").fetchSemanticsNode().boundsInRoot.bottom
        val preview = rule.onNodeWithTag("floating-dock-preview").fetchSemanticsNode().boundsInRoot
        assertEquals(bottom - 48f * density, preview.bottom, 1f)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { cancel() }
    }

    @Test fun draggingAgainstEitherSideNeverShowsTheRestorePreview() {
        rule.setContent {
            density = LocalDensity.current.density
            Box(Modifier.size(340.dp, 328.dp)) {
                FloatingKeyboardContainer(true, 0.85f, offsetX = 0, offsetY = 80,
                    availableHeightDp = 800, contentHeightDp = 300, onDrag = { _, _ -> }, onDragEnd = {}, onDock = { docks++ }) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        rule.mainClock.autoAdvance = false
        for (direction in listOf(-1, 1)) {
            rule.onNodeWithTag("floating-drag-bar").performTouchInput { down(center); moveBy(Offset(direction * 60f * density, 0f)) }
            rule.mainClock.advanceTimeBy(1600)
            rule.onNodeWithTag("floating-dock-preview").assertDoesNotExist()
            rule.onNodeWithText("松手恢复普通键盘").assertDoesNotExist()
            rule.onNodeWithTag("floating-drag-bar").performTouchInput { up() }
        }
        rule.runOnIdle { assertEquals(0, docks) }
    }

}
