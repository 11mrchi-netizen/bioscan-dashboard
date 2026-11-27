package com.bioscan.fieldterminal.domain.wellness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class StopStartTest {
    private val plan = StopStartPlan(cycles = 2, settleS = 10, minPauseS = 5, cooldownS = 4)

    @Test
    fun settleMovesToBuildAfterItsTime() {
        var run = StopStartRun(plan)
        assertEquals(StopStartPhase.SETTLE, run.phase)
        run = run.tick(9.0)
        assertEquals(StopStartPhase.SETTLE, run.phase)
        run = run.tick(10.0)
        assertEquals(StopStartPhase.BUILD, run.phase)
        assertEquals(StopStartCue.BUILD, run.cue)
    }

    @Test
    fun stopOutsideBuildDoesNothing() {
        val run = StopStartRun(plan)
        assertEquals(run, run.stop(7, 1.0))
    }

    @Test
    fun aCycleIsStopThenResumeAndResumeWaitsForTheMinimumPause() {
        var run = StopStartRun(plan).tick(10.0).stop(7, 20.0)
        assertEquals(StopStartPhase.PAUSE, run.phase)
        assertEquals(StopStartCue.STOP, run.cue)
        assertEquals(7, run.peakArousal)
        assertFalse(run.canResume(24.0))
        assertEquals(run, run.resume(24.0))
        assertTrue(run.canResume(25.0))
        run = run.resume(25.0)
        assertEquals(StopStartPhase.BUILD, run.phase)
        assertEquals(1, run.cyclesDone)
        assertEquals(StopStartCue.RESUME, run.cue)
    }

    @Test
    fun readyIsSaidOnceDuringAPause() {
        var run = StopStartRun(plan).tick(10.0).stop(7, 20.0).spoken()
        run = run.tick(25.0)
        assertEquals(StopStartCue.PAUSE_READY, run.cue)
        run = run.spoken().tick(26.0)
        assertNull(run.cue)
    }

    @Test
    fun aStopAtTheLateRatingIsCountedAndSpokenAboutDifferently() {
        val run = StopStartRun(plan).tick(10.0).stop(9, 20.0)
        assertEquals(1, run.lateStops)
        assertEquals(StopStartCue.LATE, run.cue)
        assertEquals(9, run.peakArousal)
        assertEquals(0, StopStartRun(plan).tick(10.0).stop(8, 20.0).lateStops)
    }

    @Test
    fun theLastCycleLeadsToCooldownThenDone() {
        var run = StopStartRun(plan).tick(10.0).stop(7, 20.0).resume(25.0).stop(7, 40.0).resume(45.0)
        assertEquals(StopStartPhase.COOLDOWN, run.phase)
        assertEquals(2, run.cyclesDone)
        run = run.tick(48.0)
        assertEquals(StopStartPhase.COOLDOWN, run.phase)
        run = run.tick(49.0)
        assertTrue(run.finished)
        assertEquals(StopStartCue.DONE, run.cue)
    }

    @Test
    fun nextPlanStartsAtThreeAndGrowsAfterTwoCleanSessions() {
        assertEquals(3, StopStartProgress.nextPlan(emptyList()).cycles)
        val clean = StopStartHistoryItem(completed = true, cyclesDone = 3, lateStops = 0)
        assertEquals(3, StopStartProgress.nextPlan(listOf(clean)).cycles)
        assertEquals(4, StopStartProgress.nextPlan(listOf(clean, clean)).cycles)
        val six = StopStartHistoryItem(true, 6, 0)
        assertEquals(6, StopStartProgress.nextPlan(listOf(six, six)).cycles)
    }

    @Test
    fun nextPlanEasesBackWhenMostStopsWereLate() {
        val hard = StopStartHistoryItem(completed = true, cyclesDone = 5, lateStops = 3)
        assertEquals(4, StopStartProgress.nextPlan(listOf(hard)).cycles)
        val floor = StopStartHistoryItem(true, 3, 2)
        assertEquals(3, StopStartProgress.nextPlan(listOf(floor)).cycles)
    }

    @Test
    fun theStageIsMetAfterSixSessionsWithTheLastThreeLongAndMostlyOnTime() {
        val good = StopStartHistoryItem(true, 5, 0)
        assertFalse(StopStartProgress.stageMet(List(5) { good }))
        assertTrue(StopStartProgress.stageMet(List(6) { good }))
        val late = StopStartHistoryItem(true, 5, 1)
        assertFalse(StopStartProgress.stageMet(listOf(late, late, good, good, good, good)))
    }

    @Test
    fun aShortEndedSessionIsNotSavedButAFinishedOneIs() {
        val start = LocalDateTime.of(2026, 11, 27, 21, 5, 0)
        val run = StopStartRun(plan).tick(10.0)
        assertNull(WellnessLog.record(run, 100.0, start))
        val ended = WellnessLog.record(run, 200.0, start)!!
        assertFalse(ended.completed)
        assertEquals(2, ended.cyclesPlanned)
        val done = StopStartRun(plan, phase = StopStartPhase.DONE, cyclesDone = 2, peakArousal = 8, lateStops = 1)
        val row = WellnessLog.record(done, 30.0, start, felt = 4)!!
        assertTrue(row.completed)
        assertEquals("2026-11-27T21:05Z", row.startedAt)
        assertEquals(8, row.peakArousal)
        assertEquals(4, row.felt)
        assertEquals(STOPSTART_SCRIPT_VERSION, row.scriptVersion)
    }

    @Test
    fun theScriptFileHasACueForEveryId() {
        val text = File("src/main/assets/wellness/stopstart-v1.json").readText()
        assertTrue(text.contains("\"version\": \"$STOPSTART_SCRIPT_VERSION\""))
        StopStartCue.all.forEach { id -> assertTrue("missing cue $id", text.contains("\"$id\":")) }
    }
}
