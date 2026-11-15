package com.odometer.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
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
import kotlin.math.abs
import kotlin.math.max

enum class GpsState { NoPermission, Disabled, Searching, Fix }

/** How hard the rider is changing speed right now. */
enum class DriveStyle { Eco, Brisk, SuddenAcceleration, HardBraking }

/**
 * Turns GPS fixes into a live speed, an elevation and the running figures of a trip.
 *
 * Speed comes from the receiver's own Doppler measurement when the fix carries one, which is
 * far steadier than differencing positions. Distance is that speed integrated over time, so a
 * phone sitting still does not creep forward from position jitter.
 *
 * A trip runs from [startTrip] to [finishTrip]. While it runs, every good fix is appended to
 * the trip's speed log in [store]; the trip's state is persisted, so it carries on if the
 * app is restarted mid-ride.
 */
class RideTracker(context: Context, private val store: TripStore) : LocationListener {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val prefs = appContext.getSharedPreferences("ride", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    var speedKmh by mutableFloatStateOf(0f)
        private set
    var totalKm by mutableDoubleStateOf(prefs.getDouble(KEY_TOTAL))
        private set
    var gaugeMaxKmh by mutableIntStateOf(prefs.getInt(KEY_GAUGE, GAUGE_STEP))
        private set
    var gpsState by mutableStateOf(if (hasPermission()) GpsState.Searching else GpsState.NoPermission)
        private set
    var accuracyMeters by mutableFloatStateOf(0f)
        private set

    /** Height above sea level, smoothed. NaN until the receiver has reported one. */
    var altitudeMeters by mutableFloatStateOf(Float.NaN)
        private set

    /** When the trip being ridden started, in epoch milliseconds; 0 while no trip is running. */
    var tripStartedAt by mutableLongStateOf(prefs.getLong(KEY_TRIP_START, 0L))
        private set
    val tripActive: Boolean get() = tripStartedAt != 0L

    var tripKm by mutableDoubleStateOf(prefs.getDouble(KEY_TRIP))
        private set
    var movingMillis by mutableLongStateOf(prefs.getLong(KEY_MOVING, 0L))
        private set
    var maxKmh by mutableFloatStateOf(prefs.getFloat(KEY_MAX, 0f))
        private set

    /** Change in speed between the last two fixes, in m/s². Negative while slowing down. */
    var accelerationMps2 by mutableFloatStateOf(0f)
        private set
    var driveStyle by mutableStateOf(DriveStyle.Eco)
        private set

    /** Average speed over the time actually spent moving this trip. */
    val averageKmh: Float
        get() = if (movingMillis < 1_000L) 0f else (tripKm / (movingMillis / 3_600_000.0)).toFloat()

    private var listening = false
    private var lastFix: Location? = null
    private var lastSpeedMps = 0f
    private var lastSavedAt = 0L
    private var suddenUntil = 0L
    private var suddenWasBraking = false
    private var recorder: TripRecorder? = null

    // The receiver's own sea-level altitude, read from its NMEA stream.
    private var seaLevelAltitude = 0f
    private var seaLevelAltitudeAt = NEVER
    private val nmeaListener = OnNmeaMessageListener { message, _ -> readSeaLevelAltitude(message) }

    private val signalLost = Runnable {
        speedKmh = 0f
        lastSpeedMps = 0f
        clearAcceleration()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.addNmeaListener(appContext.mainExecutor, nmeaListener)
        } else {
            @Suppress("DEPRECATION")
            locationManager.addNmeaListener(nmeaListener, handler)
        }
        listening = true
    }

    fun stop() {
        if (listening) {
            locationManager.removeUpdates(this)
            locationManager.removeNmeaListener(nmeaListener)
            listening = false
        }
        handler.removeCallbacks(signalLost)
        lastFix = null
        lastSpeedMps = 0f
        speedKmh = 0f
        clearAcceleration()
        if (gpsState == GpsState.Fix) gpsState = GpsState.Searching
        // Reopened on the next fix; closing here leaves nothing buffered if the app is killed.
        recorder?.close()
        recorder = null
        save()
    }

    fun startTrip() {
        if (tripActive) return
        tripKm = 0.0
        movingMillis = 0L
        maxKmh = 0f
        tripStartedAt = System.currentTimeMillis()
        save()
    }

