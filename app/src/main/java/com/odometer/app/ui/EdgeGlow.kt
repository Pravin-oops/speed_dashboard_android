package com.odometer.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.odometer.app.DriveStyle
import com.odometer.app.ui.theme.Amber
import com.odometer.app.ui.theme.EcoGreen
import kotlin.math.roundToInt

/**
 * Lights the whole rim of the screen in the colour of the current riding style, so it can be
 * read out of the corner of the eye without looking at the dial.
 *
 * Green is a thin, steady rim. Amber is deeper and breathes slowly. Red floods much further
 * into the screen and flashes fast. The rim is dark while standing still.
 *
 * Draw it over the content, filling the full window; it does not intercept touches.
 */
@Composable
fun EdgeGlow(driveStyle: DriveStyle, moving: Boolean, modifier: Modifier = Modifier) {
    val sudden = driveStyle == DriveStyle.SuddenAcceleration || driveStyle == DriveStyle.HardBraking
    // Warn quickly, relax back slowly.
    val millis = if (sudden) 150 else 600

    val primary by animateColorAsState(
        targetValue = when (driveStyle) {
            DriveStyle.Eco -> EcoGreen
            DriveStyle.Brisk -> Amber
            else -> AlertRed
        },
        animationSpec = tween(millis),
        label = "glowPrimary",
    )
    // A second hue the rim shades into and out of as it runs around the screen.
    val secondary by animateColorAsState(
        targetValue = when (driveStyle) {
            DriveStyle.Eco -> Color(0xFF22D3C5)
            DriveStyle.Brisk -> Color(0xFFFF7A2F)
            else -> Color(0xFFFF7A1A)
        },
        animationSpec = tween(millis),
        label = "glowSecondary",
    )
    val depth by animateDpAsState(
        targetValue = when (driveStyle) {
            DriveStyle.Eco -> 30.dp
            DriveStyle.Brisk -> 52.dp
            else -> 96.dp
        },
        animationSpec = tween(millis),
        label = "glowDepth",
    )
    val strength by animateFloatAsState(
        targetValue = when {
            !moving -> 0f
            driveStyle == DriveStyle.Eco -> 0.75f
            else -> 1f
        },
        animationSpec = tween(millis),
        label = "glowStrength",
    )

    // The pulse only exists while there is something to warn about, so steady eco riding
    // costs no animation frames at all.
    val pulse: State<Float>? = if (moving && driveStyle != DriveStyle.Eco) {
        key(sudden) {
            rememberInfiniteTransition(label = "glowPulse").animateFloat(
                initialValue = 1f,
                targetValue = if (sudden) 0.35f else 0.6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(if (sudden) 260 else 800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "glowPulseAlpha",
            )
        }
    } else {
        null
    }

    Spacer(
        modifier
            .graphicsLayer {
                // Offscreen so the fade below can erase the glow without touching the dashboard.
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = strength * (pulse?.value ?: 1f)
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                // Whole pixels, so the nine pieces below meet without seams.
                val reach = depth.toPx().coerceAtMost(size.minDimension / 2f).roundToInt().toFloat()
                val rim = Brush.sweepGradient(
                    listOf(primary, secondary, primary, secondary, primary),
                    center = Offset(w / 2f, h / 2f),
                )

                // The rim colour is painted over the whole screen and then erased from the
                // inside out, leaving light only near the edges. The erasers run from "erase
                // everything" at the inner boundary to "erase nothing" at the screen edge,
                // dropping off quickly so the light stays concentrated on the rim.
                val erase = arrayOf(
                    0f to Color.Black,
                    0.6f to Color.Black.copy(alpha = 0.68f),
                    1f to Color.Transparent,
                )
                val top = Brush.verticalGradient(*erase, startY = reach, endY = 0f)
                val bottom = Brush.verticalGradient(*erase, startY = h - reach, endY = h)
                val left = Brush.horizontalGradient(*erase, startX = reach, endX = 0f)
                val right = Brush.horizontalGradient(*erase, startX = w - reach, endX = w)
                // Corners fade radially from the inner corner, so the glow wraps around
                // them in a smooth curve instead of leaving a gap.
                val corners = listOf(
                    Offset(reach, reach) to Offset.Zero,
                    Offset(w - reach, reach) to Offset(w - reach, 0f),
                    Offset(reach, h - reach) to Offset(0f, h - reach),
                    Offset(w - reach, h - reach) to Offset(w - reach, h - reach),
                ).map { (innerCorner, topLeft) ->
                    Brush.radialGradient(*erase, center = innerCorner, radius = reach) to topLeft
                }
                val cornerSize = Size(reach, reach)
                val horizontalEdge = Size(w - 2 * reach, reach)
                val verticalEdge = Size(reach, h - 2 * reach)

                onDrawBehind {
                    if (strength <= 0.01f || reach < 1f) return@onDrawBehind
                    drawRect(rim)
                    val out = BlendMode.DstOut
                    drawRect(top, Offset(reach, 0f), horizontalEdge, blendMode = out)
                    drawRect(bottom, Offset(reach, h - reach), horizontalEdge, blendMode = out)
                    drawRect(left, Offset(0f, reach), verticalEdge, blendMode = out)
                    drawRect(right, Offset(w - reach, reach), verticalEdge, blendMode = out)
                    corners.forEach { (brush, topLeft) -> drawRect(brush, topLeft, cornerSize, blendMode = out) }
                    drawRect(
                        Color.Black,
                        topLeft = Offset(reach, reach),
                        size = Size(w - 2 * reach, h - 2 * reach),
                        blendMode = out,
                    )
                }
            },
    )
}

/** Brighter than the dial's red so the rim still reads in daylight. */
private val AlertRed = Color(0xFFFF3B30)
