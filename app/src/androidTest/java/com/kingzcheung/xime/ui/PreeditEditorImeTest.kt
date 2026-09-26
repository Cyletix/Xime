package com.kingzcheung.xime.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.SettingsPreferences
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real IME: live code edits retain the original keyboard and committed host text. */
class PreeditEditorImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val engine = RimeEngine.getInstance()
    private lateinit var editor: EditText
    private val swipeDistance get() = 80f * context.resources.displayMetrics.density
    private val imeComponent get() = ComponentName(context, "com.kingzcheung.xime.service.XimeInputMethodService")
    private val imeId get() = imeComponent.flattenToShortString()
    private val inputMethodManager get() = context.getSystemService(InputMethodManager::class.java)
    private var previousIme: String? = null
    private var previouslyEnabled = false
    private var previousSubtype = -1
    private var savedSystemImeState = false
    private var oldInputLocation = ""

    @Before fun startKeyboard(): Unit = runBlocking {
        oldInputLocation = SettingsPreferences.getInputTextLocation(context)
        SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR)
        previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        previousSubtype = Settings.Secure.getInt(context.contentResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, -1)
        previouslyEnabled = inputMethodManager.enabledInputMethodList.any { it.id == imeId }
        savedSystemImeState = true
        // A service-owned ComposeView outlives a test's ComposeTestRule. Reusing it in the
        // next test would keep the previous rule's disposed recomposer and invisible test root.
        stopKeyboardService()
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        engine.initialize(user, shared)
        assertTrue(RimeConfigHelper.ensureDeployment(context))
        assertTrue(engine.ensureSession())
        engine.clearQueuedComposition()
        shell("ime enable $imeId")
        shell("ime set $imeId")
        rule.setContent {
            AndroidView(factory = { EditText(it).also { field ->
                editor = field
                field.hint = "字面输入回归"
                field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } },
                modifier = Modifier.fillMaxWidth().height(120.dp))
        }
        rule.waitUntil(10_000) {
            var ready = false
            rule.runOnUiThread { ready = editor.isAttachedToWindow && editor.hasWindowFocus() }
            ready
        }
        rule.runOnUiThread {
            assertTrue("测试输入框必须取得焦点", editor.requestFocus())
            inputMethodManager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("language-key-control", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After fun stopKeyboardAndRestoreSystemIme() {
        if (!savedSystemImeState) return
        try {
            if (::editor.isInitialized) {
                rule.runOnUiThread {
                    inputMethodManager.hideSoftInputFromWindow(editor.windowToken, 0)
                    editor.clearFocus()
                }
            }
            stopKeyboardService()
        } finally {
            SettingsPreferences.setInputTextLocation(context, oldInputLocation)
            if (previouslyEnabled) shell("ime enable $imeId")
            previousIme?.takeIf { it.isNotBlank() }?.let { shell("ime set $it") }
            shell("settings put secure selected_input_method_subtype $previousSubtype")
        }
    }

    @Suppress("DEPRECATION") // Android still exposes this application's own running services.
    private fun stopKeyboardService() {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val waitForEngineDestroy = RimeEngine.isInitialized() &&
            activityManager.getRunningServices(Int.MAX_VALUE).any { it.service == imeComponent }
        shell("ime disable $imeId")
        rule.waitUntil(10_000) {
            activityManager.getRunningServices(Int.MAX_VALUE).none { it.service == imeComponent } &&
                (!waitForEngineDestroy || !RimeEngine.isInitialized())
        }
        // Service removal and delivery of onDestroy happen on different threads. Drain Main
        // before initializing Rime again or allowing this test's recomposer to be disposed.
        instrumentation.waitForIdleSync()
    }

    private fun chooseMode(id: String) {
        rule.chooseModeThroughLanguageAndPanel(id)
    }

    private fun tap(label: String) {
        rule.onNodeWithText(label, ignoreCase = true).performTouchInput { down(center); up() }
    }

    private fun swipeUp(label: String) {
        val distance = swipeDistance
        rule.onNodeWithText(label, ignoreCase = true).performTouchInput {
            down(center)
            moveTo(center - Offset(0f, distance), delayMillis = 120)
            up()
        }
    }

    private fun text(): String {
        var value = ""
        rule.runOnUiThread { value = editor.text.toString() }
        return value
    }

    private fun assertSettled(expected: String) {
        rule.waitUntil(5000) { text() == expected && engine.getInput().isEmpty() }
        android.os.SystemClock.sleep(350)
        rule.waitForIdle()
        assertEquals(expected, text())
        assertEquals("字面上屏后不能留旧编码", "", engine.getInput())
    }


    private fun openEditor() {
        rule.waitUntil(5000) { rule.onAllNodesWithTag("candidate-preedit").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("candidate-preedit").performTouchInput { click() }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("preedit-editor-code").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnUiThread { assertTrue("点击预编辑浮层不能抢走宿主焦点", editor.hasFocus()) }
    }
    private fun draft(value: String, caret: Int = value.length) {
        rule.onNodeWithTag("preedit-editor-code").performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString(value))) }
        rule.onNodeWithTag("preedit-editor-code").performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(caret, caret, false)) }
    }
    private fun applyDraft() {
        rule.onNodeWithTag("preedit-editor-close").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("preedit-editor").fetchSemanticsNodes().isEmpty() }
    }
    private fun selectExpandedCandidate(value: String) {
        val candidates = engine.getAllCandidates().toList()
        val candidate = candidates.firstOrNull { it.text == value }
        assertNotNull("Rime 全量候选缺少 $value，input=${engine.getInput()} candidates=${candidates.take(30)}", candidate)
        // Expanded entries render the word and its annotation in one styled Text node.
        val comment = candidate!!.comment.replace("~", "")
        val displayText = value + if (comment.isNotEmpty()) " $comment" else ""
        rule.onNodeWithTag("candidate-expansion").performTouchInput { click() }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("expanded-candidates").fetchSemanticsNodes().isNotEmpty() }
        try {
            rule.onNodeWithTag("expanded-candidates").performScrollToNode(hasText(displayText, substring = false))
            rule.onNode(hasText(displayText, substring = false) and hasAnyAncestor(hasTestTag("expanded-candidates")))
                .performTouchInput { click() }
        } catch (failure: Throwable) {
            screenshot("candidate-$value-failure")
            val dir = File(context.getExternalFilesDir(null), "preedit-editor").apply { mkdirs() }
            File(dir, "candidate-$value-failure.txt").writeText(
                "expected=$displayText input=${engine.getInput()} candidates=$candidates\n" +
                    rule.onNodeWithTag("expanded-candidates", useUnmergedTree = true).printToString())
            throw failure
        }
    }
    private fun setPrefix() = rule.runOnUiThread { editor.setText("前文"); editor.setSelection(editor.length()) }
    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "preedit-editor").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also { image ->
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }

    @Test fun liveEditorStaysAboveCandidatesAndUsesOriginalKeysWithoutChangingHostText() {
        chooseMode("rime_ice"); setPrefix()
        "nihao".forEach { tap(it.toString()) }
        rule.waitUntil(5000) { engine.getInput() == "nihao" }
        // Editor restarts reapply this setting. Idempotent updates must retain composition.
        repeat(12) { engine.setPageSize("rime_ice", SettingsPreferences.getPageSize(context)) }
        assertEquals("nihao", engine.getInput())
        val qBefore = rule.onNodeWithText("q", ignoreCase = true).fetchSemanticsNode()
        val qPosition = qBefore.positionOnScreen
        val qSize = qBefore.size
        val preview = rule.onNodeWithTag("candidate-preedit").fetchSemanticsNode()
        val previewHeight = preview.size.height
        rule.onNodeWithText("ni'hao", useUnmergedTree = true).assertExists()
        val previewLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText("ni'hao", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(previewLayouts) }
        screenshot("pinyin-preview-segmented")
        openEditor()
        val editLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithTag("preedit-editor-code").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(editLayouts) }
        assertEquals(previewLayouts.single().layoutInput.style.fontSize, editLayouts.single().layoutInput.style.fontSize)
        assertEquals(previewLayouts.single().layoutInput.style.color, editLayouts.single().layoutInput.style.color)
        screenshot("pinyin-editor-segmented")
        val strip = rule.onNodeWithTag("preedit-editor").captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "preedit-editor/pinyin-editor-surface.png").outputStream().use {
            strip.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNodeWithText("ni'hao", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("candidate-preedit").assertDoesNotExist()
        val bar = rule.onNodeWithTag("preedit-editor").fetchSemanticsNode()
        val candidate = rule.onNodeWithTag("candidate-expansion").fetchSemanticsNode()
        assertTrue("editor must be above the candidate row", bar.positionOnScreen.y + bar.size.height <= candidate.positionOnScreen.y + 2)
        val qAfter = rule.onNodeWithText("q", ignoreCase = true).fetchSemanticsNode()
        assertEquals(qPosition, qAfter.positionOnScreen); assertEquals(qSize, qAfter.size)
        rule.onNodeWithTag("preedit-apply").assertDoesNotExist()
        assertEquals(previewHeight, rule.onNodeWithTag("preedit-editor-surface").fetchSemanticsNode().size.height)
        // Both sides of a separator remain distinct cursor positions, and the normal
        // delete key removes the separator itself without deleting the preceding letter.
        for (caret in listOf(2, 3, 2, 3)) {
            rule.onNodeWithTag("preedit-editor-code").performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(caret, caret, false)) }
            rule.onNodeWithText("ni'hao", useUnmergedTree = true).assertExists()
        }
        rule.waitUntil(5000) {
            rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange].start == 3
        }
        // getInput() deliberately returns "" when its non-blocking engine lock is busy.
        // Read the locked editor snapshot so pending caret jobs cannot look like lost code.
        assertEquals("opening and moving must not rewrite code", "nihao", engine.readPinyinEditSnapshot().firstOrNull())
        rule.onNodeWithContentDescription("删除", true).performTouchInput { click() }
        rule.waitUntil(5000) { rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "nihao" }
        rule.onNodeWithTag("preedit-editor-code").assertTextEquals("nihao")
        assertEquals("nihao", engine.getInput())
        draft("nihao", 2)
        tap("m")
        rule.waitUntil(5000) { engine.getInput() == "nimhao" }
        assertEquals("前文", text())
        screenshot("pinyin-editor-above-original-keyboard")
        applyDraft()
        assertEquals("nimhao", engine.getInput())
        openEditor(); draft("womf")
        rule.waitUntil(5000) { engine.getInput() == "womf" }
        shell("input keyevent 4")
        rule.waitUntil(5000) { rule.onAllNodesWithTag("preedit-editor").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("language-key-control", useUnmergedTree = true).assertExists()
        assertEquals("womf", engine.getInput()); assertEquals("前文", text())
        rule.runOnUiThread { assertTrue(editor.hasFocus()) }
        engine.clearQueuedComposition()
    }

    @Test fun spaceDragEditsPinyinAndDeletingAllClosesEditorWithoutMovingHostCaret() {
        chooseMode("rime_ice"); setPrefix()
        "nihao".forEach { tap(it.toString()) }
        rule.waitUntil(5000) { engine.getInput() == "nihao" }
        openEditor()
        rule.onNodeWithTag("preedit-editor").assertHeightIsEqualTo(44.dp)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space-key", true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("space-key", true).performTouchInput { moveTo(center - Offset(50f, 0f)); up() }
        rule.mainClock.autoAdvance = true
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot()[1].toInt() < 5 }
        assertEquals("前文", text())
        rule.runOnUiThread { assertEquals(2, editor.selectionStart) }
        draft("a")
        rule.waitUntil(5000) { engine.getInput() == "a" }
        rule.onNodeWithContentDescription("删除", true).performTouchInput { click() }
        rule.waitUntil(5000) { engine.getInput().isEmpty() && rule.onAllNodesWithTag("preedit-editor").fetchSemanticsNodes().isEmpty() }
        assertEquals("前文", text())
    }
    @Test fun punctuationCommitsImmediatelyAndNeverLeavesCandidates() {
        chooseMode("rime_ice"); setPrefix()
        "nihao".forEach { tap(it.toString()) }
        rule.waitUntil(5000) { engine.getCandidates().contains("你好") }
        tap("，")
        assertSettled("前文你好，")
        rule.onNodeWithTag("candidate-expansion").assertDoesNotExist()
        rule.onNodeWithTag("candidate-preedit").assertDoesNotExist()
    }

    @Test fun mergedAndDoublePinyinEditsReturnToTheirOwnLayout() {
        chooseMode("pinyin_14jian"); setPrefix()
        listOf("bn", "ui", "gh", "as", "op").forEach(::tap)
        rule.waitUntil(5000) { engine.getInput() == "bugao" }
        openEditor(); draft("nihao"); applyDraft()
        rule.waitUntil(5000) { engine.getInput() == "nihao" }
        assertEquals("nihao", engine.getInput()); assertEquals("前文", text())
        rule.onNodeWithText("bn", ignoreCase = true).assertExists()
        rule.onNodeWithText("你好").performClick()
        rule.waitUntil(5000) { text() == "前文你好" }
        chooseMode("double_pinyin_flypy")
        "nihk".forEach { tap(it.toString()) }
        rule.waitUntil(5000) { engine.getInput() == "nihk" }
        openEditor(); draft("womf"); applyDraft()
        assertEquals("double_pinyin_flypy", engine.getCurrentSchema())
        rule.waitUntil(5000) { engine.getInput() == "womf" }
        assertEquals("womf", engine.getInput()); assertEquals("前文你好", text())
        engine.clearQueuedComposition()
    }

    @Test fun mergedReadingAndMultiTapEditorKeepLettersAndCaretStable() {
        chooseMode("pinyin_14jian"); setPrefix()
        listOf("qw", "er", "bn").forEach(::tap)
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().firstOrNull() == "qeb" }
        rule.onNode(hasText("wen") and hasAnyAncestor(hasTestTag("candidate-preedit")), useUnmergedTree = true).assertExists()
        screenshot("merged-reading-wen")
        openEditor()
        rule.onNodeWithTag("preedit-editor-code").assertTextEquals("wen")
        draft("we")
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().firstOrNull() == "we" }
        tap("bn"); tap("bn")
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().firstOrNull() == "wen" }
        rule.onNodeWithTag("preedit-editor-code").assertTextEquals("wen")
        draft("ni'hao", 3)
        for (caret in listOf(2, 3, 2, 3)) {
            rule.onNodeWithTag("preedit-editor-code").performSemanticsAction(SemanticsActions.SetSelection) { it(caret, caret, false) }
            rule.onNodeWithTag("preedit-editor-code").assertTextEquals("ni'hao")
        }
        rule.onNodeWithContentDescription("删除", true).performTouchInput { click() }
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().firstOrNull() == "nihao" }
        rule.onNodeWithTag("preedit-editor-code").assertTextEquals("nihao")
        rule.onNodeWithContentDescription("删除", true).performTouchInput { click() }
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().firstOrNull() == "nhao" }
        assertEquals("前文", text())
        engine.clearQueuedComposition()
    }

    @Test fun t9EditsRebuildTheBufferAndKeepPreviouslySelectedText() {
        chooseMode("t9_pinyin"); setPrefix()
        listOf("MNO", "GHI", "GHI", "ABC", "MNO").forEach(::tap)
        rule.waitUntil(5000) { engine.getInput().isNotEmpty() && engine.getCandidates().contains("你好") }
        rule.onNodeWithText("ni'hao", useUnmergedTree = true).assertExists()
        openEditor(); draft("ni'hao", 3)
        rule.onNodeWithTag("t9-delete-key").performTouchInput { click() }
        rule.waitUntil(5000) { rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "nihao" }
        rule.onNodeWithTag("t9-delete-key").performTouchInput { click() }
        rule.waitUntil(5000) { rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "nhao" }
        draft("ni'hao", 3)
        tap("MNO"); tap("MNO"); tap("MNO")
        rule.waitUntil(5000) { rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "ni'ohao" }
        rule.waitForIdle()
        val shown = rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        assertFalse("T9 editor must show its reading, not raw key digits: $shown", shown.any { it.isDigit() })
        rule.onNodeWithTag("t9-delete-key").performTouchInput { down(center); up() }
        rule.waitUntil(5000) { rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "ni'hao" }
        rule.waitForIdle()
        screenshot("t9-live-editor-original-keys")
        applyDraft()
        rule.waitUntil(5000) { engine.getInput().contains("hao") }
        val edited = engine.getInput()
        assertTrue(edited.contains("ni")); assertTrue(edited.contains("hao")); assertEquals("前文", text())
        // Select only the first word, leaving a protected nine-key partial commit.
        selectExpandedCandidate("你")
        rule.waitUntil(5000) { engine.getInput().isNotEmpty() && engine.getCandidates().isNotEmpty() }
        openEditor()
        // Actually edit the suffix. Merely reopening and assigning the same code is
        // a caret-only operation and must preserve the original T9 undo history.
        draft("ha")
        rule.waitUntil(5000) { engine.getInput() == "ha" }
        draft("hao"); applyDraft()
        assertEquals("前文", text())
        screenshot("t9-edited-partial")
        tap("MNO")
        rule.waitUntil(5000) { engine.getInput().contains("hao") && engine.getInput().endsWith("6") }
        // Delete the new key through the original nine-key control.
        rule.onNodeWithTag("t9-delete-key").performTouchInput { down(center); up() }
        try {
            rule.waitUntil(5000) { engine.getInput().contains("hao") && !engine.getInput().endsWith("6") }
        } catch (failure: Throwable) {
            screenshot("t9-partial-delete-failed")
            throw AssertionError("input=${engine.getInput()} remaining=${engine.t9GetRemainingDigits()} panel=${engine.t9GetLeftPanelState()} candidates=${engine.getCandidates().take(8)}", failure)
        }
        selectExpandedCandidate("好")
        rule.waitUntil(5000) { text() == "前文你好" }
        assertEquals("", engine.getInput())
    }

    @Test fun japanesePreeditHasNoChineseEditingEntry() {
        chooseMode("japanese")
        "kana".forEach { tap(it.toString()) }
        rule.waitUntil(5000) { engine.getInput().isNotEmpty() }
        // Its display can remain visible, but the Chinese editor action must be absent.
        rule.onAllNodesWithTag("candidate-preedit").fetchSemanticsNodes().forEach { node ->
            assertFalse(node.config.contains(SemanticsActions.OnClick))
        }
        rule.onNodeWithTag("preedit-editor").assertDoesNotExist()
        engine.clearQueuedComposition()
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
