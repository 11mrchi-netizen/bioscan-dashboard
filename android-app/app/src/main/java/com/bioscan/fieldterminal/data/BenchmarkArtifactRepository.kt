package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.BenchmarkArtifactRow
import com.bioscan.fieldterminal.domain.analysis.Directionality
import com.bioscan.fieldterminal.domain.comparison.BenchmarkArtifact
import com.bioscan.fieldterminal.domain.comparison.BenchmarkPopulation
import com.bioscan.fieldterminal.domain.comparison.ThresholdBand
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

// DAV-193/196. benchmark_artifacts is shared reference data (not per-user) --
// read-only from the app, seeded via migration/research (see
// docs/analysis-layer-2/25-population-benchmark-engine.md), same precedent
// as exercise_library's own 876 seeded rows.
class BenchmarkArtifactRepository(private val supabase: SupabaseClient) {

    suspend fun loadArtifacts(metric: String): List<BenchmarkArtifact> =
        supabase.postgrest.from("benchmark_artifacts")
            .select { filter { eq("metric", metric) } }
            .decodeList<BenchmarkArtifactRow>()
            // Superseded artifacts stay in the table for reproducibility of
            // past comparisons (DAV-193's own refresh principle) but never
            // resolve for a new comparison.
            .filter { it.supersededBy == null }
            .map { it.toDomain() }
}

private fun BenchmarkArtifactRow.toDomain(): BenchmarkArtifact = BenchmarkArtifact(
    id = id,
    metric = metric,
    sourceName = sourceName,
    sourceTier = sourceTier,
    population = BenchmarkPopulation(
        samplingFrame = population.samplingFrame,
        geography = population.geography,
        ageLow = population.ageLow,
        ageHigh = population.ageHigh,
        sex = population.sex,
    ),
    sampleSize = sampleSize,
    quantiles = quantiles?.mapKeys { (key, _) -> key.toInt() },
    thresholdBands = equation?.bands?.map { ThresholdBand(it.name, it.min, it.max) },
    directionality = Directionality.valueOf(directionality),
    supportsPercentile = supportsPercentile,
    artifactVersion = artifactVersion,
)
