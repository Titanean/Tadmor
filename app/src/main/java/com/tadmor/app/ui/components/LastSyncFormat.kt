package com.tadmor.app.ui.components

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Formats a "Last sync" timestamp for the pull-to-refresh label.
 *
 * Three tiers based on how stale the sync is:
 *
 * - Less than 24 hours ago → "Last sync: HH:mm, d MMM"   (e.g. "Last sync: 23:05, 14 May")
 * - Same year, ≥ 24 hours  → "Last sync: d MMM"          (e.g. "Last sync: 5 May")
 * - Different year         → "Last sync: d MMM yyyy"     (e.g. "Last sync: 5 May 2025")
 *
 * The < 24-hour band uses elapsed *duration*, not calendar day, so a sync
 * at 23:05 viewed at 06:11 the next morning still reads as the recent
 * "HH:mm, d MMM" form even though the calendar date has rolled over.
 *
 * Returns null when [epochMillis] is 0 — used by the label site to mean
 * "no local cache yet, suppress the label entirely".
 */
fun formatLastSync(epochMillis: Long, now: Instant = Instant.now()): String? {
    if (epochMillis <= 0L) return null
    val zone = ZoneId.systemDefault()
    val sync = Instant.ofEpochMilli(epochMillis)
    val syncZdt = sync.atZone(zone)
    val today = LocalDate.now(zone)
    val syncDate = syncZdt.toLocalDate()
    val isRecent = Duration.between(sync, now).toHours() < 24
    return when {
        isRecent ->
            "Last sync: " + syncZdt.format(DateTimeFormatter.ofPattern("HH:mm, d MMM"))
        syncDate.year == today.year ->
            "Last sync: " + syncZdt.format(DateTimeFormatter.ofPattern("d MMM"))
        else ->
            "Last sync: " + syncZdt.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}
