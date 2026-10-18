package com.bioscan.fieldterminal.domain.fuel

import com.bioscan.fieldterminal.domain.events.EventKind
import com.bioscan.fieldterminal.domain.events.EventPriority
import kotlin.math.exp

// How demanding a planned or recorded session is, for fuel planning (docs/fuel-planning/README.md, phase 2): an intensity
// class and an energy estimate. The class comes from the best evidence at hand, in order: the event (a race is a race),
// the planned session's training domain, the calendar title, the session's own heart rate or RPE once recorded, and
// last the person's typical heart-rate load for that type of session. Anything over 90 minutes is LONG, whatever its
// intensity (strength excepted): a long session's carbohydrate need is set by its length.

enum class Intensity(val label: String) {
    EASY("EASY"), MODERATE("MODERATE"), HARD("HARD"), LONG("LONG"), RACE("RACE");

    /** HARD, LONG and RACE are the sessions the day's fuelling is built around. */
    val isKey: Boolean get() = this == HARD || this == LONG || this == RACE
}

data class Classified(val intensity: Intensity, val reason: String)

const val LONG_SESSION_MIN = 90.0

// planned_sessions.domain (docs/training-programming/02-schema-and-definitions.md).
private val DOMAIN_INTENSITY = mapOf(
    "aerobic_base" to Intensity.EASY,
    "recovery" to Intensity.EASY,
    "sport_skill" to Intensity.EASY,
    "anaerobic_capacity" to Intensity.HARD,
    "speed" to Intensity.HARD,
    "power" to Intensity.HARD,
    "specific_endurance" to Intensity.HARD,
    "work_capacity" to Intensity.HARD,
    "max_strength" to Intensity.MODERATE,
    "hypertrophy" to Intensity.MODERATE,
    "strength_endurance" to Intensity.MODERATE,
)

private val HARD_WORDS = Regex("interval|ripetut|tempo|threshold|soglia|vo2|fartlek|repeats|progressiv|hiit|crossfit|wod|sprint", RegexOption.IGNORE_CASE)
private val EASY_WORDS = Regex("easy|recovery|rigenerant|\\bz2\\b|zone ?2|lento|shakeout|mobility|yoga|stretch", RegexOption.IGNORE_CASE)
private val LONG_WORDS = Regex("\\blong\\b|lungo|lunghissim|long run|long ride", RegexOption.IGNORE_CASE)

/** The class an event sets: a race at A or B priority is a RACE; anything else at an event is at least HARD. */
fun intensityForEvent(kind: EventKind, priority: EventPriority, durationMin: Double): Classified = when {
    kind == EventKind.RACE && priority != EventPriority.C -> Classified(Intensity.RACE, "${priority.key} race")
    durationMin > LONG_SESSION_MIN -> Classified(Intensity.LONG, "${kind.label.lowercase()} over ${LONG_SESSION_MIN.toInt()} min")
    else -> Classified(Intensity.HARD, kind.label.lowercase())
}

fun intensityForDomain(domain: String?): Classified? =
    DOMAIN_INTENSITY[domain]?.let { Classified(it, "planned as ${domain!!.replace('_', ' ')}") }

fun intensityForTitle(title: String?): Classified? {
    val t = title ?: return null
    return when {
        HARD_WORDS.containsMatchIn(t) -> Classified(Intensity.HARD, "\"${t.trim()}\" reads as a hard session")
        LONG_WORDS.containsMatchIn(t) -> Classified(Intensity.LONG, "\"${t.trim()}\" reads as a long session")
        EASY_WORDS.containsMatchIn(t) -> Classified(Intensity.EASY, "\"${t.trim()}\" reads as an easy session")
        "🏋" in t -> Classified(Intensity.MODERATE, "strength session")
        else -> null
    }
}

