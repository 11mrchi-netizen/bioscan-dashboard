package com.bioscan.fieldterminal.domain.comparison

// DAV-198 (docs/analysis-layer-2/22-comparable-sets-and-personal-comparison.md).
// A comparable-set filter is a caller-supplied predicate over "is this
// historical observation legitimately comparable to the current one" --
// what "context" means is metric-specific (a daily wearable reading has
// none; an exercise session has type/route_type/distance), so this stays a
// generic higher-order filter rather than one large eligibility taxonomy
// built for dimensions (environmental conditions, protocol/device, planned-
// vs-observed) nothing in this app yet needs -- those are real per-ticket
// acceptance criteria, but adding them speculatively before a metric
// actually needs them would be exactly the kind of gap-filling this
// project's own conventions avoid. Reports comparableCount vs
// totalHistoryCount per DAV-198's own acceptance criterion, pairing
// directly with PersonalBaseline.observationCount.
data class ComparableSetResult<T>(val comparable: List<T>, val totalHistoryCount: Int)

fun <T> comparableSet(history: List<T>, isComparable: (T) -> Boolean): ComparableSetResult<T> =
    ComparableSetResult(comparable = history.filter(isComparable), totalHistoryCount = history.size)

// Reuses domain/trail/Durability.kt's own established comparability rule
// (COMPARABLE_LENGTH_RATIO_MAX = 2.0: comparable if not wildly different in
// scale) rather than inventing a second rule for session-shaped metrics
// (pace, per-session load) where distance/duration band is the dimension
// that matters most -- a 2km jog and a marathon aren't comparable paces
// regardless of how similar the runner's fitness is.
const val COMPARABLE_DISTANCE_RATIO_MAX = 2.0

fun isComparableSessionDistance(currentDistanceKm: Double, historicalDistanceKm: Double): Boolean {
    if (currentDistanceKm <= 0 || historicalDistanceKm <= 0) return false
    val ratio = maxOf(currentDistanceKm, historicalDistanceKm) / minOf(currentDistanceKm, historicalDistanceKm)
    return ratio <= COMPARABLE_DISTANCE_RATIO_MAX
}
