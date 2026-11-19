package com.bioscan.fieldterminal.domain.meview

import com.bioscan.fieldterminal.data.AirDayRow
import com.bioscan.fieldterminal.data.AirSessionRow
import com.bioscan.fieldterminal.data.AirSummary
import com.bioscan.fieldterminal.data.ExposuresData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposuresMeViewTest {
    // Written by the TypeScript engine (analysis-core/airExposure.ts summarize), so a renamed field on either side fails here.
    private val payload = javaClass.classLoader!!.getResource("air_summary_payload.json")!!.readText()
    private val summary = Json { ignoreUnknownKeys = true }.decodeFromString(AirSummary.serializer(), payload)

    @Test
    fun theServersSummaryDecodes() {
        assertEquals(16.0, summary.pm25Mean!!, 1e-9)
        assertEquals(4, summary.pm25DaysOver)
        assertEquals("bad", summary.states["pm25"])
        assertEquals(listOf("measure_indoor", "purifier", "ventilate_bedroom", "session_timing", "uv"), summary.improvements.map { it.id })
        assertEquals(listOf("home_env_readings"), summary.missing)
    }

    @Test
    fun theTabShowsTheSummaryTheWaysToImproveAndTheSessions() {
        val days = listOf(
            AirDayRow("2026-11-09", 24, pm25 = 12.0, no2 = 14.0, sessions = listOf(AirSessionRow("2026-11-09T06:00:00+00:00", "run", 50, pm25 = 22.0, o3 = 60.0, uv = 2.0))),
            AirDayRow("2026-11-10", 24, pm25 = 20.0, no2 = 16.0),
        )
        val doc = exposuresMeView(ExposuresData(days, summary, "2026-11-10", "READY"))
        assertEquals("EXPOSURES", doc.tab)
        assertEquals(listOf("AIR · LAST 30 DAYS", "WAYS TO IMPROVE", "OUTDOOR SESSIONS"), doc.sections.map { it.title })
        val pm = doc.sections[0].items.first() as MeTile
        assertEquals("16.0", pm.value)
        assertEquals(Tone.BAD, pm.tone)
        assertEquals("m-pm25", pm.hue)
        assertEquals(2, pm.series.size)
        val session = doc.sections[2].items.single() as MeRow
        assertEquals(Tone.WARN, session.tone)
        assertTrue(doc.toJson().toString().contains("\"v\":1"))
    }

    @Test
    fun anEmptyAccountSaysSo() {
        val doc = exposuresMeView(ExposuresData(emptyList(), null, null, null))
        assertEquals("No air days yet.", (doc.sections.single().items.single() as MeNote).text)
    }
}
