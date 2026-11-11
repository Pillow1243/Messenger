package com.offlinemesh.messenger.presentation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/** "14:05" for today, "3 Nov" for older messages. */
fun formatTimestamp(epochMillis: Long, locale: Locale = Locale.getDefault()): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = LocalDate.now(zone)
    return if (dateTime.toLocalDate() == today) {
        dateTime.format(timeFormatter.withLocale(locale))
    } else {
        dateTime.format(dateFormatter.withLocale(locale))
    }
}

fun formatDateTime(epochMillis: Long, locale: Locale = Locale.getDefault()): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(formatter)
}

fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Groups a hex/base64-ish string into readable blocks of four characters. */
fun chunkKey(key: String, groupSize: Int = 4, maxGroups: Int = 8): String =
    key.chunked(groupSize).take(maxGroups).joinToString(" ")
