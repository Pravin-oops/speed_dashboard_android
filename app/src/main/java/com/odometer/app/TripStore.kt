package com.odometer.app

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/** What is kept about a finished trip, besides its speed log. */
data class TripSummary(
    val startedAt: Long,
    val endedAt: Long,
    val distanceKm: Double,
    val movingMillis: Long,
    val maxKmh: Float,
    /** Distance over the time spent moving, so waiting at lights does not drag it down. */
    val averageKmh: Float,
) {
    /** A trip is identified by the moment it started. */
    val id: Long get() = startedAt
}

/**
 * The log of one trip, one entry per GPS fix: [kmh] and [altitude] (metres above sea level)
 * at [seconds] after the start. An altitude is NaN where the receiver had none to give, and
 * throughout for trips recorded before elevation was logged.
 */
class TripSamples(val seconds: IntArray, val kmh: FloatArray, val altitude: FloatArray) {
    val size: Int get() = seconds.size
}

/** Appends readings to a trip's log while it is being ridden. */
class TripRecorder internal constructor(file: File) {
    private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file, true)))

    fun add(second: Int, kmh: Float, altitude: Float) = quietly {
        out.writeInt(second)
        out.writeFloat(kmh)
        out.writeFloat(altitude)
    }

    fun flush() = quietly { out.flush() }

    fun close() = quietly { out.close() }

    // A full disk must not take the dashboard down mid-ride; the log just ends early.
    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
        }
    }
}

/**
 * Finished trips, newest first.
 *
 * The summaries live together in one small index file so the history list opens instantly.
 * Each trip's log is its own binary file (a 4-byte second, speed and altitude per fix,
 * about 45 KB per hour of riding) and is only read when that trip is opened.
 */
class TripStore(context: Context) {

    private val dir = File(context.filesDir, "trips").apply { mkdirs() }
    private val index = AtomicFile(File(dir, "index.json"))

    var trips: List<TripSummary> by mutableStateOf(readIndex())
        private set

    /** Opens the log of trip [id] for appending, so a trip survives the app being restarted. */
    fun openRecorder(id: Long): TripRecorder {
        val file = trackFile(id)
        // A write cut short by a crash would shift every later record; drop the partial one.
        val whole = file.length() / RECORD_BYTES * RECORD_BYTES
        if (file.length() != whole) {
            try {
                RandomAccessFile(file, "rw").use { it.setLength(whole) }
            } catch (_: IOException) {
            }
        }
        return TripRecorder(file)
    }

    fun add(trip: TripSummary) {
        trips = listOf(trip) + trips
        writeIndex()
    }

    fun delete(trip: TripSummary) {
        trips = trips.filterNot { it.id == trip.id }
        writeIndex()
        discardSamples(trip.id)
    }

    /** Removes the log of a trip that was never saved. */
    fun discardSamples(id: Long) {
        trackFile(id).delete()
        speedOnlyFile(id).delete()
    }

    /** Reads a trip's whole log. Call it off the main thread. */
    fun loadSamples(id: Long): TripSamples {
        // Trips from before elevation was logged have the older, speed-only file.
        val withAltitude = trackFile(id).exists()
        val file = if (withAltitude) trackFile(id) else speedOnlyFile(id)
        val count = (file.length() / if (withAltitude) RECORD_BYTES else SPEED_ONLY_RECORD_BYTES).toInt()
        val seconds = IntArray(count)
        val kmh = FloatArray(count)
        val altitude = FloatArray(count) { Float.NaN }
        if (count == 0) return TripSamples(seconds, kmh, altitude)
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                for (i in 0 until count) {
                    seconds[i] = input.readInt()
                    kmh[i] = input.readFloat()
                    if (withAltitude) altitude[i] = input.readFloat()
                }
            }
            TripSamples(seconds, kmh, altitude)
        } catch (_: IOException) {
            TripSamples(IntArray(0), FloatArray(0), FloatArray(0))
        }
    }

    private fun trackFile(id: Long) = File(dir, "$id.track")

    private fun speedOnlyFile(id: Long) = File(dir, "$id.speed")

    private fun readIndex(): List<TripSummary> = try {
        val array = JSONArray(index.readFully().decodeToString())
        List(array.length()) { i ->
            val trip = array.getJSONObject(i)
            TripSummary(
                startedAt = trip.getLong("startedAt"),
                endedAt = trip.getLong("endedAt"),
                distanceKm = trip.getDouble("distanceKm"),
                movingMillis = trip.getLong("movingMillis"),
                maxKmh = trip.getDouble("maxKmh").toFloat(),
                averageKmh = trip.getDouble("averageKmh").toFloat(),
            )
        }
    } catch (_: IOException) {
        emptyList() // No trips saved yet.
    } catch (_: JSONException) {
        emptyList()
    }

    private fun writeIndex() {
        val array = JSONArray()
        for (trip in trips) {
            array.put(
                JSONObject()
                    .put("startedAt", trip.startedAt)
                    .put("endedAt", trip.endedAt)
                    .put("distanceKm", trip.distanceKm)
                    .put("movingMillis", trip.movingMillis)
                    .put("maxKmh", trip.maxKmh.toDouble())
                    .put("averageKmh", trip.averageKmh.toDouble()),
            )
        }
        var stream: FileOutputStream? = null
        try {
            stream = index.startWrite()
            stream.write(array.toString().toByteArray())
            index.finishWrite(stream)
        } catch (_: IOException) {
            index.failWrite(stream)
        }
    }

    private companion object {
        const val RECORD_BYTES = 12L
        const val SPEED_ONLY_RECORD_BYTES = 8L
    }
}