/** A recorded session's own class: its RPE when logged, else its average heart rate as a share of heart-rate reserve. */
fun intensityForRecorded(rpe: Int?, avgHr: Double?, restingHr: Double?, maxHr: Double?): Classified? {
    if (rpe != null && rpe > 0) {
        return when {
            rpe >= 7 -> Classified(Intensity.HARD, "RPE $rpe")
            rpe >= 5 -> Classified(Intensity.MODERATE, "RPE $rpe")
            else -> Classified(Intensity.EASY, "RPE $rpe")
        }
    }
    if (avgHr == null || restingHr == null || maxHr == null || maxHr <= restingHr) return null
    val reserve = ((avgHr - restingHr) / (maxHr - restingHr)).coerceIn(0.0, 1.0)
    val pct = (reserve * 100).toInt()
    return when {
        reserve >= 0.75 -> Classified(Intensity.HARD, "average heart rate at $pct % of reserve")
        reserve >= 0.6 -> Classified(Intensity.MODERATE, "average heart rate at $pct % of reserve")
        else -> Classified(Intensity.EASY, "average heart rate at $pct % of reserve")
    }
}

/** Banister TRIMP per minute at a heart-rate-reserve fraction (male coefficients, as EnduranceLoad.kt). */
fun trimpPerMinute(reserve: Double): Double = reserve * 0.64 * exp(1.92 * reserve)

/**
 * The person's usual class for a session type, from the median TRIMP per minute of their recorded sessions of that type
 * (60 % of reserve is about 1.2 per minute, 75 % about 2.0). Null with fewer than 3 sessions.
 */
fun intensityForTypical(type: String, trimpPerMinByType: Map<String, List<Double>>): Classified? {
    val values = trimpPerMinByType[type]?.sorted()?.takeIf { it.size >= 3 } ?: return null
    val median = values[values.size / 2]
    val intensity = when {
        median >= trimpPerMinute(0.75) -> Intensity.HARD
        median >= trimpPerMinute(0.6) -> Intensity.MODERATE
        else -> Intensity.EASY
    }
    return Classified(intensity, "your usual ${type.replace('_', ' ')} (${values.size} sessions)")
}

/** The first answer of [candidates], made LONG above [LONG_SESSION_MIN] (strength and races keep theirs); MODERATE when none. */
fun finalIntensity(type: String, durationMin: Double, vararg candidates: Classified?): Classified {
    val picked = candidates.firstOrNull { it != null } ?: Classified(Intensity.MODERATE, "no intensity known")
    return if (durationMin > LONG_SESSION_MIN && type != "strength" && picked.intensity != Intensity.RACE && picked.intensity != Intensity.LONG) {
        Classified(Intensity.LONG, "${durationMin.toInt()} min (${picked.reason})")
    } else {
        picked
    }
}

// ---- Energy ---------------------------------------------------------------------------------------------------------

// Compendium of Physical Activities (Ainsworth 2011), a typical value per session type.
private val MET_BY_TYPE = mapOf(
    "run" to 9.8, "ride" to 7.5, "hike" to 6.0, "walk" to 3.5, "strength" to 5.0, "swim" to 8.0, "yoga" to 2.5, "other" to 6.0,
)
private val INTENSITY_ENERGY = mapOf(
    Intensity.EASY to 0.85, Intensity.MODERATE to 1.0, Intensity.HARD to 1.15, Intensity.LONG to 0.95, Intensity.RACE to 1.15,
)

/**
 * Active energy of a session, kcal: the person's own median kcal per minute for the type (calories_active over
 * duration, at least 3 sessions) when known, else the Compendium MET minus the resting 1 MET; scaled a little by
 * intensity. A labelled estimate, used to shape the days of a week, not to count calories.
 */
fun estimateSessionKcal(type: String, durationMin: Double, intensity: Intensity, weightKg: Double?, kcalPerMinByType: Map<String, List<Double>>): Double? {
    val own = kcalPerMinByType[type]?.sorted()?.takeIf { it.size >= 3 }?.let { it[it.size / 2] }
    val perMin = own ?: weightKg?.let { kg -> ((MET_BY_TYPE[type] ?: 6.0) - 1.0) * 3.5 * kg / 200.0 } ?: return null
    return perMin * durationMin * (INTENSITY_ENERGY[intensity] ?: 1.0)
}
