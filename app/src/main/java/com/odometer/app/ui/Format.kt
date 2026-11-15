package com.odometer.app.ui

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal fun formatKm(km: Double): String = String.format(Locale.US, "%.1f", km)

/** Hours and minutes as on a trip computer: `1:05`. */
internal fun formatClockDuration(millis: Long): String {
    val minutes = millis / 60_000L
    return String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)
}

/** A duration in words: `1 h 05 min`, `32 min`. */
internal fun formatDuration(millis: Long): String {
    val minutes = (millis + 30_000L) / 60_000L
    return if (minutes >= 60) {
        String.format(Locale.US, "%d h %02d min", minutes / 60, minutes % 60)
    } else {
        "$minutes min"
    }
}

/** The time of day, in the 12- or 24-hour form the phone is set to. */
internal fun Context.clockText(millis: Long = System.currentTimeMillis()): String =
    DateFormat.getTimeFormat(this).format(Date(millis))

/** `Fri, 13 Nov` */
internal fun shortDateText(millis: Long): String =
    SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(millis))

/** `Friday, 13 November` */
internal fun longDateText(millis: Long): String =
    SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(millis))

internal fun greeting(millis: Long): String {
    val hour = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Hello, night rider"
    }
}
