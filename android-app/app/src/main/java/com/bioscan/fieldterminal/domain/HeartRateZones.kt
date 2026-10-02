package com.bioscan.fieldterminal.domain

// Session detail's HR zone breakdown. Zones are the standard %-of-max-HR
// 5-zone convention every major fitness platform ships by default (same
// "cite the real convention" treatment TSB_GAUGE_BANDS gives TrainingPeaks'
// bands) -- not an invented scheme. personalMaxHr is this account's own
// highest-ever recorded max_hr across all sessions (a real measured value),
// never an age-formula guess -- see AnalysisRepository.loadPersonalMaxHr().
enum class HeartRateZone(val label: String, val lowerPct: Double) {
    BelowZ1("Below Z1", 0.0),
    Z1("Zone 1 — Very light", 50.0),
    Z2("Zone 2 — Light", 60.0),
    Z3("Zone 3 — Moderate", 70.0),
    Z4("Zone 4 — Hard", 80.0),
    Z5("Zone 5 — Maximum", 90.0),
}

fun heartRateZoneFor(hr: Double, personalMaxHr: Double): HeartRateZone {
    if (personalMaxHr <= 0.0) return HeartRateZone.BelowZ1
    val pct = hr / personalMaxHr * 100.0
    return HeartRateZone.entries.lastOrNull { pct >= it.lowerPct } ?: HeartRateZone.BelowZ1
}

data class HeartRateZoneBreakdown(val zone: HeartRateZone, val seconds: Long)

// Standard "time in zone" method: each inter-sample interval's duration is
// attributed to the zone of its starting sample, then summed per zone.
fun heartRateZoneBreakdown(points: List<TimePoint>, personalMaxHr: Double): List<HeartRateZoneBreakdown> {
    if (points.size < 2) return emptyList()
    val sorted = points.sortedBy { it.offsetSeconds }
    return sorted.zipWithNext { a, b -> heartRateZoneFor(a.value, personalMaxHr) to (b.offsetSeconds - a.offsetSeconds) }
        .groupBy({ it.first }, { it.second })
        .map { (zone, durations) -> HeartRateZoneBreakdown(zone, durations.sum()) }
        .sortedBy { it.zone.ordinal }
}
