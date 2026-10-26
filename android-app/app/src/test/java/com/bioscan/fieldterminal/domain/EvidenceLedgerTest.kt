package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.LedgerEntry
import com.bioscan.fieldterminal.data.model.PooledEstimate
import com.bioscan.fieldterminal.data.model.RegisteredQuestion
import com.bioscan.fieldterminal.data.model.RelationshipEffect
import com.bioscan.fieldterminal.data.model.RelationshipPayload
import com.bioscan.fieldterminal.data.model.RelationshipResultRow
import com.bioscan.fieldterminal.data.model.VerdictInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Analysis review phase E: the ledger as verdict runs, registering hints, and the lines that explain the sequential
// interval and the pooled estimate. Synthetic values only.
class EvidenceLedgerTest {
    private fun entry(day: String, verdict: String) = LedgerEntry(questionId = "alcohol_hrv", asOf = day, verdict = verdict)

    @Test
    fun theLedgerReadsAsRunsOfTheSameVerdictNewestFirst() {
        val runs = verdictRuns(
            listOf(
                entry("2026-10-14", "clear"), entry("2026-10-13", "clear"), entry("2026-10-12", "unclear"),
                entry("2026-10-11", "unclear"), entry("2026-10-10", "unclear"), entry("2026-10-09", "not_yet"),
            ),
        )
        assertEquals(listOf(Verdict.Clear, Verdict.Unclear, Verdict.NotYet), runs.map { it.verdict })
        assertEquals(listOf(2, 3, 1), runs.map { it.nights })
        assertEquals("CLEAR since 13 Oct (2 nights) · UNCLEAR 10 Oct to 12 Oct (3 nights) · NOT YET 9 Oct to 9 Oct (1 night)", verdictHistoryLine(runs))
        assertNull(verdictHistoryLine(emptyList()))
    }

    @Test
    fun onlyPointingHintsOutsideTheCatalogueCanBeRegisteredOnce() {
        assertTrue(canRegister("caffeine_pm_any", "hrv", "more_often", emptyList()))
        assertFalse(canRegister("caffeine_pm_any", "hrv", "no_clear_difference", emptyList()))
        assertFalse(canRegister("alcohol_any", "hrv", "more_often", emptyList())) // the catalogue already asks it
        val live = RegisteredQuestion(id = 1, exposure = "caffeine_pm_any", outcome = "hrv", registeredOn = "2026-10-15")
        assertFalse(canRegister("caffeine_pm_any", "hrv", "more_often", listOf(live)))
        assertTrue(canRegister("caffeine_pm_any", "hrv", "more_often", listOf(live.copy(withdrawnAt = "2026-10-20T08:00:00Z"))))
        assertEquals(2.0, defaultZone("hrv"), 0.0)
        assertEquals(1.0, defaultZone("rhr"), 0.0)
        assertEquals(10.0, defaultZone("sleep_hours"), 0.0)
    }

    @Test
    fun theRowExplainsAWiderVerdictIntervalThePoolAndTheRegistration() {
        val row = RelationshipResultRow(
            key = "reg_3",
            state = "unclear",
            payload = RelationshipPayload(
                label = "Caffeine after 14:00 and next-morning HRV", outcome = "hrv", n = 90, nOn = 20, nOff = 70,
                effect = RelationshipEffect(-6.0, -10.0, -2.5, "%"), noEffect = 2.0,
                verdictInterval = VerdictInterval(lo = -14.0, hi = 1.5, boundary = 3.5, information = 0.33),
                pooled = PooledEstimate(family = "caffeine_pm_any", members = 2, value = -5.0, lo = -8.0, hi = -2.0, unit = "%", probabilityBeyondZone = 0.97),
                registeredOn = "2026-10-15", daysSince = 90,
            ),
        )
        val v = evidenceRow(row)
        assertTrue(v.verdictIntervalLine!!.startsWith("The verdict uses a wider interval (-14.0% to +1.5%) because this question is re-checked every night"))
        assertTrue(v.verdictIntervalLine!!.contains("33% of a full year's information"))
        assertTrue(v.pooledLine!!.startsWith("Pooled with 1 related question about the same exposure: -5.0% (-8.0% to -2.0%); about 97% likely"))
        assertEquals("Registered 2026-10-15 · tested only on the 90 days since", v.registeredLine)
        val groups = evidenceBoard(emptyList(), listOf(row))
        assertEquals(listOf("registered"), groups.map { it.outcome })
        assertEquals("QUESTIONS YOU REGISTERED", groups[0].title)
    }
}
