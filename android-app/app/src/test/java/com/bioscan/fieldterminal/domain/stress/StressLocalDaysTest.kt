package com.bioscan.fieldterminal.domain.stress

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StressLocalDaysTest {
    private val zone = ZoneId.of("Asia/Taipei") // UTC+8

    private fun utc(s: String, v: Int) = PhysiologicalStressReading(Instant.parse(s), v)

    @Test
    fun earlyMorningReadingsLandOnTheirOwnLocalDay() {
        // The UTC day 10 Nov holds 08:00 local on 10 Nov to 08:00 local on 11 Nov.
        val days = stressDaysByLocalDate(
            listOf(utc("2026-11-10T01:00:00Z", 30), utc("2026-11-10T15:00:00Z", 50), utc("2026-11-10T17:00:00Z", 70)),
            zone,
        )
        assertEquals(listOf(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 10)), days.map { it.date })
        assertEquals(listOf(70), days[0].samples.map { it.value })
        assertEquals(listOf(30, 50), days[1].samples.map { it.value })
    }

    @Test
    fun aSampleTwoItemsCarryCountsOnceAndZeroIsNoReading() {
        val days = stressDaysByLocalDate(
            listOf(utc("2026-11-10T01:00:00Z", 30), utc("2026-11-10T01:00:00Z", 30), utc("2026-11-10T01:05:00Z", 0)),
            zone,
        )
        assertEquals(1, days.single().samples.size)
    }

    @Test
    fun theSummaryIsTheLocalDaysOwn() {
        val s = summaryOf(listOf(20, 45, 65, 90).mapIndexed { i, v -> utc("2026-11-10T0$i:00:00Z", v) })
        assertEquals(55, s.avg)
        assertEquals(90, s.max)
        assertEquals(20, s.min)
        assertEquals(listOf(25, 25, 25, 25), listOf(s.relaxProportion, s.normalProportion, s.mediumProportion, s.highProportion))
    }
}
