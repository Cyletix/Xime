package com.kingzcheung.xime.service

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.rime.RimeProcessResult
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.util.FileLogger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps sub-dp motion until the next whole offset, without accumulating debt at an edge. */
internal class FloatingDragAxis {
    private var remainder = 0f
    private var lastPosition: Int? = null

    fun move(current: Int, delta: Float, minimum: Int, maximum: Int): Int {
        if (lastPosition != current) remainder = 0f  // external reset / cancel / geometry change
        val position = (current + remainder + delta).coerceIn(minimum.toFloat(), maximum.toFloat())
        val next = position.roundToInt()
        remainder = position - next
        lastPosition = next
        return next
    }

    fun reset() { remainder = 0f; lastPosition = null }
}

/**
 * 构建键盘回调集合（KeyboardCallbacks）。
 *
 * 所有回调直接操作服务层状态与方法；service 内部成员对同模块可见（internal）。
 * 与原始实现在 onCreateInputView 内联构建的行为完全一致：
 * 浮动模式或屏幕几何变化时重建，拖动边界不能使用切换前的窗口高度。
 */
@Composable
internal fun rememberImeKeyboardCallbacks(
    service: XimeInputMethodService,
    floatingMinY: Int,
    state: InputUIState,
    effectiveScreenH: Int,
): KeyboardCallbacks {
    val view = LocalView.current
    val preeditEditor = remember(service) { ImePreeditEditor(service) }
    return remember(floatingMinY, state.isFloatingMode, effectiveScreenH, state.inputSessionId, state.showKeyboardResize) {
        val floatingDragX = FloatingDragAxis()
        val floatingDragY = FloatingDragAxis()
        KeyboardCallbacks(
            onOpenPreeditEditor = preeditEditor::open,
            onLivePreeditEdit = preeditEditor::edit,
            onKeyPress = { key, isShifted ->
                if (service.keyboardCallbacks?.onPreeditKeyInput?.invoke(key) != true) {
                    val numberPanel = service.keyboardViewModel.keyboardState.value is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.Number
                    val separator = key == "'" && service.candidateState.value.isComposing && !service.uiState.value.isAsciiMode
                    if (!numberPanel && !separator && isLiteralPunctuation(key)) {
                        service.textCommit.commitLiteralText(key)
                    } else service.keyRouter.handleKeyPress(key, isShifted)
                }
            },
            onJapaneseKanaAction = { action ->
                val input = (action as? com.kingzcheung.xime.ui.keyboard.JapaneseKanaAction.Input)?.romaji
                if (input in listOf(",", ".", "?", "!", "/", "(", ")")) {
                    val punctuation = when (input) { "," -> "、"; "." -> "。"; "/" -> "・"; else -> input!! }
                    service.textCommit.commitLiteralText(punctuation)
                } else service.schemaController.handleJapaneseKanaAction(action)
            },
            onKeyPressDown = { key ->
                service.predictionManager.invalidatePendingPredictions()
                service.feedbackManager.performKeyPressDownEffect(key, view)
            },
            onKeyRelease = { key ->
                service.feedbackManager.hapticFeedback(view, keyUp = true, type = KeyFeedbackType.fromKey(key))
            },
            onCandidateSelect = { index ->
                service.keyRouter.selectCandidate(index)
            },
            onCandidateDelete = { index ->
                service.keyRouter.deleteCandidate(index)
            },
            onAssociationSelect = { index ->
                service.feedbackManager.performKeyPressEffect(view = view)
                val cs = service.candidateState.value
                val adjustedCandidates = if (cs.pendingEnglishText.isNotEmpty() && cs.englishReplaceSupported) {
                    listOf(cs.pendingEnglishText) + cs.associationCandidates
                } else {
                    cs.associationCandidates
                }
                if (index >= 0 && index < adjustedCandidates.size) {
                    val text = adjustedCandidates[index]
                    val pendingEnglish = cs.pendingEnglishText
                    if (pendingEnglish.isNotEmpty()) {
                        // 英文直接上屏模式：编码已逐字落盘，选中候选词时需回删屏上编码再提交候选词。
                        // 第 0 项即当前已键入文本本身（上屏确认），无需替换。
                        if (index == 0 && text == pendingEnglish) {
                            service.candidateState.value = service.candidateState.value.copy(
                                pendingEnglishText = "",
                                associationCandidates = emptyList()
                            )
                        } else {
                            // 内部编辑器（快捷发送/工具面板）与宿主 InputConnection 统一走
                            // service 层重定向，避免编码回删/替换作用到错误的屏上文本。
                            var replaced = service.replaceBeforeCursor(pendingEnglish, text)
                            if (!replaced) {
                                // 光标位置与编码不对应（用户移动过光标）：放弃替换，
                                // 候选词降级为直接追加上屏，避免误删用户文本。
                                service.commitText(text)
                            }
                            FileLogger.d(
                                XimeInputMethodService.TAG,
                                "english candidate replace: replaced=$replaced, expected=${pendingEnglish.length} chars"
                            )
                            service.candidateState.value = service.candidateState.value.copy(
                                pendingEnglishText = "",
                                associationCandidates = emptyList()
                            )
                        }
                    } else {
                        // 单次联想模式（仅中文模式——英文 commitText 不触发推理，
                        // 否则抑制标志会悬挂并吞掉下次切回中文后的首轮推理）：
                        // 联想候选上屏前先置抑制标志——commitText 触发的下一轮自动推理
                        // 被跳过并清空联想候选（只推理一次）。
                        // 连续联想模式：不抑制，commitText 自动推理新联想（一直上屏一直推理）。
                        if (!service.uiState.value.isAsciiMode && SettingsPreferences.isSingleAssociationMode(service)) {
                            service.predictionManager.suppressNextPredictionOnce()
                        }
                        service.commitText(text)
                        service.updateUI()
                    }
                }
            },
            onClearAssociation = {
                // 清空英文联想候选，并结束英文输入态：pendingEnglish 清空后，
                // 后续刷新不再触发联想异步回填（否则"清了又冒出来"，见
                // ImeSessionController/updateUIWithResult 的在途回填校验）。
                service.candidateState.value = service.candidateState.value.copy(
                    associationCandidates = emptyList(),
                    pendingEnglishText = ""
                )
            },
            onToggleDarkMode = { service.toggleDarkMode() },
            onClipboard = {},
            onDismissClipboardPreview = {
                if (service.candidateState.value.isShowingRecentClipboard) {
                    service.candidateState.value = service.candidateState.value.copy(
                        candidates = emptyList(),
                        candidateComments = emptyList(),
                        isShowingRecentClipboard = false,
                    )
                }
            },
            onClipboardSelect = { text -> service.textCommit.selectClipboardItem(text) },
            onClipboardPullRemote = { service.clipboardSyncBridge?.pullOnce() },
            onCommitText = { text -> service.textCommit.commitLiteralText(text) },
            onDeleteText = { count -> service.textCommit.deleteClipboardChars(count) },
            onQuickSend = {},
            onKeyboardResize = {
                service.keyboardViewModel.closeOverlay()
                val config = service.resources.configuration
                val isLandscape = config.screenWidthDp > config.screenHeightDp
                val s = service.uiState.value
                val currentHeight = com.kingzcheung.xime.settings.KeyboardHeightProfiles.selected(
                    service, s.isFloatingMode, isLandscape, effectiveScreenH,
                )
                // 只有悬浮卡片测量值才可作为悬浮初值；固定模式保留独立的已保存宽度。
                val wide = !com.kingzcheung.xime.ui.keyboard.isT9Schema(s.currentSchemaId) &&
                    !com.kingzcheung.xime.ui.keyboard.isStrokeSchema(s.currentSchemaId) &&
                    s.currentSchemaId != "japanese_kana"
                val currentWidth = service.currentFloatingCardWidthDp.takeIf { s.isFloatingMode && it > 0 }
                    ?: com.kingzcheung.xime.ui.keyboard.resolvedFloatingWidth(
                        config.screenWidthDp, config.screenHeightDp, 0, 0, wide,
                        SettingsPreferences.getFloatingWidthDp(service, isLandscape),
                    )
                service.uiState.value = service.uiState.value.copy(
                    showKeyboardResize = true,
                    resizePreviewHeightDp = currentHeight,
                    resizePreviewWidthDp = currentWidth,
                    resizeInitialFloating = service.uiState.value.isFloatingMode,
                    resizeInitialSplit = SettingsPreferences.isSplitKeyboardEnabled(service),
                    resizeInitialX = service.uiState.value.floatingOffsetX,
                    resizeInitialY = service.uiState.value.floatingOffsetY,
                )
            },
            onReloadConfig = { service.schemaController.reloadConfig() },
            onSettings = { service.schemaController.openSettings() },
            onSwitchSchema = { schemaId -> service.schemaController.switchSchema(schemaId) },
            onReorderSchemas = { ids ->
                val schemas = service.uiState.value.schemas
                val order = com.kingzcheung.xime.settings.InputModes.mergeOrder(schemas.map { it.schemaId }, ids)
                com.kingzcheung.xime.settings.InputModes.saveOrder(service, order)
                service.uiState.value = service.uiState.value.copy(
                    schemas = com.kingzcheung.xime.settings.InputModes.available(schemas, order))
            },
            onHandwritingToggle = { service.schemaController.toggleHandwriting() },
            onToggleSchemaSwitch = { sw -> service.sessionController.toggleSchemaSwitch(sw) },
            onHideKeyboard = { service.hideKeyboard() },
            onSwitchKeyboard = {
                val imm = service.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                @Suppress("DEPRECATION")
                imm.showInputMethodPicker()
            },
            onToolbarEditingAction = { action -> service.schemaController.handleToolbarEditingAction(action) },
            onCommitImage = { imagePath ->
                val success = service.textCommit.commitImage(imagePath)
                if (!success) {
                    android.widget.Toast.makeText(
                        service,
                        "发送失败，已复制到剪贴板",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    service.clipboardManager.copyImageToSystemClipboard(imagePath)
                }
            },
            onVoiceModeChange = { enabled ->
                if (!enabled) {
                    // 长按抬手：结束语音会话（提交当前已识别文本并停止识别）
                    service.endVoiceSession()
                } else if (!service.uiState.value.isVoiceMode) {
                    service.uiState.value = service.uiState.value.copy(
                        isVoiceMode = true,
                        voiceSticky = false,
                        voiceButtonState = VoiceButtonState(bottomActive = true),
                        voiceRecognizedText = ""
                    )
                    service.keyboardViewModel.enterVoice()
                    service.feedbackManager.performVibration()
                    service.isTrackingVoiceButtons = true
                    service.keyboardContainer.enableVoiceButtonTracking()
                    service.voiceRecordingStarted = true
                    // 立即提前启动麦克风录音（等 150ms 的话模型常驻时加载极快，
                    // preStarted 可能还没创建好就被 startRecording 跳过，导致开头丢失）
                    service.voiceRecognitionHandler.startDelayedPreStart(0)
                    service.voiceRecognitionHandler.startRecognition()
                }
            },
            onVoiceStickyToggle = {
                val state = service.uiState.value
                if (state.isVoiceMode && state.voiceSticky) {
                    // 再次点击麦克风：停止录音并提交转录。
                    service.endVoiceSession()
                } else if (!state.isVoiceMode) {
                    // 工具栏独立语音组件，不占用空格或改变键盘页面。
                    service.feedbackManager.playKeySound("voice_start")
                    service.uiState.value = service.uiState.value.copy(
                        isVoiceMode = true,
                        voiceSticky = true,
                        voiceButtonState = VoiceButtonState(bottomActive = true),
                        voiceRecognizedText = ""
                    )
                    service.voiceRecordingStarted = true
                    service.voiceRecognitionHandler.startDelayedPreStart(0)
                    service.voiceRecognitionHandler.startRecognition()
                }
            },
            onPageDown = { service.keyRouter.pageDown() },
            onPageUp = { service.keyRouter.pageUp() },
            onGlobalCandidateSelect = { globalIndex ->
                service.keyRouter.selectCandidateGlobal(globalIndex)
            },
            onGlobalCandidateDelete = { globalIndex ->
                service.keyRouter.deleteCandidateGlobal(globalIndex)
            },
            onRequestExpandedCandidates = { service.refreshExpandedCandidates() },
            onCursorMove = { direction ->
                if (service.keyboardCallbacks?.onPreeditKeyInput?.invoke("preedit_cursor:$direction") == true) {
                    service.feedbackManager.hapticFeedback(view, type = KeyFeedbackType.CURSOR_STEP)
                } else service.schemaController.moveEditorCursor(direction) {
                    service.feedbackManager.hapticFeedback(view, type = KeyFeedbackType.CURSOR_STEP)
                }
            },
            onCursorMoveVertical = { steps ->
                val edge = if (steps < 0) "start" else "end"
                if (service.keyboardCallbacks?.onPreeditKeyInput?.invoke("preedit_cursor:$edge") != true)
                    service.schemaController.moveEditorCursorVertical(steps)
            },
            onGestureAction = { action, value ->
                action.execute(service, value)
            },
            onUpdateToolbarButtons = { buttons ->
                SettingsPreferences.setToolbarButtons(service, buttons)
                service.uiState.value = service.uiState.value.copy(toolbarButtons = buttons)
            },
            onOpenToolPanel = { pluginId -> service.openToolPanel(pluginId) },
            onToolPanelClose = { service.closeToolPanel() },
            onToolPanelItemClick = { item -> service.commitToolPanelItem(item.text) },
            onToolPanelAction = { actionId -> service.dispatchToolPanelAction(actionId) },
            onToolPanelFocusChange = { focused ->
                service.uiState.value = service.uiState.value.copy(
                    toolPanelInputFocused = focused,
                )
            },
            onKeyboardModeChange = { chineseMode ->
                if (service.isChineseMode != chineseMode) {
                    service.isChineseMode = chineseMode
                    if (!chineseMode) {
                        service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
                    }
                }
            },
            onDismissDeploying = { service.notifyDeploymentStatus(false, "") },
            onFloatingModeChange = { enabled -> service.schemaController.toggleFloatingMode(enabled, floatingMinY, persist = !service.uiState.value.showKeyboardResize) },
            onFloatingKeyboardDrag = { dx, dy ->
                val s = service.uiState.value
                val density = service.resources.displayMetrics.density
                val screenW = (service.keyboardContainer.width / density).roundToInt().takeIf { it > 0 }
                    ?: service.resources.configuration.screenWidthDp
                val screenH = (service.keyboardContainer.height / density).roundToInt().takeIf { it > 0 }
                    ?: effectiveScreenH
                val cardWidth = service.currentFloatingCardWidthDp.takeIf { it > 0 }
                    ?: s.floatingWidthDp.takeIf { it > 0 } ?: screenW
                val halfMargin = ((screenW - cardWidth) / 2f).roundToInt().coerceAtLeast(0)
                val newX = floatingDragX.move(s.floatingOffsetX.coerceIn(-halfMargin, halfMargin), dx, -halfMargin, halfMargin)
                val actualCardH = if (service.currentFloatingCardHeightDp > 0) service.currentFloatingCardHeightDp else service.currentEffectiveKeyboardHeight
                val maxOffsetY = (screenH - actualCardH).coerceAtLeast(floatingMinY)
                val newY = floatingDragY.move(s.floatingOffsetY.coerceIn(floatingMinY, maxOffsetY), dy, floatingMinY, maxOffsetY)
                service.uiState.value = s.copy(
                    floatingOffsetX = newX,
                    floatingOffsetY = newY,
                )
            },
            onFloatingKeyboardDragEnd = {
                floatingDragX.reset()
                floatingDragY.reset()
                val s = service.uiState.value
                val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
                if (!s.showKeyboardResize) {
                    SettingsPreferences.setFloatingOffsetX(service, s.floatingOffsetX, isLandscape)
                    SettingsPreferences.setFloatingOffsetY(service, s.floatingOffsetY, isLandscape)
                }
            },
            onT9ReplaceFullPinyin = { pinyin ->
                service.serviceScope.launch(service.keyProcessingDispatcher) {
                    when {
                        pinyin == T9InputController.CLEAR_COMPOSITION_ONLY -> {
                            service.rimeEngine.clearComposition()
                        }
                        pinyin == T9InputController.CLEAR_ALL -> {
                            service.t9PartialSegments.clear()
                            service.rimeEngine.setInput("")
                            service.rimeEngine.clearComposition()
                        }
                        pinyin.isEmpty() -> {
                            service.rimeEngine.clearComposition()
                        }
                        else -> {
                            service.rimeEngine.setInput(pinyin)
                        }
                    }
                    val composition = service.rimeEngine.getComposition()
                    withContext(Dispatchers.Main) {
                        service.mainHandler.post { service.sessionController.applyComposition(composition) }
                    }
                }
            },
            onT9RightCommitUndone = { count ->
                // 右侧候选的 partial commit 只存在于输入法状态的 t9PartialSegments：
                // 候选栏模式只在候选栏显示，输入框模式写在 composing 区里，**都没有写入正文**。
                // 历史实现把 C++ 的"撤销段数"当成正文字符数调用 deleteBeforeCursor(count)，
                // 直接把光标前的正文删掉（2026-09-25 真机实证：选词后退格删掉正文）。
                if (SettingsPreferences.getInputTextLocation(service)
                    == SettingsPreferences.INPUT_TEXT_INPUT_BOX) {
                    service.endComposingInputBox()
                }
                // 只回滚输入法自己的段状态：count 是段数，不是字符数。
                val removed = service.t9PartialSegments.rollbackPartialSegments(count)
                removed.forEach { undone ->
                    service.serviceScope.launch(service.keyProcessingDispatcher) {
                        service.rimeEngine.t9Forget(undone.text, undone.pinyin)
                    }
                }
                if (removed.isNotEmpty()) {
                    service.uiState.value = service.uiState.value.copy(
                        t9RightCandidateSelectedCount =
                            (service.uiState.value.t9RightCandidateSelectedCount - removed.size).coerceAtLeast(0L),
                        t9SelectedCandidatePinyin = ""
                    )
                }
            },
            onT9RefreshComposition = { composition, injections ->
                // composition 由 T9 控制器在 flush 后一次取回并传入，
                // 避免在此再次 getComposition 造成重复 JNI 往返。
                // 控制器已在 Main 执行并校验刷新代际；二次 post 会让旧快照越过 literal 复位。
                service.sessionController.applyComposition(composition, emptyList(), injections)
            },
            onTCandidateTransform = { result ->
                // T9 后台线程（t9Dispatcher）调用：同步等插件至多 15ms，主线程零等待
                service.candidateTransform.transformForT9(result)
            },
            onT9SwitchAway = {
                service.keyRouter.postRimeJob {
                    service.sessionController.commitFirstCandidateAndClearT9()
                }
            },
            onCommitCandidateBeforeModeChange = {
                val cs = service.candidateState.value
                if (cs.pendingEnglishText.isNotEmpty()) {
                    // 英文直接上屏模式：编码字符已逐字落盘，切模式只需结束本轮输入（清状态），
                    // 不可再 commitText 否则会重复输出整个词。
                    service.candidateState.value = cs.copy(
                        pendingEnglishText = "",
                        associationCandidates = emptyList()
                    )
                } else if (!isT9Schema(service.uiState.value.currentSchemaId)
                    && cs.isComposing) {
                    if (cs.candidates.isNotEmpty()) {
                        if (service.rimeEngine.selectCandidate(0)) {
                            val text = service.rimeEngine.commit()
                            if (text.isNotEmpty()) service.commitText(text)
                        }
                    } else if (cs.preeditText.isNotEmpty()) {
                        // 提交原始键入串而非 preeditText：后者现为带回显分隔符的展示串（如 ni'hao），
                        // 直接上屏会混入分隔符。
                        service.commitText(cs.inputText)
                        service.rimeEngine.clearComposition()
                    }
                }
            },
            onShowQuickSendForm = {
                service.closeToolPanel()
                // 从 Overlay 页面（剪贴板面板）打开表单时必须退出 Overlay：
                // Overlay 渲染为全屏 clickable Box（吞点击），若不退出，表单会被
                // 盖在其下——可见但关闭按钮等点击全部失效（时灵时不灵的根源：
                // 从主键盘打开则无叠加）。
                service.keyboardViewModel.closeOverlay()
                val current = service.uiState.value
                service.uiState.value = current.copy(
                    showQuickSendForm = true,
                    quickSendFormFocused = true,
                    quickSendCodeFocused = false,
                    quickSendEditingItemId = null,
                    quickSendEditingItemText = "",
                    quickSendEditingItemCode = "",
                    enterKeyText = "确定",
                )
                // 撑高由 SideEffect 驱动：uiState 变化 → Compose 内容高度变化 →
                // updateHeight 改容器物理高度 → relayout → onComputeInsets 自动重算。
            },
            onQuickSendEditItem = { id, text, code ->
                // 同 onShowQuickSendForm：编辑入口在剪贴板面板内，先退出 Overlay 防表单被盖
                service.keyboardViewModel.closeOverlay()
                service.uiState.value = service.uiState.value.copy(
                    showQuickSendForm = true,
                    quickSendFormFocused = true,
                    quickSendCodeFocused = false,
                    quickSendEditingItemId = id,
                    quickSendEditingItemText = text,
                    quickSendEditingItemCode = code,
                    enterKeyText = "确定",
                )
                QuickSendFormEditTextHolder.editText?.let { et ->
                    et.setText(text)
                    et.setSelection(text.length)
                }
            },
            onHideQuickSendForm = {
                android.util.Log.d("QuickSendForm", "onHideQuickSendForm invoked")
                service.uiState.value = service.uiState.value.copy(
                    showQuickSendForm = false,
                    quickSendFormFocused = false,
                    quickSendCodeFocused = false,
                    quickSendEditingItemId = null,
                    quickSendEditingItemText = "",
                    quickSendEditingItemCode = "",
                    enterKeyText = "发送",
                )
                QuickSendFormEditTextHolder.editText = null
                QuickSendFormCodeEditTextHolder.editText = null
                service.keyboardViewModel.showOverlay(OverlayRoute.Clipboard(1))
                // insets 恢复由 SideEffect 驱动：表单收起 → 容器物理高度还原 →
                // relayout → onComputeInsets 自动重算，无需强制触发。
            },
            onQuickSendFormFocusChange = { focused: Boolean ->
                // 聚焦时回车键为"确定"；失焦不改文案——表单仍显示，回车保持"确定"
                // 语义（提交并关闭，由 handleKeyPress 的 showQuickSendForm 分支保证）。
                // 关闭表单时由 onHideQuickSendForm / onWindowHidden 统一还原"发送"。
                val s = service.uiState.value
                service.uiState.value = if (focused) {
                    // 文本框抢回焦点：编码焦点清除，按键输入回到文本框
                    s.copy(quickSendFormFocused = true, quickSendCodeFocused = false, enterKeyText = "确定")
                } else {
                    s.copy(quickSendFormFocused = false, quickSendCodeFocused = false)
                }
            },
            onQuickSendCodeFocusChange = { focused: Boolean ->
                val s = service.uiState.value
                service.uiState.value = if (focused) {
                    // 编码框获得焦点：同样视为表单聚焦（回车"确定"/按键路由生效）
                    s.copy(quickSendFormFocused = true, quickSendCodeFocused = true, enterKeyText = "确定")
                } else {
                    s.copy(quickSendCodeFocused = false)
                }
            },
        )
    }
}
