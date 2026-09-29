package com.bioscan.fieldterminal.domain.achievement

import com.bioscan.fieldterminal.domain.analysis.Directionality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementTest {

    @Test
    fun higherBetterNewRecordBeatsPriorMax() {
        assertTrue(isNewRecord(105.0, listOf(90.0, 100.0, 95.0), Directionality.HIGHER_BETTER))
        assertFalse(isNewRecord(100.0, listOf(90.0, 100.0, 95.0), Directionality.HIGHER_BETTER))
    }

    @Test
    fun lowerBetterNewRecordBeatsPriorMin() {
        // e.g. a fastest 1km time -- lower is better.
        assertTrue(isNewRecord(240.0, listOf(260.0, 250.0), Directionality.LOWER_BETTER))
        assertFalse(isNewRecord(255.0, listOf(260.0, 250.0), Directionality.LOWER_BETTER))
    }

    @Test
    fun firstObservationIsTriviallyARecord() {
        assertTrue(isNewRecord(50.0, emptyList(), Directionality.HIGHER_BETTER))
    }

    @Test
    fun nonMonotonicDirectionalityNeverProducesARecord() {
        assertFalse(isNewRecord(999.0, emptyList(), Directionality.OPTIMAL_RANGE))
        assertFalse(isNewRecord(999.0, emptyList(), Directionality.TARGET_VALUE))
        assertFalse(isNewRecord(999.0, emptyList(), Directionality.NON_DIRECTIONAL))
    }
}
