@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class)
package com.bioscan.fieldterminal.domain.score

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

// Illness and CHARGE (docs/scoring-system/README.md, "Caps"). An open illness no longer caps CHARGE at a flat 40: the
// ceiling follows how much the illness limits the person, from the TRAINING answer of their health check-ins
// (0 full participation, 1 reduced, 2 training modified or stopped, 3 daily life affected) and a logged fever. Without
// a fresh check-in the last answer holds for two days, then eases one step every two days.
//
// Beside the ceiling, the body's own signals (resting HR up, HRV down, breathing up, each against the person's own
// 60-day baseline) are read every morning:
//   * while an illness is open, as an agreement line: do the numbers agree with the check-in;
//   * otherwise, as an early heads-up when two or more move the illness way together, softened when the evening
//     check-in or the session log offers a plainer explanation (drinks, a late or long session, a stressful day).
// The pattern is the one the wearable literature describes (Mishra 2020; Radin 2020); the thresholds are NOOP's
// (z >= 2 per signal, at least two together). It is never a diagnosis and never names a condition.

/** One health check-in day for the open illness: the TRAINING answer and the highest temperature logged. */
@Serializable
data class IllnessCheckIn(val date: LocalDate, val impact: Int?, val temperatureC: Double?)

/** An open illness episode: what it is called, when it started, and its check-ins. */
@Serializable
data class IllnessEpisode(val label: String, val start: LocalDate, val checkIns: List<IllnessCheckIn>)

const val FEVER_C = 38.0
/** Days a check-in holds before the ceiling eases one step, and every step after. */
const val ILLNESS_HOLD_DAYS = 2

/** The ceiling per TRAINING answer; 0 (full participation) caps nothing. */
val IMPACT_CEILINGS: Map<Int, Double?> = mapOf(0 to null, 1 to 70.0, 2 to 55.0, 3 to CAP_CEILING)
/** No check-in about the illness yet: the middle of the scale until one says otherwise. */
const val UNKNOWN_IMPACT_CEILING = 55.0

val IMPACT_WORDS = listOf("full training", "reduced training", "training modified", "daily life affected")

/** How the open illness stands on one morning. */
@Serializable
data class IllnessState(
    val label: String,
    /** Day of the episode, day 1 being its start. */
    val day: Int,
    /** The level the ceiling uses (0-3) after easing; null when no check-in has answered yet. */
    val level: Int?,
    val fever: Boolean,
    val lastCheckIn: LocalDate?,
    val eased: Boolean,
    val ceiling: Double?,
) {
    /** "Cold · day 3 · training modified (check-in 2 Nov)". */
    val reason: String get() {
        val why = when {
            lastCheckIn == null -> "no check-in yet"
            fever -> "fever logged"
            else -> IMPACT_WORDS[level ?: 0]
        }
        val asOf = lastCheckIn?.let { " (check-in ${it.dayOfMonth} ${it.month.name.take(3).lowercase()}${if (eased) ", eased since" else ""})" } ?: ""
        return "$label · day $day · $why$asOf"
    }
    val cap: ChargeCap? get() = ceiling?.let { ChargeCap(reason, it) }
}

/** The open illness on [morning], or null before it started. */
fun illnessState(episode: IllnessEpisode, morning: LocalDate): IllnessState? {
    if (morning.isBefore(episode.start)) return null
    val day = ChronoUnit.DAYS.between(episode.start, morning).toInt() + 1
    val last = episode.checkIns.filter { !it.date.isAfter(morning) && (it.impact != null || it.temperatureC != null) }.maxByOrNull { it.date }
    if (last == null) {
        return IllnessState(episode.label, day, null, fever = false, lastCheckIn = null, eased = false, ceiling = UNKNOWN_IMPACT_CEILING)
    }
    val fever = (last.temperatureC ?: 0.0) >= FEVER_C
    val answered = if (fever) 3 else last.impact ?: 2
    val gap = ChronoUnit.DAYS.between(last.date, morning).toInt()
    val steps = ((gap - 1).coerceAtLeast(0)) / ILLNESS_HOLD_DAYS
    val level = (answered - steps).coerceAtLeast(0)
    return IllnessState(episode.label, day, level, fever && steps == 0, last.date, eased = steps > 0, ceiling = IMPACT_CEILINGS[level])
}

// ---- the body's signals ----

/** One signal's move against the person's own baseline, oriented so a positive z is the illness way. */
@Serializable
data class BodySignal(val key: String, val label: String, val z: Double, val words: String)

const val SIGNAL_FIRES_Z = 2.0
const val HEADS_UP_SIGNALS = 2

