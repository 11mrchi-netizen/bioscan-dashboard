package com.bioscan.fieldterminal.domain.comparison

import com.bioscan.fieldterminal.domain.analysis.Directionality
import org.junit.Assert.assertEquals
import org.junit.Test

class MetricDirectionalityTest {

    @Test
    fun testLowerBetterIsSignFlipped() {
        assertEquals(-52.0, normalize(52.0, Directionality.LOWER_BETTER), 0.001)
    }

    @Test
    fun testHigherBetterPassesThrough() {
        assertEquals(68.0, normalize(68.0, Directionality.HIGHER_BETTER), 0.001)
    }

    @Test
    fun testOptimalRangeAndNonDirectionalPassThrough() {
        assertEquals(7.5, normalize(7.5, Directionality.OPTIMAL_RANGE), 0.001)
        assertEquals(82.0, normalize(82.0, Directionality.NON_DIRECTIONAL), 0.001)
    }

    @Test
    fun testKnownMetricsAreAssigned() {
        assertEquals(Directionality.HIGHER_BETTER, METRIC_DIRECTIONALITY["hrv"])
        assertEquals(Directionality.LOWER_BETTER, METRIC_DIRECTIONALITY["resting_heart_rate"])
        assertEquals(Directionality.OPTIMAL_RANGE, METRIC_DIRECTIONALITY["sleep_duration"])
        assertEquals(Directionality.NON_DIRECTIONAL, METRIC_DIRECTIONALITY["body_weight"])
    }
}
