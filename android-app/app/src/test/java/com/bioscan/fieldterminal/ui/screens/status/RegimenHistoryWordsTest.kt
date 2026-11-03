package com.bioscan.fieldterminal.ui.screens.status

import com.bioscan.fieldterminal.data.SupplementChangeRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RegimenHistoryWordsTest {
    private fun snap(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun startsDoseAndScheduleChangesReadAsSentences() {
        val before = snap("""{"dose":"5 g","dose_amount":5,"dose_unit":"g","time_of_day":"morning","slots":["morning"],"schedule":{"type":"daily"}}""")
        val after = snap("""{"dose":"10 g","dose_amount":10,"dose_unit":"g","time_of_day":"morning","slots":["morning","night"],"schedule":{"type":"daily"}}""")
        val ts = "2026-11-03T08:00:00+08:00"
        assertEquals("Started: 5 g, daily · morning", changeWords(SupplementChangeRow(1, 1, ts, listOf("started"), null, before)))
        assertEquals(
            "Dose 5 g → 10 g · Daily · morning → Twice a day · morning + night",
            changeWords(SupplementChangeRow(2, 1, ts, listOf("dose", "schedule"), before, after)),
        )
        assertEquals("Stopped", changeWords(SupplementChangeRow(3, 1, ts, listOf("stopped"), null, after)))
        // A snapshot from before the model (no structured columns) still reads.
        val legacy = snap("""{"dose":"36 mg, 3x/week","time_of_day":"morning","every_n_days":3}""")
        assertEquals("Started: 36 mg, every 3 days · morning", changeWords(SupplementChangeRow(4, 2, ts, listOf("started"), null, legacy)))
    }
}
