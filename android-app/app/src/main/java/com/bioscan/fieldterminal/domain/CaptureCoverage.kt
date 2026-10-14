package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// Analysis Layer 3 / R2 (2e). How often each capture stream was actually logged: the success measure for the capture layer,
// because the relationship and experiment engines can only work with the days a person recorded. Alcohol is informational:
// a day with no drink has no row, so days WITHOUT alcohol cannot be told apart from days that were not logged.
data class StreamCoverage(
    val stream: String,
    val label: String,
    val daysLogged: Int,
    val windowDays: Int,
    val informational: Boolean,
) {
    val fraction: Double get() = if (windowDays <= 0) 0.0 else daysLogged.toDouble() / windowDays
}

val CAPTURE_STREAMS = listOf(
    "wellbeing" to "Wellbeing scores",
    "hydration" to "Water",
    "supplements" to "Supplements taken",
    "recovery" to "Recovery sessions",
    "alcohol" to "Days with alcohol logged",
)

// `streams` maps a stream key to the dates it has an entry for. Only dates inside [today - windowDays + 1, today] count.
fun captureCoverage(streams: Map<String, Set<LocalDate>>, today: LocalDate, windowDays: Int = 28): List<StreamCoverage> {
    val start = today.minusDays((windowDays - 1).toLong())
    return CAPTURE_STREAMS.map { (key, label) ->
        val inWindow = (streams[key] ?: emptySet()).count { !it.isBefore(start) && !it.isAfter(today) }
        StreamCoverage(key, label, inWindow, windowDays, informational = key == "alcohol")
    }
}
