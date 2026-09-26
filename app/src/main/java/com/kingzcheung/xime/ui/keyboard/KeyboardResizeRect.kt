package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.roundToInt

/**
 * 键盘调节的几何模型：**以矩形的四条边为唯一状态**。
 *
 * 之前用「宽度/高度 + 中心偏移」作主状态，拖左边就变成"改宽度"，于是左右两边一起动、
 * 看起来像整体缩放，边框也永远贴不到真实卡片边界。现在拖动只移动被拖的那条边：
 * 拖左边时右边必须不动，拖上边时下边必须不动，角上同时移动两条边。
 *
 * 坐标系：覆盖层（KeyboardView 内容区）本地坐标，原点在左上。
 */
internal data class ResizeRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun translated(dx: Float, dy: Float): ResizeRect =
        ResizeRect(left + dx, top + dy, right + dx, bottom + dy)
}


/**
 * 进入调节时清理历史畸形尺寸。只在 session 初始化使用；拖动过程中仍严格遵守“哪条边被拖就只动哪条边”。
 * 保持原中心 X 与底边优先，必要时整体平移回 [bounds]。
 */
internal fun ResizeRect.coerceFloatingResizeSeed(
    bounds: ResizeRect,
    minWidth: Float,
    minHeight: Float,
    maxHeight: Float,
    maxAspect: Float,
): ResizeRect {
    val w = width.coerceIn(minWidth.coerceAtMost(bounds.width), bounds.width)
    val aspectMaxHeight = w * maxAspect.coerceAtLeast(0.1f)
    val h = height.coerceIn(
        minHeight.coerceAtMost(bounds.height),
        minOf(maxHeight, aspectMaxHeight, bounds.height).coerceAtLeast(minHeight.coerceAtMost(bounds.height)),
    )
    val preferredLeft = centerX - w / 2f
    val preferredBottom = bottom
    val left = preferredLeft.coerceIn(bounds.left, (bounds.right - w).coerceAtLeast(bounds.left))
    val bottom = preferredBottom.coerceIn(bounds.top + h, bounds.bottom)
    return ResizeRect(left, bottom - h, left + w, bottom)
}

internal fun ResizeRect.toRect(): Rect = Rect(Offset(left, top), Offset(right, bottom))

internal fun Rect.toResizeRect(): ResizeRect = ResizeRect(left, top, right, bottom)


/** 把矩形完整收进给定边界；仅用于进入调节/窗口尺寸变化时消除历史越界状态。 */
internal fun ResizeRect.coerceInside(bounds: ResizeRect): ResizeRect {
    val w = width.coerceAtMost(bounds.width).coerceAtLeast(1f)
    val h = height.coerceAtMost(bounds.height).coerceAtLeast(1f)
    val newLeft = left.coerceIn(bounds.left, (bounds.right - w).coerceAtLeast(bounds.left))
    val newTop = top.coerceIn(bounds.top, (bounds.bottom - h).coerceAtLeast(bounds.top))
    return ResizeRect(newLeft, newTop, newLeft + w, newTop + h)
}

/**
 * 拖动手柄的结果矩形。固定对边，只移动被拖的边（角同时移动两条边）。
 *
 * [bounds] 是可拖范围（覆盖层尺寸），[minWidth]/[minHeight] 是尺寸下限；
 * 夹紧只作用在被拖的边上，所以对边坐标严格不变。
 */
