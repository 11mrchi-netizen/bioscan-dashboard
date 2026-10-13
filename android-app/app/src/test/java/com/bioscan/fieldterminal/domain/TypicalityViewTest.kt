package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.TypicalityDay
import com.bioscan.fieldterminal.data.model.TypicalityDriver
import com.bioscan.fieldterminal.data.model.TypicalityLookalike
import com.bioscan.fieldterminal.data.model.TypicalityPayload
import com.bioscan.fieldterminal.data.model.TypicalityResultRow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypicalityViewTest {
    private fun row(state: String, today: TypicalityDay?, vararg extra: Pair<String, Any>): TypicalityResultRow {
        val m = extra.toMap()
        return TypicalityResultRow(
            "personal_normal", state,
            TypicalityPayload(
                baselineDays = 74, baselineNeed = 45, today = today,
                suggestIllnessLog = m["suggest"] as? Boolean ?: false,
                lookalikes = (m["lookalikes"] as? List<*>)?.filterIsInstance<TypicalityLookalike>() ?: emptyList(),
                skipped = (m["skipped"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            ),
        )
    }

    @Test
    fun testView_missingAndBuilding() {
        assertEquals("Not analysed yet", typicalityView(null).headline)
        val building = typicalityView(TypicalityResultRow("personal_normal", "building", TypicalityPayload(baselineDays = 20, baselineNeed = 45)))
        assertEquals("Building your baseline", building.headline)
        assertEquals(listOf("20 of 45 complete days"), building.lines)
    }

    @Test
    fun testView_typicalDayHasNoPrompt() {
        val v = typicalityView(row("typical", TypicalityDay(date = "2026-10-12", state = "typical")))
        assertEquals("Typical for you (2026-10-12)", v.headline)
        assertFalse(v.promptIllness)
    }

    @Test
    fun testView_unusualDayShowsDriversLookalikesAndPrompt() {
        val day = TypicalityDay(
            date = "2026-10-12", state = "unusual",
            drivers = listOf(TypicalityDriver("rhr", 3.1, "up"), TypicalityDriver("hrv", -2.2, "down")),
        )
        val v = typicalityView(
            row("unusual", day, "suggest" to true, "lookalikes" to listOf(TypicalityLookalike("2026-03-02", true)), "skipped" to listOf("deep_rem")),
        )
        assertEquals("Unusual for you (2026-10-12)", v.headline)
        assertEquals("resting heart rate up, HRV down", v.lines[0])
        assertTrue(v.lines.any { it == "Looks like: 2026-03-02 (illness)" })
        assertTrue(v.lines.any { it.startsWith("Left out: deep + REM sleep") })
        assertTrue(v.promptIllness)
    }

    @Test
    fun testView_loggedIllnessDayIsNotJudgedOrPrompted() {
        val v = typicalityView(row("unusual", TypicalityDay(date = "2026-10-12", state = "unusual", strain = true, excluded = true), "suggest" to true))
        assertEquals("Illness logged: not judged", v.headline)
        assertFalse(v.promptIllness)
    }

    @Test
    fun testDecode_publishedPayloadShape() {
        val raw = """
            {"key":"personal_normal","state":"elevated","payload":{"baselineDays":74,"baselineNeed":45,"thresholds":{"elevated":1.9,"unusual":2.6},
             "today":{"date":"2026-10-12","state":"elevated","d2k":2.1,"signalsUsed":4,"drivers":[{"signal":"hrv","z":-2.1,"direction":"down"}],"strain":false,"excluded":false},
             "recent":[],"suggestIllnessLog":false,"lookalikes":[{"date":"2026-03-02","illness":true}],"skipped":["deep_rem"]}}
        """.trimIndent()
        val r = Json { ignoreUnknownKeys = true }.decodeFromString<TypicalityResultRow>(raw)
        assertEquals("elevated", r.state)
        assertEquals("hrv", r.payload.today!!.drivers.first().signal)
        assertEquals(listOf("deep_rem"), r.payload.skipped)
    }
}
