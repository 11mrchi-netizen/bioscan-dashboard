package com.bioscan.fieldterminal.domain.breath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreathPacerTest {
    private val box = BreathPresets.all.first { it.id == "preset:box" }
    private val sigh = BreathPresets.all.first { it.id == "preset:sigh" }

    @Test
    fun boxWalksThroughItsFourSteps() {
        assertEquals(16.0, box.cycleSeconds, 1e-9)
        assertEquals(BreathStepKind.INHALE, BreathPacer.momentAt(box, 1.0).step.kind)
        assertEquals(BreathStepKind.HOLD_IN, BreathPacer.momentAt(box, 5.0).step.kind)
        assertEquals(BreathStepKind.EXHALE, BreathPacer.momentAt(box, 9.0).step.kind)
        val m = BreathPacer.momentAt(box, 13.0)
        assertEquals(BreathStepKind.HOLD_OUT, m.step.kind)
        assertEquals(3.0, m.secondsLeftInStep, 1e-9)
        assertEquals(1, m.round)
        assertEquals(2, BreathPacer.momentAt(box, 17.0).round)
    }

    @Test
    fun fillRisesOnTheBreathInHoldsAndFallsOnTheBreathOut() {
        assertEquals(0.0, BreathPacer.momentAt(box, 0.0).fill, 1e-9)
        assertEquals(0.5, BreathPacer.momentAt(box, 2.0).fill, 1e-9)
        assertEquals(1.0, BreathPacer.momentAt(box, 6.0).fill, 1e-9)
        assertEquals(0.5, BreathPacer.momentAt(box, 10.0).fill, 1e-9)
        assertEquals(0.0, BreathPacer.momentAt(box, 14.0).fill, 1e-9)
    }

    @Test
    fun theSighsTopUpStillHasRoomToGrow() {
        val t = BreathPacer.targets(sigh.steps)
        assertEquals(0.7, t[0], 1e-9)
        assertEquals(1.0, t[1], 1e-9)
        assertEquals(0.0, t[2], 1e-9)
    }

    @Test
    fun theSessionEndsAfterItsRounds() {
        assertFalse(BreathPacer.momentAt(box, box.totalSeconds - 0.01).finished)
        val end = BreathPacer.momentAt(box, box.totalSeconds)
        assertTrue(end.finished)
        assertEquals(box.rounds, end.round)
    }

    @Test
    fun customPatternsSurviveTheRoundTrip() {
        val p = BreathPattern("custom:3", "Mine | evening", listOf(BreathStep(BreathStepKind.INHALE, 4.5), BreathStep(BreathStepKind.EXHALE, 7.0)), 12)
        val back = BreathPatternCodec.decode(BreathPatternCodec.encode(p))!!
        assertEquals(p.copy(name = "Mine   evening"), back)
        assertEquals("4.5 · 7", back.rhythm)
        assertNull(BreathPatternCodec.decode("broken"))
    }
}
