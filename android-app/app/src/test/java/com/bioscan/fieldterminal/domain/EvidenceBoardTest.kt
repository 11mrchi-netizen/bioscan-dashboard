package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.EventCurvePayload
import com.bioscan.fieldterminal.data.model.EventPeak
import com.bioscan.fieldterminal.data.model.RelationshipEffect
import com.bioscan.fieldterminal.data.model.RelationshipPayload
import com.bioscan.fieldterminal.data.model.RelationshipReadiness
import com.bioscan.fieldterminal.data.model.RelationshipResultRow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Analysis review phase D: the evidence board's view of the published relationship rows. Synthetic values only.
class EvidenceBoardTest {
    private fun row(id: String, state: String, outcome: String, effect: RelationshipEffect? = null, readiness: RelationshipReadiness? = null, zone: Double = 2.0) =
        RelationshipResultRow(
            key = id,
            state = state,
            payload = RelationshipPayload(
                id = id, label = id.replace('_', ' '), outcome = outcome, summary = "s", n = 200, nOn = 30, nOff = 170,
                effect = effect, noEffect = zone, mde = 4.0, readiness = readiness,
            ),
        )

    @Test
    fun rowsAreGroupedByOutcomeAndKeepTheCatalogueOrderNotThePValue() {
        val groups = evidenceBoard(
            listOf(
                row("sleep_hrv", "clear", "hrv", RelationshipEffect(4.8, 3.5, 6.1, "%")),
                row("alcohol_sleep", "unclear", "sleep", RelationshipEffect(-12.0, -30.0, 4.0, "min"), zone = 10.0),
                row("alcohol_hrv", "clear", "hrv", RelationshipEffect(-9.1, -15.2, -3.0, "%")),
                row("caffeine_pm_sleep", "not_yet", "sleep", readiness = RelationshipReadiness(4, 20, "days with it"), zone = 10.0),
            ),
        )
        assertEquals(listOf("hrv", "sleep"), groups.map { it.outcome })
        assertEquals(listOf("alcohol_hrv", "sleep_hrv"), groups[0].rows.map { it.id })
        assertEquals(listOf("alcohol_sleep", "caffeine_pm_sleep"), groups[1].rows.map { it.id })
        assertEquals("2 clear · 1 unclear · 1 not yet", evidenceTally(groups))
        // The shared axis holds every interval of the group.
        assertTrue(groups[0].scale >= 15.2)
    }

    @Test
    fun aRowSaysItsSizeIntervalAndDaysOrItsProgress() {
        val clear = evidenceRow(row("alcohol_hrv", "clear", "hrv", RelationshipEffect(-9.1, -15.2, -3.0, "%")))
        assertEquals(Verdict.Clear, clear.verdict)
        assertEquals("ASSOCIATED", clear.rung)
        assertEquals("-9.1% (95% interval -15.2% to -3.0%) · 30 days with it, 170 without", clear.sizeLine)
        assertNull(clear.progress)

        val notYet = evidenceRow(row("late_meal_sleep", "not_yet", "sleep", readiness = RelationshipReadiness(6, 21, "days with it")))
        assertEquals(Verdict.NotYet, notYet.verdict)
        assertNull(notYet.estimate)
        assertNull(notYet.sizeLine)
        assertEquals("6 of about 21 days with it", notYet.progress?.text)
        assertEquals(6f / 21f, notYet.progress!!.fraction, 1e-6f)

        // A row from an older engine (state "supported") waits for tonight's analysis instead of guessing.
        assertEquals(Verdict.NotYet, verdictOf("supported"))
    }

    @Test
    fun lastEveningsExposuresBringTheirClearEvidence() {
        val rels = listOf(
            row("alcohol_hrv", "clear", "hrv", RelationshipEffect(-9.1, -15.2, -3.0, "%")),
            row("late_meal_sleep", "unclear", "sleep", RelationshipEffect(-5.0, -20.0, 10.0, "min"), zone = 10.0),
        )
        val curve = EventCurvePayload(id = "alcohol_hrv", state = "ready", peak = EventPeak(1, -10.0), summary = "After evenings with alcohol, HRV ran 10% lower...")
        val checkin = EveningCheckin(LocalDate.of(2026, 10, 14), drinks = 2, caffeineAfter14 = false, lateMeal = true)
        val c = contributors(checkin, rels, listOf(curve))
        assertEquals(listOf("alcohol"), c.map { it.exposure }) // the late meal's question is still unclear
        assertEquals("After evenings with alcohol, HRV ran 10% lower...", c[0].line)
        assertTrue(contributors(null, rels, emptyList()).isEmpty())
    }

    @Test
    fun thePublishedPayloadDecodes() {
        val raw = """
            {"key":"alcohol_hrv","state":"clear","payload":{"id":"alcohol_hrv","label":"Alcohol and next-morning HRV","outcome":"hrv","rung":"associated",
             "summary":"A day with alcohol went with next-morning HRV -9.1% (95% interval -15.2% to -3.0%; 30 days with it, 170 without), clear of the ±2% no-effect zone.",
             "reason":null,"n":200,"nOn":30,"nOff":170,"effect":{"value":-9.1,"lo":-15.2,"hi":-3.0,"unit":"%"},"noEffect":2,"mde":4.4,"p":0.003,"pAdjusted":0.02,
             "direction":"negative","signMatchesPrior":true,"eValue":{"point":1.9,"ci":1.3},"negativeControl":{"value":0.4,"lo":-3.0,"hi":3.9,"unit":"%","clear":false},
             "readiness":null,"adjustments":["weekday","slow trend"],"notes":["n"],"inputsHash":"abcd1234","catalogueVersion":"2.0.0","familySize":9,
             "window":{"start":"2026-01-05","end":"2026-10-14"},"capture":{"firstCheckin":"2026-10-01","knownShare28":{"alcohol":0.9,"caffeine_pm":0.8},"localTime":true}}}
        """.trimIndent()
        val r = Json { ignoreUnknownKeys = true }.decodeFromString<RelationshipResultRow>(raw)
        val v = evidenceRow(r)
        assertEquals(Verdict.Clear, v.verdict)
        assertEquals(2.0, v.zone!!, 1e-9)
        assertEquals(2, v.adjustments.size)
        assertTrue(v.checks.any { it.startsWith("E-value 1.9") })
        assertTrue(v.checks.any { it.startsWith("Negative control: no difference") })
    }
}
