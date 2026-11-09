package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class InterventionDrawTest {
    @Test fun `a seeded coin is reproducible and roughly fair`() {
        val a = Random(7)
        val b = Random(7)
        repeat(20) { assertEquals(drawCoin(a), drawCoin(b)) }
        val r = Random(42)
        val dos = (1..2000).count { drawCoin(r) == DrawResult.DO }
        assertTrue("got $dos of 2000", dos in 900..1100)
    }

    @Test fun `the state follows the stored draw and whether the event was logged`() {
        assertEquals(DrawState.NOT_DRAWN, drawState(null, null))
        assertEquals(DrawState.DO_PENDING, drawState("do", null))
        assertEquals(DrawState.DO_LOGGED, drawState("do", "e1"))
        assertEquals(DrawState.SKIP, drawState("skip", null))
    }

    @Test fun `keys round trip`() {
        assertEquals(DrawResult.DO, DrawResult.fromKey("do"))
        assertEquals(DrawResult.SKIP, DrawResult.fromKey("skip"))
        assertEquals(null, DrawResult.fromKey("maybe"))
    }
}
