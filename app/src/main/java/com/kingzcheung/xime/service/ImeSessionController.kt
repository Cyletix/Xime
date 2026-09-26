package com.kingzcheung.xime.service

import com.kingzcheung.xime.keyboard.HANDWRITING_SCHEMA_ID
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.rime.buildT9DisplayState
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.isPunctuationWidthSwitch
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 输入会话组合/UI 更新与方案开关。
 *
 * 承载 Rime composition 到候选栏状态的映射（applyComposition/updateUIWithResult）、
 * 方案名称/开关刷新与切换、T9 切离提交等逻辑。共享状态通过 service 引用访问。
 */
internal class ImeSessionController(private val service: XimeInputMethodService) {
    internal fun applyComposition(
        composition: com.kingzcheung.xime.rime.RimeComposition,
        pluginActions: List<CandidateAction> = emptyList(),
        t9PluginInjections: List<T9CandidateInjection> = emptyList(),
    ) {
        val inputText = composition.input
        val codeInInputBox = SettingsPreferences.getInputTextLocation(service) == SettingsPreferences.INPUT_TEXT_INPUT_BOX
        // 日语罗马音显示归一化：末尾未拼完的字母按按下的键显示（与 updateUIWithResult 同一出口规则）
        val preeditText = service.japaneseInputController.normalizePreedit(inputText, composition.preedit)
        val candidatesWithComments = composition.candidates.toList()
        val isAsciiMode = composition.isAsciiMode
        val hasNextPage = composition.hasNextPage
        val hasPrevPage = composition.hasPrevPage

        val pendingEnglish = service.candidateState.value.pendingEnglishText

        val (filteredTexts, filteredComments) = if (isAsciiMode) {
            val filtered = candidatesWithComments.filterNot { candidate ->
                candidate.text.any { it.code in 0x4E00..0x9FFF }
            }
            filtered.map { it.text } to filtered.map { it.comment }
        } else {
            candidatesWithComments.map { it.text } to candidatesWithComments.map { it.comment }
        }

        val isT9Schema = isT9Schema(service.uiState.value.currentSchemaId)
        // T9 候选词过滤：根据左侧选择历史过滤不匹配的候选词
        val (t9FilteredTexts, t9FilteredComments) = if (isT9Schema) {
            service.keyboardCallbacks?.onFilterT9Candidates?.invoke(filteredTexts, filteredComments)
                ?: (filteredTexts to filteredComments)
        } else {
            filteredTexts to filteredComments
        }
        val displayText: String
        val displayCandidates: List<String>
        val displayComments: List<String>
        val isComposing: Boolean
        var t9CandidateActions: List<CandidateAction> = emptyList()
        if (isT9Schema) {
            // T9 编码显示永不回退原始数字 input：preedit 缺失，或含未转换数字
            //（合成切不出词时引擎把原始输入当编码回退，如全拼+简拼混合态）时，
            // 保留上一帧字母编码，用户看到的编码必须是字母
            val rawPreedit = when {
                preeditText.isNotEmpty() && preeditText.none { it.isDigit() } -> preeditText
                inputText.isEmpty() -> ""
                else -> service.candidateState.value.preeditText
            }
            FileLogger.i(XimeInputMethodService.TAG) {
                "T9 display: enginePreedit='$preeditText' rawPreedit='$rawPreedit' input='$inputText' partials=${service.t9PartialSegments.size}"
            }
            // preedit 转换由 C++ t9_filter 完成，Kotlin 侧直接使用引擎输出的 preedit
            val display = buildT9DisplayState(
                service.t9PartialSegments.map { it.text }, rawPreedit, inputText, t9FilteredTexts, t9FilteredComments
            )
            displayText = display.displayText
            // T9 插件候选按引擎锚点插入（transformForT9 已把插件输出顺序折叠为
            // anchorEngineIndex；编码命中→锚 0→第二候选位，内容命中→跟随对应候选；
            // 无锚点追加末尾）。引擎段 actions 用平行 index 引用（T9 点击走引擎分支，
            // 不实际消费 engineIndex，仅用于插件候选分流）
            val baseCands = display.displayCandidates
            val baseComments = display.displayComments
            val byAnchor = t9PluginInjections.groupBy { it.anchorEngineIndex }
            val mergedCands = ArrayList<String>(baseCands.size + t9PluginInjections.size)
            val mergedComments = ArrayList<String>(mergedCands.size)
            val mergedActions = ArrayList<CandidateAction>(mergedCands.size)
            for ((idx, cand) in baseCands.withIndex()) {
                byAnchor[idx]?.forEach { inj ->
                    mergedCands.add(inj.snapshot.text)
                    mergedComments.add(inj.snapshot.comment)
                    mergedActions.add(CandidateAction.plugin(inj.snapshot.text))
                }
                mergedCands.add(cand)
                mergedComments.add(baseComments.getOrElse(idx) { "" })
                mergedActions.add(CandidateAction.engine(idx))
            }
            byAnchor[null]?.forEach { inj ->
                mergedCands.add(inj.snapshot.text)
                mergedComments.add(inj.snapshot.comment)
                mergedActions.add(CandidateAction.plugin(inj.snapshot.text))
            }
            displayCandidates = mergedCands
            displayComments = mergedComments
            isComposing = display.isComposing
            t9CandidateActions = mergedActions
        } else {
            // 非 T9 方案：preeditText 用引擎回显（带音节分隔符，如全拼 ni'hao）供候选栏展示；
            // inputText 保留原始键入串（无分隔）——空格/切模式等提交路径会把它直接上屏，
            // 不能混入回显字符。历史上曾整体用 input 规避自装双拼插件的展开编码显示，
            // 代价是全拼丢失分隔符；现仅显示层恢复 librime 标准回显。
            displayText = if (preeditText.isNotEmpty()) preeditText else inputText
            displayCandidates = filteredTexts
            displayComments = filteredComments
            isComposing = inputText.isNotEmpty()
        }

        // 用户开始实际输入时，清除候选栏中残留的 inline suggestions
        if (displayText.isNotEmpty() || displayCandidates.isNotEmpty()) {
            service.dismissInlineSuggestions()
        }

        service.candidateState.value = service.candidateState.value.copy(
            // T9 保持合成显示态同源；非 T9 仅显示层用 preedit 回显，inputText 保留原始键入串
            inputText = if (isT9Schema) displayText else inputText,
            preeditText = displayText,
            candidates = displayCandidates,
            candidateComments = displayComments,
            isComposing = isComposing,
            associationCandidates = if ((isAsciiMode || !service.isChineseMode) && pendingEnglish.isEmpty()) emptyList() else service.candidateState.value.associationCandidates,
            isShowingRecentClipboard = false,
            hasNextPage = hasNextPage,
            hasPrevPage = hasPrevPage,
            // 候选词变换映射（全键盘 pluginActions / T9 平行 actions；防御空）
            candidateActions = if (isT9Schema) t9CandidateActions else pluginActions
        )
        if (isAsciiMode != service.uiState.value.isAsciiMode) {
            FileLogger.i(XimeInputMethodService.TAG, "applyComposition: ascii ${service.uiState.value.isAsciiMode}->$isAsciiMode")
        }
        // 展开态时刷新跨页全量候选并重置页码（编码已变化）
        service.refreshExpandedCandidates()
        // composing 快照 → 插件（input_changed 事件；T9 与候选栏同源显示态）
        service.pluginEvents.dispatchInputChanged(if (isT9Schema) displayText else inputText)

        if (pendingEnglish.isNotEmpty() && !service.isSecretEditor() && service.supportsEnglishCandidateReplace()) {
            service.serviceScope.launch {
                val candidates = service.predictionManager.getEnglishAssociations(pendingEnglish, PredictionManager.MAX_ASSOCIATION_COUNT)
                withContext(Dispatchers.Main) {
                    val current = service.candidateState.value
                    // 在途联想过期校验：pendingEnglish 已变/已清（如点击候选栏"清空"）→
                    // 丢弃迟到回填，防止"清了又冒出来"与旧联想覆盖新联想的竞态
                    if (current.pendingEnglishText == pendingEnglish) {
                        service.candidateState.value = current.copy(associationCandidates = candidates)
                    }
                }
            }
        }

        if (codeInInputBox && !service.uiState.value.toolPanelInputFocused) {
            val ic = service.currentInputConnection
            if (isComposing && displayText.isNotEmpty()) {
                showInputBoxComposition(ic, displayText)
            } else {
                service.endComposingInputBox()
            }
        }
    }

