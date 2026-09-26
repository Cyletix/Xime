package com.kingzcheung.xime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.InputModes

/** Follow the two-level navigation instead of relying on every mode being in the globe menu. */
internal fun ComposeTestRule.chooseModeThroughLanguageAndPanel(id: String) {
    mainClock.autoAdvance = true
    waitForIdle()
    val engine = RimeEngine.getInstance()
    val language = InputModes.languageOf(id)
    val globe = onAllNodesWithTag("language-key-control", useUnmergedTree = true).onLast()
    globe.performTouchInput { down(center) }
    waitUntil(5000) { onAllNodesWithContentDescription("选择${language.displayName}").fetchSemanticsNodes().isNotEmpty() }
    val target = onNodeWithContentDescription("选择${language.displayName}").performScrollTo().fetchSemanticsNode()
    val key = globe.fetchSemanticsNode()
    globe.performTouchInput {
        moveTo(target.positionOnScreen + Offset(target.size.width / 2f, target.size.height / 2f) - key.positionOnScreen)
        up()
    }
    waitUntil(10_000) {
        if (id == InputModes.ENGLISH) engine.isAsciiMode()
        else !engine.isAsciiMode() && InputModes.languageOf(engine.getCurrentSchema()) == language
    }
    waitForIdle()
    if (id != InputModes.ENGLISH && engine.getCurrentSchema() != id) {
        onNodeWithContentDescription("输入模式").performClick()
        waitUntil(5000) { onAllNodesWithTag("schema-pages", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val choice = onNodeWithTag("schema-tile:$id")
        var pages = 0
        while (!choice.isDisplayed() && pages++ < 12) {
            onNodeWithTag("schema-pages", useUnmergedTree = true).performTouchInput { swipeLeft() }
        }
        choice.assertIsDisplayed().performClick()
        waitUntil(10_000) { engine.getCurrentSchema() == id && !engine.isAsciiMode() }
    }
    waitForIdle()
}
