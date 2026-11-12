package com.odometer.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.odometer.app.ui.DashboardScreen
import com.odometer.app.ui.theme.OdometerTheme

class MainActivity : ComponentActivity() {

    private lateinit var tracker: RideTracker
    private val prefs by lazy { getSharedPreferences("display", MODE_PRIVATE) }
    private var rotationLocked by mutableStateOf(false)
    private var splitScreen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // A dashboard must never dim or sleep while it is on the handlebar.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Restore a locked orientation so the app opens the way the phone is mounted.
        val savedOrientation = prefs.getInt(KEY_ORIENTATION, NOT_LOCKED)
        rotationLocked = savedOrientation != NOT_LOCKED
        requestedOrientation =
            if (rotationLocked) savedOrientation else ActivityInfo.SCREEN_ORIENTATION_FULL_USER

        tracker = RideTracker(this)
        splitScreen = isInMultiWindowMode
        setContent {
            OdometerTheme {
                DashboardScreen(
                    tracker = tracker,
                    compact = splitScreen,
                    rotationLocked = rotationLocked,
                    onToggleRotationLock = ::toggleRotationLock,
                    onOpenMaps = ::openMapsAlongside,
                )
            }
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        splitScreen = isInMultiWindowMode
    }

    /**
     * Opens Google Maps next to the dashboard. On Android 12L and later this puts the two
     * apps into split screen by itself; on older versions Maps opens full screen and the
     * rider starts split screen from the recent-apps view.
     */
    private fun openMapsAlongside() {
        val flags = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK
        val googleMaps = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage("com.google.android.apps.maps")
            .addFlags(flags)
        val anyMapApp = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")).addFlags(flags)
        try {
            startActivity(googleMaps)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(anyMapApp)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No maps app is installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        tracker.start()
    }

    override fun onStop() {
        tracker.stop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Leaning into a corner can fool the rotation sensor, so the rider can pin the screen
     * to however it is mounted right now.
     */
    private fun toggleRotationLock() {
        if (rotationLocked) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            prefs.edit().remove(KEY_ORIENTATION).apply()
        } else {
            val orientation = currentOrientation()
            requestedOrientation = orientation
            prefs.edit().putInt(KEY_ORIENTATION, orientation).apply()
        }
        rotationLocked = !rotationLocked
    }

    private fun currentOrientation(): Int {
        @Suppress("DEPRECATION")
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (landscape) {
            if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_90) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            }
        } else {
            if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_270) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            }
        }
    }

    private companion object {
        const val KEY_ORIENTATION = "locked_orientation"
        const val NOT_LOCKED = Int.MIN_VALUE
    }
}
