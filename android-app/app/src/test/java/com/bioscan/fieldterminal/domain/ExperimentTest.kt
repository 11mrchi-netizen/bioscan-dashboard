package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.ExperimentResultRow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExperimentTest {
    @Test
    fun testBuildProtocol_abHasTwoBackToBackPhases() {
        val p = buildProtocol(LocalDate.of(2026, 11, 1), "AB", 28, 3)
        assertEquals(listOf("A", "B"), p.phases.map { it.label })
        assertEquals("2026-11-01", p.phases[0].start)
        assertEquals("2026-11-28", p.phases[0].end)
        assertEquals("2026-11-29", p.phases[1].start)
        assertEquals("2026-12-26", p.phases[1].end)
        assertEquals(3, p.washoutDays)
        assertTrue(p.excludeAlcoholDays)
        assertEquals(0.7, p.minAdherence, 1e-9)
    }

    @Test
    fun testBuildProtocol_ababRepeatsTheCycle() {
        val p = buildProtocol(LocalDate.of(2026, 11, 1), "ABAB", 14, 0)
        assertEquals(listOf("A", "B", "A", "B"), p.phases.map { it.label })
        assertEquals("2026-11-15", p.phases[1].start)
        assertEquals("2026-12-26", p.phases[3].end)
    }

    @Test
    fun testProblems_requireOneOutcomeAndSaneLengths() {
        assertTrue(experimentProblems("hrv", 28, "AB", 0).isEmpty())
        assertEquals(1, experimentProblems(null, 28, "AB", 0).size)
        assertEquals(1, experimentProblems("not_a_metric", 28, "AB", 0).size)
        assertEquals(1, experimentProblems("hrv", 10, "AB", 0).size)
        assertEquals(1, experimentProblems("hrv", 90, "AB", 0).size)
        assertEquals(1, experimentProblems("hrv", 14, "AB", 7).size) // washout must be under half a phase
        assertEquals(1, experimentProblems("hrv", 28, "ABA", 0).size)
    }

    @Test
    fun testMdeText_andCaveats() {
        val hrv = MdePreviewRow(metric = "hrv", unit = "%", mde = 12.74, usableDays = 90)
        assertEquals("Smallest difference this design could detect: 12.7%", mdeText(hrv))
        assertNull(mdeCaveat(hrv))
        assertEquals("Smallest difference this design could detect: 0.45 h", mdeText(MdePreviewRow(metric = "sleep_hours", unit = "h", mde = 0.451, usableDays = 90)))
        assertTrue(mdeCaveat(hrv.copy(unsettled = true))!!.contains("regime"))
        assertTrue(mdeCaveat(hrv.copy(usableDays = 40))!!.contains("40"))
    }

    @Test
    fun testDecode_publishedExperimentPayload() {
        val raw = """
            {"key":"exp-1","state":"no_detectable_effect","payload":{"stage":"complete","adherence":{"interventionDays":28,"interventionLogged":26,"rate":0.93,"baselineLogged":0,"ok":true},
             "metrics":[{"metric":"hrv","label":"HRV","role":"primary","verdict":"no_detectable_effect","n0":27,"n1":28,
             "diff":{"value":2.1,"lo":-9.0,"hi":14.0,"unit":"%"},"mde":12.7,"summary":"No detectable effect on hrv","notes":["x"]}],"status":"completed"}}
        """.trimIndent()
        val r = Json { ignoreUnknownKeys = true }.decodeFromString<ExperimentResultRow>(raw)
        assertEquals("no_detectable_effect", r.payload.metrics.first().verdict)
        assertEquals(26, r.payload.adherence.interventionLogged)
        assertEquals(-9.0, r.payload.metrics.first().diff!!.lo, 1e-9)
    }
}
