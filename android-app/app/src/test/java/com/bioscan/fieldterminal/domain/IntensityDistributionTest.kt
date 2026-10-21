package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class IntensityDistributionTest {

    private val today = LocalDate.of(2026, 10, 15) // a Thursday
    private val anchors = ZoneAnchors(maxHr = 196.0, restingHr = 53.0, lthr = 169.0, lthrDate = today.minusDays(5), asOf = today)

    @Test
    fun secondsFallInLowModerateAndHighByTheChosenZones() {
        // LTHR zones for a run: Z3 from 152.1, Z4 from 160.55.
        val run = IntensitySession(today, "run", mapOf(140 to 600.0, 151 to 300.0, 155 to 120.0, 170 to 60.0))
        val week = weeklyIntensity(listOf(run), { anchors }, ZoneMethod.AUTO, today).last()
        assertEquals(LocalDate.of(2026, 10, 12), week.weekStart)
        assertEquals(900.0, week.seconds.getValue(IntensityBand.LOW), 0.0)
        assertEquals(120.0, week.seconds.getValue(IntensityBand.MODERATE), 0.0)
        assertEquals(60.0, week.seconds.getValue(IntensityBand.HIGH), 0.0)
        assertEquals(900.0 / 1080.0, week.share(IntensityBand.LOW)!!, 1e-9)
        // By % of max, 140 and 151 are Z3 (from 137.2) and 155 too; 170 is Z4 (from 156.8).
        val byMax = weeklyIntensity(listOf(run), { anchors }, ZoneMethod.MAX, today).last()
        assertEquals(0.0, byMax.seconds.getValue(IntensityBand.LOW), 0.0)
        assertEquals(1020.0, byMax.seconds.getValue(IntensityBand.MODERATE), 0.0)
    }

    @Test
    fun twelveWeeksOldestFirstAndCoverageCountsSessionsWithoutHeartRate() {
        val sessions = listOf(
            IntensitySession(today.minusWeeks(2), "ride", mapOf(130 to 1200.0)),
            IntensitySession(today, "run", null), // no Zepp record
            IntensitySession(today, "run", mapOf(145 to 60.0)),
            IntensitySession(today, "strength", mapOf(150 to 900.0)), // not endurance: left out entirely
            IntensitySession(today.minusWeeks(12), "run", mapOf(145 to 60.0)), // before the window
        )
        val weeks = weeklyIntensity(sessions, { anchors }, ZoneMethod.AUTO, today)
        assertEquals(12, weeks.size)
        assertEquals(LocalDate.of(2026, 7, 27), weeks.first().weekStart)
        assertEquals(2, weeks.last().sessions)
        assertEquals(1, weeks.last().withHeartRate)
        assertEquals(1200.0, weeks[9].totalSeconds, 0.0)
        assertNull(weeks[0].share(IntensityBand.LOW))
    }

    @Test
    fun aSessionWithoutAnchorsIsNotPlaced() {
        val weeks = weeklyIntensity(listOf(IntensitySession(today, "run", mapOf(145 to 60.0))), { ZoneAnchors() }, ZoneMethod.AUTO, today)
        assertEquals(1, weeks.last().sessions)
        assertEquals(0, weeks.last().withHeartRate)
    }
}