internal fun ResizeRect.dragBy(
    handle: ResizeHandle,
    dx: Float,
    dy: Float,
    bounds: ResizeRect,
    minWidth: Float,
    minHeight: Float,
    maxWidth: Float = bounds.width,
    maxHeight: Float = bounds.height,
): ResizeRect {
    val minW = minWidth.coerceAtMost(bounds.width).coerceAtLeast(1f)
    val minH = minHeight.coerceAtMost(bounds.height).coerceAtLeast(1f)
    val maxW = maxWidth.coerceIn(minW, bounds.width)
    val maxH = maxHeight.coerceIn(minH, bounds.height)
    var left = left
    var top = top
    var right = right
    var bottom = bottom

    val leftMin = { kotlin.math.max(bounds.left, right - maxW) }
    val rightMax = { kotlin.math.min(bounds.right, left + maxW) }
    val topMin = { kotlin.math.max(bounds.top, bottom - maxH) }
    val bottomMax = { kotlin.math.min(bounds.bottom, top + maxH) }

    when (handle) {
        ResizeHandle.LEFT -> left = (left + dx).coerceIn(leftMin(), right - minW)
        ResizeHandle.RIGHT -> right = (right + dx).coerceIn(left + minW, rightMax())
        ResizeHandle.TOP -> top = (top + dy).coerceIn(topMin(), bottom - minH)
        ResizeHandle.BOTTOM -> bottom = (bottom + dy).coerceIn(top + minH, bottomMax())
        ResizeHandle.TOP_LEFT -> {
            left = (left + dx).coerceIn(leftMin(), right - minW)
            top = (top + dy).coerceIn(topMin(), bottom - minH)
        }
        ResizeHandle.TOP_RIGHT -> {
            right = (right + dx).coerceIn(left + minW, rightMax())
            top = (top + dy).coerceIn(topMin(), bottom - minH)
        }
        ResizeHandle.BOTTOM_LEFT -> {
            left = (left + dx).coerceIn(leftMin(), right - minW)
            bottom = (bottom + dy).coerceIn(top + minH, bottomMax())
        }
        ResizeHandle.BOTTOM_RIGHT -> {
            right = (right + dx).coerceIn(left + minW, rightMax())
            bottom = (bottom + dy).coerceIn(top + minH, bottomMax())
        }
        // 中间留白：整体平移，宽高不变（悬浮键盘用来移动位置）。
        ResizeHandle.NONE -> {
            val w = width.coerceAtMost(bounds.width)
            val h = height.coerceAtMost(bounds.height)
            left = (left + dx).coerceIn(bounds.left, (bounds.right - w).coerceAtLeast(bounds.left))
            top = (top + dy).coerceIn(bounds.top, (bounds.bottom - h).coerceAtLeast(bounds.top))
            right = left + w
            bottom = top + h
        }
    }
    return ResizeRect(left, top, right, bottom)
}

/** 手柄命中所在的边/角；[ResizeHandle.NONE] 表示不是手柄（悬浮键盘上用于移动位置）。 */
internal enum class ResizeHandle {
    NONE, TOP, BOTTOM, LEFT, RIGHT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
}

/**
 * 手柄命中：只认正在绘制的那一个矩形（[frame]）的边带，
 * 所以「看得见的边框」就是「拖得动的边框」，不存在两套坐标。
 *
 * 固定和悬浮都支持左右及角手柄；固定底边保留底部留白语义。
 */
internal fun resizeHandleAt(frame: ResizeRect, point: Offset, hitPx: Float, floating: Boolean): ResizeHandle {
    if (point.x < frame.left - hitPx || point.x > frame.right + hitPx) return ResizeHandle.NONE
    if (point.y < frame.top - hitPx || point.y > frame.bottom + hitPx) return ResizeHandle.NONE
    val top = point.y < frame.top + hitPx
    val bottom = point.y > frame.bottom - hitPx
    val left = point.x < frame.left + hitPx
    val right = point.x > frame.right - hitPx
    return when {
        top && left -> ResizeHandle.TOP_LEFT
        top && right -> ResizeHandle.TOP_RIGHT
        top -> ResizeHandle.TOP
        bottom && left -> ResizeHandle.BOTTOM_LEFT
        bottom && right -> ResizeHandle.BOTTOM_RIGHT
        // 底部中央移动，底部两角仍沿对角线调整大小。
        floating && bottom -> ResizeHandle.NONE
        left -> ResizeHandle.LEFT
        right -> ResizeHandle.RIGHT
        bottom -> ResizeHandle.BOTTOM
        else -> ResizeHandle.NONE
    }
}

/** 一个角的示意圆弧：[topLeft] 是外接方框左上角，[startAngle] 为 Compose 弧度起点。 */
internal data class ResizeCornerArc(val topLeft: Offset, val startAngle: Float)

