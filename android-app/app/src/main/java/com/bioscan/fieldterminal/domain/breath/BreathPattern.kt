package com.bioscan.fieldterminal.domain.breath

import kotlin.math.roundToInt

// Breathwork (Lab › TESTS › BREATHWORK): a breathing pattern is a list of steps (breathe in, hold, breathe out, hold
// empty), each a number of seconds, repeated for a number of rounds. The presets are the common ones; any other
// combination is built by hand and kept on the phone (data/BreathworkStore.kt). Nothing is logged yet: the page only
// paces the breath.

enum class BreathStepKind(val label: String, val cue: String) {
    INHALE("IN", "Breathe in"),
    HOLD_IN("HOLD", "Hold"),
    EXHALE("OUT", "Breathe out"),
    HOLD_OUT("HOLD EMPTY", "Hold, empty"),
}

data class BreathStep(val kind: BreathStepKind, val seconds: Double)

data class BreathPattern(
    /** "preset:<key>" for a preset, "custom:<n>" for one built by hand. */
    val id: String,
    val name: String,
    val steps: List<BreathStep>,
    val rounds: Int,
    /** One line on what it is for; empty for a custom pattern. */
    val purpose: String = "",
) {
    val isCustom: Boolean get() = id.startsWith("custom:")
    val cycleSeconds: Double get() = steps.sumOf { it.seconds }
    val totalSeconds: Double get() = cycleSeconds * rounds
    val isRunnable: Boolean get() = steps.isNotEmpty() && cycleSeconds > 0 && rounds > 0

    /** "4 · 7 · 8" -- the steps' seconds in order. */
    val rhythm: String get() = steps.joinToString(" · ") { formatSeconds(it.seconds) }
}

object BreathPresets {
    private fun s(kind: BreathStepKind, seconds: Double) = BreathStep(kind, seconds)
    private val IN = BreathStepKind.INHALE
    private val HOLD = BreathStepKind.HOLD_IN
    private val OUT = BreathStepKind.EXHALE
    private val EMPTY = BreathStepKind.HOLD_OUT

    val all: List<BreathPattern> = listOf(
        BreathPattern("preset:box", "Box", listOf(s(IN, 4.0), s(HOLD, 4.0), s(OUT, 4.0), s(EMPTY, 4.0)), rounds = 8,
            purpose = "Even four-count square. Steadies attention before a hard task."),
        BreathPattern("preset:478", "4-7-8", listOf(s(IN, 4.0), s(HOLD, 7.0), s(OUT, 8.0)), rounds = 4,
            purpose = "Long hold and longer exhale. For winding down and falling asleep."),
        BreathPattern("preset:coherent", "Coherent", listOf(s(IN, 5.5), s(OUT, 5.5)), rounds = 27,
            purpose = "About 5.5 breaths a minute, in and out the same length. Five minutes of calm."),
        BreathPattern("preset:sigh", "Physiological sigh", listOf(s(IN, 2.0), s(IN, 1.0), s(OUT, 6.0)), rounds = 5,
            purpose = "A full breath in, a short top-up, then a long slow exhale. The quickest way down from stress."),
        BreathPattern("preset:exhale", "Extended exhale", listOf(s(IN, 4.0), s(OUT, 8.0)), rounds = 15,
            purpose = "Breathe out twice as long as in. Settles the heart rate after training."),
        BreathPattern("preset:triangle", "Triangle", listOf(s(IN, 4.0), s(HOLD, 4.0), s(OUT, 4.0)), rounds = 10,
            purpose = "Box without the empty hold. Gentler if holding empty feels hard."),
    )
}

/** Where a session is, [elapsedSeconds] after it started. */
data class BreathMoment(
    val round: Int, // 1-based
    val stepIndex: Int,
    val step: BreathStep,
    /** 0..1 through the current step. */
    val stepProgress: Double,
    val secondsLeftInStep: Double,
    /** How full the lungs are drawn, 0 (empty) .. 1 (full), for the guide circle. */
    val fill: Double,
    val finished: Boolean,
)

