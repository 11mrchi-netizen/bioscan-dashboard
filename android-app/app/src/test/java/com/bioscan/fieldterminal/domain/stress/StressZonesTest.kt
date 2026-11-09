package com.bioscan.fieldterminal.domain.stress

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StressZonesTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val day = LocalDate.of(2026, 11, 2)

    private fun at(h: Int, m: Int, v: Int) = PhysiologicalStressReading(day.atTime(h, m).atZone(zone).toInstant(), v)

    @Test
    fun zonesFollowZeppsCutOffs() {
        assertEquals(StressZone.RELAXED, StressZone.of(39.9))
        assertEquals(StressZone.NORMAL, StressZone.of(40.0))
        assertEquals(StressZone.MEDIUM, StressZone.of(79.0))
        assertEquals(StressZone.HIGH, StressZone.of(80.0))
    }

    @Test
    fun readingsAreAveragedInHalfHoursFromSixInTheMorning() {
        val samples = listOf(at(5, 50, 90), at(9, 0, 30), at(9, 20, 50), at(9, 40, 85), at(23, 59, 20))
        val stressDay = StressDay(day, samples, StressDailySummary(null, null, null, null, null, null, null))
        val slots = stressSlots(stressDay, zone)
        assertEquals(listOf(540, 570, 1410), slots.map { it.minuteOfDay })
        assertEquals(40.0, slots[0].mean, 1e-9)
        assertEquals(StressZone.NORMAL, slots[0].zone)
        assertEquals(StressZone.HIGH, slots[1].zone)
        assertEquals(mapOf(StressZone.HIGH to 30, StressZone.MEDIUM to 0, StressZone.NORMAL to 30, StressZone.RELAXED to 30), minutesByZone(slots))
    }
}
