package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘调节几何：**拖边固定对边**、命中与绘制共用同一个矩形、矩形与渲染状态互为逆映射。
 *
 * 这几个纯函数是修复的核心：旧实现以「宽度 + 中心」为主状态，拖左右边等于改宽度，
 * 两边一起动（看起来像整体缩放），而且调节框由屏幕宽度和偏移另算，和真实卡片边界对不上。
 */
class KeyboardResizeGeometryTest {

    @Test fun fixedWidthAndPositionPreserveBottomInsetsAcrossHostHeights() {
        val normal = fixedKeyboardRect(1000f, 400f, 300f, 20f, 24f, 400f, 300f)
        val preview = fixedKeyboardRect(1000f, 900f, 300f, 20f, 24f, 400f, 300f)
        assertEquals(600f, normal.left, 0.01f)
        assertEquals(1000f, normal.right, 0.01f)
        assertEquals(normal.width, preview.width, 0.01f)
        assertEquals(normal.height, preview.height, 0.01f)
        assertEquals(24f, 900f - preview.bottom, 0.01f)
        assertEquals(0f, fixedKeyboardRect(360f, 400f, 300f, 0f, 24f, 800f, -300f).left, 0.01f)
    }

    private val bounds = ResizeRect(0f, 0f, 1000f, 800f)
    private val start = ResizeRect(100f, 100f, 700f, 500f)

    private fun drag(handle: ResizeHandle, dx: Float, dy: Float): ResizeRect =
        start.dragBy(handle, dx, dy, bounds, minWidth = 260f, minHeight = 200f)

    @Test
    fun `拖左边时右边不动`() {
        val r = drag(ResizeHandle.LEFT, 50f, 0f)
        assertEquals(150f, r.left, 0.01f)
        assertEquals(700f, r.right, 0.01f)
        assertEquals(100f, r.top, 0.01f)
        assertEquals(500f, r.bottom, 0.01f)
    }

    @Test
    fun `拖右边时左边不动`() {
        val r = drag(ResizeHandle.RIGHT, -50f, 0f)
        assertEquals(100f, r.left, 0.01f)
        assertEquals(650f, r.right, 0.01f)
        assertEquals(500f, r.bottom, 0.01f)
    }

    @Test
    fun `拖上边时下边不动`() {
        val r = drag(ResizeHandle.TOP, 0f, 50f)
        assertEquals(150f, r.top, 0.01f)
        assertEquals(500f, r.bottom, 0.01f)
        assertEquals(100f, r.left, 0.01f)
    }

    @Test
    fun `拖下边时上边不动`() {
        val r = drag(ResizeHandle.BOTTOM, 0f, -50f)
        assertEquals(100f, r.top, 0.01f)
        assertEquals(450f, r.bottom, 0.01f)
        assertEquals(700f, r.right, 0.01f)
    }

    @Test
    fun `四角同时移动两条边 对侧边不动`() {
        val topLeft = drag(ResizeHandle.TOP_LEFT, 50f, 50f)
        assertEquals(150f, topLeft.left, 0.01f)
        assertEquals(150f, topLeft.top, 0.01f)
        assertEquals(700f, topLeft.right, 0.01f)
        assertEquals(500f, topLeft.bottom, 0.01f)

        val topRight = drag(ResizeHandle.TOP_RIGHT, -50f, 50f)
        assertEquals(100f, topRight.left, 0.01f)
        assertEquals(150f, topRight.top, 0.01f)
        assertEquals(650f, topRight.right, 0.01f)
        assertEquals(500f, topRight.bottom, 0.01f)

        val bottomLeft = drag(ResizeHandle.BOTTOM_LEFT, 50f, -50f)
        assertEquals(150f, bottomLeft.left, 0.01f)
        assertEquals(100f, bottomLeft.top, 0.01f)
        assertEquals(700f, bottomLeft.right, 0.01f)
        assertEquals(450f, bottomLeft.bottom, 0.01f)

        val bottomRight = drag(ResizeHandle.BOTTOM_RIGHT, -50f, -50f)
        assertEquals(100f, bottomRight.left, 0.01f)
        assertEquals(100f, bottomRight.top, 0.01f)
        assertEquals(650f, bottomRight.right, 0.01f)
        assertEquals(450f, bottomRight.bottom, 0.01f)
    }

