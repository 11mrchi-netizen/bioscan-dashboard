package com.bioscan.fieldterminal.domain.comparison

import com.bioscan.fieldterminal.domain.analysis.ComparisonState
import com.bioscan.fieldterminal.domain.analysis.Directionality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PopulationComparisonTest {

    // Small synthetic artifact, not a real published table -- chosen for
    // exact-by-hand interpolation: p10=100, p50=200, p90=300.
    private fun artifact(supportsPercentile: Boolean = true, ageLow: Int? = 18, ageHigh: Int? = 39, sex: String? = "male") = BenchmarkArtifact(
        id = 1, metric = "test_metric", sourceName = "Synthetic Test Source", sourceTier = "A",
        population = BenchmarkPopulation("test_cohort", "US", ageLow, ageHigh, sex),
        sampleSize = 1000, quantiles = if (supportsPercentile) mapOf(10 to 100.0, 50 to 200.0, 90 to 300.0) else null,
        directionality = Directionality.HIGHER_BETTER, supportsPercentile = supportsPercentile, artifactVersion = 1,
    )

    @Test
    fun testInterpolationHandCalculated() {
        val sorted = listOf(10 to 100.0, 50 to 200.0, 90 to 300.0).map { java.util.AbstractMap.SimpleEntry(it.first, it.second) }
        assertEquals(50.0, interpolatePercentile(200.0, sorted), 0.001) // exactly at p50
        assertEquals(30.0, interpolatePercentile(150.0, sorted), 0.001) // halfway p10-p50
        assertEquals(70.0, interpolatePercentile(250.0, sorted), 0.001) // halfway p50-p90
        assertEquals(10.0, interpolatePercentile(50.0, sorted), 0.001) // below table -> clamped, never extrapolated
        assertEquals(90.0, interpolatePercentile(400.0, sorted), 0.001) // above table -> clamped
    }

    @Test
    fun testResolveArtifactPrefersMostSpecific() {
        val specific = artifact(ageLow = 18, ageHigh = 39, sex = "male")
        val generic = artifact(ageLow = null, ageHigh = null, sex = null).copy(id = 2)
        val resolved = resolveArtifact("test_metric", PopulationContext(25, "male", "US"), listOf(generic, specific))
        assertEquals(specific.id, resolved!!.id)
    }

    @Test
    fun testResolveArtifactReturnsNullForUnknownMetric() {
        assertNull(resolveArtifact("not_a_metric", PopulationContext(25, "male", "US"), listOf(artifact())))
    }

    @Test
    fun testComparePopulationHandCalculated() {
        val result = comparePopulation("test_metric", current = 150.0, context = PopulationContext(25, "male", "US"), artifacts = listOf(artifact()))
        assertEquals(ComparisonState.OK, result.state)
        assertEquals(30.0, result.percentile!!, 0.001)
        assertEquals(200.0, result.referenceValue!!, 0.001)
        assertEquals(-50.0, result.delta!!, 0.001)
        assertNull(result.standardizedDelta) // no mean/SD in a quantile-table artifact -- never fabricated
    }

    @Test
    fun testComparePopulationNoReferenceWhenNoArtifact() {
        val result = comparePopulation("test_metric", 150.0, PopulationContext(25, "male", "US"), emptyList())
        assertEquals(ComparisonState.NO_REFERENCE, result.state)
        assertNull(result.percentile)
    }

    @Test
    fun testComparePopulationLowConfidenceWhenPercentileUnsupportedAndNoBands() {
        val result = comparePopulation("test_metric", 150.0, PopulationContext(25, "male", "US"), listOf(artifact(supportsPercentile = false)))
        assertEquals(ComparisonState.LOW_CONFIDENCE, result.state)
        assertNull(result.percentile)
        assertNull(result.bandLabel)
    }

    // Real fixture: Tudor-Locke & Bassett's steps/day categories --
    // sedentary <5000, low_active 5000-7499, somewhat_active 7500-9999,
    // active 10000-12499, highly_active >=12500.
    private val stepsBands = listOf(
        ThresholdBand("sedentary", null, 4999.0),
        ThresholdBand("low_active", 5000.0, 7499.0),
        ThresholdBand("somewhat_active", 7500.0, 9999.0),
        ThresholdBand("active", 10000.0, 12499.0),
        ThresholdBand("highly_active", 12500.0, null),
    )

    @Test
    fun testMatchThresholdBand() {
        assertEquals("sedentary", matchThresholdBand(3000.0, stepsBands))
        assertEquals("low_active", matchThresholdBand(6000.0, stepsBands))
        assertEquals("somewhat_active", matchThresholdBand(8500.0, stepsBands))
        assertEquals("active", matchThresholdBand(11000.0, stepsBands))
        assertEquals("highly_active", matchThresholdBand(15000.0, stepsBands))
    }

    @Test
    fun testComparePopulationUsesThresholdBandWhenNoPercentileTable() {
        val stepsArtifact = artifact(supportsPercentile = false).copy(metric = "steps", thresholdBands = stepsBands)
        val result = comparePopulation("steps", current = 8500.0, context = PopulationContext(25, "male", "US"), artifacts = listOf(stepsArtifact))
        assertEquals(ComparisonState.OK, result.state)
        assertEquals("somewhat_active", result.bandLabel)
        assertNull(result.percentile) // never fabricated from a categorical source
    }
}
