package com.bioscan.fieldterminal.domain.comparison

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.analysis.ComparisonResult
import com.bioscan.fieldterminal.domain.analysis.ComparisonState
import com.bioscan.fieldterminal.domain.analysis.ComparisonType
import com.bioscan.fieldterminal.domain.analysis.Directionality
import com.bioscan.fieldterminal.domain.analysis.InputCompleteness
import com.bioscan.fieldterminal.domain.analysis.Provenance
import java.time.LocalDate

// DAV-197 (docs/analysis-layer-2/22-comparable-sets-and-personal-comparison.md).
// Wires DAV-194 (personalBaseline), DAV-198 (comparableSet, applied by the
// caller before history reaches this function) and DAV-195 (directionality/
// normalize) into one ComparisonResult. `history` is this account's own
// past observations for `metric`, already filtered to whatever's legitimately
// comparable to `current` -- this function has no opinion on eligibility,
// only on the arithmetic once a comparable set exists.
const val PERSONAL_COMPARISON_ALGORITHM_VERSION = "1"
const val PERSONAL_BASELINE_WINDOW_DAYS = 28

fun comparePersonal(
    metric: String,
    current: Double,
    history: List<Pair<LocalDate, Double>>,
    presentSources: Set<String>,
    idealSources: Set<String>,
    origin: String,
    asOf: LocalDate = LocalDate.now(),
): ComparisonResult {
    val directionality = METRIC_DIRECTIONALITY[metric]
        ?: return ComparisonResult(
            metric = metric, comparisonType = ComparisonType.PERSONAL_HISTORY, state = ComparisonState.NO_REFERENCE,
            rawValue = current, normalizedValue = null, referenceValue = null, delta = null, standardizedDelta = null,
            percentile = null, rank = null, rankDenominator = null, directionality = Directionality.NON_DIRECTIONAL,
            referenceIdentity = "personal_${PERSONAL_BASELINE_WINDOW_DAYS}d", referenceVersion = PERSONAL_COMPARISON_ALGORITHM_VERSION,
            confidence = Confidence(0, 1), breadth = InputCompleteness(presentSources, idealSources),
            provenance = Provenance(origin, "personal_comparison", PERSONAL_COMPARISON_ALGORITHM_VERSION),
        )

    val baseline = personalBaseline(history, windowDays = PERSONAL_BASELINE_WINDOW_DAYS, asOf = asOf)
        ?: return ComparisonResult(
            metric = metric, comparisonType = ComparisonType.PERSONAL_HISTORY, state = ComparisonState.INSUFFICIENT_DATA,
            rawValue = current, normalizedValue = normalize(current, directionality), referenceValue = null, delta = null,
            standardizedDelta = null, percentile = null, rank = null, rankDenominator = null, directionality = directionality,
            referenceIdentity = "personal_${PERSONAL_BASELINE_WINDOW_DAYS}d", referenceVersion = PERSONAL_COMPARISON_ALGORITHM_VERSION,
            confidence = Confidence(history.size, MIN_OBSERVATIONS_FOR_BASELINE), breadth = InputCompleteness(presentSources, idealSources),
            provenance = Provenance(origin, "personal_comparison", PERSONAL_COMPARISON_ALGORITHM_VERSION),
        )

    val sortedHistory = history.map { it.second }.sorted()
    // Percentile rank: fraction of this account's own comparable history at
    // or below the current value -- inclusive, so a current value equal to
    // every historical value reads as the 100th percentile, not <100th.
    val rank = sortedHistory.count { it <= current }
    val percentile = rank.toDouble() / sortedHistory.size * 100.0
    val delta = current - baseline.mean
    val standardizedDelta = if (baseline.stdDev > 0) delta / baseline.stdDev else null

    return ComparisonResult(
        metric = metric,
        comparisonType = ComparisonType.PERSONAL_HISTORY,
        state = if (baseline.isStable) ComparisonState.OK else ComparisonState.LOW_CONFIDENCE,
        rawValue = current,
        normalizedValue = normalize(current, directionality),
        referenceValue = baseline.mean,
        delta = delta,
        standardizedDelta = standardizedDelta,
        percentile = percentile,
        rank = rank,
        rankDenominator = sortedHistory.size,
        directionality = directionality,
        referenceIdentity = "personal_${PERSONAL_BASELINE_WINDOW_DAYS}d",
        referenceVersion = PERSONAL_COMPARISON_ALGORITHM_VERSION,
        // "n of N" confidence chip, this app's established idiom (domain/EvalState.kt) --
        // capped at the stability threshold so it never reads e.g. "40/14".
        confidence = Confidence(baseline.observationCount.coerceAtMost(MIN_OBSERVATIONS_FOR_STABLE_BASELINE), MIN_OBSERVATIONS_FOR_STABLE_BASELINE),
        breadth = InputCompleteness(presentSources, idealSources),
        provenance = Provenance(origin, "personal_comparison", PERSONAL_COMPARISON_ALGORITHM_VERSION),
    )
}

// Personal best/worst per DAV-197's own required output -- the extreme that
// counts as "best" depends on directionality (PersonalBaseline itself stays
// agnostic, see its own header comment), decided here instead.
fun personalBest(baseline: PersonalBaseline, directionality: Directionality): Double? = when (directionality) {
    Directionality.HIGHER_BETTER -> baseline.max
    Directionality.LOWER_BETTER -> baseline.min
    Directionality.OPTIMAL_RANGE, Directionality.TARGET_VALUE, Directionality.NON_DIRECTIONAL -> null
}
