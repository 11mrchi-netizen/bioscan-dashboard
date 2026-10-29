package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class EventEvidenceTest {
    private fun draft(role: ExpectationRole = ExpectationRole.PRIMARY, key: String = "sleep_respiratory_rate", start: Double = 0.0, end: Double = 12.0) =
        ExpectationDraft("series", key, key, role, ExpectedDirection.DOWN, start, end)

    @Test fun `a window starts at or after the event and lasts at most 14 days`() {
        assertTrue(validWindow(0.0, 12.0))
        assertTrue(validWindow(6.0, 36.0))
        assertFalse(validWindow(-1.0, 5.0))
        assertFalse(validWindow(5.0, 5.0))
        assertFalse(validWindow(0.0, 24.0 * 14 + 1))
    }

    @Test fun `window text reads naturally`() {
        assertEquals("within 12 h", windowText(0.0, 12.0))
        assertEquals("+6 to +36 h", windowText(6.0, 36.0))
    }

    @Test fun `exactly one primary expectation is required`() {
        assertNotNull(validateExpectations(emptyList()))
        assertNull(validateExpectations(listOf(draft())))
        assertNotNull(validateExpectations(listOf(draft(), draft(key = "spo2"))))
        assertNull(validateExpectations(listOf(draft(), draft(role = ExpectationRole.WATCH, key = "spo2"))))
        assertNotNull(validateExpectations(listOf(draft(), draft(role = ExpectationRole.WATCH))))
    }

    @Test fun `tracked values must fit the scale`() {
        assertEquals(4.0, parseTrackedValue(TrackedScale.BRISTOL, "4")!!, 0.0)
        assertNull(parseTrackedValue(TrackedScale.BRISTOL, "8"))
        assertNull(parseTrackedValue(TrackedScale.BRISTOL, "3.5"))
        assertEquals(7.5, parseTrackedValue(TrackedScale.RATING, "7,5")!!, 0.0)
        assertNull(parseTrackedValue(TrackedScale.YES_NO, "2"))
        assertNull(parseTrackedValue(TrackedScale.COUNT, "-1"))
    }

    @Test fun `local stamp uses the phone clock`() {
        val s = localStamp(ZonedDateTime.of(2026, 10, 30, 22, 5, 0, 0, ZoneId.of("Europe/Rome")))
        assertEquals(LocalStamp("2026-10-30", 22 * 60 + 5), s)
    }

    @Test fun `ladder wording never claims an answer below the top`() {
        assertEquals("ANECDOTE", ladderLabel("anecdote"))
        assertEquals("READY TO TEST PROPERLY", ladderLabel("ready"))
        assertTrue(ladderHint("anecdote", 1).startsWith("2 more"))
        assertTrue(ladderHint("emerging", 9).startsWith("6 more"))
    }

    @Test fun `the packaged series file offers daily series including respiratory rate and SpO2`() {
        val text = File("../../supabase/functions/_shared/metric-registry/metric-series.json").readText()
        val ids = parseOutcomeSeries(text).map { it.id }
        assertTrue(ids.containsAll(listOf("sleep_respiratory_rate", "spo2", "hrv", "energy")))
        assertFalse("per-event series are not offered", ids.contains("meal_energy"))
    }

    @Test fun `a stored series that is a formal experiment outcome can be promoted`() {
        assertEquals("hrv", promotionTarget("series", "hrv", "HRV").metric)
        assertEquals("sleep_hours", promotionTarget("series", "sleep_hours", "Sleep").metric)
    }

    @Test fun `outcomes the formal experiments cannot compare are explained, not offered`() {
        val resp = promotionTarget("series", "sleep_respiratory_rate", "Respiratory rate")
        assertNull(resp.metric)
        assertTrue(resp.reason!!.contains("Respiratory rate"))
        val tracked = promotionTarget("tracked", "some-id", "Stool consistency")
        assertNull(tracked.metric)
        assertNotNull(tracked.reason)
    }
}
