package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.TimePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurabilityTest {

    private val early = TerrainSegment(
        direction = SegmentDirection.UPHILL, startOffsetSeconds = 0, endOffsetSeconds = 300,
        startDistanceM = 0.0, endDistanceM = 500.0, elevationChangeM = 50.0,
        movingDurationSeconds = 300, averageGradePercent = 10.0, gradeBand = GradeBand.MODERATE_UP,
    )
    private val late = TerrainSegment(
        direction = SegmentDirection.UPHILL, startOffsetSeconds = 1000, endOffsetSeconds = 1300,
        startDistanceM = 2000.0, endDistanceM = 2500.0, elevationChangeM = 40.0,
        movingDurationSeconds = 300, averageGradePercent = 8.0, gradeBand = GradeBand.MODERATE_UP,
    )

    @Test
    fun testFindComparableSegmentPair_withinToleranceMatches() {
        val pair = findComparableSegmentPair(listOf(early, late), SegmentDirection.UPHILL)
        assertEquals(early, pair?.first)
        assertEquals(late, pair?.second)
    }

    @Test
    fun testFindComparableSegmentPair_gradeTooDifferentReturnsNull() {
        val steep = late.copy(averageGradePercent = 25.0) // 15pp from early's 10% -- exceeds the 3pp tolerance
        assertNull(findComparableSegmentPair(listOf(early, steep), SegmentDirection.UPHILL))
    }

    @Test
    fun testFindComparableSegmentPair_lengthTooDifferentReturnsNull() {
        val long = late.copy(startDistanceM = 2000.0, endDistanceM = 3200.0) // 1200m vs early's 500m = 2.4x
        assertNull(findComparableSegmentPair(listOf(early, long), SegmentDirection.UPHILL))
    }

    // Hand-verified: early VAM 50/(300/3600)=600, late VAM 40/(300/3600)=480.
    // (480-600)/600*100 = -20%.
    @Test
    fun testComputeVamDegradationPercent() {
        assertEquals(-20.0, computeVamDegradationPercent(listOf(early, late))!!, 0.001)
    }

    @Test
    fun testComputeVamDegradationPercent_noComparablePairIsNull() {
        val steep = late.copy(averageGradePercent = 25.0)
        assertNull(computeVamDegradationPercent(listOf(early, steep)))
    }

    // Hand-verified via the Minetti polynomial: 8km/h (7.5 min/km) at 10%
    // grade -> GAP ~4.524; 6km/h (10 min/km) at 8% grade -> GAP ~6.626.
    // (6.626-4.524)/4.524*100 ~= 46.45% (slower late climb).
    @Test
    fun testComputeUphillPaceDegradationPercent() {
        val heartRate = listOf(TimePoint(0, 150.0), TimePoint(1300, 150.0))
        val speed = listOf(TimePoint(0, 8.0), TimePoint(300, 8.0), TimePoint(1000, 6.0), TimePoint(1300, 6.0))

        val degradation = computeUphillPaceDegradationPercent(listOf(early, late), heartRate, emptyList(), speed)

        assertEquals(46.45, degradation!!, 0.5)
    }

    // Hand-verified: first half (0-450s) HR=140, speed=10 -> ratio 0.071429;
    // second half (450-900s) HR=160, speed=8 -> ratio 0.05.
    // (0.071429-0.05)/0.071429*100 = 30.0%.
    @Test
    fun testComputeHrDecouplingPercent() {
        val heartRate = listOf(TimePoint(0, 140.0), TimePoint(300, 140.0), TimePoint(600, 160.0), TimePoint(900, 160.0))
        val speed = listOf(TimePoint(0, 10.0), TimePoint(300, 10.0), TimePoint(600, 8.0), TimePoint(900, 8.0))

        assertEquals(30.0, computeHrDecouplingPercent(heartRate, speed)!!, 0.01)
    }

    @Test
    fun testComputeHrDecouplingPercent_emptyDataIsNull() {
        assertNull(computeHrDecouplingPercent(emptyList(), emptyList()))
    }
}
