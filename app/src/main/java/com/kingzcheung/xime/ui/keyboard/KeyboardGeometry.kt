package com.kingzcheung.xime.ui.keyboard

import kotlin.math.roundToInt

/**
 * 悬浮键盘调节时的最小尺寸：手机基准下限，横屏也不放宽。
 *
 * 以前横屏单独给更小的下限（高度 130），平板横屏反而能缩得比手机还小；
 * 现在两个方向共用同一基准，横屏只允许更大，不允许因比例算法变小。
 */
internal const val FLOATING_RESIZE_MIN_WIDTH_DP = 400
internal const val FLOATING_RESIZE_MIN_HEIGHT_DP = 300

/**
 * 悬浮调节专用高度范围。
 *
 * 不能复用普通键盘的 keyboardHeightBounds：普通键盘的范围很窄，悬浮调节会几乎拖不动；
 * 也不能直接放到整屏高，否则九键会被拉成夸张的长条。
 * 下限恒为 [FLOATING_RESIZE_MIN_HEIGHT_DP]；可用高度不足时由调用方按
 * [floatingResizeMinHeightDp] 夹到可用区，不产生越界初值。
 */
internal fun floatingResizeHeightBounds(hostHeightDp: Int, landscape: Boolean): IntRange {
    val host = hostHeightDp.coerceAtLeast(1)
    val min = FLOATING_RESIZE_MIN_HEIGHT_DP
    // 悬浮调节不能把九键拉成长柱。横屏留出操作面板空间，竖屏最多占可用高约一半。
    val maxByScreen = if (landscape) {
        (host * 64 / 100) - FLOATING_DRAG_BAR_HEIGHT_DP
    } else {
        (host * 48 / 100) - FLOATING_DRAG_BAR_HEIGHT_DP
    }
    val max = maxByScreen.coerceAtLeast(min)
    return min..max
}

/** 最小宽度：手机基准下限与可用宽度取小（[availableWidthDp] 已扣边距）。 */
internal fun floatingResizeMinWidthDp(availableWidthDp: Int): Int =
    minOf(FLOATING_RESIZE_MIN_WIDTH_DP, availableWidthDp.coerceAtLeast(1))

/** 最小高度：手机基准下限与可用高度取小（[availableHeightDp] 已扣边距）。 */
internal fun floatingResizeMinHeightDp(availableHeightDp: Int): Int =
    minOf(FLOATING_RESIZE_MIN_HEIGHT_DP, availableHeightDp.coerceAtLeast(1))

/** 悬浮键盘允许的最大高宽比。只限制畸形，不强制等比缩放。 */
internal fun floatingResizeMaxAspect(landscape: Boolean): Float = if (landscape) 0.95f else 1.35f

/** 高度包含44dp工具栏与四行按键，不含悬浮拖条及系统导航栏。 */
internal fun keyboardHeightBounds(screenHeight: Int, landscape: Boolean, navInset: Int = 24): IntRange {
    val max = if (landscape) (screenHeight - navInset - FLOATING_DRAG_BAR_HEIGHT_DP - 24).coerceAtLeast(160)
        else (screenHeight * 45 / 100).coerceAtLeast(228)
    val min = if (landscape) 244.coerceAtMost(max) else (screenHeight * 28 / 100).coerceAtLeast(228).coerceAtMost(max)
    return min..max
}

internal fun floatingKeyboardWidth(screenWidth: Int, screenHeight: Int, height: Int, portraitHeight: Int, wide: Boolean): Int {
    val landscape = screenWidth > screenHeight
    // 没有用户保存尺寸时给一个真正可用的默认值：竖屏约 78%，横屏约 48% 屏宽。
    // 不再从普通键盘高度反推宽度，避免横屏默认尺寸忽大忽小。
    val ratio = if (landscape) (if (wide) 0.52f else 0.48f) else 0.78f
    return ((screenWidth * ratio / 10f).roundToInt() * 10)
        .coerceIn(minOf(FLOATING_RESIZE_MIN_WIDTH_DP, screenWidth), screenWidth)
}

/** 悬浮键盘宽度范围（dp）。 */
internal fun keyboardWidthBounds(screenWidth: Int): IntRange {
    val cap = screenWidth.coerceAtLeast(1)
    return minOf(FLOATING_RESIZE_MIN_WIDTH_DP, cap)..cap
}

/**
 * 悬浮卡片宽度解析：设置过宽度就用设置值（夹在 [keyboardWidthBounds] 内），
 * 未设置（0）时按高度推导，保持历史外观。
 */
internal fun resolvedFloatingWidth(
    screenWidth: Int,
    screenHeight: Int,
    height: Int,
    portraitHeight: Int,
    wide: Boolean,
    overrideWidth: Int,
): Int = if (overrideWidth > 0) {
    overrideWidth.coerceIn(keyboardWidthBounds(screenWidth))
} else {
    floatingKeyboardWidth(screenWidth, screenHeight, height, portraitHeight, wide)
}
