package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// Analysis Layer 3 / R1 (docs/analysis-layer-3). A baseline restarts only after a CONFIRMED series break: a statistical
// break alone is a candidate, because a real physiological change looks identical to a device or classifier change.
// The analysis-run Edge Function publishes the breaks and marks one confirmed when the recorded origin of the field
// changed within 3 days of it, or when the user said "source changed" (series_break_decisions). This holds, per
// metric key, the start of the current regime -- the date to pass as `windowStart` to the SWC evaluators.
//
// Metric keys match the published results: "hrv", "rhr", "steps", "sleep_hours", "deep_min", "rem_min", "light_min".
data class SeriesBreakStarts(val byMetric: Map<String, LocalDate> = emptyMap()) {
    fun start(metric: String): LocalDate? = byMetric[metric]

    companion object {
        val NONE = SeriesBreakStarts()
    }
}

// `lastConfirmed` per metric key exactly as published in the series_break payload (ISO date or null).
fun seriesBreakStartsFrom(lastConfirmedByMetric: Map<String, String?>): SeriesBreakStarts =
    SeriesBreakStarts(lastConfirmedByMetric.mapNotNull { (metric, date) -> date?.let { metric to LocalDate.parse(it) } }.toMap())
