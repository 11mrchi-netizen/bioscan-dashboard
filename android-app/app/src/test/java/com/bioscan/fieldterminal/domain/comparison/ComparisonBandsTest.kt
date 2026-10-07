package com.bioscan.fieldterminal.domain.comparison

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.analysis.ComparisonResult
import com.bioscan.fieldterminal.domain.analysis.ComparisonState
import com.bioscan.fieldterminal.domain.analysis.ComparisonType
import com.bioscan.fieldterminal.domain.analysis.Directionality
import com.bioscan.fieldterminal.domain.analysis.InputCompleteness
import com.bioscan.fieldterminal.domain.analysis.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComparisonBandsTest {

    private fun result(state: ComparisonState, percentile: Double?, directionality: Directionality) = ComparisonResult(
        metric = "hrv", comparisonType = ComparisonType.PERSONAL_HISTORY, state = state,
        rawValue = 60.0, normalizedValue = 60.0, referenceValue = 49.0, delta = 11.0, standardizedDelta = 1.9,
        percentile = percentile, rank = 10, rankDenominator = 10, directionality = directionality,
        referenceIdentity = "personal_28d", referenceVersion = "1", confidence = Confidence(10, 14),
        breadth = InputCompleteness(emptySet(), emptySet()), provenance = Provenance("health_connect", "personal_comparison", "1"),
    )

    @Test
    fun testPercentileBandThresholds() {
        assertEquals(PercentileBand.TOP_DECILE, percentileBand(95.0))
        assertEquals(PercentileBand.ABOVE_AVERAGE, percentileBand(60.0))
        assertEquals(PercentileBand.AVERAGE, percentileBand(50.0))
        assertEquals(PercentileBand.BELOW_AVERAGE, percentileBand(15.0))
        assertEquals(PercentileBand.BOTTOM_DECILE, percentileBand(5.0))
    }

    @Test
    fun testOkStateGetsABand() {
        val presentation = presentComparison(result(ComparisonState.OK, 95.0, Directionality.HIGHER_BETTER))
        assertEquals("OK", presentation.stateLabel)
        assertEquals(PercentileBand.TOP_DECILE, presentation.band)
    }

    @Test
    fun testNonOkStatesNeverGetABand() {
        assertNull(presentComparison(result(ComparisonState.INSUFFICIENT_DATA, null, Directionality.HIGHER_BETTER)).band)
        assertNull(presentComparison(result(ComparisonState.LOW_CONFIDENCE, 95.0, Directionality.HIGHER_BETTER)).band)
        assertNull(presentComparison(result(ComparisonState.NO_REFERENCE, null, Directionality.HIGHER_BETTER)).band)
    }

    @Test
    fun testOptimalRangeAndTargetValueNeverGetAMonotonicBand() {
        assertNull(presentComparison(result(ComparisonState.OK, 95.0, Directionality.OPTIMAL_RANGE)).band)
        assertNull(presentComparison(result(ComparisonState.OK, 95.0, Directionality.TARGET_VALUE)).band)
    }

    @Test
    fun testEveryStateHasRealLabelText() {
        ComparisonState.entries.forEach { state ->
            val label = presentComparison(result(state, 50.0, Directionality.HIGHER_BETTER)).stateLabel
            assert(label.isNotBlank()) { "state $state has a blank label" }
        }
    }
}