    @Test
    fun `夹紧既不越过对边也不越出可拖范围`() {
        // 拖左边过头 → 停在 right - minWidth
        assertEquals(440f, drag(ResizeHandle.LEFT, 9999f, 0f).left, 0.01f)
        // 拖左边出界 → 停在可拖范围左边缘
        assertEquals(0f, drag(ResizeHandle.LEFT, -9999f, 0f).left, 0.01f)
        // 拖下边过头 → 停在 bottom 边界，上边仍然不动
        val bottomOver = drag(ResizeHandle.BOTTOM, 0f, 9999f)
        assertEquals(800f, bottomOver.bottom, 0.01f)
        assertEquals(100f, bottomOver.top, 0.01f)
        // 拖上边过头 → 停在 top + minHeight
        assertEquals(300f, drag(ResizeHandle.TOP, 0f, 9999f).top, 0.01f)
    }

    @Test
    fun `中间拖动整体平移 宽高不变`() {
        val r = drag(ResizeHandle.NONE, 50f, 30f)
        assertEquals(150f, r.left, 0.01f)
        assertEquals(130f, r.top, 0.01f)
        assertEquals(750f, r.right, 0.01f)
        assertEquals(530f, r.bottom, 0.01f)
        assertEquals(start.width, r.width, 0.01f)
        assertEquals(start.height, r.height, 0.01f)
    }

    @Test
    fun `命中只认正在绘制的那个矩形`() {
        val card = ResizeRect(150f, 0f, 850f, 400f)
        assertEquals(ResizeHandle.LEFT, resizeHandleAt(card, Offset(160f, 200f), 28f, true))
        assertEquals(ResizeHandle.RIGHT, resizeHandleAt(card, Offset(840f, 200f), 28f, true))
        assertEquals(ResizeHandle.TOP_LEFT, resizeHandleAt(card, Offset(154f, 4f), 28f, true))
        assertEquals(ResizeHandle.BOTTOM_RIGHT, resizeHandleAt(card, Offset(846f, 396f), 28f, true))
        assertEquals(ResizeHandle.BOTTOM_LEFT, resizeHandleAt(card, Offset(154f, 396f), 28f, true))
        assertEquals(ResizeHandle.NONE, resizeHandleAt(card, Offset(500f, 396f), 28f, true))
        // 卡片外不是手柄：留给"拖动移动位置"
        assertEquals(ResizeHandle.NONE, resizeHandleAt(card, Offset(20f, 200f), 28f, true))
        assertEquals(ResizeHandle.NONE, resizeHandleAt(card, Offset(600f, 200f), 28f, true))

        // 固定键盘也支持宽度与角手柄，中间仍不是缩放手柄
        val full = ResizeRect(0f, 0f, 1000f, 400f)
        assertEquals(ResizeHandle.TOP_LEFT, resizeHandleAt(full, Offset(4f, 4f), 28f, false))
        assertEquals(ResizeHandle.BOTTOM_RIGHT, resizeHandleAt(full, Offset(996f, 396f), 28f, false))
        assertEquals(ResizeHandle.LEFT, resizeHandleAt(full, Offset(2f, 200f), 28f, false))
        assertEquals(ResizeHandle.NONE, resizeHandleAt(full, Offset(500f, 200f), 28f, false))
    }

    @Test
    fun `四角圆弧与卡片圆角同心`() {
        val card = ResizeRect(100f, 0f, 900f, 400f)
        val arcs = resizeCornerArcs(card, 16f)
        assertEquals(4, arcs.size)
        assertEquals(Offset(100f, 0f), arcs[0].topLeft)
        assertEquals(180f, arcs[0].startAngle, 0.01f)
        assertEquals(Offset(868f, 0f), arcs[1].topLeft)
        assertEquals(270f, arcs[1].startAngle, 0.01f)
        assertEquals(Offset(100f, 368f), arcs[2].topLeft)
        assertEquals(90f, arcs[2].startAngle, 0.01f)
        assertEquals(Offset(868f, 368f), arcs[3].topLeft)
        assertEquals(0f, arcs[3].startAngle, 0.01f)
    }

    @Test
    fun `矩形与渲染状态互为逆映射 含偏移`() {
        val viewW = 1000f
        val viewH = 800f
        val dragBar = 28f
        val density = 2f
        val rect = ResizeRect(150f, 120f, 850f, 600f)
        val geometry = rect.toGeometry(viewW, viewH, dragBar, density)
        val back = geometryToResizeRect(
            geometry.widthDp, geometry.heightDp,
            geometry.horizontalOffsetDp, geometry.bottomOffsetDp,
            viewW, viewH, dragBar, density,
        )
        // dp 取整只允许 1px 级误差：按状态渲染出来的卡片就是调节框本身
        assertEquals(rect.left, back.left, 1.5f)
        assertEquals(rect.top, back.top, 1.5f)
        assertEquals(rect.right, back.right, 1.5f)
        assertEquals(rect.bottom, back.bottom, 1.5f)
    }

