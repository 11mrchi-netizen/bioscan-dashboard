package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import kotlin.math.sqrt

// Ported 1:1 from index.html's computeHRVReadinessSeries()/readinessBand()
// (see ROADMAP.md P8 Step 5) -- same trailing-baseline z-score, same bands.
// Deliberately NOT a composite/multi-stream score: this is exactly the
// single HRV-vs-its-own-baseline signal the web dashboard computes, nothing
// blended in, per mobile-app-implementation-roadmap.md's explicit non-goal
// ("No composite/multi-stream health index").
//
// The 3d mockup's "82 READY" numeric score has no equivalent in the web
// dashboard at all -- it never computes a 0-100 readiness number, only this
// categorical band. Rather than invent a number with no defined formula,
// the mobile app shows the real band (e.g. "PRIMED") instead of a fabricated
// score. Flagged in ROADMAP.md rather than silently matching the mockup's
// cosmetic placeholder.

enum class ReadinessLabel(val display: String) {
    Low("LOW"),
    Reduced("REDUCED"),
    Normal("NORMAL"),
    Primed("PRIMED"),
    Unknown("—"),
}

data class ReadinessBand(val label: ReadinessLabel, val note: String)

/**
 * Each day's HRV expressed as a z-score against a trailing window-day
 * baseline mean/SD (excluding that day itself). Needs >=3 prior valid days
 * to compute a baseline; entries before that are null. Index-aligned with
 * [hrvValues] (nulls in, nulls or z-scores out) -- same shape as the web
 * version's array-in/array-out contract.
 */
fun computeHrvReadinessSeries(hrvValues: List<Double?>, window: Int = 7): List<Double?> {
    return hrvValues.mapIndexed { i, v ->
        val start = maxOf(0, i - window)
        val priorVals = hrvValues.subList(start, i).filterNotNull()
        if (priorVals.size < 3 || v == null) {
            null
        } else {
            val mean = priorVals.sum() / priorVals.size
            val variance = priorVals.sumOf { (it - mean) * (it - mean) } / priorVals.size
            val sd = sqrt(variance)
            if (sd > 0) (v - mean) / sd else 0.0
        }
    }
}

fun readinessBand(z: Double?): ReadinessBand = when {
    z == null -> ReadinessBand(ReadinessLabel.Unknown, "Not enough prior data yet.")
    z <= -2 -> ReadinessBand(ReadinessLabel.Low, "HRV well below your recent baseline — a strong signal to ease off today.")
    z <= -1 -> ReadinessBand(ReadinessLabel.Reduced, "HRV below baseline — consider an easier session or extra recovery.")
    z < 1 -> ReadinessBand(ReadinessLabel.Normal, "HRV in line with your recent baseline.")
    else -> ReadinessBand(ReadinessLabel.Primed, "HRV above baseline — a good day to push if the plan calls for it.")
}

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
