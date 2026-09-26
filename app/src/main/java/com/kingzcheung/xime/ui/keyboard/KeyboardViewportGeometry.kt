package com.kingzcheung.xime.ui.keyboard

/** Service 的内层宿主与窗口必须同时使用这个条件，不能仅在调节时全高。 */
internal fun usesFullKeyboardHost(floating: Boolean, resizing: Boolean): Boolean = floating || resizing

/** 同一个实际宿主视口；系统底部安全区只扣一次，预览和普通悬浮不能各设一套边距。 */
internal fun floatingViewportBounds(widthPx: Float, heightPx: Float, bottomInsetPx: Float): ResizeRect {
    val width = widthPx.coerceAtLeast(1f)
    val height = heightPx.coerceAtLeast(1f)
    val inset = bottomInsetPx.coerceIn(0f, height - 1f)
    return ResizeRect(0f, 0f, width, height - inset)
}

/** 普通悬浮和调节初值共用；所有单位为当前宿主的物理像素，不读取屏幕配置。 */
internal fun floatingCardRect(
    hostWidthPx: Float,
    hostHeightPx: Float,
    widthPx: Float,
    totalHeightPx: Float,
    horizontalOffsetPx: Float,
    bottomOffsetPx: Float,
    minBottomInsetPx: Float,
): ResizeRect {
    val bounds = floatingViewportBounds(hostWidthPx, hostHeightPx, minBottomInsetPx)
    val width = widthPx.coerceIn(1f, bounds.width)
    val height = totalHeightPx.coerceIn(1f, bounds.height)
    val left = (hostWidthPx / 2f + horizontalOffsetPx - width / 2f)
        .coerceIn(bounds.left, bounds.right - width)
    val bottom = (hostHeightPx - bottomOffsetPx).coerceIn(bounds.top + height, bounds.bottom)
    return ResizeRect(left, bottom - height, left + width, bottom)
}

/** 固定键盘框包含用户底部留白，但不包含系统导航留白；与正常显示完全相同。 */
internal fun fixedKeyboardRect(
    hostWidthPx: Float,
    hostHeightPx: Float,
    contentHeightPx: Float,
    bottomPaddingPx: Float,
    navigationInsetPx: Float,
    widthPx: Float = hostWidthPx,
    horizontalOffsetPx: Float = 0f,
): ResizeRect {
    val bounds = floatingViewportBounds(hostWidthPx, hostHeightPx, navigationInsetPx)
    val height = (contentHeightPx + bottomPaddingPx.coerceAtLeast(0f)).coerceIn(1f, bounds.height)
    val width = widthPx.coerceIn(1f, bounds.width)
    val left = (bounds.width / 2f + horizontalOffsetPx - width / 2f).coerceIn(0f, bounds.width - width)
    return ResizeRect(left, bounds.bottom - height, left + width, bounds.bottom)
}

/** 控件直接在整个键盘框内排布，不再生成第二个小面板；边缘和底部拖条保持可拖。 */
internal fun resizeControlsRect(
    frame: ResizeRect,
    density: Float,
    floating: Boolean,
    dragBarHeightPx: Float,
): ResizeRect {
    val scale = density.coerceAtLeast(0.1f)
    val side = (20f * scale).coerceAtMost(frame.width / 8f)
    val top = ((if (floating) 30f else 62f) * scale).coerceAtMost(frame.height / 4f)
    val bottom = (if (floating) dragBarHeightPx + 8f * scale else 26f * scale)
        .coerceAtMost(frame.height / 4f)
    return ResizeRect(frame.left + side, frame.top + top, frame.right - side, frame.bottom - bottom)
}
