package com.bioscan.fieldterminal.domain.comparison

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.analysis.ComparisonResult
import com.bioscan.fieldterminal.domain.analysis.ComparisonState
import com.bioscan.fieldterminal.domain.analysis.ComparisonType
import com.bioscan.fieldterminal.domain.analysis.Directionality
import com.bioscan.fieldterminal.domain.analysis.InputCompleteness
import com.bioscan.fieldterminal.domain.analysis.Provenance

// DAV-196 (docs/analysis-layer-2/25-population-benchmark-engine.md). Reads a
// versioned benchmark artifact (DAV-193 -- a published quantile table or
// equation, never a raw participant-level dataset) and positions a current
// observation against it. No raw external dataset is ever needed at
// runtime, per DAV-196's own "runtime principle" -- only the compact
// artifact this app already resolved/cached.
data class BenchmarkPopulation(val samplingFrame: String, val geography: String?, val ageLow: Int?, val ageHigh: Int?, val sex: String?)

// Not every real reference is percentile-shaped -- Tudor-Locke & Bassett's
// steps/day categories (sedentary/low_active/somewhat_active/active/
// highly_active) are real, well-cited, and DAV-193 itself frames steps as a
// case for "prevalence... rather than individual percentile ranking." min/
// max null means unbounded on that side (e.g. "highly_active" has no max).
data class ThresholdBand(val name: String, val min: Double?, val max: Double?)

fun matchThresholdBand(current: Double, bands: List<ThresholdBand>): String? =
    bands.firstOrNull { band -> (band.min == null || current >= band.min) && (band.max == null || current <= band.max) }?.name

data class BenchmarkArtifact(
    val id: Long,
    val metric: String,
    val sourceName: String,
    val sourceTier: String,
    val population: BenchmarkPopulation,
    val sampleSize: Int?,
    // percentile -> value, e.g. {10 -> 4200.0, 50 -> 7500.0, 90 -> 11800.0}.
    // Always ascending value with ascending percentile, by construction of
    // what a percentile table means -- directionality decides how a
    // position is later LABELED good/bad, not how the table itself reads.
    val quantiles: Map<Int, Double>?,
    val thresholdBands: List<ThresholdBand>? = null,
    val directionality: Directionality,
    val supportsPercentile: Boolean,
    val artifactVersion: Int,
)

data class PopulationContext(val ageYears: Int?, val sex: String?, val geography: String?)

// Most-specific-first per DAV-196's own resolution strategy: age-band+sex
// scores higher than sex alone, which scores higher than an unqualified
// metric-only artifact. Never guesses across metrics -- candidates are
// pre-filtered to the exact metric name.
private fun specificity(artifact: BenchmarkArtifact, context: PopulationContext): Int {
    var score = 0
    val pop = artifact.population
    if (context.ageYears != null && pop.ageLow != null && pop.ageHigh != null && context.ageYears in pop.ageLow..pop.ageHigh) score += 2
    if (context.sex != null && pop.sex != null && context.sex.equals(pop.sex, ignoreCase = true)) score += 1
    if (context.geography != null && pop.geography != null && context.geography.equals(pop.geography, ignoreCase = true)) score += 1
    return score
}

fun resolveArtifact(metric: String, context: PopulationContext, artifacts: List<BenchmarkArtifact>): BenchmarkArtifact? =
    artifacts.filter { it.metric == metric }.maxByOrNull { specificity(it, context) }

// Conservative linear interpolation between the artifact's own published
// points -- never extrapolated past the table's own min/max percentile, per
// DAV-196's own explicit rule. A value at or below the lowest published
// point reads as that lowest percentile, not below it; symmetric at the top.
fun interpolatePercentile(current: Double, sortedQuantiles: List<Map.Entry<Int, Double>>): Double {
    val first = sortedQuantiles.first()
    val last = sortedQuantiles.last()
    if (current <= first.value) return first.key.toDouble()
    if (current >= last.value) return last.key.toDouble()
    for (i in 0 until sortedQuantiles.size - 1) {
        val (p0, v0) = sortedQuantiles[i]
        val (p1, v1) = sortedQuantiles[i + 1]
        if (current in v0..v1) {
            val fraction = (current - v0) / (v1 - v0)
            return p0 + fraction * (p1 - p0)
        }
    }
    return last.key.toDouble()
}