/** 四角示意：与卡片圆角同圆心、同半径的 90° 圆弧（旧实现画直角 L，与圆角不同形）。 */
internal fun resizeCornerArcs(frame: ResizeRect, radiusPx: Float): List<ResizeCornerArc> {
    val diameter = radiusPx * 2f
    return listOf(
        ResizeCornerArc(Offset(frame.left, frame.top), 180f),
        ResizeCornerArc(Offset(frame.right - diameter, frame.top), 270f),
        ResizeCornerArc(Offset(frame.left, frame.bottom - diameter), 90f),
        ResizeCornerArc(Offset(frame.right - diameter, frame.bottom - diameter), 0f),
    )
}

/**
 * 四角对角线提示：每个角内侧沿 45° 的一小段线（↖ ↗ ↙ ↘）。
 *
 * 只做视觉提示，触摸热区仍由 [resizeHandleAt] 的边带决定，二者互不影响。
 * 长度取 10~14dp，inset 与顶/侧手柄一致，避免压在卡片的圆角描边上。
 */
internal fun resizeCornerDiagonals(
    frame: ResizeRect,
    lengthPx: Float,
    insetPx: Float,
): List<Pair<Offset, Offset>> {
    val length = lengthPx.coerceAtLeast(0f)
    val inset = insetPx.coerceAtLeast(0f)
    return listOf(
        Offset(frame.left + inset, frame.top + inset) to
            Offset(frame.left + inset + length, frame.top + inset + length),
        Offset(frame.right - inset, frame.top + inset) to
            Offset(frame.right - inset - length, frame.top + inset + length),
        Offset(frame.left + inset, frame.bottom - inset) to
            Offset(frame.left + inset + length, frame.bottom - inset - length),
        Offset(frame.right - inset, frame.bottom - inset) to
            Offset(frame.right - inset - length, frame.bottom - inset - length),
    )
}

/**
 * 矩形 → 服务状态（悬浮卡片）。
 *
 * 卡片按「底部居中 + 水平偏移」渲染，所以：
 * - horizontalOffsetDp = 矩形中心相对视图中心的位移；
 * - bottomOffsetDp = 矩形底边距视图底边的距离；
 * - heightDp 只算键盘内容高度，卡片总高还含 [dragBarHeightPx] 的拖条
 *   （与 FloatingKeyboardContainer 的 cardTotalHeight 一致）。
 */
internal data class ResizeGeometry(
    val widthDp: Int,
    val heightDp: Int,
    val horizontalOffsetDp: Int,
    val bottomOffsetDp: Int,
)

internal fun ResizeRect.toGeometry(
    viewWidthPx: Float,
    viewHeightPx: Float,
    dragBarHeightPx: Float,
    density: Float,
): ResizeGeometry = ResizeGeometry(
    widthDp = (width / density).roundToInt(),
    heightDp = ((height - dragBarHeightPx) / density).roundToInt(),
    horizontalOffsetDp = ((centerX - viewWidthPx / 2f) / density).roundToInt(),
    bottomOffsetDp = ((viewHeightPx - bottom) / density).roundToInt(),
)

/**
 * 服务状态 → 矩形（[toGeometry] 的逆）：用来验证「按状态渲染出来的卡片」和「调节框」
 * 是同一个矩形。
 */
internal fun geometryToResizeRect(
    widthDp: Int,
    heightDp: Int,
    horizontalOffsetDp: Int,
    bottomOffsetDp: Int,
    viewWidthPx: Float,
    viewHeightPx: Float,
    dragBarHeightPx: Float,
    density: Float,
): ResizeRect {
    val width = widthDp * density
    val height = heightDp * density + dragBarHeightPx
    val centerX = viewWidthPx / 2f + horizontalOffsetDp * density
    val bottom = viewHeightPx - bottomOffsetDp * density
    return ResizeRect(centerX - width / 2f, bottom - height, centerX + width / 2f, bottom)
}

/** 覆盖层尺寸矩形（可拖范围）。 */
internal fun viewRectOf(widthPx: Float, heightPx: Float): ResizeRect =
    ResizeRect(0f, 0f, widthPx.coerceAtLeast(1f), heightPx.coerceAtLeast(1f))
