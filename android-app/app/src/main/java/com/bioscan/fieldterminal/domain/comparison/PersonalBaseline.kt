package com.bioscan.fieldterminal.domain.comparison

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

// DAV-194 (docs/analysis-layer-2/21-personal-baseline-engine.md). Generalizes
// a pattern already hand-rolled per file across this codebase --
// HrvRhrEvaluation.kt's 60-day mean/SWC window, TrainingScreen.kt's 4-week
// average -- into one shared implementation, operating on the exact
// (LocalDate, Double) shape every existing evaluate*() function already
// takes. Existing evaluations are not required to migrate; this is additive.
//
// max/min are plain descriptive extremes over the window, deliberately NOT
// named "best"/"worst" -- whether a maximum or minimum is the "good" end
// depends on the metric's directionality (domain/comparison/
// MetricDirectionality.kt), which this pure engine has no opinion on. The
// comparison layer (DAV-197) decides which extreme is "personal best."
data class PersonalBaseline(
    val windowDays: Int,
    val mean: Double,
    val median: Double,
    val stdDev: Double,
    val quantiles: Map<Int, Double>,
    val max: Double,
    val min: Double,
    val observationCount: Int,
    // Per DAV-194's own "sparse or unstable history lowers confidence
    // instead of manufacturing a baseline" -- a real, named threshold rather
    // than silently trusting any 5-point window.
    val isStable: Boolean,
)

const val MIN_OBSERVATIONS_FOR_BASELINE = 5
const val MIN_OBSERVATIONS_FOR_STABLE_BASELINE = 14

// Null, not a fabricated baseline, below MIN_OBSERVATIONS_FOR_BASELINE --
// matches this app's own "null means NoData, never a disguised zero"
// convention (domain/analysis/AnalysisLayer2Contract.kt's own DimensionState
// doc comment).
fun personalBaseline(points: List<Pair<LocalDate, Double>>, windowDays: Int, asOf: LocalDate = LocalDate.now()): PersonalBaseline? {
    val windowed = points.filter { ChronoUnit.DAYS.between(it.first, asOf) in 0..windowDays.toLong() }
    if (windowed.size < MIN_OBSERVATIONS_FOR_BASELINE) return null

    val values = windowed.map { it.second }.sorted()
    val mean = values.average()
    val variance = values.sumOf { (it - mean) * (it - mean) } / values.size

    return PersonalBaseline(
        windowDays = windowDays,
        mean = mean,
        median = percentileOf(values, 50),
        stdDev = sqrt(variance),
        quantiles = listOf(25, 50, 75, 90).associateWith { percentileOf(values, it) },
        max = values.last(),
        min = values.first(),
        observationCount = values.size,
        isStable = values.size >= MIN_OBSERVATIONS_FOR_STABLE_BASELINE,
    )
}

// Nearest-rank percentile over an already-sorted list -- the simplest
// defensible method for this sample size, matching this codebase's existing
// preference for the simplest correct algorithm over a more elaborate one
// (e.g. Stats.kt's own Mann-Kendall choice) -- no interpolation needed at
// the observation counts real personal history reaches.
fun percentileOf(sorted: List<Double>, percentile: Int): Double {
    val index = ((percentile / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
    return sorted[index]
}