    /**
     * Ends the running trip and files it in the history. Returns the saved trip, or null if
     * it covered too little ground to be worth keeping (a start pressed by mistake).
     */
    fun finishTrip(): TripSummary? {
        if (!tripActive) return null
        recorder?.close()
        recorder = null
        val trip = TripSummary(
            startedAt = tripStartedAt,
            endedAt = System.currentTimeMillis(),
            distanceKm = tripKm,
            movingMillis = movingMillis,
            maxKmh = maxKmh,
            averageKmh = averageKmh,
        )
        tripStartedAt = 0L
        save()
        return if (trip.distanceKm >= MIN_TRIP_KM) {
            store.add(trip)
            trip
        } else {
            store.discardSamples(trip.id)
            null
        }
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
                if (tripActive) {
                    tripKm += meters / 1000.0
                    if (seconds <= MAX_GAP_S) movingMillis += (seconds * 1000).toLong()
                }
            }
        }
        // Acceleration is only meaningful between two good fixes a moment apart.
        val comparable = reliable && previous != null && seconds > 0.0 && seconds <= MAX_GAP_S
        updateDriveStyle(if (comparable) ((speedMps - lastSpeedMps) / seconds).toFloat() else 0f)

        if (reliable) {
            lastFix = location
            lastSpeedMps = speedMps
            updateAltitude(location)
        }

        speedKmh = speedMps * 3.6f
        while (speedKmh > gaugeMaxKmh * 0.92f && gaugeMaxKmh < GAUGE_LIMIT) gaugeMaxKmh += GAUGE_STEP

        if (tripActive) {
            maxKmh = max(maxKmh, speedKmh)
            if (reliable) {
                val log = recorder ?: store.openRecorder(tripStartedAt).also { recorder = it }
                log.add(((System.currentTimeMillis() - tripStartedAt) / 1000L).toInt(), speedKmh, altitudeMeters)
            }
        }

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
        clearAcceleration()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    /**
     * A fix's own altitude is measured from the GPS ellipsoid, which sits up to a hundred
     * metres off sea level depending on where you are. The receiver's GGA sentence carries
     * the sea-level figure, so that is preferred whenever it is fresh.
     */
    private fun updateAltitude(location: Location) {
        val nmeaFresh = seaLevelAltitudeAt != NEVER &&
            SystemClock.elapsedRealtime() - seaLevelAltitudeAt < NMEA_FRESH_MS
        val altitude = when {
            nmeaFresh -> seaLevelAltitude
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && location.hasMslAltitude() ->
                location.mslAltitudeMeters.toFloat()
            location.hasAltitude() -> location.altitude.toFloat()
            else -> return
        }
        // GPS height wanders by several metres between fixes; follow it gently.
        altitudeMeters = if (altitudeMeters.isNaN()) {
            altitude
        } else {
            altitudeMeters + ALTITUDE_SMOOTHING * (altitude - altitudeMeters)
        }
    }

    /** `$GPGGA,time,lat,N,lon,E,quality,satellites,hdop,altitude,M,...` */
    private fun readSeaLevelAltitude(message: String) {
        if (!message.startsWith("GGA", startIndex = 3)) return
        val fields = message.split(',')
        if (fields.size < 11) return
        val hasFix = fields[6].isNotEmpty() && fields[6] != "0"
        val altitude = fields[9].toFloatOrNull()
        if (hasFix && altitude != null) {
            seaLevelAltitude = altitude
            seaLevelAltitudeAt = SystemClock.elapsedRealtime()
        }
    }

    private fun updateDriveStyle(acceleration: Float) {
        accelerationMps2 = acceleration
        val now = SystemClock.elapsedRealtime()
        if (abs(acceleration) >= SUDDEN_MPS2) {
            // Hold the warning for a moment so a one-second spike is still noticed.
            suddenUntil = now + SUDDEN_HOLD_MS
            suddenWasBraking = acceleration < 0f
        }
        driveStyle = when {
            now < suddenUntil -> if (suddenWasBraking) DriveStyle.HardBraking else DriveStyle.SuddenAcceleration
            abs(acceleration) >= ECO_LIMIT_MPS2 -> DriveStyle.Brisk
            else -> DriveStyle.Eco
        }
    }

    private fun clearAcceleration() {
        accelerationMps2 = 0f
        suddenUntil = 0L
        driveStyle = DriveStyle.Eco
    }

    private fun save() {
        lastSavedAt = SystemClock.elapsedRealtime()
        recorder?.flush()
        prefs.edit()
            .putLong(KEY_TOTAL, totalKm.toRawBits())
            .putLong(KEY_TRIP_START, tripStartedAt)
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
        const val KEY_TRIP_START = "trip_started_at"
        const val KEY_TRIP = "trip_km"
        const val KEY_MOVING = "moving_ms"
        const val KEY_MAX = "max_kmh"
        const val KEY_GAUGE = "gauge_max_kmh"

        const val UPDATE_INTERVAL_MS = 1_000L
        const val SIGNAL_TIMEOUT_MS = 4_000L
        const val SAVE_INTERVAL_MS = 5_000L
        const val NMEA_FRESH_MS = 5_000L
        const val NEVER = -1L
        const val MAX_ACCURACY_M = 30f
        const val STANDSTILL_MPS = 0.5f
        const val MAX_GAP_S = 5.0
        const val MAX_PLAUSIBLE_MPS = 70.0
        const val ALTITUDE_SMOOTHING = 0.25f
        const val MIN_TRIP_KM = 0.05
        const val GAUGE_STEP = 60

        // Speed change per second. Up to 1.5 m/s² (about 5 km/h each second) is smooth,
        // fuel-saving riding; 2.5 m/s² (9 km/h each second) and beyond is a sudden surge
        // or hard braking, in line with common driver-behaviour thresholds.
        const val ECO_LIMIT_MPS2 = 1.5f
        const val SUDDEN_MPS2 = 2.5f
        const val SUDDEN_HOLD_MS = 2_500L
        const val GAUGE_LIMIT = 240
    }
}
