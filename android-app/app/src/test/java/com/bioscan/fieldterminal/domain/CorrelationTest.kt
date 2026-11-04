package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CorrelationTest {
    private val d0 = LocalDate.of(2026, 9, 1)

    @Test
    fun aLaggedLinkIsFoundOnlyAtItsLag() {
        val x = (0 until 40).associate { d0.plusDays(it.toLong()) to (it % 5).toDouble() }
        // y the day after is 10 - 2x, plus a little noise.
        val y = (0 until 40).associate { d0.plusDays(it.toLong() + 1) to 10 - 2 * (it % 5) + (if (it % 3 == 0) 0.3 else -0.2) }
        val c = correlate(x, y, 1)!!
        assertEquals(40, c.n)
        assertTrue(c.r < -0.95 && c.clear)
        assertEquals(-2.0, c.slope, 0.1)
        assertTrue(correlationWords(c).startsWith("A strong negative link"))
        assertNull(correlate(x.entries.take(5).associate { it.key to it.value }, y, 1))
    }
}
