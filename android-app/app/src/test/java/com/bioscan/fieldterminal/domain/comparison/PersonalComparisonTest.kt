package com.bioscan.fieldterminal.domain.comparison

import com.bioscan.fieldterminal.domain.analysis.ComparisonState
import com.bioscan.fieldterminal.domain.analysis.Directionality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PersonalComparisonTest {

    // Same fixture as PersonalBaselineTest: mean=49, stdDev=sqrt(33).
    private val tenPointFixture = (0..9).map { i -> LocalDate.of(2026, 9, 14 + i) to (40.0 + i * 2.0) }
    private val asOf = LocalDate.of(2026, 9, 23)

    @Test
    fun testHandCalculatedComparison() {
        // current=60 is above every one of the 10 historical values (max 58)
        // -> rank 10/10 -> 100th percentile. delta = 60-49 = 11.
        val result = comparePersonal(
            metric = "hrv", current = 60.0, history = tenPointFixture,
            presentSources = setOf("wearable_daily"), idealSources = setOf("wearable_daily"),
            origin = "health_connect", asOf = asOf,
        )
        assertEquals(ComparisonState.LOW_CONFIDENCE, result.state) // only 10 of 14 needed for stability
        assertEquals(100.0, result.percentile!!, 0.001)
        assertEquals(10, result.rank)
        assertEquals(10, result.rankDenominator)
        assertEquals(11.0, result.delta!!, 0.001)
        assertEquals(11.0 / kotlin.math.sqrt(33.0), result.standardizedDelta!!, 0.0001)
        assertEquals(60.0, result.normalizedValue!!, 0.001) // HIGHER_BETTER passes through unchanged
        assertEquals(10, result.confidence.have)
        assertEquals(14, result.confidence.need)
    }

    @Test
    fun testUnknownMetricIsNoReference() {
        val result = comparePersonal(
            metric = "not_a_real_metric", current = 5.0, history = tenPointFixture,
            presentSources = emptySet(), idealSources = emptySet(), origin = "manual", asOf = asOf,
        )
        assertEquals(ComparisonState.NO_REFERENCE, result.state)
        assertNull(result.percentile)
    }

    @Test
    fun testSparseHistoryIsInsufficientData() {
        val result = comparePersonal(
            metric = "hrv", current = 60.0, history = tenPointFixture.take(3),
            presentSources = emptySet(), idealSources = emptySet(), origin = "health_connect", asOf = asOf,
        )
        assertEquals(ComparisonState.INSUFFICIENT_DATA, result.state)
        assertEquals(3, result.confidence.have)
        assertEquals(MIN_OBSERVATIONS_FOR_BASELINE, result.confidence.need)
    }

    @Test
    fun testPersonalBestRespectsDirectionality() {
        val baseline = personalBaseline(tenPointFixture, windowDays = 28, asOf = asOf)!!
        assertEquals(58.0, personalBest(baseline, Directionality.HIGHER_BETTER)!!, 0.001)
        assertEquals(40.0, personalBest(baseline, Directionality.LOWER_BETTER)!!, 0.001)
        assertNull(personalBest(baseline, Directionality.OPTIMAL_RANGE))
    }

    @Test
    fun testComparableSessionDistance() {
        assertTrue(isComparableSessionDistance(10.0, 15.0))  // ratio 1.5
        assertFalse(isComparableSessionDistance(10.0, 25.0)) // ratio 2.5
        assertFalse(isComparableSessionDistance(0.0, 10.0))
    }

    @Test
    fun testComparableSetReportsCounts() {
        val history = listOf(5.0, 10.0, 15.0, 40.0)
        val result = comparableSet(history) { isComparableSessionDistance(10.0, it) }
        assertEquals(4, result.totalHistoryCount)
        assertEquals(listOf(5.0, 10.0, 15.0), result.comparable)
    }
}
