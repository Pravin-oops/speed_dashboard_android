package com.odometer.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.odometer.app.GpsState
import com.odometer.app.RideTracker
import com.odometer.app.ui.theme.Amber
import com.odometer.app.ui.theme.Chalk
import com.odometer.app.ui.theme.EcoGreen
import com.odometer.app.ui.theme.Label
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The riding dashboard: the speedometer over the whole screen, with the elevation scope in
 * the top-left corner, the status controls in the top-right and the trip's running figures
 * along the bottom. Ending the trip needs a long press, so a glove brushing the screen
 * cannot do it.
 *
 * When [compact] (the app is sharing the screen with a navigation app), only the speedometer
 * and the edge glow are shown; the trip keeps recording in the background.
 */
@Composable
fun DashboardScreen(
    tracker: RideTracker,
    compact: Boolean,
    rotationLocked: Boolean,
    batterySaver: Boolean,
    onToggleRotationLock: () -> Unit,
    onToggleBatterySaver: () -> Unit,
    onOpenMaps: () -> Unit,
    onGpsClick: () -> Unit,
    onEndTrip: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val speedometer: @Composable (Modifier) -> Unit = { modifier ->
        Speedometer(
            speedKmh = tracker.speedKmh,
            scaleMaxKmh = tracker.gaugeMaxKmh,
            driveStyle = tracker.driveStyle,
            modifier = modifier.onHold {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                tracker.cycleGaugeRange()
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        if (compact) {
            // Sharing the screen with a map: the dial takes the whole window. The GPS line
            // only appears when something is wrong, since a 0 would otherwise be misleading.
            Box(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(8.dp),
            ) {
                speedometer(Modifier.fillMaxSize())
                if (tracker.gpsState != GpsState.Fix) {
                    GpsPill(
                        gpsState = tracker.gpsState,
                        accuracyMeters = tracker.accuracyMeters,
                        onClick = onGpsClick,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        } else BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(12.dp),
        ) {
            // Sideways on the handlebar the dial is as tall as the screen, which leaves the
            // corners either side of it free. Upright it is as wide as the screen, with
            // room above and below instead.
            val wide = maxWidth > maxHeight
            val statusHeight = 44.dp
            val tripBarHeight = 52.dp

            speedometer(
                Modifier
                    .fillMaxSize()
                    // Sideways, just enough to keep the top of the arc clear of the status row.
                    .padding(top = if (wide) 20.dp else statusHeight, bottom = if (wide) 0.dp else tripBarHeight),
            )
            ElevationScope(
                altitudeMeters = tracker.altitudeMeters,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = if (wide) 0.dp else statusHeight + 8.dp)
                    .size(if (wide) min(maxHeight * 0.45f, maxWidth * 0.2f) else maxWidth * 0.34f),
            )
            Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
                StatusRow(
                    gpsState = tracker.gpsState,
                    accuracyMeters = tracker.accuracyMeters,
                    rotationLocked = rotationLocked,
                    fill = !wide,
                    onToggleRotationLock = onToggleRotationLock,
                    onOpenMaps = onOpenMaps,
                    onGpsClick = onGpsClick,
                    modifier = Modifier.heightIn(min = statusHeight),
                )
                // Under the status row, in the corner the dial leaves free either way up.
                val saverColor by animateColorAsState(if (batterySaver) EcoGreen else Label, label = "saverChip")
                GlassChip(
                    text = if (batterySaver) "BATTERY SAVER ON" else "BATTERY SAVER",
                    color = saverColor,
                    onClick = onToggleBatterySaver,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TripBar(
                tracker = tracker,
                spread = wide,
                onEndTrip = onEndTrip,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Over everything and out to the physical edges of the screen, so the riding-style
        // colour is visible in peripheral vision.
        EdgeGlow(
            driveStyle = tracker.driveStyle,
            moving = tracker.speedKmh > 0f,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** GPS, the clock, MAPS and the rotation lock. [fill] stretches it across the screen. */
@Composable
private fun StatusRow(
    gpsState: GpsState,
    accuracyMeters: Float,
    rotationLocked: Boolean,
    fill: Boolean,
    onToggleRotationLock: () -> Unit,
    onOpenMaps: () -> Unit,
    onGpsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clock by produceState(initialValue = context.clockText()) {
        while (true) {
            delay(1_000L)
            value = context.clockText()
        }
    }
    Row(
        modifier = if (fill) modifier.fillMaxWidth() else modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GpsPill(gpsState, accuracyMeters, onGpsClick, if (fill) Modifier.weight(1f) else Modifier)
        // While GPS needs attention, its message gets the room instead of the clock.
        if (gpsState == GpsState.Fix) {
            Text(text = clock, color = Chalk, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        GlassChip(text = "MAPS", color = Chalk, onClick = onOpenMaps)
        val lockColor by animateColorAsState(if (rotationLocked) Amber else Label, label = "rotationChip")
        GlassChip(
            text = if (rotationLocked) "LOCKED" else "LOCK",
            color = lockColor,
            onClick = onToggleRotationLock,
        )
    }
}

/**
 * The running figures of the trip and the control that ends it. [spread] pushes the figures
 * into the two bottom corners, leaving the middle to the dial.
 */
@Composable
private fun TripBar(tracker: RideTracker, spread: Boolean, onEndTrip: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        val cell = if (spread) Modifier else Modifier.weight(1f)
        Stat("TRIP", formatKm(tracker.tripKm), "km", cell)
        if (spread) Spacer(Modifier.width(24.dp))
        Stat("MOVING", formatClockDuration(tracker.movingMillis), "h:mm", cell)
        if (spread) Spacer(Modifier.weight(1f))
        Stat("AVG", "${tracker.averageKmh.roundToInt()}", "km/h", cell)
        if (spread) Spacer(Modifier.width(20.dp))
        HoldChip(text = "HOLD TO END", color = Chalk, onHold = onEndTrip)
    }
}

/** Runs [action] on a long press, without reacting to ordinary taps. */
@Composable
private fun Modifier.onHold(action: () -> Unit): Modifier {
    val current by rememberUpdatedState(action)
    return pointerInput(Unit) { detectTapGestures(onLongPress = { current() }) }
}
