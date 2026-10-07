package com.bioscan.fieldterminal.domain.levels

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

class DomainLevelTest {

    private fun result(metric: String, percentile: Double, confidence: Confidence) = ComparisonResult(
        metric = metric, comparisonType = ComparisonType.POPULATION, state = ComparisonState.OK,
        rawValue = 50.0, normalizedValue = 50.0, referenceValue = 45.0, delta = 5.0, standardizedDelta = 1.0,
        percentile = percentile, rank = 9, rankDenominator = 10, directionality = Directionality.HIGHER_BETTER,
        referenceIdentity = "population_v1", referenceVersion = "1", confidence = confidence,
        breadth = InputCompleteness(emptySet(), emptySet()), provenance = Provenance("wearable", "population_comparison", "1"),
    )

    @Test
    fun picksTheMostConfidentResultNotTheBestLookingOne() {
        // vo2max looks better (95th percentile) but threshold pace has far
        // more history behind it -- the level should follow the data, not
        // whichever metric currently flatters the athlete most.
        val vo2max = result("vo2max", percentile = 95.0, confidence = Confidence(3, 14))
        val thresholdPace = result("threshold_pace", percentile = 65.0, confidence = Confidence(12, 14))

        val level = runningLevel(listOf(vo2max, thresholdPace))
        assertEquals("Strong", level.label) // ABOVE_AVERAGE band for the 65th percentile result
        assertEquals(thresholdPace, level.evidence)
    }

    @Test
    fun emptyResultsGiveNotEnoughDataLabel() {
        val level = strengthLevel(emptyList())
        assertEquals("Not enough data yet", level.label)
        assertNull(level.evidence)
    }
}
