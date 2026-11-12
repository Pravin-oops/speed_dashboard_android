package com.odometer.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.odometer.app.ui.theme.Amber
import com.odometer.app.ui.theme.Chalk
import com.odometer.app.ui.theme.Muted
import com.odometer.app.ui.theme.Signal
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val StartAngle = 150f
private const val SweepAngle = 240f
private const val Divisions = 12

// The arc is drawn in a frame rotated back by this much, so the seam of the sweep
// gradient sits just before the arc's rounded start cap instead of inside it.
private const val SeamOffset = 8f

/**
 * A 240° arc speedometer with the speed as a large number in the middle.
 *
 * GPS reports speed about once a second; a soft spring carries the arc and the number
 * between reports so they glide instead of ticking.
 */
@Composable
fun Speedometer(speedKmh: Float, scaleMaxKmh: Int, modifier: Modifier = Modifier) {
    val speed = animateFloatAsState(
        targetValue = speedKmh,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 30f),
        label = "speed",
    )
    val scale = animateFloatAsState(
        targetValue = scaleMaxKmh.toFloat(),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 80f),
        label = "scale",
    )
    val measurer = rememberTextMeasurer(cacheSize = 32)

    // Everything is read inside the draw lambda, so an animation frame only redraws.
    Canvas(modifier) {
        // A 240° arc is 2R wide and 1.5R tall; leave a little room for the rounded caps.
        val outer = min(size.width / 2f, size.height / 1.58f)
        val stroke = outer * 0.11f
        val radius = outer - stroke / 2f
        val center = Offset(size.width / 2f, (size.height - 1.5f * outer) / 2f + outer)
        val arcTopLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)

        drawArc(
            color = Color(0xFF1C1F26),
            startAngle = StartAngle,
            sweepAngle = SweepAngle,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )

        val fraction = (speed.value / scale.value).coerceIn(0f, 1f)
        if (fraction > 0.002f) {
            rotate(StartAngle - SeamOffset, center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Amber,
                        SeamOffset / 360f to Amber,
                        (SeamOffset + SweepAngle * 0.6f) / 360f to Color(0xFFFF7A2F),
                        (SeamOffset + SweepAngle) / 360f to Signal,
                        1f to Signal,
                        center = center,
                    ),
                    startAngle = SeamOffset,
                    sweepAngle = SweepAngle * fraction,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }

        // Tick marks and scale labels inside the arc.
        val tickOuter = radius - stroke / 2f - outer * 0.035f
        val labelStyle = TextStyle(color = Muted, fontSize = (outer * 0.085f).toSp(), fontWeight = FontWeight.Medium)
        for (i in 0..Divisions) {
            val major = i % 2 == 0
            val angle = Math.toRadians((StartAngle + SweepAngle * i / Divisions).toDouble())
            val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            val length = outer * if (major) 0.07f else 0.035f
            drawLine(
                color = if (major) Muted else Muted.copy(alpha = 0.5f),
                start = center + direction * tickOuter,
                end = center + direction * (tickOuter - length),
                strokeWidth = outer * if (major) 0.012f else 0.008f,
                cap = StrokeCap.Round,
            )
            if (major) {
                val label = measurer.measure((scaleMaxKmh * i / Divisions).toString(), labelStyle)
                val at = center + direction * (tickOuter - length - outer * 0.1f)
                drawText(label, topLeft = Offset(at.x - label.size.width / 2f, at.y - label.size.height / 2f))
            }
        }

        val number = measurer.measure(
            speed.value.roundToInt().toString(),
            TextStyle(color = Chalk, fontSize = (outer * 0.52f).toSp(), fontWeight = FontWeight.Black),
        )
        drawText(
            number,
            topLeft = Offset(center.x - number.size.width / 2f, center.y - outer * 0.06f - number.size.height / 2f),
        )
        val unit = measurer.measure(
            "km/h",
            TextStyle(color = Muted, fontSize = (outer * 0.1f).toSp(), fontWeight = FontWeight.Medium),
        )
        drawText(unit, topLeft = Offset(center.x - unit.size.width / 2f, center.y + outer * 0.26f))
    }
}
