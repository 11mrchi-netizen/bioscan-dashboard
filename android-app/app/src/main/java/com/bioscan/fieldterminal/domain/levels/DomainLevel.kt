package com.bioscan.fieldterminal.domain.levels

import com.bioscan.fieldterminal.domain.analysis.ComparisonResult
import com.bioscan.fieldterminal.domain.comparison.PercentileBand
import com.bioscan.fieldterminal.domain.comparison.presentComparison

// DAV-292: a "level" is a plain descriptive label over the *existing*
// comparison layer's PercentileBand -- same band-from-number shape as
// TrainingLoadEvaluation.kt's tsbBandLabel() / ItraClassification.kt's
// itraCategory(), not a new scoring mechanism. Never aggregated across
// domains into one figure (explicit milestone constraint) -- one DomainLevel
// per domain, built only from whichever ComparisonResults the caller actually
// has for that domain.
data class DomainLevel(
    val label: String,
    val band: PercentileBand?,
    // Which result this level was actually derived from, so it stays
    // explainable rather than a black box (DAV-292's "provenance-aware"
    // requirement) -- never dropped even when band is null.
    val evidence: ComparisonResult?,
)

private fun bandLabel(band: PercentileBand?): String = when (band) {
    PercentileBand.TOP_DECILE -> "Elite"
    PercentileBand.ABOVE_AVERAGE -> "Strong"
    PercentileBand.AVERAGE -> "Solid"
    PercentileBand.BELOW_AVERAGE -> "Developing"
    PercentileBand.BOTTOM_DECILE -> "Building"
    null -> "Not enough data yet"
}

// Multiple metrics can back one domain (e.g. running has VO2max, threshold
// pace, EF); the *most-confident* one represents the domain here -- not the
// most flattering one, which would make a level gameable by cherry-picking
// whichever metric currently looks best. Confidence is `have` (a real
// observation count, domain/EvalState.kt's own convention), so this always
// prefers the result backed by the most data.
private fun levelFrom(results: List<ComparisonResult>): DomainLevel {
    val representative = results.maxByOrNull { it.confidence.have } ?: return DomainLevel(bandLabel(null), null, null)
    val band = presentComparison(representative).band
    return DomainLevel(bandLabel(band), band, representative)
}

fun runningLevel(results: List<ComparisonResult>): DomainLevel = levelFrom(results)
fun strengthLevel(results: List<ComparisonResult>): DomainLevel = levelFrom(results)
fun conditioningLevel(results: List<ComparisonResult>): DomainLevel = levelFrom(results)
fun mountainLevel(results: List<ComparisonResult>): DomainLevel = levelFrom(results)