object BreathPacer {
    /**
     * The guide's fill at the end of each step. A breath in fills to full and a breath out empties, except when the next
     * step goes the same way (the sigh's top-up): then it stops 70 % of the way, so the second one still has room to move.
     * A hold keeps the fill it starts with. The cycle starts empty.
     */
    fun targets(steps: List<BreathStep>): DoubleArray {
        val out = DoubleArray(steps.size)
        var fill = 0.0
        for ((i, step) in steps.withIndex()) {
            val next = steps.getOrNull(i + 1)?.kind
            fill = when (step.kind) {
                BreathStepKind.INHALE -> if (next == BreathStepKind.INHALE) fill + (1 - fill) * 0.7 else 1.0
                BreathStepKind.EXHALE -> if (next == BreathStepKind.EXHALE) fill * 0.3 else 0.0
                BreathStepKind.HOLD_IN, BreathStepKind.HOLD_OUT -> fill
            }
            out[i] = fill
        }
        return out
    }

    fun momentAt(pattern: BreathPattern, elapsedSeconds: Double): BreathMoment {
        require(pattern.isRunnable) { "pattern has no time in it" }
        val steps = pattern.steps
        val targets = targets(steps)
        val cycle = pattern.cycleSeconds
        val t = elapsedSeconds.coerceAtLeast(0.0)
        if (t >= pattern.totalSeconds) {
            val last = steps.lastIndex
            return BreathMoment(pattern.rounds, last, steps[last], 1.0, 0.0, targets[last], finished = true)
        }
        val round = (t / cycle).toInt().coerceAtMost(pattern.rounds - 1)
        var inCycle = t - round * cycle
        var index = 0
        while (index < steps.lastIndex && (steps[index].seconds <= 0 || inCycle >= steps[index].seconds)) {
            inCycle -= steps[index].seconds
            index++
        }
        val step = steps[index]
        val progress = if (step.seconds <= 0) 1.0 else (inCycle / step.seconds).coerceIn(0.0, 1.0)
        // The cycle wraps from the last step's fill to the first step's start (empty).
        val from = if (index == 0) 0.0 else targets[index - 1]
        val to = targets[index]
        val fill = from + (to - from) * easeInOut(progress)
        return BreathMoment(round + 1, index, step, progress, (step.seconds - inCycle).coerceAtLeast(0.0), fill, finished = false)
    }

    // A breath speeds up and slows down at its ends; the guide moves the same way.
    private fun easeInOut(x: Double): Double = x * x * (3 - 2 * x)
}

fun formatSeconds(seconds: Double): String {
    val tenths = (seconds * 10).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "%d.%d".format(tenths / 10, tenths % 10)
}

/** "5:30" for a session length. */
fun formatDuration(seconds: Double): String {
    val total = seconds.roundToInt()
    return "%d:%02d".format(total / 60, total % 60)
}

// Custom patterns on disk: one line each, "id|name|rounds|IN:4,HOLD_IN:7,EXHALE:8". The name has its separators removed
// when it is saved, so a line always splits the same way.
object BreathPatternCodec {
    fun encode(p: BreathPattern): String {
        val name = p.name.replace("|", " ").replace("\n", " ").trim()
        val steps = p.steps.joinToString(",") { "${it.kind.name}:${formatSeconds(it.seconds)}" }
        return "${p.id}|$name|${p.rounds}|$steps"
    }

    fun decode(line: String): BreathPattern? {
        val parts = line.split("|")
        if (parts.size != 4) return null
        val rounds = parts[2].toIntOrNull() ?: return null
        val steps = if (parts[3].isBlank()) emptyList() else parts[3].split(",").map { token ->
            val kind = runCatching { BreathStepKind.valueOf(token.substringBefore(":")) }.getOrNull() ?: return null
            val secs = token.substringAfter(":").toDoubleOrNull() ?: return null
            BreathStep(kind, secs)
        }
        return BreathPattern(parts[0], parts[1], steps, rounds)
    }
}
