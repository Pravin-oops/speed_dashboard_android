package com.odometer.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.odometer.app.ui.theme.Chalk
import com.odometer.app.ui.theme.EcoGreen
import com.odometer.app.ui.theme.Label
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

// Not a whole number of seconds, so once-a-second readings land all around the dial.
private const val SweepMillis = 6_500
private const val TrailAngle = 110f
private const val MaxBlips = 7

/** Readings closer together than this share the middle of the dial instead of being spread. */
private const val MinSpanMeters = 12f

private class Blip(val angle: Float, val altitude: Float)

/**
 * Elevation above sea level in a round glass bubble styled as a radar scope.
 *
 * The beam turns steadily. Each new altitude reading leaves a blip where the beam was at
 * that moment, further from the centre the higher it is compared with the last few
 * readings, so a climb shows as blips walking outwards and a descent as blips closing in.
 * A blip fades as the beam moves on, as on a real scope.
 *
 * [altitudeMeters] is NaN until the GPS has reported a height; the scope stays still and
 * shows a dash until then.
 */
@Composable
fun ElevationScope(altitudeMeters: Float, modifier: Modifier = Modifier) {
    val known = !altitudeMeters.isNaN()
    // Keyed so the number starts at the first reading instead of counting up from zero.
    val shown = key(known) {
        animateFloatAsState(
            targetValue = if (known) altitudeMeters else 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 30f),
            label = "elevation",
        )
    }
    val beam: State<Float>? = if (known && !LocalBatterySaver.current) {
        rememberInfiniteTransition(label = "scopeBeam").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(SweepMillis, easing = LinearEasing)),
            label = "scopeBeamAngle",
        )
    } else {
        null
    }
    val blips = remember { ArrayDeque<Blip>() }
    LaunchedEffect(altitudeMeters) {
        if (known) {
            blips.addLast(Blip(beam?.value ?: 0f, altitudeMeters))
            if (blips.size > MaxBlips) blips.removeFirst()
        }
    }
    val measurer = rememberTextMeasurer(cacheSize = 16)

    Canvas(modifier.liquid(CircleShape).glass(CircleShape)) {
        val radius = size.minDimension / 2f
        val hairline = max(1f, radius * 0.012f)
        val grid = EcoGreen.copy(alpha = 0.32f)

        // Range rings and cross hairs.
        for (ring in 1..3) drawCircle(grid, radius * ring / 3.3f, center, style = Stroke(hairline))
        drawLine(grid, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), hairline)
        drawLine(grid, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), hairline)

        if (beam != null) {
            val angle = beam.value
            // The afterglow behind the beam, brightest at its leading edge.
            rotate(angle - TrailAngle, center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        TrailAngle / 360f to EcoGreen.copy(alpha = 0.34f),
                        // Clear again past the beam, or the far end of the gradient bleeds
                        // a faint line along the trail's back edge.
                        TrailAngle / 360f + 0.01f to Color.Transparent,
                        1f to Color.Transparent,
                        center = center,
                    ),
                    startAngle = 0f,
                    sweepAngle = TrailAngle,
                    useCenter = true,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                )
            }
            val radians = Math.toRadians(angle.toDouble())
            drawLine(
                color = EcoGreen.copy(alpha = 0.85f),
                start = center,
                end = center + Offset(cos(radians).toFloat(), sin(radians).toFloat()) * radius,
                strokeWidth = hairline * 1.5f,
            )

            if (blips.isNotEmpty()) {
                val low = blips.minOf { it.altitude }
                val high = blips.maxOf { it.altitude }
                val middle = (low + high) / 2f
                val span = max(high - low, MinSpanMeters)
                for (blip in blips) {
                    // How far the beam has travelled since it painted this blip.
                    val behind = ((angle - blip.angle) % 360f + 360f) % 360f
                    val glow = 1f - behind / 360f
                    // Kept to the outer band, clear of the number in the middle.
                    val distance = radius * (0.73f + 0.3f * (blip.altitude - middle) / span)
                    val at = Math.toRadians(blip.angle.toDouble())
                    val spot = center + Offset(cos(at).toFloat(), sin(at).toFloat()) * distance
                    drawCircle(EcoGreen.copy(alpha = 0.3f * glow), radius * 0.085f, spot)
                    drawCircle(EcoGreen.copy(alpha = glow), radius * 0.035f, spot)
                }
            }
        }

        val number = measurer.measure(
            if (known) shown.value.roundToInt().toString() else "–",
            TextStyle(color = Chalk, fontSize = (radius * 0.52f).toSp(), fontWeight = FontWeight.Black),
        )
        drawText(
            number,
            topLeft = Offset(center.x - number.size.width / 2f, center.y - radius * 0.08f - number.size.height / 2f),
        )
        val label = measurer.measure(
            "ELEVATION · M",
            TextStyle(
                color = Label,
                fontSize = (radius * 0.125f).toSp(),
                fontWeight = FontWeight.Medium,
                letterSpacing = (radius * 0.02f).toSp(),
            ),
        )
        drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y + radius * 0.3f))
    }
}
