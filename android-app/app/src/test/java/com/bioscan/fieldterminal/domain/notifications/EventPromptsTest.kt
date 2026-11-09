package com.bioscan.fieldterminal.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EventPromptsTest {
    private val now = Instant.parse("2026-10-29T09:00:00Z")
    private fun ev(id: String, hoursAgo: Long, feeling: Int? = null, iv: String = "i1") =
        PromptEvent(id, iv, now.minusSeconds(hoursAgo * 3600), feeling)

    @Test fun `last night's unrated event asks for a rating`() {
        assertTrue(eventRatingNeeded(listOf(ev("a", 11)), now))
    }

    @Test fun `rated, too fresh or too old events do not`() {
        assertFalse(eventRatingNeeded(listOf(ev("a", 11, feeling = 6)), now))
        assertFalse(eventRatingNeeded(listOf(ev("b", 1)), now))
        assertFalse(eventRatingNeeded(listOf(ev("c", 40)), now))
    }

    @Test fun `a draw is wanted only for draw-enabled interventions not yet drawn today`() {
        assertEquals(listOf("b"), interventionsToDraw(listOf("a", "b"), setOf("a")))
        assertFalse(eventDrawNeeded(listOf("a"), setOf("a")))
        assertFalse(eventDrawNeeded(emptyList(), emptySet()))
    }

    @Test fun `a follow-up is due once the window closed without a value, and not when one was logged inside it`() {
        val e = ev("a", 30)
        val w = PromptWindow("i1", "stool", 6.0, 24.0) // closed 6 h ago
        assertEquals(1, followUpsDue(listOf(e), listOf(w), emptyList(), now).size)
        val inside = listOf("stool" to e.occurredAt.plusSeconds(10 * 3600))
        assertTrue(followUpsDue(listOf(e), listOf(w), inside, now).isEmpty())
        val otherOutcome = listOf("headache" to e.occurredAt.plusSeconds(10 * 3600))
        assertEquals(1, followUpsDue(listOf(e), listOf(w), otherOutcome, now).size)
    }

    @Test fun `an open window or one closed long ago is not a follow-up`() {
        val w = PromptWindow("i1", "stool", 6.0, 24.0)
        assertTrue(followUpsDue(listOf(ev("a", 10)), listOf(w), emptyList(), now).isEmpty()) // still open
        assertTrue(followUpsDue(listOf(ev("b", 80)), listOf(w), emptyList(), now).isEmpty()) // closed 56 h ago
    }
}
