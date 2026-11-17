package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SeriesCleaningTest {
    @Test
    fun aSpikeIsRemovedByTheRollingMedian() {
        val pts = (0L until 60L).map { TimePoint(it, if (it == 30L) 190.0 else 150.0) }
        assertEquals(150.0, rollingMedian(pts, 20).first { it.offsetSeconds == 30L }.value, 1e-9)
        assertEquals(pts, rollingMedian(pts, 0))
    }

    @Test
    fun paceKeepsOnlyMovingPace() {
        val pace = paceSeries(listOf(TimePoint(0, 12.0), TimePoint(1, 0.5), TimePoint(2, 0.0), TimePoint(3, 35.0)))
        assertEquals(listOf(0L), pace.map { it.offsetSeconds })
        assertEquals(5.0, pace.single().value, 1e-9)
    }

    @Test
    fun oneValueADay() {
        val d = LocalDate.of(2026, 11, 1)
        val pts = listOf(d to 140.0, d to 150.0, d.plusDays(1) to 145.0)
        assertEquals(listOf(d to 145.0, d.plusDays(1) to 145.0), oneADay(pts))
        assertEquals(150.0, oneADay(pts, DayReduce.MAX).first().second, 1e-9)
        assertEquals(150.0, oneADay(pts, DayReduce.LAST).first().second, 1e-9)
    }
}

class SeriesMeanTest {
    @org.junit.Test
    fun rollingMeanAveragesTheWindow() {
        val pts = (0L until 5L).map { TimePoint(it, it.toDouble()) }
        org.junit.Assert.assertEquals(2.0, rollingMean(pts, 2)[2].value, 1e-9) // (1 + 2 + 3) / 3
        org.junit.Assert.assertEquals(0.5, rollingMean(pts, 2)[0].value, 1e-9)
    }
}
