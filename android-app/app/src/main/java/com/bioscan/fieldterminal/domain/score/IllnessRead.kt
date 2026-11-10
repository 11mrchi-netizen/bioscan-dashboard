@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class)
package com.bioscan.fieldterminal.domain.score

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

// Illness and CHARGE (docs/scoring-system/README.md, "Caps"; docs/health-concerns/README.md, "Illness gravity"). An open
// illness holds CHARGE down by how grave it is, not by being open: a stuffed nose is not the flu. Each check-in is graded
//   NONE      nothing left (severity 0, full training, no fever): no ceiling;
//   MILD      above the neck only (nose, throat, sneezing, a headache), up to 4/10, training as usual or reduced: 85;
//   MODERATE  below the neck (chest, stomach, aches, fatigue), or 4-6/10, or training modified: 65;
//   SEVERE    fever (38 °C), 7/10 or more, below the neck at 5/10 or more, or daily life affected: the 40 cap.
// The "neck check" (above versus below the neck) is the sports-medicine rule for training with a cold. Without a fresh
// check-in the last grade holds for two days, then eases one step every two days. Small things logged through the symptom
// check belong to no illness and never cap anything.
//
// Beside the ceiling, the body's own signals (resting HR up, HRV down, breathing up, each against the person's own
// 60-day baseline) are read every morning:
//   * while an illness is open, as an agreement line: do the numbers agree with the check-in;
//   * otherwise, as an early heads-up when two or more move the illness way together, softened when the evening
//     check-in or the session log offers a plainer explanation (drinks, a late or long session, a stressful day).
// The pattern is the one the wearable literature describes (Mishra 2020; Radin 2020); the thresholds are NOOP's
// (z >= 2 per signal, at least two together). It is never a diagnosis and never names a condition.

/**
 * One health check-in day for the open illness: the TRAINING answer, the highest temperature, the highest severity and
 * the symptoms logged that day.
 */
@Serializable
data class IllnessCheckIn(
    val date: LocalDate,
    val impact: Int?,
    val temperatureC: Double?,
    val severity: Int? = null,
    val symptoms: Set<String> = emptySet(),
)

/** An open illness episode: what it is called, when it started, and its check-ins. */
@Serializable
data class IllnessEpisode(val label: String, val start: LocalDate, val checkIns: List<IllnessCheckIn>)

const val FEVER_C = 38.0
/** Days a check-in holds before the ceiling eases one step, and every step after. */
const val ILLNESS_HOLD_DAYS = 2

@Serializable
enum class IllnessGravity(val label: String, val ceiling: Double?) {
    NONE("nothing left", null), MILD("mild", 85.0), MODERATE("moderate", 65.0), SEVERE("severe", CAP_CEILING),
}

/** No check-in about the illness yet: moderate until one says otherwise. */
val UNKNOWN_GRAVITY = IllnessGravity.MODERATE

/** Symptoms above the neck: a cold that stays here is the mild kind. */
val ABOVE_THE_NECK = setOf(
    "sym.congestion", "sym.sneezing", "sym.sore_throat", "sym.sinus_pressure", "sym.loss_smell", "sym.headache",
    "sym.earache", "sym.eye_irritation",
)

/** The gravity of one check-in day. */
fun gravityOf(c: IllnessCheckIn): IllnessGravity {
    val fever = (c.temperatureC ?: 0.0) >= FEVER_C || "sym.fever" in c.symptoms
    val severity = c.severity ?: 0
    val impact = c.impact ?: 0
    val belowNeck = c.symptoms.any { it !in ABOVE_THE_NECK }
    return when {
        fever || impact >= 3 || severity >= 7 || (belowNeck && severity >= 5) -> IllnessGravity.SEVERE
        impact == 2 || severity >= 4 || belowNeck -> IllnessGravity.MODERATE
        severity == 0 && impact == 0 -> IllnessGravity.NONE
        else -> IllnessGravity.MILD
    }
}

/** How the open illness stands on one morning. */
@Serializable
data class IllnessState(
    val label: String,
    /** Day of the episode, day 1 being its start. */
    val day: Int,
    /** The grade the ceiling uses after easing; null when no check-in has answered yet. */
    val gravity: IllnessGravity?,
    val fever: Boolean,
    val lastCheckIn: LocalDate?,
    val eased: Boolean,
    val ceiling: Double?,
    /** Why it is graded so: "above the neck, 3/10". */
    val why: String = "",
) {
    /** "Cold · day 3 · mild: above the neck, 3/10 (check-in 2 nov)". */
    val reason: String get() {
        val grade = when {
            lastCheckIn == null -> "no check-in yet"
            fever -> "severe: fever logged"
            gravity == null -> "no check-in yet"
            else -> gravity.label + if (why.isNotEmpty()) ": $why" else ""
        }
        val asOf = lastCheckIn?.let { " (check-in ${it.dayOfMonth} ${it.month.name.take(3).lowercase()}${if (eased) ", eased since" else ""})" } ?: ""
        return "$label · day $day · $grade$asOf"
    }
    val cap: ChargeCap? get() = ceiling?.let { ChargeCap(reason, it) }
}

/** The open illness on [morning], or null before it started. */
fun illnessState(episode: IllnessEpisode, morning: LocalDate): IllnessState? {
    if (morning.isBefore(episode.start)) return null
    val day = ChronoUnit.DAYS.between(episode.start, morning).toInt() + 1
    val last = episode.checkIns.filter { !it.date.isAfter(morning) && (it.impact != null || it.temperatureC != null || it.severity != null) }
        .maxByOrNull { it.date }
    if (last == null) {
        return IllnessState(episode.label, day, null, fever = false, lastCheckIn = null, eased = false, ceiling = UNKNOWN_GRAVITY.ceiling)
    }
    val fever = (last.temperatureC ?: 0.0) >= FEVER_C || "sym.fever" in last.symptoms
    val answered = gravityOf(last)
    val gap = ChronoUnit.DAYS.between(last.date, morning).toInt()
    val steps = ((gap - 1).coerceAtLeast(0)) / ILLNESS_HOLD_DAYS
    val gravity = IllnessGravity.entries[(answered.ordinal - steps).coerceAtLeast(0)]
    val why = listOfNotNull(
        when {
            last.symptoms.isEmpty() -> null
            last.symptoms.any { it !in ABOVE_THE_NECK } -> "below the neck"
            else -> "above the neck"
        },
        last.severity?.let { "$it/10" },
        last.impact?.takeIf { it > 0 }?.let { IMPACT_WORDS[it] },
    ).joinToString(", ")
    return IllnessState(episode.label, day, gravity, fever && steps == 0, last.date, eased = steps > 0, ceiling = gravity.ceiling, why = why)
}

val IMPACT_WORDS = listOf("full training", "reduced training", "training modified", "daily life affected")

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
