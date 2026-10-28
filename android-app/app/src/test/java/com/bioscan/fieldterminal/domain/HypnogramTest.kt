package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.HrPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HypnogramTest {
    // A real night (2026-10-25): light 7, deep 58, light 6, REM 37, light 109, awake 2, light 3, REM 37, light 45, then wake blips.
    private val night = parseStageBlocks(
        listOf(
            listOf(0, 7, 4), listOf(7, 58, 5), listOf(65, 6, 4), listOf(71, 37, 8), listOf(108, 109, 4), listOf(217, 2, 7),
            listOf(219, 3, 4), listOf(222, 37, 8), listOf(259, 45, 4), listOf(304, 1, 7), listOf(305, 8, 4), listOf(313, 4, 8), listOf(317, 3, 4),
        ),
    )

    @Test
    fun badRowsAreSkippedAndTheRestSorted() {
        val out = parseStageBlocks(listOf(listOf(10, 5, 8), listOf(0, 10, 4), listOf(3, 0, 5), listOf(4, 4, 99), listOf(1)))
        assertEquals(listOf(SleepStage.Light, SleepStage.Rem), out.map { it.stage })
    }

    @Test
    fun stageMinutesSumToTheNight() {
        val m = stageMinutes(night)
        assertEquals(320, m.values.sum())
        assertEquals(58, m[SleepStage.Deep])
        assertEquals(78, m[SleepStage.Rem])
    }

    @Test
    fun cyclesEndWhereRemEndsAndTheTailJoinsTheLast() {
        val c = estimateCycles(night)
        // REM ends at 108 and 259; the REM at 313..317 is within 10 min of nothing earlier, so a third period ends at 317.
        assertEquals(listOf(SleepCycle(0, 108), SleepCycle(108, 259), SleepCycle(259, 320)), c)
    }

    @Test
    fun anEmptyNightHasNoCycles() {
        assertEquals(emptyList<SleepCycle>(), estimateCycles(emptyList()))
    }

    @Test
    fun overnightHeartRateSpansTwoDaysAndUsesTheBandsOffset() {
        // Band at UTC+8; sleep starts 23:30 local on 24 Oct (= 15:30 UTC) and lasts 120 minutes.
        val bedtime = Instant.parse("2026-10-24T15:30:00Z")
        val evening = listOf(HrPoint(23 * 60 + 30, 60), HrPoint(23 * 60 + 59, 58), HrPoint(10, 90))   // 24 Oct local; 00:10 is before bed
        val morning = listOf(HrPoint(0, 57), HrPoint(29, 55), HrPoint(30, 54), HrPoint(31, 250))     // 25 Oct local; 250 is not a heart rate
        val out = nightHeartRate(bedtime, 480, 120, listOf(LocalDate.of(2026, 10, 24) to evening, LocalDate.of(2026, 10, 25) to morning))
        assertEquals(listOf(0 to 60, 29 to 58, 30 to 57, 59 to 55, 60 to 54), out.map { it.minute to it.bpm })
    }

    @Test
    fun clockLabelIsLocalTime() {
        assertEquals("01:27", clockLabel(Instant.parse("2026-10-24T17:27:00Z"), 480, 0))
        assertEquals("03:27", clockLabel(Instant.parse("2026-10-24T17:27:00Z"), 480, 120))
    }
}
