package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.FitnessFatigueRow
import com.bioscan.fieldterminal.data.model.TypicalityResultRow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Analysis review phase G: training load drawn with the published spans, and the phase G payloads decoding.
class LoadSpansTest {
    private val today = LocalDate.of(2026, 10, 15)
    private val loads = (0 until 120).filter { it % 2 == 0 }.map { today.minusDays(it.toLong()) to 80.0 + (it % 7) * 10 }

    @Test
    fun personalSpansChangeFitnessAndFatigueAndTheSeriesAgreesWithTheEvaluation() {
        val standard = evaluateTrainingLoad(loads, today)
        val personal = evaluateTrainingLoad(loads, today, LoadSpans(70.0, 14.0, personal = true))
        assertEquals(LoadSpans(42.0, 7.0), LoadSpans.STANDARD)
        assertNotEquals(standard.ctl!!, personal.ctl!!, 1e-6)
        val series = trainingLoadSeries(loads, today, LoadSpans(70.0, 14.0, personal = true))
        assertEquals(personal.tsb!!, series.last().tsb, 1e-9)
        assertEquals(standard.tsb!!, trainingLoadSeries(loads, today).last().tsb, 1e-9)
    }

    @Test
    fun theFitnessFatigueAndTypicalityScorecardPayloadsDecode() {
        val json = Json { ignoreUnknownKeys = true }
        val ff = json.decodeFromString<FitnessFatigueRow>(
            """{"key":"hrv","state":"predictive","payload":{"state":"predictive","standard":{"ctl":42,"atl":7},"personal":{"ctl":70,"atl":14},"adopted":true,"heldOutDays":180,"difference":0.03,"lo":0.01,"hi":0.05,"summary":"Your own time constants..."}}""",
        )
        assertTrue(ff.payload.adopted)
        assertEquals(70.0, ff.payload.personal!!.ctl, 0.0)
        val ty = json.decodeFromString<TypicalityResultRow>(
            """{"key":"personal_normal","state":"typical","payload":{"baselineDays":120,"baselineNeed":45,"today":{"date":"2026-10-15","state":"typical"},"scorecard":{"verdict":"no_better","onsets":6,"caught":1,"flags":30,"flagsBeforeOnset":2,"precision":0.067,"recall":0.167,"baseRate":0.08,"sentence":"2 of 30 strain days came in the 3 days before a logged illness"}}}""",
        )
        val view = typicalityView(ty)
        assertEquals("NO BETTER THAN CHANCE", view.scorecard!!.first)
        assertTrue(view.scorecard!!.second.startsWith("2 of 30"))
    }
}
