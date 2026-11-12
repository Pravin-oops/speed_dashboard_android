package com.odometer.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odometer.app.ui.theme.Amber
import com.odometer.app.ui.theme.Ink
import com.odometer.app.ui.theme.Muted
import com.odometer.app.ui.theme.OdometerTheme
import com.odometer.app.ui.theme.Panel
import com.odometer.app.ui.theme.PanelEdge
import com.odometer.app.ui.theme.Signal
import kotlin.math.roundToInt
import kotlin.random.Random

private val TimeWarps = listOf(1, 60, 600)
private val QuickAdds = listOf(0.1 to "+0.1", 1.0 to "+1", 42.0 to "+42", 1000.0 to "+1k")

@Composable
fun OdometerScreen() {
    var total by rememberSaveable { mutableDoubleStateOf(48_213.6) }
    var trip by rememberSaveable { mutableDoubleStateOf(127.4) }
    var driving by rememberSaveable { mutableStateOf(false) }
    var speedKmh by rememberSaveable { mutableFloatStateOf(90f) }
    var timeWarp by rememberSaveable { mutableIntStateOf(60) }

    // While driving, advance the reading every frame so the wheels roll continuously.
    LaunchedEffect(driving) {
        if (!driving) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val hours = (now - last) / NanosPerHour
                last = now
                val km = speedKmh * timeWarp * hours
                total += km
                trip += km
            }
        }
    }

    fun add(km: Double) {
        // Snap to a tenth so button presses always land on a whole digit.
        total = roundToTenth(total + km)
        trip = roundToTenth(trip + km)
    }

    val spec = if (driving) OdometerDefaults.TrackingSpring else OdometerDefaults.FluidSpring
    val stagger = if (driving) 0L else 40L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ink, androidx.compose.ui.graphics.Color(0xFF12141A), Ink)))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(driving)

        Section {
            Label("TOTAL · KM")
            Spacer(Modifier.height(10.dp))
            Odometer(
                value = total,
                modifier = Modifier.fillMaxWidth(),
                integerDigits = 6,
                fractionDigits = 1,
                animationSpec = spec,
                staggerMillis = stagger,
            )
        }

        Section {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("TRIP · KM", Modifier.weight(1f))
                TextButton(onClick = { trip = 0.0 }) { Text("Reset") }
            }
            Odometer(
                value = trip,
                modifier = Modifier.fillMaxWidth(),
                integerDigits = 4,
                fractionDigits = 1,
                maxDigitWidth = 36.dp,
                animationSpec = spec,
                staggerMillis = stagger,
            )
        }

        Section {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Label("SPEED", Modifier.weight(1f))
                Text(
                    text = speedKmh.roundToInt().toString(),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = " km/h",
                    color = Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Slider(
                value = speedKmh,
                onValueChange = { speedKmh = it },
                valueRange = 0f..240f,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("TIME", Modifier.padding(end = 4.dp))
                TimeWarps.forEach { warp ->
                    FilterChip(
                        selected = timeWarp == warp,
                        onClick = { timeWarp = warp },
                        label = { Text("$warp×") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            DriveButton(driving = driving, onToggle = { driving = !driving })
        }

        Section {
            Label("QUICK ADD")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAdds.forEach { (km, label) ->
                    OutlinedButton(
                        onClick = { add(km) },
                        modifier = Modifier.weight(1f),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) { Text(label, maxLines = 1) }
                }
            }
            OutlinedButton(
                onClick = { total = roundToTenth(Random.nextDouble(0.0, 999_999.9)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Random reading") }
        }
    }
}

@Composable
private fun Header(driving: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "ODOMETER",
            modifier = Modifier.weight(1f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 6.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        val lamp by animateColorAsState(if (driving) Amber else Muted, label = "lamp")
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(lamp),
        )
        Text(
            text = if (driving) "  DRIVING" else "  PARKED",
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            color = lamp,
        )
    }
}

@Composable
private fun DriveButton(driving: Boolean, onToggle: () -> Unit) {
    val container by animateColorAsState(if (driving) Signal else Amber, label = "driveColor")
    val content by animateColorAsState(
        if (driving) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onPrimary,
        label = "driveContent",
    )
    // A soft squash on toggle so the button feels as springy as the wheels.
    val scale by animateFloatAsState(
        targetValue = if (driving) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 300f),
        label = "driveScale",
    )
    Button(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(
            text = if (driving) "Stop" else "Drive",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .border(1.dp, PanelEdge, RoundedCornerShape(20.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
        color = Muted,
    )
}

private fun roundToTenth(km: Double): Double = Math.round(km * 10.0) / 10.0

private const val NanosPerHour = 3.6e12

@Preview(widthDp = 380, heightDp = 820)
@Composable
private fun OdometerScreenPreview() {
    OdometerTheme { OdometerScreen() }
}
