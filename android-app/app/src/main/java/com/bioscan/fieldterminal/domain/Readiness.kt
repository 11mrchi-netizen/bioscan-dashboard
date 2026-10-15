package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// Analysis review phase G: the HRV "condition" band that used to live here (a z-score of today's HRV against up to the 7
// days before, LOW / REDUCED / NORMAL / PRIMED) is retired; the expected band (ExpectedBand.kt, the analysis-run
// `expected_band` result) answers the same question against weekday, trend and a calibrated band.

// Ported 1:1 from index.html's isStatusCurrentlyRelevant()/isHealthEventActive()
// -- same 7-day-after-resolution display cutoff, same "active/monitoring
// always counts, resolved only within cutoffDays" rule. A display filter
// only; nothing about the underlying data changes.
fun isStatusCurrentlyRelevant(
    status: String,
    endDate: LocalDate?,
    activeStatuses: Set<String>,
    endedStatuses: Set<String>,
    today: LocalDate,
    cutoffDays: Long = 7,
): Boolean {
    if (status in activeStatuses) return true
    if (status in endedStatuses && endDate != null) {
        val daysSince = java.time.temporal.ChronoUnit.DAYS.between(endDate, today)
        return daysSince <= cutoffDays
    }
    return false
}

fun isHealthEventActive(status: String, endDate: LocalDate?, today: LocalDate): Boolean =
    isStatusCurrentlyRelevant(status, endDate, setOf("active", "monitoring"), setOf("resolved"), today)
