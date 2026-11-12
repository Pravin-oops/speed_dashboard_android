package com.odometer.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.max

enum class GpsState { NoPermission, Disabled, Searching, Fix }

/**
 * Turns GPS fixes into a live speed and running distances.
 *
 * Speed comes from the receiver's own Doppler measurement when the fix carries one, which is
 * far steadier than differencing positions. Distance is that speed integrated over time, so a
 * phone sitting still does not creep forward from position jitter. Totals survive restarts.
 */
class RideTracker(context: Context) : LocationListener {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val prefs = appContext.getSharedPreferences("ride", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    var speedKmh by mutableFloatStateOf(0f)
        private set
    var maxKmh by mutableFloatStateOf(prefs.getFloat(KEY_MAX, 0f))
        private set
    var totalKm by mutableDoubleStateOf(prefs.getDouble(KEY_TOTAL))
        private set
    var tripKm by mutableDoubleStateOf(prefs.getDouble(KEY_TRIP))
        private set
    var movingMillis by mutableLongStateOf(prefs.getLong(KEY_MOVING, 0L))
        private set
    var gaugeMaxKmh by mutableIntStateOf(prefs.getInt(KEY_GAUGE, GAUGE_STEP))
        private set
    var gpsState by mutableStateOf(if (hasPermission()) GpsState.Searching else GpsState.NoPermission)
        private set
    var accuracyMeters by mutableFloatStateOf(0f)
        private set

    /** Average speed over the time actually spent moving this trip. */
    val averageKmh: Float
        get() = if (movingMillis < 1_000L) 0f else (tripKm / (movingMillis / 3_600_000.0)).toFloat()

    private var listening = false
    private var lastFix: Location? = null
    private var lastSpeedMps = 0f
    private var lastSavedAt = 0L

    private val signalLost = Runnable {
        speedKmh = 0f
        lastSpeedMps = 0f
        if (listening && gpsState == GpsState.Fix) gpsState = GpsState.Searching
    }

    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Safe to call repeatedly; re-checks permission and whether GPS is switched on. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) {
            gpsState = GpsState.NoPermission
            return
        }
        if (LocationManager.GPS_PROVIDER !in locationManager.allProviders) {
            gpsState = GpsState.Disabled
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            gpsState = GpsState.Disabled
        } else if (gpsState != GpsState.Fix) {
            gpsState = GpsState.Searching
        }
        if (listening) return
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, this, Looper.getMainLooper(),
        )
        listening = true
    }

    fun stop() {
        if (listening) {
            locationManager.removeUpdates(this)
            listening = false
        }
        handler.removeCallbacks(signalLost)
        lastFix = null
        lastSpeedMps = 0f
        speedKmh = 0f
        if (gpsState == GpsState.Fix) gpsState = GpsState.Searching
        save()
    }

    fun resetTrip() {
        tripKm = 0.0
        movingMillis = 0L
        maxKmh = 0f
        save()
    }

    fun setTotal(km: Double) {
        totalKm = km.coerceIn(0.0, 999_999.9)
        save()
    }

    /** Steps the dial through its ranges: 60, 120, 180, 240 km/h. */
    fun cycleGaugeRange() {
        gaugeMaxKmh = if (gaugeMaxKmh >= GAUGE_LIMIT) GAUGE_STEP else gaugeMaxKmh + GAUGE_STEP
        save()
    }

    override fun onLocationChanged(location: Location) {
        handler.removeCallbacks(signalLost)
        handler.postDelayed(signalLost, SIGNAL_TIMEOUT_MS)

        gpsState = GpsState.Fix
        accuracyMeters = if (location.hasAccuracy()) location.accuracy else 0f
        val reliable = !location.hasAccuracy() || location.accuracy <= MAX_ACCURACY_M

        val previous = lastFix
        val seconds = previous?.let { (location.elapsedRealtimeNanos - it.elapsedRealtimeNanos) / 1e9 } ?: 0.0

        var speedMps = when {
            location.hasSpeed() -> location.speed
            previous != null && seconds > 0.0 -> (previous.distanceTo(location) / seconds).toFloat()
            else -> 0f
        }
        // Below walking pace a GPS speed is mostly noise; treat it as standing still.
        if (!reliable || speedMps < STANDSTILL_MPS) speedMps = 0f

        if (reliable && previous != null && seconds > 0.0) {
            val meters = if (seconds <= MAX_GAP_S) {
                // Normal case: integrate speed between the two fixes.
                (speedMps + lastSpeedMps) / 2.0 * seconds
            } else {
                // Signal dropped (tunnel, underpass): fall back to the straight line travelled,
                // if it is clearly more than jitter and not an impossible jump.
                val straight = previous.distanceTo(location).toDouble()
                if (straight > 2 * MAX_ACCURACY_M && straight / seconds < MAX_PLAUSIBLE_MPS) straight else 0.0
            }
            if (meters > 0.0) {
                totalKm += meters / 1000.0
                tripKm += meters / 1000.0
                if (seconds <= MAX_GAP_S) movingMillis += (seconds * 1000).toLong()
            }
        }
        if (reliable) {
            lastFix = location
            lastSpeedMps = speedMps
        }

        speedKmh = speedMps * 3.6f
        maxKmh = max(maxKmh, speedKmh)
        while (speedKmh > gaugeMaxKmh * 0.92f && gaugeMaxKmh < GAUGE_LIMIT) gaugeMaxKmh += GAUGE_STEP

        val now = SystemClock.elapsedRealtime()
        if (now - lastSavedAt > SAVE_INTERVAL_MS) save()
    }

    override fun onProviderEnabled(provider: String) {
        if (gpsState == GpsState.Disabled) gpsState = GpsState.Searching
    }

    override fun onProviderDisabled(provider: String) {
        gpsState = GpsState.Disabled
        speedKmh = 0f
        lastSpeedMps = 0f
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun save() {
        lastSavedAt = SystemClock.elapsedRealtime()
        prefs.edit()
            .putLong(KEY_TOTAL, totalKm.toRawBits())
            .putLong(KEY_TRIP, tripKm.toRawBits())
            .putLong(KEY_MOVING, movingMillis)
            .putFloat(KEY_MAX, maxKmh)
            .putInt(KEY_GAUGE, gaugeMaxKmh)
            .apply()
    }

    private fun android.content.SharedPreferences.getDouble(key: String): Double =
        Double.fromBits(getLong(key, 0.0.toRawBits()))

    private companion object {
        const val KEY_TOTAL = "total_km"
        const val KEY_TRIP = "trip_km"
        const val KEY_MOVING = "moving_ms"
        const val KEY_MAX = "max_kmh"
        const val KEY_GAUGE = "gauge_max_kmh"

        const val UPDATE_INTERVAL_MS = 1_000L
        const val SIGNAL_TIMEOUT_MS = 4_000L
        const val SAVE_INTERVAL_MS = 5_000L
        const val MAX_ACCURACY_M = 30f
        const val STANDSTILL_MPS = 0.5f
        const val MAX_GAP_S = 5.0
        const val MAX_PLAUSIBLE_MPS = 70.0
        const val GAUGE_STEP = 60
        const val GAUGE_LIMIT = 240
    }
}
