package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.RelationshipPayload
import com.bioscan.fieldterminal.data.model.RelationshipResultRow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PatternsTest {
    private fun row(key: String, state: String, pAdj: Double? = null) =
        RelationshipResultRow(key, state, RelationshipPayload(label = key, summary = "s", pAdjusted = pAdj))

    @Test
    fun testPatternsView_listsOnlySupportedStrongestFirstAndCountsTheRest() {
        val view = patternsView(
            listOf(
                row("a", "supported", 0.04),
                row("b", "not_distinguishable", 0.4),
                row("c", "supported", 0.001),
                row("d", "insufficient"),
                row("e", "insufficient"),
            ),
        )
        assertEquals(listOf("c", "a"), view.supported.map { it.key })
        assertEquals(1, view.notDistinguishable)
        assertEquals(2, view.insufficient)
        assertEquals(5, view.total)
        assertEquals(0, patternsView(emptyList()).total)
    }

    @Test
    fun testDecode_publishedPayloadShape() {
        val raw = """
            {"key":"alcohol_hrv","state":"supported","payload":{"label":"Does alcohol lower next-day HRV?","summary":"A day with alcohol -> next-day HRV: -11.5%",
             "reason":null,"n":235,"nOn":40,"nOff":195,"effect":{"value":-11.46,"lo":-14.0,"hi":-8.7,"unit":"%"},"mde":4.77,"pAdjusted":0.00063,
             "notes":["Both series are adjusted for day of week."],"catalogueVersion":"1.0.0","window":{"start":"2026-01-05","end":"2026-10-12"}}}
        """.trimIndent()
        val r = Json { ignoreUnknownKeys = true }.decodeFromString<RelationshipResultRow>(raw)
        assertEquals("supported", r.state)
        assertEquals(-11.46, r.payload.effect!!.value, 1e-9)
        assertEquals(235, r.payload.n)
        assertEquals(1, r.payload.notes.size)
    }
}
