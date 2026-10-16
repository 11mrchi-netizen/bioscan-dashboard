package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.ExperimentResultRow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentTest {
    @Test
    fun testMdeCaveats() {
        val hrv = MdePreviewRow(metric = "hrv", unit = "%", mde = 12.74, usableDays = 90)
        assertNull(mdeCaveat(hrv))
        assertTrue(mdeCaveat(hrv.copy(unsettled = true))!!.contains("regime"))
        assertTrue(mdeCaveat(hrv.copy(usableDays = 40))!!.contains("40"))
    }

    // The Supabase client's JSON leaves out fields equal to their default; the registration settings must still be stored.
    @Test
    fun testProtocol_registrationDefaultsAreAlwaysWritten() {
        val p = ExperimentProtocol(design = "randomized_pairs", phases = emptyList())
        val encoded = Json { ignoreUnknownKeys = true }.encodeToString(ExperimentProtocol.serializer(), p)
        assertTrue(encoded, encoded.contains("\"washoutDays\":0"))
        assertTrue(encoded, encoded.contains("\"minAdherence\":0.7"))
        assertTrue(encoded, encoded.contains("\"excludeAlcoholDays\":true"))
        assertTrue("unset version 2 fields stay out", !encoded.contains("assignments"))
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
