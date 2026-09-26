package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

internal const val KEY_GLOW_DURATION_MS = 500

private data class GlowSquare(val x: Float, val y: Float, val side: Float, val angle: Float, val spin: Float)

/** Transform only the cap/shadow, inside the unchanged key hit target. */
internal fun Modifier.keyGlow(cap: Modifier, animateCap: Boolean = true): Modifier = composed {
    if (!LocalKeyboardInputPreferences.current.keyGlowEnabled) return@composed this.then(cap)
    // One coherent colour per press, as in the reference; overlapping squares
    // vary in brightness instead of mixing three unrelated theme colours.
    var color by remember { mutableStateOf(glowPalette.first()) }
    val elapsed = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var animation by remember { mutableStateOf<Job?>(null) }
    var particles by remember { mutableStateOf(emptyList<GlowSquare>()) }
    this.pointerInput(Unit) {
        awaitEachGesture {
            // 只旁观 DOWN，因此同一帧内按下/松开也不会丢失动画；不干扰拂动和长按。
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            animation?.cancel()
            color = glowPalette[Random.nextInt(glowPalette.size)]
            particles = List(4) { index ->
                GlowSquare(0.2f + (index % 2) * 0.6f + (Random.nextFloat() - 0.5f) * 0.25f,
                    0.15f + (index / 2) * 0.7f + (Random.nextFloat() - 0.5f) * 0.25f,
                    0.85f + Random.nextFloat() * 0.50f,
                    Random.nextFloat() * 60f - 30f, Random.nextFloat() * 100f - 50f)
            }
            animation = scope.launch {
                elapsed.snapTo(0f)
                // This is only the clock. Scale, travel and opacity each have their own
                // continuous nonlinear curve below; none waits at an intermediate state.
                elapsed.animateTo(1f, tween(KEY_GLOW_DURATION_MS, easing = LinearEasing))
            }
        }
    }.graphicsLayer {
        val scale = if (animateCap) keyGlowScale(elapsed.value) else 1f
        scaleX = scale
        scaleY = scale
    }.then(cap).drawWithCache {
        // Only the halo uses a gradient. The four particles must remain squares.
        val glowBrush = Brush.radialGradient(
            listOf(color.copy(alpha = 0.25f), Color.Transparent),
            center = Offset.Zero, radius = size.maxDimension.coerceAtLeast(1f) * 0.8f,
        )
        onDrawWithContent {
            val t = elapsed.value
            if (t < 1f && particles.isNotEmpty()) {
                val remaining = keyGlowRemaining(t)
                // The first rendered DOWN frame is already lit in the reference.
                val fade = remaining
                val travel = keyGlowTravel(t)
                drawRect(color, alpha = fade * 0.75f)
                val glowCenter = Offset(size.width * 0.5f, size.height * 0.55f)
                translate(glowCenter.x, glowCenter.y) {
                    drawRect(glowBrush, topLeft = -glowCenter, size = size, alpha = fade,
                        blendMode = BlendMode.Screen)
                }
                particles.forEach { square ->
                    val side = size.minDimension * square.side * keyGlowSquareSize(t)
                    val center = Offset(
                        size.width * square.x,
                        size.height * (square.y - travel * 0.06f),
                    )
                    rotate(square.angle + square.spin * travel, center) {
                        drawKeyGlowSquare(color, center, side, fade)
                    }
                }
            }
            drawContent() // Keep glyphs sharp, above the light.
        }
    }
}

/** Narrow feather around a square, preserving straight sides and four corners. */
internal fun DrawScope.drawKeyGlowSquare(color: Color, center: Offset, side: Float, alpha: Float) {
    for (layer in 2 downTo 0) {
        val edge = side * layer * 0.015f
        val extent = side + edge * 2f
        drawRoundRect(
            color = color,
            topLeft = center - Offset(extent / 2f, extent / 2f),
            size = Size(extent, extent),
            cornerRadius = CornerRadius(side * 0.09f + edge),
            alpha = alpha * if (layer == 0) 0.22f else 0.035f,
            blendMode = BlendMode.Screen,
        )
    }
}

private val glowPalette = listOf(
    Color(0xFF83E8AC), Color(0xFFFFD58F), Color(0xFF9C9AEC),
    Color(0xFFB19BCC), Color(0xFFEFACD2), Color(0xFFD9ED8B), Color(0xFF89E9D0),
)

// Measured cap widths from the user's 2026-09-24 recording, relative to rest.
// Cubic Hermite interpolation keeps velocity continuous between captured frames.
private val capTimes = floatArrayOf(0f, 17f, 33f, 50f, 67f, 83f, 100f, 117f, 133f, 150f, 167f, 183f, 200f, 217f, 235f)
private val capScales = floatArrayOf(1f, .952f, .852f, .752f, .686f, .667f, .743f, .867f, .967f, 1.029f, 1.052f, 1.052f, 1.033f, 1.014f, 1f)

internal fun keyGlowScale(t: Float): Float {
    val ms = t.coerceIn(0f, 1f) * 500f
    if (ms >= capTimes.last()) return 1f
    val i = (0 until capTimes.lastIndex).first { ms < capTimes[it + 1] }
    val span = capTimes[i + 1] - capTimes[i]
    val x = (ms - capTimes[i]) / span
    fun slope(j: Int): Float = if (j == 0 || j == capTimes.lastIndex) 0f else
        (capScales[j + 1] - capScales[j - 1]) / (capTimes[j + 1] - capTimes[j - 1])
    val x2 = x * x
    val x3 = x2 * x
    return (2f * x3 - 3f * x2 + 1f) * capScales[i] +
        (x3 - 2f * x2 + x) * span * slope(i) +
        (-2f * x3 + 3f * x2) * capScales[i + 1] +
        (x3 - x2) * span * slope(i + 1)
}

/** Keep visible square faces during the tail, instead of collapsing to dots. */
internal fun keyGlowSquareSize(t: Float): Float = 0.3f + 0.7f * (1f - t.coerceIn(0f, 1f))

/** Opacity has an independent smooth tail across the complete 500 ms. */
internal fun keyGlowRemaining(t: Float): Float {
    val remaining = 1f - t.coerceIn(0f, 1f)
    return remaining * remaining * (3f - 2f * remaining)
}

/** Rotation and outward travel start fast and decelerate continuously. */
internal fun keyGlowTravel(t: Float): Float {
    val remaining = 1f - t.coerceIn(0f, 1f)
    return 1f - remaining * remaining * remaining * remaining
}
