package com.odometer.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.odometer.app.GpsState
import com.odometer.app.RideTracker
import com.odometer.app.ui.theme.Amber
import com.odometer.app.ui.theme.Chalk
import com.odometer.app.ui.theme.Ink
import com.odometer.app.ui.theme.Muted
import com.odometer.app.ui.theme.Panel
import com.odometer.app.ui.theme.PanelEdge
import com.odometer.app.ui.theme.Signal
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val LocationPermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)
private val GoodSignal = Color(0xFF4CD080)

/**
 * The riding dashboard. Wide screens put the speedometer on the left and the odometers on
 * the right; tall screens stack them. Anything that changes data needs a long press, so a
 * glove brushing the screen cannot reset a trip.
 */
@Composable
fun DashboardScreen(
    tracker: RideTracker,
    rotationLocked: Boolean,
    onToggleRotationLock: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var editingTotal by remember { mutableStateOf(false) }

    // If the system answers a tapped request instantly, it refused to show its dialog
    // (permission permanently denied), so the only way forward is the app's settings page.
    var tappedRequestAt by remember { mutableLongStateOf(0L) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        tracker.start()
        val answeredInstantly = SystemClock.elapsedRealtime() - tappedRequestAt < 400L
        if (!tracker.hasPermission() && tappedRequestAt != 0L && answeredInstantly) context.openAppSettings()
        tappedRequestAt = 0L
    }
    LaunchedEffect(Unit) {
        if (!tracker.hasPermission()) permissionLauncher.launch(LocationPermissions)
    }

    val status: @Composable () -> Unit = {
        StatusRow(
            gpsState = tracker.gpsState,
            accuracyMeters = tracker.accuracyMeters,
            rotationLocked = rotationLocked,
            onToggleRotationLock = onToggleRotationLock,
            onGpsClick = {
                when (tracker.gpsState) {
                    GpsState.NoPermission -> {
                        tappedRequestAt = SystemClock.elapsedRealtime()
                        permissionLauncher.launch(LocationPermissions)
                    }
                    GpsState.Disabled -> context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    else -> Unit
                }
            },
        )
    }
    val speedometer: @Composable (Modifier) -> Unit = { modifier ->
        Speedometer(
            speedKmh = tracker.speedKmh,
            scaleMaxKmh = tracker.gaugeMaxKmh,
            modifier = modifier.onHold {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                tracker.cycleGaugeRange()
            },
        )
    }
    val total: @Composable (Dp) -> Unit = { digitWidth ->
        Section(onHold = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            editingTotal = true
        }) {
            SectionHeader("TOTAL · KM", "HOLD TO SET")
            Odometer(
                value = tracker.totalKm,
                modifier = Modifier.fillMaxWidth(),
                integerDigits = 6,
                fractionDigits = 1,
                maxDigitWidth = digitWidth,
                animationSpec = OdometerDefaults.GlideSpring,
                staggerMillis = 0L,
            )
        }
    }
    val trip: @Composable (Dp) -> Unit = { digitWidth ->
        Section(onHold = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            tracker.resetTrip()
        }) {
            SectionHeader("TRIP · KM", "HOLD TO RESET")
            Odometer(
                value = tracker.tripKm,
                modifier = Modifier.fillMaxWidth(),
                integerDigits = 4,
                fractionDigits = 1,
                maxDigitWidth = digitWidth,
                animationSpec = OdometerDefaults.GlideSpring,
                staggerMillis = 0L,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("MAX", "${tracker.maxKmh.roundToInt()}", "km/h", Modifier.weight(1f))
                Stat("AVG", "${tracker.averageKmh.roundToInt()}", "km/h", Modifier.weight(1f))
                Stat("MOVING", formatDuration(tracker.movingMillis), "h:mm", Modifier.weight(1f))
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ink, Color(0xFF12141A), Ink)))
            .safeDrawingPadding()
            .padding(12.dp),
    ) {
        if (maxWidth > maxHeight) {
            // Sideways on the handlebar: size the wheels from the short edge so both
            // odometers always fit without scrolling.
            val totalDigit = min(44.dp, maxHeight * 0.105f)
            val tripDigit = min(36.dp, maxHeight * 0.085f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                speedometer(Modifier.weight(1f).fillMaxHeight())
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    status()
                    total(totalDigit)
                    trip(tripDigit)
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                status()
                speedometer(Modifier.weight(1f).fillMaxWidth())
                total(48.dp)
                trip(38.dp)
            }
        }
    }

    if (editingTotal) {
        SetOdometerDialog(
            currentKm = tracker.totalKm,
            onDismiss = { editingTotal = false },
            onConfirm = { km ->
                tracker.setTotal(km)
                editingTotal = false
            },
        )
    }
}

@Composable
private fun StatusRow(
    gpsState: GpsState,
    accuracyMeters: Float,
    rotationLocked: Boolean,
    onToggleRotationLock: () -> Unit,
    onGpsClick: () -> Unit,
) {
    val context = LocalContext.current
    val clock by produceState(initialValue = context.clockText()) {
        while (true) {
            delay(1_000L)
            value = context.clockText()
        }
    }
    val (text, color) = when (gpsState) {
        GpsState.NoPermission -> "TAP TO ALLOW LOCATION" to Signal
        GpsState.Disabled -> "GPS IS OFF · TAP TO TURN ON" to Signal
        GpsState.Searching -> "SEARCHING FOR GPS" to Amber
        GpsState.Fix -> "GPS ±${accuracyMeters.roundToInt()} M" to GoodSignal
    }
    val lamp by animateColorAsState(color, label = "gpsLamp")
    val actionable = gpsState == GpsState.NoPermission || gpsState == GpsState.Disabled

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = actionable, onClick = onGpsClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(lamp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                color = lamp,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = clock,
            color = Chalk,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        val chipColor by animateColorAsState(if (rotationLocked) Amber else Muted, label = "rotationChip")
        Text(
            text = if (rotationLocked) "ROTATION LOCKED" else "LOCK ROTATION",
            color = chipColor,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, chipColor.copy(alpha = 0.6f), RoundedCornerShape(50))
                .clickable(onClick = onToggleRotationLock)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Section(onHold: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .border(1.dp, PanelEdge, RoundedCornerShape(20.dp))
            .onHold(onHold)
            .padding(12.dp),
        content = content,
    )
}

@Composable
private fun SectionHeader(label: String, hint: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
            color = Muted,
        )
        Text(text = hint, fontSize = 9.sp, letterSpacing = 1.5.sp, color = Muted.copy(alpha = 0.6f))
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(text = label, fontSize = 9.sp, letterSpacing = 1.5.sp, color = Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Chalk)
            Text(
                text = " $unit",
                fontSize = 10.sp,
                color = Muted,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun SetOdometerDialog(currentKm: Double, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var text by remember { mutableStateOf(String.format(Locale.US, "%.1f", currentKm)) }
    val parsed = text.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..999_999.9 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set odometer") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = parsed == null,
                label = { Text("Total distance (km)") },
                supportingText = { Text("Match it to the reading on your bike.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Runs [action] on a long press, without reacting to ordinary taps. */
@Composable
private fun Modifier.onHold(action: () -> Unit): Modifier {
    val current by rememberUpdatedState(action)
    return pointerInput(Unit) { detectTapGestures(onLongPress = { current() }) }
}

private fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000L
    return String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)
}

private fun Context.clockText(): String = DateFormat.getTimeFormat(this).format(Date())

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
