package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.KeyboardHeightProfiles
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

import kotlin.math.roundToInt

/** 悬浮键盘卡片圆角；调整覆盖层的四角示意与它同心，避免示意被圆角切掉。 */
internal val FloatingKeyboardCardCorner = 16.dp
internal val FloatingKeyboardCardShape = RoundedCornerShape(FloatingKeyboardCardCorner)

@Composable
internal fun FloatingKeyboardContainer(
    isFloatingMode: Boolean,
    scaleFactor: Float,
    fontScaleFactor: Float = 1f,
    opacity: Float = 1f,
    offsetX: Int,
    offsetY: Int,
    minOffsetY: Int = 0,
    availableHeightDp: Int = 0,
    /** 非调节态真实键盘内容高度；宿主可以是全屏，但卡片不能再拿宿主高度当自身高度。 */
    contentHeightDp: Int,
    backgroundColor: Color = Color.Transparent,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDock: () -> Unit = {},
    /** 调节模式中的唯一预览矩形；非空时直接决定真实卡片最终位置与尺寸。 */
    previewRect: ResizeRect? = null,
    onCardPositioned: (left: Int, top: Int, right: Int, bottom: Int) -> Unit = { _: Int, _: Int, _: Int, _: Int -> },
    fixedWidthDp: Int = 0,
    fixedOffsetX: Int = 0,
    keyboardContent: @Composable () -> Unit,
) {
    val density = LocalDensity.current

    // 调节模式下固定键盘也放进稳定的全屏宿主里，previewRect 直接决定它的真实区域。
    // 这样控制面板可以放在键盘外面，边框也不会因为宿主高度变化而漂移。
    if (!isFloatingMode) {
        if (previewRect == null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val width = if (fixedWidthDp > 0) fixedWidthDp.dp.coerceAtMost(maxWidth) else maxWidth
                val travel = ((maxWidth - width) / 2f).value
                Box(Modifier.align(Alignment.TopCenter)
                    .absoluteOffset(x = fixedOffsetX.toFloat().coerceIn(-travel, travel).dp)
                    .width(width).fillMaxSize().testTag("fixed-keyboard-card")) {
                    keyboardContent()
                }
            }
        } else {
            val previewWidth = with(density) { previewRect.width.toDp() }
            val previewHeight = with(density) { previewRect.height.toDp() }
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .absoluteOffset { IntOffset(previewRect.left.roundToInt(), previewRect.top.roundToInt()) }
                        .size(previewWidth, previewHeight)
                        .testTag("fixed-keyboard-resize-preview")
                        .onGloballyPositioned { coords ->
                            val pos = coords.positionInWindow()
                            val size = coords.size
                            onCardPositioned(
                                pos.x.roundToInt(),
                                pos.y.roundToInt(),
                                (pos.x + size.width).roundToInt(),
                                (pos.y + size.height).roundToInt(),
                            )
                        }
                ) {
                    keyboardContent()
                }
            }
        }
        return
    }

    val screenHeightDp = availableHeightDp.takeIf { it > 0 } ?: LocalConfiguration.current.screenHeightDp

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        val previewWidthDp = previewRect?.let { with(density) { it.width.toDp() } }
        val previewHeightDp = previewRect?.let { with(density) { it.height.toDp() } }
        // 宿主在悬浮模式下可以是整屏；卡片高度必须来自键盘内容高度，而不是 BoxWithConstraints.maxHeight。
        val normalRect = floatingCardRect(
            maxWidth.value * density.density, maxHeight.value * density.density,
            maxWidth.value * scaleFactor * density.density,
            (contentHeightDp.coerceAtLeast(1) + FLOATING_DRAG_BAR_HEIGHT_DP) * density.density,
            offsetX * density.density, offsetY * density.density,
            minOffsetY.coerceAtLeast(0) * density.density,
        )
        val cardRect = previewRect ?: normalRect
        val cardTotalHeight = with(density) { cardRect.height.toDp() }
        val horizontalTravel = ((maxWidth.value - normalRect.width / density.density) / 2f).coerceAtLeast(0f)
        val minimumY = minOffsetY.coerceIn(0, maxHeight.value.roundToInt()).toFloat()
        val maxOffsetY = (maxHeight.value - cardTotalHeight.value).coerceAtLeast(minimumY)
        val safeOffsetY = (maxHeight.value - normalRect.bottom / density.density)
        val safeOffsetX = (normalRect.centerX / density.density - maxWidth.value / 2f)
        val positionedEdge = floatingDockEdge(safeOffsetX, safeOffsetY, horizontalTravel, maxOffsetY, minimumY)
        val dockGesture = remember(maxWidth, cardTotalHeight, screenHeightDp, minOffsetY) { FloatingDockGesture() }
        var isDragging by remember(dockGesture) { mutableStateOf(false) }
        var dockReady by remember(dockGesture) { mutableStateOf(false) }
        var dragX by remember(dockGesture) { mutableFloatStateOf(safeOffsetX) }
        var dragY by remember(dockGesture) { mutableFloatStateOf(safeOffsetY) }
        var dragEdge by remember(dockGesture) { mutableStateOf(positionedEdge) }
        var dockEpoch by remember(dockGesture) { mutableIntStateOf(0) }
        val edge = if (isDragging) dragEdge else positionedEdge
        val effectEpoch = dockEpoch
        val dockProgress = remember(dockGesture) { Animatable(0f) }
        LaunchedEffect(isDragging, edge, dockGesture, effectEpoch) {
            dockReady = false
            dockProgress.snapTo(0f)
            if (isDragging) {
                dockGesture.update(edge, 0L)
                if (edge != null) {
                    coroutineScope {
                        launch { dockProgress.animateTo(1f, tween(FLOATING_DOCK_DURATION_MILLIS.toInt())) }
                        delay(FLOATING_DOCK_DURATION_MILLIS)
                        // 停留时间由可取消的协程延时确定，避免与动画使用不同的时钟。
                        if (effectEpoch == dockEpoch && isDragging && dragEdge == edge) {
                            dockReady = dockGesture.update(edge, FLOATING_DOCK_DURATION_MILLIS)
                        }
                    }
                }
            }
        }
        if (isDragging && edge != null) {
            val progress = dockProgress.value
            val config = LocalConfiguration.current
            val restoredHeight = KeyboardHeightProfiles.fixed(
                LocalContext.current, config.screenWidthDp > config.screenHeightDp
            ).toFloat()
            val previewColor = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .wrapContentSize(Alignment.BottomCenter, unbounded = true)
                    .width(maxWidth * (scaleFactor + (1f - scaleFactor) * progress))
                    .height(restoredHeight.dp)
                    .offset(x = (offsetX * (1f - progress)).dp, y = (-minimumY).dp)
                    .background(previewColor.copy(alpha = 0.12f * progress), RoundedCornerShape(16.dp))
                    .border(2.dp, Brush.horizontalGradient(listOf(
                        previewColor.copy(alpha = progress * 0.4f),
                        previewColor.copy(alpha = progress),
                        previewColor.copy(alpha = progress * 0.4f),
                    )), RoundedCornerShape(16.dp))
                    .testTag("floating-dock-preview").semantics { stateDescription = if (dockReady) "ready" else "expanding" },
            )
        }
        // 正常态和调节态共用 TopStart 放置，不在确认时重新换坐标系。
        val cardPlacement = Modifier
            .align(Alignment.TopStart)
            .absoluteOffset { IntOffset(cardRect.left.roundToInt(), cardRect.top.roundToInt()) }
            .size(with(density) { cardRect.width.toDp() }, with(density) { cardRect.height.toDp() })
        Box(
            modifier = cardPlacement
                .clip(FloatingKeyboardCardShape)
                .graphicsLayer {
                    alpha = opacity.coerceIn(0.3f, 1f)
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .testTag("floating-keyboard-card")
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInWindow()
                    val size = coords.size
                    onCardPositioned(
                        pos.x.roundToInt(),
                        pos.y.roundToInt(),
                        (pos.x + size.width).roundToInt(),
                        (pos.y + size.height).roundToInt()
                    )
                }
        ) {
            Column {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(density = density.density, fontScale = density.fontScale * fontScaleFactor)
                    ) {
                        keyboardContent()
                    }
                }
                DragBar(
                    backgroundColor = backgroundColor,
                    onDragStart = {
                        dockEpoch++
                        dockReady = false
                        dragX = safeOffsetX
                        dragY = safeOffsetY
                        dragEdge = positionedEdge
                        dockGesture.start(dragEdge, 0L)
                        isDragging = true
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val dxDp = with(density) { dragAmount.x.toDp().value }
                        val dyDp = with(density) { dragAmount.y.toDp().value }
                        dragX = (dragX + dxDp).coerceIn(-horizontalTravel, horizontalTravel)
                        dragY = (dragY - dyDp).coerceIn(minimumY, maxOffsetY)
                        val nextEdge = floatingDockEdge(dragX.roundToInt().toFloat(), dragY.roundToInt().toFloat(),
                            horizontalTravel, maxOffsetY, minOffsetY.toFloat())
                        if (nextEdge != dragEdge) {
                            // MOVE 与 UP 可能早于下一帧重组；必须在触摸回调中立即撤销准备态。
                            dragEdge = nextEdge
                            dockEpoch++
                            dockReady = false
                            dockGesture.update(nextEdge, 0L)
                        }
                        onDrag(dxDp, -dyDp)
                    },
                    onDragEnd = {
                        val restore = dockReady && dockGesture.release(dragEdge, FLOATING_DOCK_DURATION_MILLIS)
                        dockGesture.cancel()
                        dockEpoch++
                        isDragging = false
                        dockReady = false
                        onDragEnd()
                        if (restore) onDock()
                    },
                    onDragCancel = {
                        dockGesture.cancel()
                        dockEpoch++
                        isDragging = false
                        dockReady = false
                        onDragEnd()
                    },
                )
            }
        }

    }
}

@Composable
private fun DragBar(
    backgroundColor: Color,
    onDragStart: () -> Unit,
    onDrag: (change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: androidx.compose.ui.geometry.Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(FLOATING_DRAG_BAR_HEIGHT_DP.dp)
            .background(backgroundColor)
            .testTag("floating-drag-bar")
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { currentOnDragStart() },
                    onDrag = { change, amount -> currentOnDrag(change, amount) },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragCancel() },
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.36f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.6f))
        )
    }
}