    /** 在输入框模式向编辑器写入编码文本。 */
    private fun showInputBoxComposition(ic: android.view.inputmethod.InputConnection, displayText: String) {
        // 第二参数为 1：光标相对编码起始偏移 1 个字符，使光标落在编码末尾，
        // 避免传 displayText.length 时被 AOSP 钳制到整段文本末尾（光标跑到最右边）。
        // 标记输入框存在 composing 区域：endComposingInputBox 仅在此标记下执行 setComposingText("") 清空，
        // 否则该调用会在光标处插入空串，光标处有选中文字时等于删除选区。
        service.markInputBoxComposing()
        ic.beginBatchEdit()
        try {
            ic.setComposingText(displayText, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    internal fun updateUIWithResult(
        result: com.kingzcheung.xime.rime.RimeProcessResult,
        pluginActions: List<CandidateAction> = emptyList(),
    ) {
        val isAsciiMode = result.isAsciiMode
        val candidatesWithComments = result.candidates
        // 日语罗马音显示归一化：末尾未拼完的字母按按下的键显示（引擎回显会给 っ / ん），
        // 与退格单位同源。放在出口而不是按键路径，刷新（updateUI/applyComposition）才不打回原回显。
        val preeditText = service.japaneseInputController.normalizePreedit(result.inputText, result.preeditText)

        val pendingEnglish = service.candidateState.value.pendingEnglishText

        val (filteredTexts, filteredComments) = if (isAsciiMode) {
            val filtered = candidatesWithComments.filterNot { candidate ->
                candidate.text.any { it.code in 0x4E00..0x9FFF }
            }
            filtered.map { it.text } to filtered.map { it.comment }
        } else {
            candidatesWithComments.map { it.text } to candidatesWithComments.map { it.comment }
        }

        // 非 T9 方案（如双拼）使用原始输入文本显示，
        // 避免显示 rime speller 展开后的编码（如双拼 i → ch）
        val isT9Schema = isT9Schema(service.uiState.value.currentSchemaId)
        // T9 候选词过滤：根据左侧选择历史（全拼/简拼）过滤不匹配的候选词
        val (t9FilteredTexts, t9FilteredComments) = if (isT9Schema) {
            service.keyboardCallbacks?.onFilterT9Candidates?.invoke(filteredTexts, filteredComments)
                ?: (filteredTexts to filteredComments)
        } else {
            filteredTexts to filteredComments
        }
        val displayText: String
        val displayCandidates: List<String>
        val displayComments: List<String>
        val isComposing: Boolean
        if (isT9Schema) {
            // 同 applyComposition：T9 编码显示永不回退原始数字 input，
            // preedit 缺失或含未转换数字时保留上一帧字母编码
            val rawPreedit = when {
                preeditText.isNotEmpty() && preeditText.none { it.isDigit() } -> preeditText
                result.inputText.isEmpty() -> ""
                else -> service.candidateState.value.preeditText
            }
            val display = buildT9DisplayState(
                service.t9PartialSegments.map { it.text }, rawPreedit, result.inputText, t9FilteredTexts, t9FilteredComments
            )
            displayText = display.displayText
            displayCandidates = display.displayCandidates
            displayComments = display.displayComments
            isComposing = display.isComposing
        } else {
            // 非 T9 方案：preeditText 用引擎回显（带音节分隔符，如全拼 ni'hao），空则回退 input；
            // inputText 保留原始键入串供提交路径直接上屏（见 applyComposition 注释）。
            displayText = if (preeditText.isNotEmpty()) preeditText else result.inputText
            displayCandidates = filteredTexts
            displayComments = filteredComments
            isComposing = result.inputText.isNotEmpty()
        }

        // 用户开始实际输入时，清除候选栏中残留的 inline suggestions
        if (displayText.isNotEmpty() || displayCandidates.isNotEmpty()) {
            service.dismissInlineSuggestions()
        }

        service.candidateState.value = service.candidateState.value.copy(
            inputText = if (isT9Schema) displayText else result.inputText,
            preeditText = displayText,
            candidates = displayCandidates,
            candidateComments = displayComments,
            isComposing = isComposing,
            associationCandidates = if ((isAsciiMode || !service.isChineseMode) && pendingEnglish.isEmpty()) emptyList() else service.candidateState.value.associationCandidates,
            isShowingRecentClipboard = false,
            hasNextPage = result.hasNextPage,
            hasPrevPage = result.hasPrevPage,
            // 候选词变换映射（T9 不接入变换，防御清空；ascii 时调用方不产生 actions）
            candidateActions = if (isT9Schema) emptyList() else pluginActions
        )
        service.uiState.value = service.uiState.value.copy(isAsciiMode = isAsciiMode)
        // 展开态时刷新跨页全量候选并重置页码（编码已变化）
        service.refreshExpandedCandidates()
        // 候选展开页：编码删空（候选与联想均空）时自动收起，不留空页
        service.maybeCollapseCandidatePage()
        // composing 快照 → 插件（input_changed 事件；空编码表示本轮输入结束）
        service.pluginEvents.dispatchInputChanged(if (isT9Schema) displayText else result.inputText)

        if (pendingEnglish.isNotEmpty() && !service.isSecretEditor() && service.supportsEnglishCandidateReplace()) {
            service.serviceScope.launch {
                val candidates = service.predictionManager.getEnglishAssociations(pendingEnglish, PredictionManager.MAX_ASSOCIATION_COUNT)
                withContext(Dispatchers.Main) {
                    val current = service.candidateState.value
                    // 在途联想过期校验：pendingEnglish 已变/已清 → 丢弃迟到回填
                    if (current.pendingEnglishText == pendingEnglish) {
                        service.candidateState.value = current.copy(associationCandidates = candidates)
                    }
                }
            }
        }

        if (SettingsPreferences.getInputTextLocation(service) == SettingsPreferences.INPUT_TEXT_INPUT_BOX) {
            val ic = service.currentInputConnection
            if (isComposing && displayText.isNotEmpty()) {
                showInputBoxComposition(ic, displayText)
            } else {
                service.endComposingInputBox()
            }
        }
    }

    private val schemaRefreshRevision = java.util.concurrent.atomic.AtomicLong()

    internal fun updateSchemaName() {
        val revision = schemaRefreshRevision.incrementAndGet()
        val context = service
        service.serviceScope.launch(Dispatchers.IO) {
            val page = service.keyboardViewModel.page.value
            val isHandwritingMode = (page as? com.kingzcheung.xime.keyboard.KeyboardPage.Main)?.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING
            val engineSchemaId = service.rimeEngine.getCurrentSchema()
            // session 未就绪时 getCurrentSchema() 返回空串：用持久化方案兜底，
            // 避免空值覆盖已正确的 currentSchemaId/schemaName 导致键盘退化为全键盘
            val currentSchemaId = when {
                // 引擎已切到非手写方案时以引擎为准：键盘若仍停留手写页（部署期间
                // fallback 的残留），钉死 handwriting 会让 UI 与引擎永久脱节，
                // 表现为选完方案后键盘卡在手写页
                engineSchemaId.isNotEmpty() && !isHandwritingSchema(engineSchemaId) -> engineSchemaId
                // 手写页在态时报告当前持久化的手写方案 id（内置为 handwriting，
                // 第三方手写方案报告其自身 id，方案名/图标显示才正确）
                isHandwritingMode -> SettingsPreferences.getCurrentSchema(context)
                    .takeIf { isHandwritingSchema(it) } ?: HANDWRITING_SCHEMA_ID
                engineSchemaId.isNotEmpty() -> engineSchemaId
                else -> SettingsPreferences.getCurrentSchema(context)
            }
            val name = SchemaManager.getSchemaDisplayName(context, currentSchemaId)

            val enabledIds = SchemaManager.getEnabledSchemas(context)
            val allSchemas = SchemaManager.discoverSchemas(context)
            val schemas = allSchemas
                .filter { meta -> meta.schemaId in enabledIds && SchemaManager.isSchemaCompiled(context, meta.schemaId) }
                .map { meta ->
                    com.kingzcheung.xime.settings.SchemaInfo(
                        schemaId = meta.schemaId,
                        name = meta.name,
                        version = meta.version,
                        author = meta.author,
                        description = meta.description,
                        isDownloaded = true
                    )
                }

            withContext(Dispatchers.Main) {
                if (revision != schemaRefreshRevision.get()) return@withContext
                service.uiState.value = service.uiState.value.copy(
                    schemaName = name ?: currentSchemaId,
                    currentSchemaId = currentSchemaId,
                    schemas = com.kingzcheung.xime.settings.InputModes.ordered(service, schemas)
                )
                refreshSchemaSwitches()
            }
        }
    }

    /** 读取当前引擎方案 `switches` 的实际取值，组装成菜单栏可展示的状态。 */
    private fun loadSchemaSwitches(schemaId: String): List<com.kingzcheung.xime.viewmodel.SchemaSwitchUiState> {
        if (schemaId.isEmpty()) return emptyList()
        val defs = SchemaManager.getSchemaSwitches(service, schemaId)
        val fullPunctuation = service.textCommit.isFullWidthPunctuation()
        // 全角/半角是标点宽度的唯一入口：方案里的 full_shape / half_shape / ascii_punct
        //（含 options 选项组）不再作为独立菜单项重复列出；引擎选项改由 applyPunctuationWidth 同步。
        val switches = defs.filterNot { isPunctuationWidthSwitch(it) }.map { def ->
            val index = when {
                def.name.isNotEmpty() && def.states.isNotEmpty() ->
                    if (service.rimeEngine.getOption(def.name)) minOf(1, def.states.size - 1) else 0
                def.options.isNotEmpty() -> {
                    val active = def.options.indexOfFirst { service.rimeEngine.getOption(it) }
                    if (active < 0) 0 else active
                }
                else -> 0
            }
            com.kingzcheung.xime.viewmodel.SchemaSwitchUiState(
                name = def.name,
                options = def.options,
                states = def.states,
                abbrev = def.abbrev,
                currentIndex = index
            )
        }
        // All layouts, including schemas without a native full_shape switch, share this control.
        return switches + com.kingzcheung.xime.viewmodel.SchemaSwitchUiState(
            name = "full_shape", states = listOf("半角", "全角"), currentIndex = if (fullPunctuation) 1 else 0)
    }

    /** 在引擎线程上刷新菜单栏方案开关状态。 */
    private fun refreshSchemaSwitches() {
        service.serviceScope.launch(service.keyProcessingDispatcher) {
            val schemaId = service.rimeEngine.getCurrentSchema()
            val switches = loadSchemaSwitches(schemaId)
            withContext(Dispatchers.Main) {
                service.uiState.value = service.uiState.value.copy(schemaSwitches = switches)
            }
        }
    }

    /** 菜单栏方案开关点击：切换引擎选项并刷新状态。 */
    internal fun toggleSchemaSwitch(sw: com.kingzcheung.xime.viewmodel.SchemaSwitchUiState) {
        service.serviceScope.launch(service.keyProcessingDispatcher) {
            if (sw.name == "ascii_mode") {
                service.schemaController.switchInputMethod()
            } else if (sw.name == "full_shape" || sw.name == "ascii_punct") {
                // 英文（ASCII）模式标点固定半角，全角/半角对它不生效：入口已隐藏，这里再兜底，
                // 不让英文下的点击悄悄改掉用户的中文宽度选择。
                if (service.uiState.value.isAsciiMode) return@launch
                val full = !service.textCommit.isFullWidthPunctuation()
                SettingsPreferences.setPunctuationFullWidth(service, full)
                // 用户选择是唯一权威：把同一份选择写回引擎，避免"菜单显示半角、实际输出全角"。
                applyPunctuationWidth(asciiMode = false)
                service.updateUI()
            } else if (sw.name.isNotEmpty()) {
                val newValue = !service.rimeEngine.getOption(sw.name)
                service.rimeEngine.setOption(sw.name, newValue)
                persistSchemaOption(sw.name, newValue)
                service.updateUI()
            } else if (sw.options.isNotEmpty()) {
                val nextIndex = (sw.currentIndex + 1) % sw.options.size
                sw.options.forEachIndexed { i, opt ->
                    service.rimeEngine.setOption(opt, i == nextIndex)
                    persistSchemaOption(opt, i == nextIndex)
                }
                service.updateUI()
            }
            refreshSchemaSwitches()
        }
    }

    /**
     * 把用户持久化的标点宽度选择写回引擎选项，让菜单/键面显示与实际输出同源。
     *
     * 全角/半角只作用于中文与日文：英文（ASCII）模式固定半角，只清掉全角字符输出
     * （ascii_punct 在英文态由 ascii_composer 接管，不动它，退出英文后中文标点不会被改写）。
     * 用户从未选择过宽度时同样不动引擎——此时界面显示值取自方案自身的 ascii_punct，两者本来一致。
     *
     * @param asciiMode 权威的中英状态，由调用方给出；不要读 uiState，切换方案/退出英文时
     *   uiState 尚未同步，会误判成英文而把中文键盘按半角处理。
     */
    internal fun applyPunctuationWidth(asciiMode: Boolean) {
        if (asciiMode) {
            service.rimeEngine.setOption("full_shape", false)
            return
        }
        if (!SettingsPreferences.hasPunctuationFullWidth(service)) return
        val full = SettingsPreferences.punctuationFullWidth(service, true)
        service.rimeEngine.setOption("ascii_punct", !full)
        service.rimeEngine.setOption("full_shape", full)
    }

    /** 将方案选项状态写入 librime user.yaml（var/option/<name>）。 */
    internal fun persistSchemaOption(name: String, value: Boolean) {
        if (RimeEngine.isInitialized()) {
            service.rimeEngine.setUserConfigBool("var/option/$name", value)
        }
    }

    /** 从 librime user.yaml 恢复方案选项（中/西、简/繁等），在切换方案后调用。 */
    internal fun restorePersistedSchemaOptions() {
        if (!RimeEngine.isInitialized()) return
        val schemaId = service.rimeEngine.getCurrentSchema()
        if (schemaId.isEmpty()) return
        val rimeAsciiBefore = service.rimeEngine.isAsciiMode()
        val defs = SchemaManager.getSchemaSwitches(service, schemaId)
        for (def in defs) {
            // 标点宽度不从 user.yaml 恢复：它的权威来源是用户的「全角／半角」选择，
            // 由 applyPunctuationWidth 统一回写（否则这里会把用户选择覆盖成旧值）。
            if (isPunctuationWidthSwitch(def)) continue
            if (def.name.isNotEmpty()) {
                service.rimeEngine.setOption(def.name, service.rimeEngine.getUserConfigBool("var/option/${def.name}"))
            } else if (def.options.isNotEmpty()) {
                val activeIndex = def.options.indexOfFirst { service.rimeEngine.getUserConfigBool("var/option/$it") }
                if (activeIndex >= 0) {
                    def.options.forEachIndexed { i, opt -> service.rimeEngine.setOption(opt, i == activeIndex) }
                }
            }
        }
        applyPunctuationWidth(service.rimeEngine.isAsciiMode())
        val rimeAsciiAfter = service.rimeEngine.isAsciiMode()
        if (rimeAsciiBefore != rimeAsciiAfter) {
            FileLogger.i(XimeInputMethodService.TAG, "restorePersistedSchemaOptions: ascii $rimeAsciiBefore -> $rimeAsciiAfter (ui=${service.uiState.value.isAsciiMode})")
        }
    }

    /**
     * T9 键盘切换离开时：提交右侧候选词列表首位候选词并清理 T9 和 Rime 状态。
     * 运行在 keyProcessingDispatcher 线程。
     */
    internal suspend fun commitFirstCandidateAndClearT9() {
        val isT9 = isT9Schema(service.uiState.value.currentSchemaId)
        if (!isT9) return

        val candState = service.candidateState.value
        val candidates = candState.candidates

        if (candidates.isNotEmpty()) {
            if (service.rimeEngine.selectCandidate(0)) {
                val committedText = service.rimeEngine.commit()
                if (committedText.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        service.commitText(committedText)
                    }
                }
            }
        }

        service.rimeEngine.clearComposition()

        withContext(Dispatchers.Main) {
            service.keyboardCallbacks?.onT9ReplaceFullPinyin?.invoke(T9InputController.CLEAR_ALL)
            service.uiState.value = service.uiState.value.copy(
                t9ResetSignal = service.uiState.value.t9ResetSignal + 1,
                t9RightCandidateSelectedCount = 0,
                t9SelectedCandidatePinyin = ""
            )
            service.t9PartialSegments.clear()
            service.candidateState.value = service.candidateState.value.copy(
                inputText = "",
                preeditText = "",
                candidates = emptyList(),
                candidateComments = emptyList(),
                isComposing = false,
                associationCandidates = emptyList(),
                hasNextPage = false,
                hasPrevPage = false,
                candidateActions = emptyList()
            )
        }
    }

}