package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.tour.tourPerson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The web desk reads today_views.view in this shape (the phone's own ScoresView serialization): keep the two in step.
class TodayViewPublisherTest {
    @Test fun `the view is written with the day and the scores, and a big one loses only its series`() {
        val view = tourPerson()
        val (json, trimmed) = todayViewSnapshot(view)
        val size = json.toString().length
        val full = finiteOnly(Json { allowSpecialFloatingPointValues = true; encodeDefaults = true }.encodeToJsonElement(ScoresView.serializer(), view)).toString().length
        println("today_views size for the tour person: full=$full chars, written=$size chars, series dropped=$trimmed, series keys=${view.series.size}")
        assertEquals(view.day.toString(), (json["day"] as JsonPrimitive).content)
        assertTrue("scores" in json && "chargeStrip" in json && "series" in json)
        assertEquals(full > TODAY_VIEW_MAX_BYTES, trimmed)
        if (trimmed) assertEquals(0, (json["series"] as JsonObject).size)
    }

    @Test fun `NaN and infinity become null and strings are left alone`() {
        val e = Json.parseToJsonElement("""{"a":[1,2],"b":"NaN"}""") as JsonObject
        assertEquals(e, finiteOnly(e))
        val bad = JsonObject(mapOf("n" to JsonPrimitive(Double.NaN), "l" to JsonArray(listOf(JsonPrimitive(Double.POSITIVE_INFINITY), JsonPrimitive(1)))))
        val ok = finiteOnly(bad) as JsonObject
        assertEquals(JsonNull, ok["n"])
        assertEquals(JsonNull, (ok["l"] as JsonArray)[0])
    }
}
