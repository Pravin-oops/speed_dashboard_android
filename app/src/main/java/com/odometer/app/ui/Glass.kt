package com.odometer.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.max
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.odometer.app.ui.theme.Ink

/** Generous, soft corners in the ColorOS style. */
val GlassShape: Shape = RoundedCornerShape(28.dp)
val PillShape: Shape = RoundedCornerShape(50)

/**
 * A pane of dark liquid glass: a smoky translucent body that lets the backdrop's colour
 * through, a sheen across the top as if lit from above, and a bright rim that catches the
 * light at the top-left and again faintly at the bottom-right.
 *
 * The backdrop behind it is made of soft light already, so the pane reads as frosted
 * without a real blur pass, which keeps it cheap and identical on every Android version.
 */
fun Modifier.glass(shape: Shape = GlassShape): Modifier = this
    .clip(shape)
    .background(Color(0x59090B10))
    .background(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.04f)),
        ),
    )
    .drawBehind {
        // Sheen: a band of light on the upper part of the pane.
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.10f),
                0.4f to Color.Transparent,
            ),
        )
    }
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.55f),
            0.35f to Color.White.copy(alpha = 0.10f),
            0.7f to Color.White.copy(alpha = 0.04f),
            1f to Color.White.copy(alpha = 0.26f),
        ),
        shape = shape,
    )

/**
 * Makes a pane behave like a drop of liquid under a finger. It gives way the moment it is
 * touched, leans and stretches a little towards wherever the finger is, carries a pool of
 * light under the fingertip, and wobbles back into shape when let go.
 *
 * It only watches the touch, so taps, long presses and scrolling still reach whatever sits
 * on the pane. Put it before [glass], with the same [shape]. [pull] is how far the pane may
 * lean; use zero for anything inside a list, which should not slide about while scrolling.
 */
@Composable
fun Modifier.liquid(shape: Shape = GlassShape, pressedScale: Float = 0.94f, pull: Dp = 6.dp): Modifier {
    var pressed by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    // Where the finger is on the pane, from -1 (left or top edge) to 1 (right or bottom edge).
    var lean by remember { mutableStateOf(Offset.Zero) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.38f, stiffness = 420f),
        label = "liquidScale",
    )
    val shift by animateOffsetAsState(
        targetValue = if (pressed) lean else Offset.Zero,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 300f),
        label = "liquidLean",
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (pressed) 90 else 420),
        label = "liquidGlow",
    )
    val pullPx = with(LocalDensity.current) { pull.toPx() }
    return this
        .graphicsLayer {
            scaleX = scale * (1f + LeanStretch * abs(shift.x))
            scaleY = scale * (1f + LeanStretch * abs(shift.y))
            translationX = shift.x * pullPx
            translationY = shift.y * pullPx
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                // The last pass: by then the pane's own controls have had the touch.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                fun follow(position: Offset) {
                    touch = position
                    lean = Offset(
                        (position.x / size.width * 2f - 1f).coerceIn(-1f, 1f),
                        (position.y / size.height * 2f - 1f).coerceIn(-1f, 1f),
                    )
                }
                follow(down.position)
                pressed = true
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Final).changes
                            .firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        // A list or a sheet has started scrolling with this finger.
                        if (change.isConsumed && change.positionChanged()) break
                        follow(change.position)
                    }
                } finally {
                    pressed = false
                }
            }
        }
        .drawWithCache {
            val inside = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val radius = max(size.minDimension, 56.dp.toPx())
            onDrawWithContent {
                drawContent()
                if (glow > 0f) {
                    clipPath(inside) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color.White.copy(alpha = 0.30f * glow), Color.Transparent),
                                center = touch,
                                radius = radius,
                            ),
                            radius = radius,
                            center = touch,
                        )
                    }
                }
            }
        }
}

/** How much a pane stretches along the direction it is leaning. */
private const val LeanStretch = 0.04f

/**
 * The scene behind the glass: near-black with a few large pools of soft coloured light,
 * so the panes have something to tint and refract.
 */
@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier, strength: Float = 1f) {
    if (LocalBatterySaver.current) {
        // An OLED pixel that is black is switched off, so black is the cheapest backdrop there is.
        Spacer(modifier.background(Color.Black))
        return
    }
    Spacer(
        modifier
            .background(Ink)
            .drawBehind {
                val reach = size.maxDimension
                fun pool(color: Color, x: Float, y: Float, radius: Float) {
                    val center = Offset(size.width * x, size.height * y)
                    drawRect(
                        Brush.radialGradient(
                            listOf(color, Color.Transparent),
                            center = center,
                            radius = reach * radius,
                        ),
                    )
                }
                pool(Color(0xFF2F6BFF).copy(alpha = 0.40f * strength), x = 0.10f, y = 0.05f, radius = 0.55f)
                pool(Color(0xFF8A3FFC).copy(alpha = 0.34f * strength), x = 0.98f, y = 0.30f, radius = 0.50f)
                pool(Color(0xFF00BFA6).copy(alpha = 0.26f * strength), x = 0.50f, y = 1.05f, radius = 0.50f)
            },
    )
}

/**
 * True while battery saver is on. The backdrop turns pure black and everything that
 * animates without stopping (the speed bar's pulse, the scope's beam, the rim's flashing)
 * holds still, so the screen is only redrawn when a reading actually changes.
 */
val LocalBatterySaver = staticCompositionLocalOf { false }