    @Test
    fun `offset 就是矩形中心与底边 移动后仍与矩形一致`() {
        val viewW = 1000f
        val viewH = 800f
        val dragBar = 0f
        val density = 1f
        val centered = ResizeRect(200f, 300f, 800f, 800f)
        val base = centered.toGeometry(viewW, viewH, dragBar, density)
        assertEquals(0, base.horizontalOffsetDp)
        assertEquals(0, base.bottomOffsetDp)

        // 往左 100、往上 50：offset 必须完整反映位移，并能逆映射回同一个矩形
        val moved = centered.dragBy(
            ResizeHandle.NONE, -100f, -50f,
            ResizeRect(0f, 0f, viewW, viewH), 260f, 200f,
        )
        val geometry = moved.toGeometry(viewW, viewH, dragBar, density)
        assertEquals(-100, geometry.horizontalOffsetDp)
        assertEquals(50, geometry.bottomOffsetDp)
        val back = geometryToResizeRect(
            geometry.widthDp, geometry.heightDp,
            geometry.horizontalOffsetDp, geometry.bottomOffsetDp,
            viewW, viewH, dragBar, density,
        )
        assertEquals(moved.left, back.left, 0.01f)
        assertEquals(moved.top, back.top, 0.01f)
        assertEquals(moved.right, back.right, 0.01f)
        assertEquals(moved.bottom, back.bottom, 0.01f)
    }

    @Test
    fun `悬浮宽度：设置值优先 未设置按高度推导`() {
        assertEquals(FLOATING_RESIZE_MIN_WIDTH_DP, keyboardWidthBounds(800).first)
        assertEquals(800, keyboardWidthBounds(800).last)
        // 屏宽小于下限时上限跟着收紧，不产生空区间
        assertEquals(240, keyboardWidthBounds(240).first)
        assertEquals(240, keyboardWidthBounds(240).last)
        assertEquals(700, resolvedFloatingWidth(800, 600, 300, 300, false, overrideWidth = 700))
        assertEquals(800, resolvedFloatingWidth(800, 600, 300, 300, false, overrideWidth = 9999))
        assertEquals(FLOATING_RESIZE_MIN_WIDTH_DP, resolvedFloatingWidth(800, 600, 300, 300, false, overrideWidth = 10))
        // 0 = 未设置 → 与旧的高度推导完全一致
        assertEquals(
            floatingKeyboardWidth(800, 600, 300, 300, false),
            resolvedFloatingWidth(800, 600, 300, 300, false, overrideWidth = 0),
        )
    }

    @Test
    fun `最小尺寸：手机基准与可用区域取小`() {
        // 契约：手机基准不低于 360×220，横屏不放宽；具体取值允许按需调大
        assertTrue(FLOATING_RESIZE_MIN_WIDTH_DP >= 360)
        assertTrue(FLOATING_RESIZE_MIN_HEIGHT_DP >= 220)
        assertEquals(FLOATING_RESIZE_MIN_WIDTH_DP, floatingResizeMinWidthDp(1000))
        assertEquals(FLOATING_RESIZE_MIN_HEIGHT_DP, floatingResizeMinHeightDp(800))
        // 可用区比基准还小时取可用区，不产生越界初值
        assertEquals(280, floatingResizeMinWidthDp(280))
        assertEquals(180, floatingResizeMinHeightDp(180))
    }

    @Test
    fun `横屏高度下限不再随方向放宽`() {
        // 旧实现横屏下限 130，比竖屏还小；现在两个方向共用同一手机基准
        assertEquals(FLOATING_RESIZE_MIN_HEIGHT_DP, floatingResizeHeightBounds(360, landscape = true).first)
        assertEquals(FLOATING_RESIZE_MIN_HEIGHT_DP, floatingResizeHeightBounds(900, landscape = false).first)
        // 上限仍按屏幕比例收窄，但不低于下限
        assertEquals(FLOATING_RESIZE_MIN_HEIGHT_DP, floatingResizeHeightBounds(200, landscape = true).last)
    }

    @Test
    fun `四角对角线提示在角内侧且只做视觉`() {
        val frame = ResizeRect(100f, 50f, 900f, 450f)
        val lines = resizeCornerDiagonals(frame, lengthPx = 24f, insetPx = 12f)
        assertEquals(4, lines.size)
        // ↖ 与 ↘ 沿角平分线向内，端点到角点距离相同
        assertEquals(Offset(112f, 62f), lines[0].first)
        assertEquals(Offset(136f, 86f), lines[0].second)
        assertEquals(Offset(888f, 62f), lines[1].first)
        assertEquals(Offset(864f, 86f), lines[1].second)
        assertEquals(Offset(112f, 438f), lines[2].first)
        assertEquals(Offset(136f, 414f), lines[2].second)
        assertEquals(Offset(888f, 438f), lines[3].first)
        assertEquals(Offset(864f, 414f), lines[3].second)
    }
}
