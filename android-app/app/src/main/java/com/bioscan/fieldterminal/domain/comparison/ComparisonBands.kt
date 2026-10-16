package com.bioscan.fieldterminal.domain.comparison

import com.bioscan.fieldterminal.domain.analysis.ComparisonResult
import com.bioscan.fieldterminal.domain.analysis.ComparisonState
import com.bioscan.fieldterminal.domain.analysis.Directionality

// DAV-199 (docs/analysis-layer-2/23-percentile-bands-and-comparison-states.md).
// Turns a raw ComparisonResult into consistent, deterministic presentation
// metadata -- never a replacement for the underlying percentile/value
// (DAV-199's own acceptance criterion), and never a single collapsed score
// across dimensions.
enum class PercentileBand { TOP_DECILE, ABOVE_AVERAGE, AVERAGE, BELOW_AVERAGE, BOTTOM_DECILE }

fun percentileBand(percentile: Double): PercentileBand = when {
    percentile >= 90.0 -> PercentileBand.TOP_DECILE
    percentile >= 60.0 -> PercentileBand.ABOVE_AVERAGE
    percentile >= 40.0 -> PercentileBand.AVERAGE
    percentile >= 10.0 -> PercentileBand.BELOW_AVERAGE
    else -> PercentileBand.BOTTOM_DECILE
}

data class ComparisonPresentation(val stateLabel: String, val band: PercentileBand?)

// Every ComparisonState renders real text -- never a hidden row -- matching
// this session's own established convention (the TRAIL card's not-ready
// messaging, the STEPS card's honest omission of an eval function that
// doesn't exist). A band is only ever computed for a fully OK, monotonic
// comparison: a low-quality reference cannot produce a falsely precise
// ranking, per DAV-199's own acceptance criterion.
//
// OPTIMAL_RANGE/TARGET_VALUE metrics never get a percentile band -- there is
// no real per-metric optimum threshold stored anywhere in this app yet (e.g.
// sleep duration's true ~7-9h physiological optimum), so this returns null
// rather than fabricating one. A real gap, left for a future ticket once a
// per-metric optimal-range table exists, not guessed at here.
fun presentComparison(result: ComparisonResult): ComparisonPresentation {
    val stateLabel = when (result.state) {
        ComparisonState.OK -> "OK"
        ComparisonState.NO_REFERENCE -> "No reference available yet"
        ComparisonState.INSUFFICIENT_DATA -> "Not enough history yet"
        ComparisonState.LOW_CONFIDENCE -> "Limited history"
        ComparisonState.NON_COMPARABLE -> "No comparable observations"
        ComparisonState.STALE_REFERENCE -> "Reference is out of date"
    }
    // Analysis review A3: only a metric with a better end gets a band, and the band is taken from the FAVORABLE position.
    // It used the raw percentile, so the highest resting heart rate or the slowest pace read "Top 10%". A
    // NON_DIRECTIONAL metric (body weight) has no better end, so "Top 10%" means nothing there either.
    val band = if (result.state == ComparisonState.OK) favorablePercentile(result)?.let(::percentileBand) else null
    return ComparisonPresentation(stateLabel, band)
}

// The share of the reference the current value is at least as good as: the raw percentile for higher-better metrics,
// its mirror for lower-better ones, null when the metric has no better end.
fun favorablePercentile(result: ComparisonResult): Double? {
    val p = result.percentile ?: return null
    return when (result.directionality) {
        Directionality.HIGHER_BETTER -> p
        Directionality.LOWER_BETTER -> 100.0 - p
        Directionality.OPTIMAL_RANGE, Directionality.TARGET_VALUE, Directionality.NON_DIRECTIONAL -> null
    }
}

// 1st, 2nd, 3rd, 4th ... 11th, 12th, 13th ... 21st, 22nd, 23rd.
fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}
