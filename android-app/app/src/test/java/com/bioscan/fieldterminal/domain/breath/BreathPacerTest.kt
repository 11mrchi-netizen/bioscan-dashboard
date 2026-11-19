package com.bioscan.fieldterminal.domain.breath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bioscan.fieldterminal.data.model.LogBreathworkRow
import com.bioscan.fieldterminal.domain.LogEntryKind
import com.bioscan.fieldterminal.domain.LogSource
import com.bioscan.fieldterminal.domain.buildLogEntries
import java.time.LocalDateTime

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

    private val start = LocalDateTime.of(2026, 11, 18, 7, 20, 5, 123_000_000)

    @Test
    fun aFinishedSessionIsSavedWithItsWallClockStart() {
        val row = BreathworkLog.record(box, box.totalSeconds, start)!!
        assertEquals("2026-11-18T07:20:05Z", row.startedAt)
        assertEquals("preset:box", row.patternKey)
        assertEquals(8, row.roundsDone)
        assertEquals(128, row.durationS)
        assertTrue(row.completed)
        assertEquals(listOf("INHALE", "HOLD_IN", "EXHALE", "HOLD_OUT"), row.steps.map { it.kind })
    }

    @Test
    fun anEarlyEndIsSavedFromThirtySecondsWithTheRoundsDone() {
        assertNull(BreathworkLog.record(box, 29.0, start))
        val row = BreathworkLog.record(box, 40.0, start)!!
        assertFalse(row.completed)
        assertEquals(2, row.roundsDone)
        assertEquals(40, row.durationS)
    }

    @Test
    fun aSessionReadsInTheLogAsBreathwork() {
        val row = LogBreathworkRow(7, "2026-11-18T07:20:05+00:00", "Box", BreathPresets.all.first().steps.map { com.bioscan.fieldterminal.data.model.BreathStepJson(it.kind.name, it.seconds) }, 8, 3, 48, completed = false)
        val e = buildLogEntries(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), breathwork = listOf(row)).single()
        assertEquals(LogSource.Breathwork, e.source)
        assertEquals(LogEntryKind.Breathwork, e.kind)
        assertEquals(LocalDateTime.of(2026, 11, 18, 7, 20, 5), e.timestamp)
        assertEquals("Breathwork Box · 0:48", e.headline)
        assertEquals("ended early · 3 of 8 rounds · 4 · 4 · 4 · 4", e.detail)
    }
}
