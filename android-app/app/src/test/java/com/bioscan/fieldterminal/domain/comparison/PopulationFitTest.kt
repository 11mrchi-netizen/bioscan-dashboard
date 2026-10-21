package com.bioscan.fieldterminal.domain.comparison

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.analysis.ComparisonState
import com.bioscan.fieldterminal.domain.analysis.Directionality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Analysis review A7: a table for a stratum the person is known not to belong to is never used, and the confidence of a
// population comparison says how well the table fits instead of a constant 2 of 2.
class PopulationFitTest {
    private fun artifact(id: Long, ageLow: Int?, ageHigh: Int?, sex: String?) = BenchmarkArtifact(
        id = id, metric = "vo2max", sourceName = "Synthetic $id", sourceTier = "A",
        population = BenchmarkPopulation("cohort", null, ageLow, ageHigh, sex),
        sampleSize = 500, quantiles = mapOf(10 to 30.0, 50 to 40.0, 90 to 50.0),
        directionality = Directionality.HIGHER_BETTER, supportsPercentile = true, artifactVersion = 1,
    )

    @Test
    fun aTableForAnotherAgeBandIsNotUsed() {
        val older = artifact(1, 60, 69, "male")
        assertNull(resolveArtifact("vo2max", PopulationContext(30, "male", null), listOf(older)))
        val result = comparePopulation("vo2max", 45.0, PopulationContext(30, "male", null), listOf(older))
        assertEquals(ComparisonState.NO_REFERENCE, result.state)
    }

    @Test
    fun fitCountsConfirmedStrata() {
        val matched = artifact(2, 25, 34, "male")
        val generic = artifact(3, null, null, null)
        val ctx = PopulationContext(30, "male", null)
        assertEquals(Confidence(2, 2), populationFit(matched, ctx))
        assertEquals(Confidence(0, 2), populationFit(generic, ctx))
        assertEquals(Confidence(2, 2), comparePopulation("vo2max", 45.0, ctx, listOf(generic, matched)).confidence)
        // unknown age: the age-banded table may be used, but only the sex match counts
        assertEquals(Confidence(1, 2), populationFit(matched, PopulationContext(null, "male", null)))
    }
}
