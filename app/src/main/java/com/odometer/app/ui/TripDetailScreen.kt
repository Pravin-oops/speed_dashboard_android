package com.odometer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odometer.app.TripSamples
import com.odometer.app.TripStore
import com.odometer.app.TripSummary
import com.odometer.app.ui.theme.Amber
import com.odometer.app.ui.theme.Chalk
import com.odometer.app.ui.theme.EcoGreen
import com.odometer.app.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** The elevation line and its scale, clearly apart from the green of speed. */
private val ElevationBlue = Color(0xFF6FB6FF)

/** A gap this long between two fixes is drawn as a break in the line, not a straight join. */
private const val MaxGapSeconds = 15

/** One saved trip: its figures and its speed over time. */
@Composable
fun TripDetailScreen(trip: TripSummary, store: TripStore, onBack: () -> Unit, onDelete: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val samples by produceState<TripSamples?>(initialValue = null, trip.id) {
        value = withContext(Dispatchers.IO) { store.loadSamples(trip.id) }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassChip(text = "BACK", color = Chalk, onClick = onBack)
            Column(Modifier.weight(1f)) {
                Text(
                    text = shortDateText(trip.startedAt),
                    color = Chalk,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = "${context.clockText(trip.startedAt)} – ${context.clockText(trip.endedAt)}",
                    color = Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HoldChip(text = "HOLD TO DELETE", color = Muted, onHold = onDelete)
        }

        Row(Modifier.fillMaxWidth().liquid(pressedScale = 0.98f, pull = 3.dp).glass().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Stat("DISTANCE", formatKm(trip.distanceKm), "km", Modifier.weight(1f))
            Stat("MOVING", formatClockDuration(trip.movingMillis), "h:mm", Modifier.weight(1f))
            Stat("AVG", "${trip.averageKmh.roundToInt()}", "km/h", Modifier.weight(1f))
            Stat("MAX", "${trip.maxKmh.roundToInt()}", "km/h", Modifier.weight(1f))
        }

        val log = samples
        val hasElevation = log != null && log.altitude.any { !it.isNaN() }
        Column(Modifier.fillMaxWidth().weight(1f).liquid(pressedScale = 0.985f, pull = 3.dp).glass().padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                // The header doubles as the graph's legend: each name is in its line's colour.
                Text(
                    text = "SPEED · KM/H",
                    color = EcoGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                )
                if (hasElevation) {
                    Text(
                        text = "ELEVATION · M",
                        modifier = Modifier.padding(start = 14.dp),
                        color = ElevationBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "– –  AVG ${trip.averageKmh.roundToInt()}",
                    color = Amber,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                )
            }
            when {
                log == null -> Spacer(Modifier.weight(1f))
                log.size < 2 -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No speed was recorded on this trip.",
                        color = Muted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> SpeedGraph(
                    samples = log,
                    averageKmh = trip.averageKmh,
                    startLabel = context.clockText(trip.startedAt),
                    endLabel = context.clockText(trip.startedAt + log.seconds.last() * 1000L),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

/**
 * Speed against time for a whole trip, with the average as a dashed line. Where the trip
 * has elevation readings, they are drawn over it as a second line with its own scale down
 * the right-hand side.
 *
 * A long trip has far more readings than the screen has pixels, so readings are averaged
 * into one point every few pixels. Stretches with no readings (the app was in the
 * background, or GPS dropped out) are left empty rather than bridged.
 */
@Composable
private fun SpeedGraph(
    samples: TripSamples,
    averageKmh: Float,
    startLabel: String,
    endLabel: String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    Spacer(
        modifier.drawWithCache {
            val labelStyle = TextStyle(color = Muted, fontSize = 10.sp)
            val hasElevation = samples.altitude.any { !it.isNaN() }
            val left = 30.dp.toPx()
            val right = size.width - (if (hasElevation) 34.dp else 4.dp).toPx()
            val top = 6.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            val width = right - left
            val height = bottom - top
            if (width <= 0f || height <= 0f) return@drawWithCache onDrawBehind { }

            // A scale that ends on a round number just above the fastest reading.
            val peak = samples.kmh.max()
            val step = when {
                peak <= 50f -> 10f
                peak <= 100f -> 20f
                peak <= 200f -> 40f
                else -> 60f
            }
            val ceiling = max(step, ceil(peak / step) * step)
            fun y(kmh: Float) = bottom - height * (kmh / ceiling).coerceIn(0f, 1f)

            val first = samples.seconds.first()
            val span = max(1, samples.seconds.last() - first)
            val buckets = (width / 3.dp.toPx()).toInt().coerceIn(2, max(2, samples.size))
            val sum = FloatArray(buckets)
            val count = IntArray(buckets)
            val firstAt = IntArray(buckets)
            val lastAt = IntArray(buckets)
            val heightSum = FloatArray(buckets)
            val heightCount = IntArray(buckets)
            for (i in 0 until samples.size) {
                val second = samples.seconds[i]
                val bucket = ((second - first).toLong() * (buckets - 1) / span).toInt().coerceIn(0, buckets - 1)
                if (count[bucket] == 0) firstAt[bucket] = second
                lastAt[bucket] = second
                sum[bucket] += samples.kmh[i]
                count[bucket]++
                val altitude = samples.altitude[i]
                if (!altitude.isNaN()) {
                    heightSum[bucket] += altitude
                    heightCount[bucket]++
                }
            }

            // Elevation gets a scale of its own, on round tens and never tighter than 20 m,
            // so GPS wobble on a flat road does not look like hills.
            val climb = Path()
            var lowest = 0f
            var highest = 0f
            if (hasElevation) {
                val heights = (0 until buckets).filter { heightCount[it] > 0 }.map { heightSum[it] / heightCount[it] }
                lowest = floor(heights.min() / 10f) * 10f
                highest = max(ceil(heights.max() / 10f) * 10f, lowest + 20f)
                var climbing = false
                var climbEnd = 0
                for (bucket in 0 until buckets) {
                    if (heightCount[bucket] == 0) continue
                    val x = left + width * bucket / (buckets - 1)
                    val pointY = bottom - height * (heightSum[bucket] / heightCount[bucket] - lowest) / (highest - lowest)
                    if (climbing && firstAt[bucket] - climbEnd <= MaxGapSeconds) {
                        climb.lineTo(x, pointY)
                    } else {
                        climb.moveTo(x, pointY)
                        climbing = true
                    }
                    climbEnd = lastAt[bucket]
                }
            }
            val heightLabels = if (hasElevation) {
                listOf(highest to top, (lowest + highest) / 2f to (top + bottom) / 2f, lowest to bottom).map { (metres, at) ->
                    at to measurer.measure(metres.roundToInt().toString(), labelStyle.copy(color = ElevationBlue))
                }
            } else {
                emptyList()
            }

            val line = Path()
            val area = Path()
            var drawing = false
            var previousEnd = 0
            var lastX = 0f
            for (bucket in 0 until buckets) {
                if (count[bucket] == 0) continue
                val x = left + width * bucket / (buckets - 1)
                val pointY = y(sum[bucket] / count[bucket])
                if (drawing && firstAt[bucket] - previousEnd > MaxGapSeconds) {
                    area.lineTo(lastX, bottom)
                    area.close()
                    drawing = false
                }
                if (drawing) {
                    line.lineTo(x, pointY)
                    area.lineTo(x, pointY)
                } else {
                    line.moveTo(x, pointY)
                    area.moveTo(x, bottom)
                    area.lineTo(x, pointY)
                    drawing = true
                }
                previousEnd = lastAt[bucket]
                lastX = x
            }
            if (drawing) {
                area.lineTo(lastX, bottom)
                area.close()
            }

            val fill = Brush.verticalGradient(
                listOf(EcoGreen.copy(alpha = 0.35f), Color.Transparent),
                startY = top,
                endY = bottom,
            )
            val scale = (0..(ceiling / step).roundToInt()).map { i ->
                val kmh = i * step
                y(kmh) to measurer.measure(kmh.roundToInt().toString(), labelStyle)
            }
            val start = measurer.measure(startLabel, labelStyle)
            val end = measurer.measure(endLabel, labelStyle)
            val averageY = y(averageKmh)
            val dashes = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))

            onDrawBehind {
                for ((lineY, label) in scale) {
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(left, lineY), Offset(right, lineY), 1.dp.toPx())
                    drawText(
                        label,
                        topLeft = Offset(left - 8.dp.toPx() - label.size.width, lineY - label.size.height / 2f),
                    )
                }
                drawPath(area, fill)
                drawPath(
                    line,
                    EcoGreen,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    climb,
                    ElevationBlue,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                for ((labelY, label) in heightLabels) {
                    drawText(label, topLeft = Offset(right + 8.dp.toPx(), labelY - label.size.height / 2f))
                }
                drawLine(
                    color = Amber,
                    start = Offset(left, averageY),
                    end = Offset(right, averageY),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = dashes,
                )
                drawText(start, topLeft = Offset(left, bottom + 6.dp.toPx()))
                drawText(end, topLeft = Offset(right - end.size.width, bottom + 6.dp.toPx()))
            }
        },
    )
}
