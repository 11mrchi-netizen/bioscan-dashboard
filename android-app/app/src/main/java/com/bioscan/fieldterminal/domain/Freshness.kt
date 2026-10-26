package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Contract A5 (design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md, "Freshness"): the line under a hero that shows synced data.
// It says which day the reading is from and where it came from, plus the last sync time when the app knows it, and turns
// STALE when the reading is older than the data layer's threshold (StatusRepository.STALE_AFTER_DAYS). The threshold is
// passed in, never decided here.
data class Freshness(val text: String, val stale: Boolean)

fun freshnessLine(
    readingDate: LocalDate?,
    source: String,
    lastSync: LocalDateTime?,
    today: LocalDate,
    staleAfterDays: Long,
): Freshness {
    val synced = lastSync?.let { "SYNCED " + (if (it.toLocalDate() == today) it.format(TIME) else it.format(DAY_TIME).uppercase()) }
    if (readingDate == null) return Freshness(listOfNotNull("NO READING YET", source, synced).joinToString(" · "), stale = false)
    val days = ChronoUnit.DAYS.between(readingDate, today)
    val stale = days > staleAfterDays
    val day = when (days) {
        0L -> "TODAY'S READING"
        1L -> "YESTERDAY'S READING"
        else -> "READING FROM " + readingDate.format(DAY).uppercase()
    }
    return Freshness(listOfNotNull(if (stale) "STALE" else null, day, source, synced).joinToString(" · "), stale)
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DAY_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)
