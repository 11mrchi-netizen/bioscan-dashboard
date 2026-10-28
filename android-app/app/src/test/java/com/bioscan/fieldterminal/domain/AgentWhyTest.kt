package com.bioscan.fieldterminal.domain

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWhyTest {

    @Test
    fun theQuestionNamesTheCardAndItsWords() {
        val why = WhyContext("THIS MORNING", words = listOf("HRV" to "BELOW EXPECTED", "RESTING HR" to "AS EXPECTED"))
        assertEquals("Why does THIS MORNING read HRV BELOW EXPECTED, RESTING HR AS EXPECTED?", why.question())
        assertEquals("Why does TODAY'S RECOVERY read MIXED SIGNALS?", WhyContext("TODAY'S RECOVERY", words = listOf("" to "MIXED SIGNALS")).question())
        assertEquals("What does READY FOR show, and what does it rest on?", WhyContext("READY FOR").question())
    }

    @Test
    fun theContextCarriesOnlyWhatTheCardShows() {
        val json = WhyContext(
            card = "THIS MORNING",
            words = listOf("HRV" to "BELOW EXPECTED"),
            shown = mapOf("HRV" to "38 ms against 44 to 61 ms"),
            series = "hrv",
        ).toJson("2026-10-28")
        assertEquals("THIS MORNING", json["card"]!!.jsonPrimitive.content)
        assertEquals("2026-10-28", json["date"]!!.jsonPrimitive.content)
        assertEquals("BELOW EXPECTED", json["words"]!!.jsonObject["HRV"]!!.jsonPrimitive.content)
        assertEquals("hrv", json["series"]!!.jsonPrimitive.content)
        assertFalse("no item id was given", "item_id" in json)
    }

    @Test
    fun serverErrorsReadAsSentences() {
        assertTrue(agentErrorMessage("assistant_off", null).contains("Setup"))
        assertEquals("Answered 40 today.", agentErrorMessage("daily_cap", "Answered 40 today."))
        assertTrue(agentErrorMessage(null, null).contains("Try again"))
    }
}