private data class SignalSpec(val key: String, val label: String, val log: Boolean, val minSd: Double, val illnessUp: Boolean)

private val SIGNALS = listOf(
    SignalSpec(ScoreKeys.RHR, "resting HR", log = false, minSd = 1.0, illnessUp = true),
    SignalSpec(ScoreKeys.HRV, "HRV", log = true, minSd = 0.05, illnessUp = false),
    SignalSpec(ScoreKeys.SLEEP_BREATHING, "breathing", log = false, minSd = 0.3, illnessUp = true),
)

/** Every signal with a value and a baseline on [morning], strongest illness-ward first. */
fun bodySignals(series: Map<String, Series>, morning: LocalDate): List<BodySignal> = SIGNALS.mapNotNull { s ->
    val values = series[s.key] ?: return@mapNotNull null
    val v = values[morning] ?: return@mapNotNull null
    if (s.log && v <= 0) return@mapNotNull null
    val (med, sd) = baselineOf(values, morning, 60, 14, s.log, s.minSd) ?: return@mapNotNull null
    val x = if (s.log) ln(v) else v
    val z = (x - med) / sd * if (s.illnessUp) 1 else -1
    val words = if (s.log) {
        val pct = ((exp(x - med) - 1) * 100).roundToInt()
        "${s.label} ${if (pct >= 0) "+" else "−"}${kotlin.math.abs(pct)} %"
    } else {
        val d = x - med
        val unit = if (s.key == ScoreKeys.RHR) " bpm" else "/min"
        val shown = if (s.key == ScoreKeys.RHR) d.roundToInt().toString() else "%.1f".format(kotlin.math.abs(d))
        "${s.label} ${if (d >= 0) "+" else "−"}${shown.removePrefix("-")}$unit"
    }
    BodySignal(s.key, s.label, z, words)
}.sortedByDescending { it.z }

/** What else the day before offers as an explanation for the signals. */
data class Confounders(val drinks: Int? = null, val lateOrLongSession: Boolean = false, val stressfulDay: Boolean = false) {
    val words: List<String> get() = listOfNotNull(
        drinks?.takeIf { it > 0 }?.let { if (it == 1) "1 drink" else "$it drinks" },
        "a late or long session".takeIf { lateOrLongSession },
        "a stressful day".takeIf { stressfulDay },
    )
}

/** A session yesterday that ended at 20:00 or later, or lasted 90 minutes or more. */
const val LATE_SESSION_HOUR = 20
const val LONG_SESSION_MIN = 90
const val STRESSFUL_DAY = 4

@Serializable
enum class SignalsVerdict { AGREE, NORMAL, HEADS_UP, EXPLAINED, QUIET }

@Serializable
data class SignalsRead(val verdict: SignalsVerdict, val fired: List<BodySignal>, val explainedBy: List<String>, val line: String)

private fun joinWords(w: List<String>): String = when (w.size) {
    0 -> ""
    1 -> w[0]
    else -> w.dropLast(1).joinToString(", ") + " and " + w.last()
}

/**
 * The morning's read of the body's signals. With an illness open: whether the numbers agree with it. Without one: an
 * early heads-up when [HEADS_UP_SIGNALS] or more fire together, explained away (not raised) when the day before offers a
 * plainer cause. Null when there is nothing to say (no illness and fewer than two signals firing, or no baselines).
 */
fun readSignals(signals: List<BodySignal>, illness: IllnessState?, confounders: Confounders): SignalsRead? {
    val fired = signals.filter { it.z >= SIGNAL_FIRES_Z }
    val firedWords = joinWords(fired.map { it.words })
    if (illness != null) {
        if (signals.isEmpty()) return null
        return if (fired.isNotEmpty()) {
            SignalsRead(SignalsVerdict.AGREE, fired, emptyList(), "You logged feeling unwell, and your numbers agree: $firedWords.")
        } else {
            SignalsRead(SignalsVerdict.NORMAL, fired, emptyList(),
                "You logged feeling unwell; resting HR, HRV and breathing look like your usual. The ceiling follows your check-in.")
        }
    }
    if (fired.size < HEADS_UP_SIGNALS) return null
    val explained = confounders.words
    return if (explained.isNotEmpty()) {
        SignalsRead(SignalsVerdict.EXPLAINED, fired, explained,
            "Some signals are up ($firedWords), but you logged ${joinWords(explained)}: likely that, not illness.")
    } else {
        SignalsRead(SignalsVerdict.HEADS_UP, fired, emptyList(),
            "Heads-up: $firedWords together, with nothing logged to explain it. Worth an easier day; if you feel off, add it in a health check-in. An estimate, not a diagnosis.")
    }
}
