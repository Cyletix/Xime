package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.ui.keyboard.KeyboardInputActions
import com.kingzcheung.xime.ui.keyboard.LanguageKeyButton
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageKeyButtonTest {
    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()
    private var density = 1f

    private fun setKey() {
        rule.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalKeyboardInputActions provides KeyboardInputActions(
                    schemas = listOf(
                        SchemaInfo("first", "同名方案", "", "", ""),
                        SchemaInfo("second", "日语模式", "", "", "", language = com.kingzcheung.xime.settings.InputLanguage.JAPANESE),
                    ),
                    currentInputModeId = "first",
                    onSwitchSchema = { events += "schema:$it" },
                )
            ) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomStart) {
                    LanguageKeyButton(
                        text = "中/英",
                        modifier = Modifier.size(100.dp).testTag("language-key"),
                        backgroundColor = Color.White, textColor = Color.Black,
                        onClick = { events += "tap" },
                        swipeText = "中", onSwipe = { events += "up:$it" },
                        swipeDownText = "中", onSwipeDown = { events += "down:$it" },
                        longPressItems = listOf("中", "英"),
                        onLongPressSelect = { events += "legacy:$it" },
                        onSwipeStateChange = { _, _ -> events += "legacy-preview" },
                        shadowEnabled = false,
                    )
                }
            }
        }
    }

    @Test
    fun numberPanelReturnIsTextOnlyAndCannotOpenTheLanguageMenu() {
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputActions provides KeyboardInputActions(
                schemas = listOf(SchemaInfo("second", "中文九键", "", "", "")),
                onSwitchSchema = { events += "schema:$it" },
            )) {
                com.kingzcheung.xime.ui.keyboard.NumberKeyboardLayout(
                    onKeyPress = { events += it }, keyBackgroundColor = Color.Gray,
                    keyTextColor = Color.Black, specialKeyBackgroundColor = Color.Blue,
                    modifier = Modifier.size(360.dp, 280.dp),
                )
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("mode-slot-2", useUnmergedTree = true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(350L)
        rule.onNodeWithTag("language-schema:second").assertDoesNotExist()
        rule.onNodeWithTag("language-schema:__xime_english").assertDoesNotExist()
        rule.onNodeWithTag("mode-slot-2", useUnmergedTree = true).performTouchInput { cancel() }
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("mode-slot-2", useUnmergedTree = true).performTouchInput { down(center); up() }
        rule.runOnIdle { assertEquals(listOf("abc"), events) }
    }

    @Test fun currentModeIsHighlightedBeforeDragging() {
        setKey(); holdKey()
        rule.onNodeWithTag("language-schema:first").assertIsSelected()
        rule.onNodeWithText("中文").assertIsDisplayed()
        rule.onNodeWithText("日语").assertIsDisplayed()
        rule.onNodeWithTag("language-key").performTouchInput { cancel() }
    }

    private fun holdKey() {
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("language-key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(350L)
        rule.onNodeWithTag("language-schema:second").assertIsDisplayed()
    }

    private fun moveToSecondSchema() {
        val key = rule.onNodeWithTag("language-key").fetchSemanticsNode()
        val schema = rule.onNodeWithTag("language-schema:second").fetchSemanticsNode()
        val target = schema.positionOnScreen + Offset(schema.size.width / 2f, schema.size.height / 2f)
        rule.onNodeWithTag("language-key").performTouchInput {
            moveTo(target - key.positionOnScreen)
        }
        rule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun menuWaitsFor200msThenOpensWithoutAnInstructionHeading() {
        setKey()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("language-key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(150L)
        rule.onNodeWithTag("language-schema:first").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(100L)
        rule.onNodeWithTag("language-schema:first").assertIsDisplayed()
        rule.onNodeWithText("滑动选择输入方案，松手确认").assertDoesNotExist()
        rule.onNodeWithTag("language-key").performTouchInput { cancel() }
    }

    @Test
    fun shortTapKeepsTheOriginalLanguageToggle() {
        setKey()
        rule.onNodeWithTag("language-key").performTouchInput {
            down(center)
            up()
        }
        rule.runOnIdle { assertEquals(listOf("tap"), events) }
        rule.onNodeWithTag("language-schema:first").assertDoesNotExist()
    }

    @Test
    fun upSwipeIsCancelledWithoutTypingOrOpeningMenu() {
        setKey()
        rule.onNodeWithTag("language-key").performTouchInput {
            down(Offset(center.x, height * 0.85f))
            moveTo(Offset(center.x, height * 0.85f - 65f * density))
            up()
        }
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithTag("language-schema:first").assertDoesNotExist()
    }

    @Test
    fun slideReleaseSelectsTheSchemaIdWithoutTriggeringTapOrSwipe() {
        setKey()
        holdKey()
        moveToSecondSchema()
        rule.onNodeWithTag("language-schema:second").assertIsSelected()
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithTag("language-key").performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf("schema:second"), events) }
        // 长按使用手动时钟，松手后的菜单移除也需要一帧重组。
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("language-schema:second").assertDoesNotExist()
    }

    @Test
    fun cancellingAfterSelectingDoesNotSwitch() {
        setKey()
        holdKey()
        moveToSecondSchema()
        rule.onNodeWithTag("language-key").performTouchInput { cancel() }
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("language-schema:second").assertDoesNotExist()
    }

    @Test
    fun releasingOnTheOriginalKeyDismissesWithoutSwitching() {
        setKey()
        holdKey()
        rule.onNodeWithTag("language-key").performTouchInput { up() }
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("language-schema:second").assertDoesNotExist()
    }

    @Test fun englishIsAlwaysListedAndSlideSelectable() {
        setKey()
        holdKey()
        val englishId = com.kingzcheung.xime.settings.InputModes.ENGLISH
        val key = rule.onNodeWithTag("language-key").fetchSemanticsNode()
        val english = rule.onNodeWithTag("language-schema:$englishId").assertIsDisplayed().fetchSemanticsNode()
        val target = english.positionOnScreen + Offset(english.size.width / 2f, english.size.height / 2f)
        rule.onNodeWithTag("language-key").performTouchInput { moveTo(target - key.positionOnScreen); up() }
        rule.runOnIdle { assertEquals(listOf("schema:$englishId"), events) }
    }
}
