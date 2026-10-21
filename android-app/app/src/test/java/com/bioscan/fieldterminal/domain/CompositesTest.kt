package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.PlannedOutcomeRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln

// Analysis review phase G: the shared composite inputs, their history as each morning would have read it, and the outcomes
// the scorecards use. Synthetic data only.
class CompositesTest {
    private val today = LocalDate.of(2026, 10, 15)
    private fun day(i: Int, n: Int) = today.minusDays((n - 1 - i).toLong())

    private fun rows(n: Int, seed: Long = 3): CompositeInputs {
        val random = Random(seed)
        var state = 0.0
        val wearable = (0 until n).map { i ->
            state = 0.6 * state + 0.07 * random.nextGaussian()
            WearableAnalysisRow(date = day(i, n).toString(), hrv = exp(4.0 + state + 0.04 * random.nextGaussian()), rhr = 52 + random.nextGaussian())
        }
        val sleep = (0 until n).map { i ->
            val d = day(i, n)
            val bedtime = d.minusDays(1).atTime(22, 30).plusMinutes(random.nextInt(30).toLong()).toInstant(java.time.ZoneOffset.UTC)
            val hours = 7.2 + 0.5 * random.nextGaussian()
            SleepAnalysisRow(
                date = d.toString(), hours = hours, bedtime = bedtime.toString(), wakeTime = bedtime.plusSeconds((hours * 3600).toLong()).toString(),
                respiratoryRate = 14.5 + 0.3 * random.nextGaussian(), deepMin = 80 + 10 * random.nextGaussian(),
            )
        }
        val wellbeing = (0 until n).map { i -> WellbeingAnalysisRow(date = day(i, n).toString(), energy = 3 + random.nextInt(3), stress = 2 + random.nextInt(3), soreness = 1 + random.nextInt(3)) }
        val sessions = (0 until n).filter { it % 2 == 0 }.map { i -> TrainingLoadSessionRow(startTime = "${day(i, n)}T07:00:00Z", durationMin = 45.0 + random.nextInt(30), rpe = 4 + random.nextInt(4)) }
        return compositeInputs(wearable, sleep, wellbeing, sessions, SeriesBreakStarts.NONE)
    }

    @Test
    fun hrvResidualIsEachMorningAgainstThe28DaysBefore() {
        val hrv = (0 until 40).map { i -> today.minusDays(39L - i) to (if (i < 30) 50.0 else 55.0) }
        val residual = hrvResidualByDay(hrv)
        assertNull("fewer than 14 days before it", residual[today.minusDays(39)])
        assertEquals(0.0, residual.getValue(today.minusDays(10)), 1e-12)
        assertEquals(ln(55.0 / 50.0), residual.getValue(today.minusDays(9)), 1e-12)
        val next = nextMorning(residual)
        assertEquals(residual.getValue(today.minusDays(9)), next.getValue(today.minusDays(10)), 0.0)
    }

    @Test
    fun plannedDoneIsTheShareOfTheDaysPlannedSessionsDone() {
        val done = plannedDoneByDay(
            listOf(
                PlannedOutcomeRow("2026-10-01", "planned", exerciseSessionId = 12),
                PlannedOutcomeRow("2026-10-01", "planned", null),
                PlannedOutcomeRow("2026-10-02", "confirmed", null),
                PlannedOutcomeRow("2026-10-03", "planned", null),
            ),
        )
        assertEquals(0.5, done.getValue(LocalDate.of(2026, 10, 1)), 0.0)
        assertEquals(1.0, done.getValue(LocalDate.of(2026, 10, 2)), 0.0)
        assertEquals(0.0, done.getValue(LocalDate.of(2026, 10, 3)), 0.0)
    }

    @Test
    fun theHistoryReadsEachMorningAsItWasAndEveryCompositeGetsAScorecard() {
        val inputs = rows(200)
        val history = inputs.history(today, 200)
        assertTrue("recovery on ${history.recovery.size} days", history.recovery.size > 100)
        assertTrue("sleep index on ${history.sleepIndex.size} days", history.sleepIndex.size > 100)
        assertTrue(history.recoveryInputs.map { it.key }.containsAll(listOf("sleep", "hrv", "rhr", "load", "energy")))
        assertTrue(history.sleepInputs.any { it.key == "hours" })
        // Today's entry is today's composite: the same as computing it now.
        assertEquals(recoveryOrdinal(inputs.recovery(today).state), history.recovery[today])
        assertEquals(inputs.sleepIndex(today).score, history.sleepIndex[today])
        val cards = history.scorecards()
        listOf(cards.recovery, cards.sleepEnergy, cards.sleepHrv, cards.sleepIndex).forEach { card ->
            assertTrue(card.sentence, card.sentence.isNotBlank())
            assertTrue(card.days >= 0)
        }
        // Random data: nothing here should earn its place against its inputs.
        assertTrue(cards.recovery.verdict != ScorecardVerdict.EARNS_ITS_PLACE)
        val ready = history.readyForScorecard(history.recovery.keys.filter { it.dayOfMonth % 3 == 0 }.associateWith { if (it.dayOfMonth % 2 == 0) 1.0 else 0.0 })
        assertEquals(ScoreRule.BRIER, ready.rule)
    }

    @Test
    fun aConfirmedBreakAppliesOnlyFromItsDate() {
        val inputs = rows(200).copy(breakStarts = SeriesBreakStarts(mapOf("hrv" to today.minusDays(20))))
        // Before the break the earlier regime is read in full; after it, only the days since the break.
        assertTrue(inputs.evaluations(today.minusDays(60)).hrv.confidence.met)
        assertEquals(EvalState.Building, inputs.evaluations(today.minusDays(15)).hrv.state)
    }
}
