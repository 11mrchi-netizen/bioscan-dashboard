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
    val monotonic = result.directionality != Directionality.OPTIMAL_RANGE && result.directionality != Directionality.TARGET_VALUE
    val band = if (result.state == ComparisonState.OK && monotonic) result.percentile?.let(::percentileBand) else null
    return ComparisonPresentation(stateLabel, band)
}
