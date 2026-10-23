package com.klkl.tracknav.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

fun formatDistance(meters: Double): String =
    if (meters >= 1_000) {
        String.format(Locale.CHINA, "%.1f km", meters / 1_000)
    } else {
        "${meters.roundToInt()} m"
    }

fun formatElevation(meters: Double?): String =
    meters?.let { "${it.roundToInt()} m" } ?: "—"

fun formatAscent(meters: Double): String = "${meters.roundToInt()} m"

fun formatTimestamp(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateFormatter)

fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.CHINA, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.CHINA, minutes, seconds)
    }
}

fun formatEta(seconds: Long?): String {
    if (seconds == null) return "--"
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分"
        else -> "不到 1 分"
    }
}

fun formatSpeed(metersPerSecond: Double?): String =
    if (metersPerSecond == null) {
        "--"
    } else {
        String.format(Locale.CHINA, "%.1f km/h", metersPerSecond * 3.6)
    }
