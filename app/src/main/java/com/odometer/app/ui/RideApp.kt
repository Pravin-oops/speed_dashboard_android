package com.odometer.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.odometer.app.GpsState
import com.odometer.app.RideTracker
import com.odometer.app.TripStore
import com.odometer.app.TripSummary
import com.odometer.app.ui.theme.EcoGreen

private val LocationPermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/**
 * The whole app: the home screen with the trip history, the riding dashboard while a trip is
 * running, and the page of a saved trip. Which one shows follows from the state, so an app
 * restarted mid-ride comes straight back to the dashboard.
 */
@Composable
fun RideApp(
    tracker: RideTracker,
    store: TripStore,
    splitScreen: Boolean,
    rotationLocked: Boolean,
    batterySaver: Boolean,
    onToggleBatterySaver: () -> Unit,
    onToggleRotationLock: () -> Unit,
    onOpenMaps: () -> Unit,
) {
    val context = LocalContext.current

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
    // Asked on the home screen, so GPS has its fix by the time a trip is started.
    LaunchedEffect(Unit) {
        if (!tracker.hasPermission()) permissionLauncher.launch(LocationPermissions)
    }
    val onGpsClick: () -> Unit = {
        when (tracker.gpsState) {
            GpsState.NoPermission -> {
                tappedRequestAt = SystemClock.elapsedRealtime()
                permissionLauncher.launch(LocationPermissions)
            }
            GpsState.Disabled -> context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else -> Unit
        }
    }

    // A dashboard must never dim or sleep on the handlebar, but the home screen and the
    // history should, like any other app.
    val view = LocalView.current
    val riding = tracker.tripActive
    DisposableEffect(view, riding) {
        view.keepScreenOn = riding
        onDispose { view.keepScreenOn = false }
    }
    // In battery saver, ask for 30 frames a second. Only phones with a variable-rate screen
    // (Android 15 and later) honour it; MainActivity also picks the panel's slowest mode.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        DisposableEffect(view, batterySaver) {
            view.requestedFrameRate = if (batterySaver) SaverFrameRate else View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
            onDispose { view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE }
        }
    }

    var openTrip by remember { mutableStateOf<TripSummary?>(null) }

    // Starting a trip opens the dashboard out of the play button: a circle that grows from
    // the button until it has uncovered the whole screen. 1 means fully open, which is also
    // how the dashboard appears when the app is reopened mid-ride.
    var revealFrom by remember { mutableStateOf(Rect.Zero) }
    var reveal by remember { mutableFloatStateOf(1f) }
    val revealing by remember { derivedStateOf { reveal < 1f } }
    LaunchedEffect(riding) {
        if (riding && reveal < 1f) {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(RevealMillis, easing = FastOutSlowInEasing),
            ) { value, _ -> reveal = value }
        } else {
            reveal = 1f
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Pools of soft colour for the glass panes above to pick up.
        AmbientBackdrop(Modifier.fillMaxSize())

        // Home and the saved trips. Kept underneath while the dashboard is still opening.
        if (!riding || revealing) {
            Crossfade(
                targetState = openTrip,
                modifier = Modifier.fillMaxSize(),
                animationSpec = tween(350),
                label = "screen",
            ) { trip ->
                if (trip == null) {
                    HomeScreen(
                        trips = store.trips,
                        totalKm = tracker.totalKm,
                        gpsState = tracker.gpsState,
                        accuracyMeters = tracker.accuracyMeters,
                        onGpsClick = onGpsClick,
                        onStartTrip = { playButton ->
                            revealFrom = playButton
                            reveal = 0f
                            tracker.startTrip()
                        },
                        onOpenTrip = { openTrip = it },
                        onDeleteTrip = store::delete,
                    )
                } else {
                    TripDetailScreen(
                        trip = trip,
                        store = store,
                        onBack = { openTrip = null },
                        onDelete = {
                            store.delete(trip)
                            openTrip = null
                        },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = riding,
            modifier = Modifier.fillMaxSize(),
            // The reveal below is the entrance; ending a trip fades out to the trip's page.
            enter = EnterTransition.None,
            exit = fadeOut(tween(350)),
        ) {
            Box(Modifier.fillMaxSize().circularReveal(progress = { reveal }, from = revealFrom)) {
                // Its own backdrop, so the home screen does not show through inside the circle.
                AmbientBackdrop(Modifier.fillMaxSize(), strength = 0.55f)
                DashboardScreen(
                    tracker = tracker,
                    compact = splitScreen,
                    rotationLocked = rotationLocked,
                    batterySaver = batterySaver,
                    onToggleBatterySaver = onToggleBatterySaver,
                    onToggleRotationLock = onToggleRotationLock,
                    onOpenMaps = onOpenMaps,
                    onGpsClick = onGpsClick,
                    onEndTrip = {
                        // Straight to the trip just ridden; back from there is the home screen.
                        openTrip = tracker.finishTrip()
                        if (openTrip == null) {
                            Toast.makeText(context, "Trip was too short to save", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

/**
 * Shows only the part of the content inside a circle that grows out of [from] as [progress]
 * runs from 0 to 1, reaching the furthest corner at 1. A ring in the play button's green
 * rides the edge of the opening and fades as it spreads.
 */
private fun Modifier.circularReveal(progress: () -> Float, from: Rect): Modifier = drawWithCache {
    val origin = if (from.isEmpty) size.center else from.center
    val startRadius = from.minDimension / 2f
    val fullRadius = maxOf(
        (origin - Offset.Zero).getDistance(),
        (origin - Offset(size.width, 0f)).getDistance(),
        (origin - Offset(0f, size.height)).getDistance(),
        (origin - Offset(size.width, size.height)).getDistance(),
    )
    val opening = Path()
    val ring = Stroke(width = 3.dp.toPx())
    onDrawWithContent {
        val shown = progress()
        if (shown >= 1f) {
            drawContent()
        } else {
            val radius = startRadius + (fullRadius - startRadius) * shown
            opening.rewind()
            opening.addOval(Rect(origin, radius))
            clipPath(opening) { this@onDrawWithContent.drawContent() }
            drawCircle(EcoGreen.copy(alpha = 0.8f * (1f - shown)), radius, origin, style = ring)
        }
    }
}

private const val RevealMillis = 700
private const val SaverFrameRate = 30f

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
