package com.bioscan.fieldterminal.domain.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IllnessReadTest {
    private val start = LocalDate.of(2026, 11, 1)
    private fun ep(vararg c: IllnessCheckIn) = IllnessEpisode("Cold", start, c.toList())

    @Test
    fun theCeilingFollowsTheTrainingAnswer() {
        assertEquals(null, illnessState(ep(IllnessCheckIn(start, 0, null)), start)!!.ceiling)
        assertEquals(70.0, illnessState(ep(IllnessCheckIn(start, 1, null)), start)!!.ceiling)
        assertEquals(55.0, illnessState(ep(IllnessCheckIn(start, 2, null)), start)!!.ceiling)
        assertEquals(40.0, illnessState(ep(IllnessCheckIn(start, 3, null)), start)!!.ceiling)
        // A fever is the lowest ceiling whatever the answer.
        val fever = illnessState(ep(IllnessCheckIn(start, 1, 38.4)), start)!!
        assertEquals(40.0, fever.ceiling)
        assertTrue(fever.reason.contains("fever"))
        // No check-in yet: the middle.
        assertEquals(55.0, illnessState(ep(), start.plusDays(1))!!.ceiling)
        assertNull(illnessState(ep(), start.minusDays(1)))
    }

    @Test
    fun anAnswerHoldsTwoDaysThenEases() {
        val e = ep(IllnessCheckIn(start, 3, null))
        assertEquals(40.0, illnessState(e, start.plusDays(1))!!.ceiling)
        assertEquals(40.0, illnessState(e, start.plusDays(2))!!.ceiling)
        assertEquals(55.0, illnessState(e, start.plusDays(3))!!.ceiling)
        assertEquals(70.0, illnessState(e, start.plusDays(5))!!.ceiling)
        assertEquals(null, illnessState(e, start.plusDays(7))!!.ceiling)
        // A newer check-in replaces the old one.
        val better = ep(IllnessCheckIn(start, 3, null), IllnessCheckIn(start.plusDays(2), 1, null))
        assertEquals(70.0, illnessState(better, start.plusDays(2))!!.ceiling)
        assertEquals("Cold · day 3 · reduced training (check-in 3 nov)", illnessState(better, start.plusDays(2))!!.reason)
    }

    private fun steady(key: String, base: Double, today: Double, day: LocalDate): Series =
        (1..60).associate { day.minusDays(it.toLong()) to base + (if (it % 2 == 0) 0.5 else -0.5) } + (day to today)

    @Test
    fun signalsAgreeWarnOrAreExplained() {
        val day = LocalDate.of(2026, 11, 4)
        val series = mapOf(
            ScoreKeys.RHR to steady(ScoreKeys.RHR, 50.0, 57.0, day),
            ScoreKeys.HRV to steady(ScoreKeys.HRV, 60.0, 40.0, day),
            ScoreKeys.SLEEP_BREATHING to steady(ScoreKeys.SLEEP_BREATHING, 14.0, 14.2, day),
        )
        val signals = bodySignals(series, day)
        assertEquals(setOf(ScoreKeys.RHR, ScoreKeys.HRV), signals.filter { it.z >= SIGNAL_FIRES_Z }.map { it.key }.toSet())
        val heads = readSignals(signals, null, Confounders())!!
        assertEquals(SignalsVerdict.HEADS_UP, heads.verdict)
        assertTrue(heads.line.contains("resting HR +7 bpm"))
        val explained = readSignals(signals, null, Confounders(drinks = 3))!!
        assertEquals(SignalsVerdict.EXPLAINED, explained.verdict)
        assertTrue(explained.line.contains("3 drinks"))
        val ill = illnessState(ep(IllnessCheckIn(start, 2, null)), day)
        assertEquals(SignalsVerdict.AGREE, readSignals(signals, ill, Confounders())!!.verdict)
        // Quiet numbers while ill say so; quiet numbers otherwise say nothing.
        val calm = bodySignals(series.mapValues { (k, s) -> s + (day to s.getValue(day.minusDays(2))) }, day)
        assertEquals(SignalsVerdict.NORMAL, readSignals(calm, ill, Confounders())!!.verdict)
        assertNull(readSignals(calm, null, Confounders()))
    }
}
