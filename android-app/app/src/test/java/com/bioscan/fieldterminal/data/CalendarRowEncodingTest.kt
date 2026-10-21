package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.NewCalendarEventRow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRowEncodingTest {
    // supabase-kt encodes without defaults; a bulk upsert whose rows omit is_cancelled sends null for it, which the
    // not-null column rejects for the whole batch.
    @Test
    fun aRowAlwaysCarriesIsCancelled() {
        val json = Json.encodeToString(NewCalendarEventRow(calendarId = "primary", eventId = "e1"))
        assertTrue(json, json.contains("\"is_cancelled\":false"))
    }
}