const val POPULATION_COMPARISON_ALGORITHM_VERSION = "1"

fun comparePopulation(
    metric: String,
    current: Double,
    context: PopulationContext,
    artifacts: List<BenchmarkArtifact>,
    origin: String = "population_benchmark",
): ComparisonResult {
    val artifact = resolveArtifact(metric, context, artifacts)
        ?: return ComparisonResult(
            metric = metric, comparisonType = ComparisonType.POPULATION, state = ComparisonState.NO_REFERENCE,
            rawValue = current, normalizedValue = null, referenceValue = null, delta = null, standardizedDelta = null,
            percentile = null, rank = null, rankDenominator = null,
            directionality = METRIC_DIRECTIONALITY[metric] ?: Directionality.NON_DIRECTIONAL,
            referenceIdentity = "population_none", referenceVersion = POPULATION_COMPARISON_ALGORITHM_VERSION,
            confidence = Confidence(0, 1), breadth = InputCompleteness(emptySet(), setOf("benchmark_artifacts")),
            provenance = Provenance(origin, "population_comparison", POPULATION_COMPARISON_ALGORITHM_VERSION),
        )

    val quantiles = artifact.quantiles
    // "A mean and SD alone do not automatically justify a percentile unless
    // the source/model explicitly supports that inference" (DAV-196) -- an
    // artifact with no percentile table falls back to a named threshold
    // band when it has one (a real, full-confidence categorical reference,
    // e.g. Tudor-Locke steps/day categories -- never a fabricated
    // percentile), and only demotes to LOW_CONFIDENCE when neither exists.
    if (!artifact.supportsPercentile || quantiles.isNullOrEmpty()) {
        val bandLabel = artifact.thresholdBands?.let { matchThresholdBand(current, it) }
        return ComparisonResult(
            metric = metric, comparisonType = ComparisonType.POPULATION,
            state = if (bandLabel != null) ComparisonState.OK else ComparisonState.LOW_CONFIDENCE,
            rawValue = current, normalizedValue = normalize(current, artifact.directionality), referenceValue = null,
            delta = null, standardizedDelta = null, percentile = null, rank = null, rankDenominator = artifact.sampleSize,
            directionality = artifact.directionality, referenceIdentity = artifact.sourceName,
            referenceVersion = artifact.artifactVersion.toString(),
            confidence = if (bandLabel != null) Confidence(2, 2) else Confidence(1, 2),
            breadth = InputCompleteness(setOf(artifact.sourceName), setOf(artifact.sourceName)),
            provenance = Provenance(origin, "population_comparison", artifact.artifactVersion.toString()),
            bandLabel = bandLabel,
        )
    }

    val sorted = quantiles.entries.sortedBy { it.key }
    val percentile = interpolatePercentile(current, sorted)
    val median = quantiles[50]

    return ComparisonResult(
        metric = metric,
        comparisonType = ComparisonType.POPULATION,
        state = ComparisonState.OK,
        rawValue = current,
        normalizedValue = normalize(current, artifact.directionality),
        referenceValue = median,
        delta = median?.let { current - it },
        standardizedDelta = null, // no mean/SD in a quantile-table artifact -- never fabricated
        percentile = percentile,
        rank = null,
        rankDenominator = artifact.sampleSize,
        directionality = artifact.directionality,
        referenceIdentity = artifact.sourceName,
        referenceVersion = artifact.artifactVersion.toString(),
        confidence = Confidence(2, 2),
        breadth = InputCompleteness(setOf(artifact.sourceName), setOf(artifact.sourceName)),
        provenance = Provenance(origin, "population_comparison", artifact.artifactVersion.toString()),
    )
}
