package com.kingzcheung.xime.ui.keyboard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun KeyboardResizeOverlay(
    initialHeightDp: Int,
    defaultHeightDp: Int,
    currentBottomPaddingDp: Int,
    isFloatingMode: Boolean,
    initialWidthDp: Int = 0,
    minWidthDp: Int = 200,
    maxWidthDp: Int = Int.MAX_VALUE,
    initialOpacity: Float = 1f,
    onHeightChange: (Int) -> Unit,
    onWidthChange: (Int) -> Unit = {},
    onGeometryChange: (ResizeGeometry) -> Unit = {},
    onBottomPaddingChange: (Int) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onReset: (Int) -> Unit,
    onConfirm: (Int, Int, Boolean, Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onFloatingModeChange: ((Boolean) -> Unit)? = null,
    isSplitKeyboard: Boolean = false,
    onSplitKeyboardChange: ((Boolean) -> Unit)? = null,
    splitKeyboardSupported: Boolean = true,
    onPositionDrag: ((Float, Float) -> Unit)? = null,
    onPositionDragEnd: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    var currentHeightDp by remember { mutableFloatStateOf(initialHeightDp.toFloat()) }
    var currentWidthDp by remember { mutableFloatStateOf(initialWidthDp.toFloat()) }
    var currentBottomPaddingDpState by remember { mutableFloatStateOf(currentBottomPaddingDp.toFloat()) }
    var floatingMode by remember(isFloatingMode) { mutableStateOf(isFloatingMode) }
    var splitMode by remember(isSplitKeyboard) { mutableStateOf(isSplitKeyboard) }
    var opacity by remember { mutableFloatStateOf(initialOpacity.coerceIn(0.3f, 1f)) }

    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(onBack = onCancel)
    }

    val currentOnGeometryChange by rememberUpdatedState(onGeometryChange)
    val currentOnReset by rememberUpdatedState(onReset)
    val currentOnPreviewOpacity by rememberUpdatedState(onOpacityChange)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        val viewRect = viewRectOf(maxWidth.value * density.density, maxHeight.value * density.density)
        val previewState = LocalKeyboardResizePreviewState.current
        var dragRect by remember(floatingMode, maxWidth, maxHeight) { mutableStateOf<ResizeRect?>(null) }
        var dragEdge by remember { mutableStateOf(ResizeHandle.NONE) }
        val frame = dragRect ?: previewState.rect ?: viewRect
        val currentPreviewRect by rememberUpdatedState(previewState.rect)
        val currentInitialPreviewRect by rememberUpdatedState(previewState.initialRect)
        val currentOnPreviewRectChange by rememberUpdatedState(previewState.onRectChange)
        val currentViewRect by rememberUpdatedState(viewRect)
        val currentPreviewState by rememberUpdatedState(previewState)

        val surfaceColor = MaterialTheme.colorScheme.surface
        val onSurfaceColor = MaterialTheme.colorScheme.onSurface
        val outlineColor = MaterialTheme.colorScheme.outline
        val inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
        val accentColor = MaterialTheme.colorScheme.primary
        // 透明度区的弱边界：只描边、不铺底，避免又出现"大黑框"。
        val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

        // 一层只负责暗背景、边框和手势。真实键盘由同一个 previewRect 驱动。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .testTag("keyboard-resize-frame")
                .semantics { contentDescription = "拖动边框调整大小，拖动内部移动悬浮键盘" }
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    // 视觉线与触摸热区分开：线 3dp，热区仍是 resizeHandleAt 的 26dp；
                    // 所有手柄整体往框内缩 6dp，不再压在卡片边框与圆角描边上。
                    val tabThickness = 3.dp.toPx()
                    val handleInset = 6.dp.toPx()
                    val tabLength = 34.dp.toPx().coerceAtMost(frame.width / 4f)
                    val cornerPx = FloatingKeyboardCardCorner.toPx()
                    val rectSize = Size(frame.width.coerceAtLeast(1f), frame.height.coerceAtLeast(1f))

                    // 用整张键盘的主题色承载调节控件，不再绘制中央独立小面板。
                    drawRoundRect(
                        color = surfaceColor.copy(alpha = 0.88f),
                        topLeft = Offset(frame.left, frame.top), size = rectSize,
                        cornerRadius = CornerRadius(if (floatingMode) cornerPx else 0f),
                    )
                    // 边框只画一次。所有 stroke 都向框内收半线宽，避免贴屏幕/圆角时被裁成畸形。
                    val inset = stroke / 2f
                    val borderLeft = frame.left + inset
                    val borderTop = frame.top + inset
                    val borderWidth = (frame.width - stroke).coerceAtLeast(1f)
                    val borderHeight = (frame.height - stroke).coerceAtLeast(1f)
                    if (floatingMode && previewState.rect != null) {
                        drawRoundRect(
                            color = accentColor,
                            topLeft = Offset(borderLeft, borderTop),
                            size = Size(borderWidth, borderHeight),
                            cornerRadius = CornerRadius(
                                (cornerPx - inset).coerceAtLeast(0f),
                                (cornerPx - inset).coerceAtLeast(0f),
                            ),
                            style = Stroke(stroke),
                        )
                    } else {
                        drawRect(
                            color = accentColor,
                            topLeft = Offset(borderLeft, borderTop),
                            size = Size(borderWidth, borderHeight),
                            style = Stroke(stroke),
                        )
                    }

                    // 手柄全部画在边框内侧，不跨出 Rect，因此不会被宿主裁切，也不会和圆角重复描边。
                    fun horizontalHandle(y: Float, insideSign: Float) {
                        drawRoundRect(
                            color = accentColor,
                            topLeft = Offset(
                                frame.centerX - tabLength / 2f,
                                if (insideSign > 0) y + handleInset else y - handleInset - tabThickness,
                            ),
                            size = Size(tabLength, tabThickness),
                            cornerRadius = CornerRadius(tabThickness),
                        )
                    }
                    horizontalHandle(frame.top, 1f)
                    if (floatingMode) {
                        val barHeight = 5.dp.toPx()
                        drawRoundRect(
                            color = onSurfaceColor.copy(alpha = 0.6f),
                            topLeft = Offset(frame.centerX - frame.width * 0.18f,
                                frame.bottom - FLOATING_DRAG_BAR_HEIGHT_DP.dp.toPx() / 2f - barHeight / 2f),
                            size = Size(frame.width * 0.36f, barHeight),
                            cornerRadius = CornerRadius(barHeight),
                        )
                    }
                    // 悬浮键盘底部是永久移动拖条，不再画 resize 手柄。固定键盘仍保留底边调节。
                    if (!floatingMode) horizontalHandle(frame.bottom, -1f)

                    if (previewState.rect != null) {
                        val sideLength = tabLength.coerceAtMost(frame.height / 4f)
                        drawRoundRect(
                            color = accentColor,
                            topLeft = Offset(
                                frame.left + handleInset,
                                frame.centerY - sideLength / 2f,
                            ),
                            size = Size(tabThickness, sideLength),
                            cornerRadius = CornerRadius(tabThickness),
                        )
                        drawRoundRect(
                            color = accentColor,
                            topLeft = Offset(
                                frame.right - handleInset - tabThickness,
                                frame.centerY - sideLength / 2f,
                            ),
                            size = Size(tabThickness, sideLength),
                            cornerRadius = CornerRadius(tabThickness),
                        )
                        // 四角对角线提示（↖ ↗ ↙ ↘）：只做视觉，命中仍是 resizeHandleAt 的边带。
                        val cornerLineLength = 12.dp.toPx()
                        resizeCornerDiagonals(frame, cornerLineLength, handleInset).forEach { (start, end) ->
                            drawLine(color = accentColor, start = start, end = end, strokeWidth = stroke)
                        }
                    }
                }
                .pointerInput(floatingMode, isLandscape, maxWidth, maxHeight) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val hit = 26.dp.toPx()
                        val gestureViewRect = currentViewRect
                        val gestureRect = currentPreviewRect ?: dragRect ?: gestureViewRect
                        val gestureFloating = floatingMode && currentPreviewRect != null
                        dragEdge = resizeHandleAt(gestureRect, down.position, hit, gestureFloating)

                        val session = currentPreviewState
                        val stableBounds = session.bounds ?: gestureViewRect
                        val fixedRange = session.fixedHeightRange ?:
                            keyboardHeightBounds(configuration.screenHeightDp, isLandscape)
                        // 框外不是移动区域；框内空白和底部拖条仍可移动，控件自己消费触摸。
                        if ((down.position.x < gestureRect.left - hit ||
                            down.position.x > gestureRect.right + hit ||
                            down.position.y < gestureRect.top - hit || down.position.y > gestureRect.bottom + hit)) {
                            return@awaitEachGesture
                        }
                        val floatingHeightRange = floatingResizeHeightBounds(maxHeight.value.roundToInt(), isLandscape)
                        val dragBarDp = if (gestureFloating) FLOATING_DRAG_BAR_HEIGHT_DP else 0
                        // 手机基准下限与可用区域取小：平板横屏不允许因为"按比例算"反而比手机更小。
                        val availableWidthDp = (stableBounds.width / density.density).roundToInt()
                        val availableHeightDp = (stableBounds.height / density.density).roundToInt()
                        val minContentHeightDp = minOf(
                            floatingHeightRange.first,
                            floatingResizeMinHeightDp(availableHeightDp - dragBarDp),
                        )
                        val minHeightPx = ((if (gestureFloating) minContentHeightDp else
                            fixedRange.first + session.bottomPaddingDp) + dragBarDp) * density.density
                        val screenMaxHeightPx = ((if (gestureFloating) floatingHeightRange.last else
                            fixedRange.last + session.bottomPaddingDp) + dragBarDp) * density.density
                        val baseMinWidthPx = if (gestureFloating) {
                            maxOf(
                                floatingResizeMinWidthDp(availableWidthDp),
                                minWidthDp.coerceAtMost(availableWidthDp.coerceAtLeast(1)),
                            ).toFloat() * density.density
                        } else gestureRect.width
                        val maxWidthPx = if (gestureFloating) {
                            minOf(stableBounds.width, maxWidthDp.toFloat() * density.density)
                        } else gestureRect.width
                        val maxAspect = floatingResizeMaxAspect(isLandscape)

                        var workingRect = gestureRect
                        var workingPadding = session.bottomPaddingDp.toFloat()

                        fun applyDelta(amount: Offset) {
                            if (gestureFloating) {
                                // 横向拖动不能把键盘压成细长柱；纵向拖动也不能超过当前宽度允许的高宽比。
                                // 只收紧当前轴的边界，不做等比缩放，因此“拖哪条边就只动哪条边”的语义不变。
                                val proposedWidth = when (dragEdge) {
                                    ResizeHandle.LEFT, ResizeHandle.TOP_LEFT, ResizeHandle.BOTTOM_LEFT ->
                                        (workingRect.width - amount.x).coerceAtLeast(1f)
                                    ResizeHandle.RIGHT, ResizeHandle.TOP_RIGHT, ResizeHandle.BOTTOM_RIGHT ->
                                        (workingRect.width + amount.x).coerceAtLeast(1f)
                                    else -> workingRect.width
                                }
                                val proposedHeight = when (dragEdge) {
                                    ResizeHandle.TOP, ResizeHandle.TOP_LEFT, ResizeHandle.TOP_RIGHT ->
                                        (workingRect.height - amount.y).coerceAtLeast(1f)
                                    ResizeHandle.BOTTOM, ResizeHandle.BOTTOM_LEFT, ResizeHandle.BOTTOM_RIGHT ->
                                        (workingRect.height + amount.y).coerceAtLeast(1f)
                                    else -> workingRect.height
                                }
                                val dynamicMinWidthPx = maxOf(baseMinWidthPx, proposedHeight / maxAspect)
                                    .coerceAtMost(maxWidthPx)
                                val dynamicMaxHeightPx = minOf(screenMaxHeightPx, proposedWidth * maxAspect)
                                    .coerceAtLeast(minHeightPx)
                                workingRect = workingRect.dragBy(
                                    handle = dragEdge,
                                    dx = amount.x,
                                    dy = amount.y,
                                    bounds = stableBounds,
                                    minWidth = dynamicMinWidthPx,
                                    minHeight = minHeightPx,
                                    maxWidth = maxWidthPx,
                                    maxHeight = dynamicMaxHeightPx,
                                )
                                dragRect = workingRect
                                currentOnPreviewRectChange(workingRect)
                                currentWidthDp = (workingRect.width / density.density)
                                currentHeightDp = (workingRect.height / density.density) - FLOATING_DRAG_BAR_HEIGHT_DP
                            } else {
                                when (dragEdge) {
                                    ResizeHandle.TOP, ResizeHandle.TOP_LEFT, ResizeHandle.TOP_RIGHT,
                                    ResizeHandle.LEFT, ResizeHandle.RIGHT, ResizeHandle.BOTTOM_LEFT, ResizeHandle.BOTTOM_RIGHT -> {
                                        val edge = when (dragEdge) {
                                            ResizeHandle.BOTTOM_LEFT -> ResizeHandle.LEFT
                                            ResizeHandle.BOTTOM_RIGHT -> ResizeHandle.RIGHT
                                            else -> dragEdge
                                        }
                                        workingRect = workingRect.dragBy(
                                            handle = edge,
                                            dx = amount.x,
                                            dy = amount.y,
                                            bounds = stableBounds,
                                            minWidth = minOf(280f * density.density, stableBounds.width),
                                            minHeight = minHeightPx,
                                            maxWidth = stableBounds.width,
                                            maxHeight = screenMaxHeightPx,
                                        )
                                        dragRect = workingRect
                                        currentOnPreviewRectChange(workingRect)
                                        currentHeightDp = workingRect.height / density.density - workingPadding
                                    }
                                    ResizeHandle.BOTTOM -> {
                                        // 固定键盘底边代表“底部留白”：拖上去增加留白，键盘整体上移，尺寸不变。
                                        val contentHeight = workingRect.height / density.density - workingPadding
                                        val maxPadding = minOf(80f,
                                            (stableBounds.height / density.density - contentHeight).coerceAtLeast(0f))
                                        workingPadding = (workingPadding - amount.y / density.density).coerceIn(0f, maxPadding)
                                        currentBottomPaddingDpState = workingPadding
                                        session.onBottomPaddingChange(workingPadding.roundToInt())
                                        workingRect = fixedKeyboardRect(
                                            gestureViewRect.width, gestureViewRect.height,
                                            contentHeight * density.density, workingPadding * density.density,
                                            session.fixedBottomInsetDp * density.density,
                                            workingRect.width, workingRect.centerX - gestureViewRect.width / 2f,
                                        )
                                        dragRect = workingRect
                                        currentOnPreviewRectChange(workingRect)
                                    }
                                    ResizeHandle.NONE -> {
                                        workingRect = workingRect.translated(amount.x, 0f).coerceInside(stableBounds)
                                        dragRect = workingRect
                                        currentOnPreviewRectChange(workingRect)
                                    }
                                }
                            }
                        }

                        try {
                            val start = awaitTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                applyDelta(over)
                            }
                            if (start != null) {
                                drag(start.id) { change ->
                                    applyDelta(change.positionChange())
                                    change.consume()
                                }
                            }
                        } finally {
                            dragRect = null
                        }
                    }
                },
        )

        // 控件直接占用键盘框内的可用区域；没有第二层小卡片/底板/描边。
        // 顶部工具栏、左右拖边及底部永久移动条均留出命中空间。
        val controls = resizeControlsRect(frame, density.density, floatingMode,
            FLOATING_DRAG_BAR_HEIGHT_DP * density.density)
        val controlsWidthDp = with(density) { controls.width.toDp() }
        val controlsHeightDp = with(density) { controls.height.toDp() }
        val buttonHeight = minOf(48f, (controlsHeightDp.value / 3f).coerceAtLeast(28f)).dp
        val showButtonLabels = controlsWidthDp.value >= 288f
        Box(
            modifier = Modifier
                .absoluteOffset { IntOffset(controls.left.roundToInt(), controls.top.roundToInt()) }
                .size(controlsWidthDp, controlsHeightDp)
                .testTag("keyboard-resize-controls"),
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 透明度只给一块弱边界：描边 + 圆角内边距，表达"这块属于透明度"，不做独立面板。
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("keyboard-resize-opacity-panel")
                        .border(1.dp, outlineVariantColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "透明度 ${((1f - opacity) * 100).roundToInt()}%",
                        modifier = Modifier.fillMaxWidth(),
                        color = onSurfaceColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides buttonHeight) {
                        Slider(
                            value = 1f - opacity,
                            onValueChange = {
                                opacity = 1f - it
                                currentOnPreviewOpacity(opacity)
                            },
                            valueRange = 0f..0.7f,
                            colors = SliderDefaults.colors(
                                thumbColor = accentColor,
                                activeTrackColor = accentColor,
                                inactiveTrackColor = inactiveTrackColor,
                            ),
                            modifier = Modifier.fillMaxWidth().height(buttonHeight).testTag("keyboard-opacity-slider"),
                        )
                    }
                }

                @Composable
                fun EqualActionButton(
                    modifier: Modifier,
                    enabled: Boolean = true,
                    text: String? = null,
                    description: String,
                    icon: @Composable () -> Unit,
                    onClick: () -> Unit,
                ) {
                    OutlinedButton(
                        onClick = onClick,
                        enabled = enabled,
                        modifier = modifier.height(buttonHeight).semantics { contentDescription = description },
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, if (enabled) outlineColor else outlineColor.copy(alpha = 0.35f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            contentColor = onSurfaceColor,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            disabledContentColor = onSurfaceColor.copy(alpha = 0.35f),
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        icon()
                        if (text != null && showButtonLabels) {
                            Spacer(Modifier.width(3.dp))
                            Text(text, fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EqualActionButton(
                        modifier = Modifier.weight(1f),
                        description = "重置",
                        icon = { Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    ) {
                        opacity = 1f
                        currentOnPreviewOpacity(opacity)
                        if (floatingMode) {
                            currentInitialPreviewRect?.let { currentOnPreviewRectChange(it) }
                        } else {
                            currentBottomPaddingDpState = 0f
                            previewState.onBottomPaddingChange(0)
                            val range = previewState.fixedHeightRange ?:
                                keyboardHeightBounds(configuration.screenHeightDp, isLandscape)
                            val height = defaultHeightDp.coerceIn(range)
                            currentOnPreviewRectChange(fixedKeyboardRect(
                                viewRect.width, viewRect.height, height * density.density, 0f,
                                previewState.fixedBottomInsetDp * density.density,
                            ))
                            currentHeightDp = height.toFloat()
                        }
                    }

                    EqualActionButton(
                        modifier = Modifier.weight(1f).testTag("keyboard-resize-floating-button"),
                        enabled = onFloatingModeChange != null,
                        text = if (floatingMode) "固定" else "悬浮",
                        description = "悬浮键盘",
                        icon = {
                            Icon(
                                if (floatingMode) Icons.Default.Keyboard else Icons.Default.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    ) {
                        floatingMode = !floatingMode
                        onFloatingModeChange?.invoke(floatingMode)
                    }

                    EqualActionButton(
                        modifier = Modifier.weight(1f).testTag("keyboard-resize-split-button"),
                        enabled = onSplitKeyboardChange != null && splitKeyboardSupported,
                        text = if (splitMode) "完整" else "分体",
                        description = "分体键盘",
                        icon = {
                            Icon(
                                if (splitMode) Icons.Default.Keyboard else Icons.Default.HorizontalSplit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    ) {
                        splitMode = !splitMode
                        onSplitKeyboardChange?.invoke(splitMode)
                    }

                    EqualActionButton(
                        modifier = Modifier.weight(1f),
                        description = "确认",
                        icon = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    ) {
                        val finalRect = currentPreviewRect ?: previewState.rect
                        if (floatingMode && finalRect != null) {
                            val geometry = finalRect.toGeometry(
                                viewWidthPx = viewRect.width,
                                viewHeightPx = viewRect.height,
                                dragBarHeightPx = FLOATING_DRAG_BAR_HEIGHT_DP * density.density,
                                density = density.density,
                            )
                            currentWidthDp = geometry.widthDp.toFloat()
                            currentHeightDp = geometry.heightDp.toFloat()
                            currentOnGeometryChange(geometry)
                        } else if (finalRect != null) {
                            currentHeightDp = finalRect.height / density.density - previewState.bottomPaddingDp
                            currentOnGeometryChange(finalRect.toGeometry(viewRect.width, viewRect.height, 0f, density.density))
                        }
                        onConfirm(
                            currentHeightDp.roundToInt(),
                            previewState.bottomPaddingDp,
                            floatingMode,
                            opacity,
                        )
                    }
                }
            }
        }

        // 悬浮模式的底部整条移动拖条：最高层级、独立于控件布局，按钮与 slider 不会吃掉它的触摸；
        // 中央只移动位置；两端为四角调整热区，不被此条覆盖。
        val currentOnPositionDragEnd by rememberUpdatedState(onPositionDragEnd)
        val moveKeyboardBy by rememberUpdatedState<(Float, Float) -> Unit> { dxPx, dyPx ->
            val base = dragRect ?: currentPreviewRect
            if (base != null) {
                val moveBounds = currentPreviewState.bounds ?: currentViewRect
                val moved = base.translated(dxPx, if (floatingMode) dyPx else 0f).coerceInside(moveBounds)
                dragRect = moved
                currentOnPreviewRectChange(moved)
                // 预览矩形是调节事务的唯一位置源；确认时统一保存，避免同时改宿主偏移。
            }
        }
        if (!floatingMode) {
            val stripHeight = 26.dp
            Box(Modifier.absoluteOffset {
                IntOffset(frame.left.roundToInt(), (frame.bottom - stripHeight.toPx()).roundToInt())
            }.size(with(density) { frame.width.toDp() }, stripHeight)
                .testTag("keyboard-resize-padding-bar"), contentAlignment = Alignment.TopCenter) {
                Text("上下拖动调节底部留白", color = onSurfaceColor.copy(alpha = 0.7f), fontSize = 10.sp)
            }
        }
        if (!floatingMode) {
            Box(Modifier.absoluteOffset {
                IntOffset((frame.left + 30.dp.toPx()).roundToInt(), (frame.top + 28.dp.toPx()).roundToInt())
            }.size(with(density) { (frame.width.toDp() - 60.dp).coerceAtLeast(1.dp) }, 28.dp)
                .zIndex(1f).testTag("keyboard-resize-fixed-move-bar")
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { dragRect = null }, onDragCancel = { dragRect = null }) { change, amount ->
                        change.consume()
                        moveKeyboardBy(amount.x, 0f)
                    }
                }, contentAlignment = Alignment.Center) {
                Text("左右拖动移动键盘", color = onSurfaceColor, fontSize = 11.sp)
            }
        }
        if (floatingMode) {
            val dragBarHeightPx = FLOATING_DRAG_BAR_HEIGHT_DP * density.density
            Box(
                modifier = Modifier
                    .absoluteOffset {
                        IntOffset((frame.left + 26.dp.toPx()).roundToInt(), (frame.bottom - dragBarHeightPx).roundToInt())
                    }
                    .size(with(density) { (frame.width.toDp() - 52.dp).coerceAtLeast(1.dp) }, FLOATING_DRAG_BAR_HEIGHT_DP.dp)
                    .zIndex(1f)
                    .testTag("keyboard-resize-move-bar")
                    .semantics { contentDescription = "拖动移动键盘，不改变尺寸" }
                    .pointerInput(floatingMode) {
                        detectDragGestures(
                            onDragEnd = {
                                dragRect = null
                                currentOnPositionDragEnd?.invoke()
                            },
                            onDragCancel = {
                                dragRect = null
                                currentOnPositionDragEnd?.invoke()
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                moveKeyboardBy(amount.x, amount.y)
                            },
                        )
                    },
            )
        }
    }
}

/** 调节会话唯一预览矩形；真实键盘、边框、命中全部使用它。 */
internal data class KeyboardResizePreviewState(
    val rect: ResizeRect? = null,
    val initialRect: ResizeRect? = null,
    val onRectChange: (ResizeRect) -> Unit = {},
    val bounds: ResizeRect? = null,
    val fixedHeightRange: IntRange? = null,
    val fixedBottomInsetDp: Int = 0,
    val bottomPaddingDp: Int = 0,
    val initialBottomPaddingDp: Int = 0,
    val onBottomPaddingChange: (Int) -> Unit = {},
)

internal val LocalKeyboardResizePreviewState = compositionLocalOf { KeyboardResizePreviewState() }

// 兼容旧测试/调用方；新的 resize 不再依赖测量回读的 cardRect。
internal val LocalKeyboardResizeCardRect = compositionLocalOf<ResizeRect?> { null }
