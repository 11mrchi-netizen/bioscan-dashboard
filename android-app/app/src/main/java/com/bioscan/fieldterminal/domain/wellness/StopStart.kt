package com.bioscan.fieldterminal.domain.wellness

import com.bioscan.fieldterminal.data.model.NewWellnessRow
import com.bioscan.fieldterminal.domain.WallClock
import java.time.LocalDateTime

// Lab › WELLNESS › STOP-START: the stop-start technique as a guided session. Arousal is raised to about 7 of 10, stimulation
// stops, arousal falls to about 4, and it starts again; each go is a cycle. The session is a small state machine driven by
// the clock and two taps (STOP with the rating reached, RESUME), so it is paced by the person, not by a fixed timeline.
// The words spoken for each phase are in assets/wellness/stopstart-v1.json (one cue id per phase, below).

const val STOPSTART_SCRIPT_VERSION = "stopstart-v1"
const val PROGRAMME_STOPSTART = "stopstart"
const val STAGE_STOPSTART = "stopstart"

/** The cue ids of assets/wellness/stopstart-v1.json; a cue with a recording is assets/wellness/audio/<id>.mp3. */
object StopStartCue {
    const val SETTLE = "settle"
    const val BUILD = "build"
    const val STOP = "stop"
    const val LATE = "late"
    const val PAUSE_WAIT = "pause_wait"
    const val PAUSE_READY = "pause_ready"
    const val RESUME = "resume"
    const val COOLDOWN = "cooldown"
    const val DONE = "done"
    val all = listOf(SETTLE, BUILD, STOP, LATE, PAUSE_WAIT, PAUSE_READY, RESUME, COOLDOWN, DONE)
}

enum class StopStartPhase(val label: String) {
    SETTLE("SETTLE"), BUILD("BUILD"), PAUSE("PAUSE"), COOLDOWN("COOL DOWN"), DONE("DONE"),
}

data class StopStartPlan(
    val cycles: Int,
    val settleS: Int = 120,
    /** The shortest rest after a stop before RESUME is offered: arousal needs time to fall. */
    val minPauseS: Int = 45,
    val cooldownS: Int = 60,
    /** The rating to stop at. */
    val targetArousal: Int = 7,
    /** A stop at or above this rating is a late stop: it came too close to the point of no return. */
    val lateAt: Int = 9,
)

data class StopStartRun(
    val plan: StopStartPlan,
    val phase: StopStartPhase = StopStartPhase.SETTLE,
    /** Seconds on the session clock when the current phase began. */
    val phaseStartS: Double = 0.0,
    val cyclesDone: Int = 0,
    val stops: Int = 0,
    val lateStops: Int = 0,
    val peakArousal: Int? = null,
    /** The cue to speak now; set on every change of phase, cleared by [spoken]. */
    val cue: String? = StopStartCue.SETTLE,
    /** True when the last stop was a late one; the next pause then speaks the LATE cue first. */
    val lastWasLate: Boolean = false,
    /** True once "when you feel ready" has been said in this pause. */
    val pauseReadySaid: Boolean = false,
) {
    val finished: Boolean get() = phase == StopStartPhase.DONE
    fun canResume(nowS: Double): Boolean = phase == StopStartPhase.PAUSE && nowS - phaseStartS >= plan.minPauseS
    fun spoken(): StopStartRun = copy(cue = null)

    /** Moves the timed phases on: SETTLE into BUILD, COOLDOWN into DONE, and PAUSE says it is ready. */
    fun tick(nowS: Double): StopStartRun = when {
        phase == StopStartPhase.SETTLE && nowS - phaseStartS >= plan.settleS ->
            copy(phase = StopStartPhase.BUILD, phaseStartS = nowS, cue = StopStartCue.BUILD)
        phase == StopStartPhase.COOLDOWN && nowS - phaseStartS >= plan.cooldownS ->
            copy(phase = StopStartPhase.DONE, phaseStartS = nowS, cue = StopStartCue.DONE)
        phase == StopStartPhase.PAUSE && !pauseReadySaid && canResume(nowS) ->
            copy(cue = StopStartCue.PAUSE_READY, pauseReadySaid = true)
        else -> this
    }

    /** STOP in BUILD, with the rating reached (0-10). */
    fun stop(rating: Int, nowS: Double): StopStartRun {
        if (phase != StopStartPhase.BUILD) return this
        val r = rating.coerceIn(0, 10)
        val late = r >= plan.lateAt
        return copy(
            phase = StopStartPhase.PAUSE,
            phaseStartS = nowS,
            stops = stops + 1,
            lateStops = lateStops + if (late) 1 else 0,
            peakArousal = maxOf(peakArousal ?: 0, r),
            cue = if (late) StopStartCue.LATE else StopStartCue.STOP,
            lastWasLate = late,
            pauseReadySaid = false,
        )
    }

    /** RESUME after a pause; the cycle that just ended counts. After the last cycle the session cools down. */
    fun resume(nowS: Double): StopStartRun {
        if (!canResume(nowS)) return this
        val done = cyclesDone + 1
        return if (done >= plan.cycles) {
            copy(phase = StopStartPhase.COOLDOWN, phaseStartS = nowS, cyclesDone = done, cue = StopStartCue.COOLDOWN)
        } else {
            copy(phase = StopStartPhase.BUILD, phaseStartS = nowS, cyclesDone = done, cue = StopStartCue.RESUME)
        }
    }
}

