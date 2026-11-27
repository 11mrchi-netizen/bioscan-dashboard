package com.bioscan.fieldterminal.ui.screens.detail

import com.bioscan.fieldterminal.domain.score.InputDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPatternTest {

    @Test
    fun theSavedMeasureLeadsWhileItIsStillOffered() {
        val keys = listOf("duration", "deep", "light", "rem")
        assertEquals("rem", focusKey("rem", keys))
        assertEquals("duration", focusKey(null, keys))
        assertEquals("duration", focusKey("breathing", keys))
        assertNull(focusKey("rem", emptyList()))
    }

    @Test
    fun supportIsCappedAtFive() {
        assertEquals(5, focusSupport((1..8).toList()).size)
        assertEquals(listOf(1, 2), focusSupport(listOf(1, 2)))
    }

    @Test
    fun theVerdictLeadsWithTheWordAndNamesTheNormal() {
        assertEquals(
            "BELOW NORMAL · normal 7 h 10 to 8 h 05 · -40 min vs the week before",
            focusVerdictLine(InputDirection.BELOW, "7 h 10 to 8 h 05", "-40 min vs the week before"),
        )
        assertEquals("IN YOUR NORMAL", focusVerdictLine(InputDirection.NORMAL, null, null))
        assertEquals("NO NORMAL YET", focusVerdictLine(null, null, null))
    }

    @Test
    fun aLongVerdictIsCutAtTheLimitWithAnEllipsis() {
        val line = focusVerdictLine(InputDirection.ABOVE, "x".repeat(120), null)
        assertTrue(line.length <= FOCUS_VERDICT_MAX)
        assertTrue(line.endsWith("…"))
        assertTrue(line.startsWith("ABOVE NORMAL"))
    }
}
