package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 用户明确调整过固定宽度后，按实际宽度排键，不再自动收回屏幕中央。 */
internal val LocalKeyboardExplicitWidth = staticCompositionLocalOf { false }

/**
 * 当前键盘体的视觉度量（由布局策略算出）。
 *
 * [KeyVisualMetrics.Unspecified] = 没有策略上下文（候选栏等嵌套网格），
 * 此时 [scaledKeyVisualPadding] 原样返回声明值，不改变嵌套网格的既有视觉。
 */
internal val LocalKeyboardKeyVisualMetrics = staticCompositionLocalOf { KeyVisualMetrics.Unspecified }

/**
 * 键盘体尺寸上下文：算出 gutter、每侧 inset 与内容缩放。
 *
 * - 命中区域不变：gutter 只作用于内容尺寸，键仍按整格接收触摸（见 KeyButton）。
 * - [columns] 是“单位列数”（26 键 = 首行键数；九键/数字/笔画 = 4.41）。
 * - [policy] 决定缝的目标值与上下限，集中定义在 [KeyVisualPolicy]。
 * - [allowShrink] 悬浮键盘：允许整体缩小，不套用手机最小缝。
 * - [applyGutter] 是否把算出的 gutter 作为内容左右边距（手机呼吸空间 + 大屏居中）。
 */
@Composable
internal fun KeyboardKeySpacingScope(
    modifier: Modifier,
    columns: Float = 10f,
    rows: Float = 4f,
    horizontalInset: Dp = 8.dp,
    verticalInset: Dp = 8.dp,
    widthFraction: Float = 1f,
    policy: KeyVisualPolicy = KeyVisualPolicy.Qwerty,
    allowShrink: Boolean = false,
    applyGutter: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        // 不铺 gutter 时（嵌套面板/编辑盘）：不加键宽上限，度量按真实格宽算，
        // 避免"按上限缩格"却又不把多出的宽度变成留白。
        val effectivePolicy = when {
            !applyGutter -> policy.copy(maxKeyWidth = Float.MAX_VALUE, minGutter = 0f)
            LocalKeyboardExplicitWidth.current -> policy.copy(maxKeyWidth = Float.MAX_VALUE)
            else -> policy
        }
        val metrics = keyVisualMetrics(
            policy = effectivePolicy,
            availableWidthDp = maxWidth.value * widthFraction,
            availableHeightDp = maxHeight.value,
            columns = columns,
            rows = rows,
            verticalInsetDp = verticalInset.value,
            allowShrink = allowShrink,
        )
        CompositionLocalProvider(
            LocalKeyboardKeyVisualMetrics provides metrics,
            LocalKeyboardKeyContentScale provides KeyboardKeyMetrics.contentScale(metrics.capShortEdge, metrics.capShortEdge),
        ) {
            // 调用方声明的 horizontalInset 只作为 gutter 下限（历史参数：曾经只是公式预留）
            val gutter = if (applyGutter) maxOf(metrics.gutterX, horizontalInset.value) else 0f
            content(Modifier.fillMaxSize().padding(start = gutter.dp, end = gutter.dp))
        }
    }
}

/**
 * 把布局声明的键帽内缩解析成最终视觉。
 *
 * - 声明值 == [KeyVisualPolicy.DeclaredDefaultGap]（2dp，历史默认）→ 用布局策略的每侧 inset；
 * - 声明其他数值 → 视为相邻键帽之间的**总缝**（单侧 = 一半），供 xime.yaml 逐键盘覆盖；
 * - 无策略上下文 → 原样返回声明值。
 */
@Composable
internal fun scaledKeyVisualPadding(padding: PaddingValues = LocalKeyVisualPadding.current): PaddingValues {
    val metrics = LocalKeyboardKeyVisualMetrics.current
    val direction = LocalLayoutDirection.current
    return remember(padding, metrics, direction) {
        PaddingValues(
            start = resolvedKeyInset(padding.calculateStartPadding(direction), metrics.insetX),
            top = resolvedKeyInset(padding.calculateTopPadding(), metrics.insetY),
            end = resolvedKeyInset(padding.calculateEndPadding(direction), metrics.insetX),
            bottom = resolvedKeyInset(padding.calculateBottomPadding(), metrics.insetY),
        )
    }
}

private fun resolvedKeyInset(declared: Dp, policyInset: Float?): Dp = when {
    policyInset == null -> declared
    declared.value == KeyVisualPolicy.DeclaredDefaultGap -> policyInset.dp
    else -> (declared.value / 2f).dp
}
