package com.bioscan.fieldterminal.domain.meview

import com.bioscan.fieldterminal.data.model.BodyMetricsAnalysisRow
import com.bioscan.fieldterminal.data.model.UserProfileRow
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// The web Dashboard reads me_views.view in this shape (console/src/dashboard/meView.ts): keep the two in step.
class BodyMeViewTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val rows = (0 until 20).map { i -> BodyMetricsAnalysisRow(today.minusDays(19L - i).toString(), 80.0 - i * 0.1, if (i % 5 == 0) 15.0 else null) }

    @Test fun `body sections in the phone's order with formatted values`() {
        val doc = bodyMeView(rows, emptyList(), 180.0, UserProfileRow("1990-01-01", "male", 180.0), emptyList(), today)
        assertEquals(listOf("WEIGHT", "BODY FAT", "BODY MEASUREMENTS", "FROM THE SCALE", "MUSCLE POTENTIAL"), doc.sections.map { it.title })
        val tile = doc.sections[0].items.filterIsInstance<MeTile>().single()
        assertEquals("78.1", tile.value)
        assertEquals("kg", tile.unit)
        val potential = doc.sections[4].items.single()
        assertTrue(potential is MeRing)
    }

    @Test fun `json carries v, tab and typed items`() {
        val json = bodyMeView(rows, emptyList(), 180.0, null, emptyList(), today).toJson()
        assertEquals("1", json["v"]!!.jsonPrimitive.content)
        assertEquals("BODY", json["tab"]!!.jsonPrimitive.content)
        val weight = json["sections"]!!.jsonArray[0].jsonObject["items"]!!.jsonArray
        assertEquals("tile", weight[0].jsonObject["kind"]!!.jsonPrimitive.content)
        val chart = weight[1].jsonObject
        assertEquals("chart", chart["kind"]!!.jsonPrimitive.content)
        val first = chart["series"]!!.jsonArray[0].jsonObject["points"]!!.jsonArray[0].jsonArray
        assertEquals(today.minusDays(19).toString(), first[0].jsonPrimitive.content)
    }
}