/** A saved session, for choosing the next one: oldest first is not assumed, pass them newest first. */
data class StopStartHistoryItem(val completed: Boolean, val cyclesDone: Int, val lateStops: Int)

object StopStartProgress {
    const val FIRST_CYCLES = 3
    const val MAX_CYCLES = 6
    /** Sessions to string together before the stage can be called met. */
    const val TARGET_SESSIONS = 6

    /**
     * The plan for the next session, from recent ones (newest first). It starts at 3 cycles. Two completed sessions in a row
     * with no late stop add a cycle (up to 6); a session with more late stops than half its cycles takes one away (not below
     * 3); anything else repeats the last length.
     */
    fun nextPlan(recent: List<StopStartHistoryItem>): StopStartPlan {
        val last = recent.firstOrNull() ?: return StopStartPlan(FIRST_CYCLES)
        val lastCycles = last.cyclesDone.coerceIn(FIRST_CYCLES, MAX_CYCLES)
        val twoClean = recent.take(2).let { it.size == 2 && it.all { s -> s.completed && s.lateStops == 0 } }
        val struggled = last.cyclesDone > 0 && last.lateStops * 2 > last.cyclesDone
        val cycles = when {
            twoClean -> (lastCycles + 1).coerceAtMost(MAX_CYCLES)
            struggled -> (lastCycles - 1).coerceAtLeast(FIRST_CYCLES)
            else -> lastCycles
        }
        return StopStartPlan(cycles)
    }

    /** The stage is met after [TARGET_SESSIONS] completed sessions, the last three with at most one late stop between them and 5+ cycles. */
    fun stageMet(recent: List<StopStartHistoryItem>): Boolean {
        val completed = recent.filter { it.completed }
        if (completed.size < TARGET_SESSIONS) return false
        val last3 = completed.take(3)
        return last3.all { it.cyclesDone >= 5 } && last3.sumOf { it.lateStops } <= 1
    }
}

object WellnessLog {
    /** A session shorter than this when ended is not saved, unless it finished. */
    const val MIN_LOGGED_SECONDS = 180.0

    fun record(run: StopStartRun, elapsedS: Double, startedAt: LocalDateTime, felt: Int? = null): NewWellnessRow? {
        if (!run.finished && elapsedS < MIN_LOGGED_SECONDS) return null
        return NewWellnessRow(
            startedAt = WallClock.write(startedAt.withNano(0)),
            programmeKey = PROGRAMME_STOPSTART,
            stageKey = STAGE_STOPSTART,
            scriptVersion = STOPSTART_SCRIPT_VERSION,
            durationS = elapsedS.toInt().coerceIn(0, 86400),
            completed = run.finished,
            cyclesPlanned = run.plan.cycles,
            cyclesDone = run.cyclesDone,
            peakArousal = run.peakArousal,
            lateStops = run.lateStops,
            felt = felt?.coerceIn(1, 5),
        )
    }
}
