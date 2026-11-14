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
    fun theCeilingFollowsTheGravityNotTheIllnessBeingOpen() {
        val nose = setOf("sym.congestion")
        // A stuffed nose at 2/10 while training as usual: mild.
        assertEquals(85.0, illnessState(ep(IllnessCheckIn(start, 0, null, 2, nose)), start)!!.ceiling)
        // The same nose with a cough (below the neck): moderate.
        assertEquals(65.0, illnessState(ep(IllnessCheckIn(start, 0, null, 3, nose + "sym.cough")), start)!!.ceiling)
        // Training modified, or 5/10 below the neck, or a fever: graver.
        assertEquals(65.0, illnessState(ep(IllnessCheckIn(start, 2, null, 2, nose)), start)!!.ceiling)
        assertEquals(40.0, illnessState(ep(IllnessCheckIn(start, 1, null, 5, setOf("sym.gi_upset"))), start)!!.ceiling)
        val fever = illnessState(ep(IllnessCheckIn(start, 1, 38.4, 3, nose)), start)!!
        assertEquals(40.0, fever.ceiling)
        assertTrue(fever.reason.contains("fever"))
        // Daily life affected is severe whatever else.
        assertEquals(40.0, illnessState(ep(IllnessCheckIn(start, 3, null)), start)!!.ceiling)
        // Nothing left: no ceiling.
        assertNull(illnessState(ep(IllnessCheckIn(start, 0, null, 0)), start)!!.ceiling)
        // No check-in yet: moderate.
        assertEquals(65.0, illnessState(ep(), start.plusDays(1))!!.ceiling)
        assertNull(illnessState(ep(), start.minusDays(1)))
        assertEquals("Cold · day 1 · mild: above the neck, 2/10 (check-in 1 nov)", illnessState(ep(IllnessCheckIn(start, 0, null, 2, nose)), start)!!.reason)
    }

    @Test
    fun aGradeHoldsTwoDaysThenEasesAStepEveryTwo() {
        val e = ep(IllnessCheckIn(start, 3, null, 8, setOf("sym.fever")))
        assertEquals(40.0, illnessState(e, start.plusDays(1))!!.ceiling)
        assertEquals(40.0, illnessState(e, start.plusDays(2))!!.ceiling)
        assertEquals(65.0, illnessState(e, start.plusDays(3))!!.ceiling)
        assertEquals(85.0, illnessState(e, start.plusDays(5))!!.ceiling)
        assertNull(illnessState(e, start.plusDays(7))!!.ceiling)
        // A newer check-in replaces the old one.
        val better = ep(IllnessCheckIn(start, 3, null, 8, setOf("sym.fever")), IllnessCheckIn(start.plusDays(2), 1, null, 2, setOf("sym.congestion")))
        assertEquals(85.0, illnessState(better, start.plusDays(2))!!.ceiling)
        assertEquals("Cold · day 3 · mild: above the neck, 2/10, reduced training (check-in 3 nov)", illnessState(better, start.plusDays(2))!!.reason)
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
        val ill = illnessState(ep(IllnessCheckIn(start, 2, null, 5, setOf("sym.cough"))), day)
        assertEquals(SignalsVerdict.AGREE, readSignals(signals, ill, Confounders())!!.verdict)
        // Quiet numbers while ill say so; quiet numbers otherwise say nothing.
        val calm = bodySignals(series.mapValues { (k, s) -> s + (day to s.getValue(day.minusDays(2))) }, day)
        assertEquals(SignalsVerdict.NORMAL, readSignals(calm, ill, Confounders())!!.verdict)
        assertNull(readSignals(calm, null, Confounders()))
    }
}
