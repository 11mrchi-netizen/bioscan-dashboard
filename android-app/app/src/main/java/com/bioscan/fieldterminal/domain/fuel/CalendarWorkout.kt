package com.bioscan.fieldterminal.domain.fuel

import com.bioscan.fieldterminal.domain.parseGpxLink
import kotlin.math.roundToInt

// What a calendar training entry says about the session (fuel planning phase 11). Calendar entries are often one-hour
// placeholders whose real content is in the text: "Long Run — 25.7km", "Route: … (15.7km, +868m)", "800m Repeats — 5-8
// rounds", "Overhead Press 4x4 @47.5kg", "Wave 3 (85%)", "Deload — light activity". Fixed rules read the distance,
// climb, intervals, sets and load from the title and description; the duration then comes from the person's own runs
// (estimateRunTime) or a rule per kind of session, and replaces the placeholder's length. Nothing is read by a model.

data class ParsedWorkout(
    val distanceKm: Double? = null,
    val ascentM: Double? = null,
    /** Interval rounds (the middle of a range such as 5-8). */
    val rounds: Double? = null,
    /** Interval repetition length, m. */
    val repDistanceM: Double? = null,
    /** Strength: total working sets across the exercises listed. */
    val sets: Double? = null,
    /** Strength: the fewest reps of a set listed. */
    val minReps: Int? = null,
    /** Strength: the load as % of maximum when given ("@85%", "(85%)"). */
    val loadPct: Double? = null,
    /** Strength: true when a load is given in kg. */
    val loadKg: Boolean = false,
    val deload: Boolean = false,
    val easy: Boolean = false,
    val long: Boolean = false,
    val hard: Boolean = false,
    val gpxUrl: String? = null,
) {
    val isEmpty: Boolean get() = distanceKm == null && rounds == null && sets == null && !deload
}

private val NUMBER = """(\d+(?:[.,]\d+)?)"""
private val DISTANCE = Regex("""$NUMBER\s?km\b""", RegexOption.IGNORE_CASE)
private val ASCENT = Regex("""(?:\+|d\+\s?)\s?(\d{2,5})\s?m\b""", RegexOption.IGNORE_CASE)
private val REPS_BY_DISTANCE = Regex("""(\d{1,2})\s?[x×]\s?(\d{3,4})\s?m?\b""", RegexOption.IGNORE_CASE)
private val REP_DISTANCE = Regex("""(\d{3,4})\s?m\b""", RegexOption.IGNORE_CASE)
private val ROUNDS = Regex("""(\d{1,2})(?:\s?[-–]\s?(\d{1,2}))?\s?(?:rounds|round|reps|repeats|ripetute|giri)\b""", RegexOption.IGNORE_CASE)
// Sets x reps, a range of sets allowed ("4x4", "3-5x3"); not a distance ("6x800m").
private val SETS_REPS = Regex("""(\d{1,2})(?:\s?[-–]\s?(\d{1,2}))?\s?[x×]\s?(\d{1,2})(?!\d|\s?m\b)""", RegexOption.IGNORE_CASE)
private val PCT = Regex("""(\d{2,3})\s?%""")
private val KG = Regex("""@\s?\d+(?:[.,]\d+)?\s?kg""", RegexOption.IGNORE_CASE)
private val DELOAD = Regex("""deload|light activity|scarico|rest day|riposo""", RegexOption.IGNORE_CASE)
private val EASY_TEXT = Regex("""\blss\b|easy|recovery|rigenerant|\bz2\b|zone ?2|lento|shakeout|easy pace""", RegexOption.IGNORE_CASE)
private val LONG_TEXT = Regex("""\blong\b|lungo|back-to-back|b2b""", RegexOption.IGNORE_CASE)
private val HARD_TEXT = Regex("""interval|ripetut|tempo|threshold|soglia|vo2|fartlek|repeats|hiit|sprint""", RegexOption.IGNORE_CASE)

private fun num(s: String): Double = s.replace(',', '.').toDouble()

/** Reads [title] and [description] of a calendar training entry; [type] (run, strength, …) decides what the numbers mean. */
fun parseCalendarWorkout(title: String, description: String?, type: String): ParsedWorkout {
    val desc = description.orEmpty()
    val text = "$title\n$desc"
    // The title's distance is the plan; a route in the description may differ ("Trail Run 16K (15.7km, +868m)").
    val distance = (DISTANCE.find(title) ?: DISTANCE.find(desc))?.let { num(it.groupValues[1]) }?.takeIf { it in 0.5..300.0 }
    val ascent = ASCENT.find(text)?.groupValues?.get(1)?.toDouble()?.takeIf { it in 10.0..10000.0 }

    var rounds: Double? = null
    var rep: Double? = null
    if (type != "strength") {
        REPS_BY_DISTANCE.find(text)?.let { m -> rounds = m.groupValues[1].toDouble(); rep = m.groupValues[2].toDouble() }
        if (rounds == null) {
            ROUNDS.find(text)?.let { m ->
                val lo = m.groupValues[1].toDouble()
                val hi = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDouble() ?: lo
                rounds = (lo + hi) / 2
            }
            if (rounds != null) rep = REP_DISTANCE.find(text)?.groupValues?.get(1)?.toDouble()
        }
    }

    var sets: Double? = null
    var minReps: Int? = null
    if (type == "strength") {
        val found = SETS_REPS.findAll(desc.ifBlank { title }).toList()
        if (found.isNotEmpty()) {
            sets = found.sumOf { m ->
                val lo = m.groupValues[1].toDouble()
                val hi = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDouble() ?: lo
                (lo + hi) / 2
            }
            minReps = found.minOf { it.groupValues[3].toInt() }
        }
    }
    val pct = PCT.findAll(text).map { it.groupValues[1].toDouble() }.filter { it in 40.0..105.0 }.maxOrNull()

    return ParsedWorkout(
        distanceKm = distance,
        ascentM = ascent,
        rounds = rounds,
        repDistanceM = rep,
        sets = sets,
        minReps = minReps,
        loadPct = if (type == "strength") pct else null,
        loadKg = type == "strength" && KG.containsMatchIn(text),
        deload = DELOAD.containsMatchIn(title),
        easy = EASY_TEXT.containsMatchIn(text),
        long = LONG_TEXT.containsMatchIn(title),
        hard = HARD_TEXT.containsMatchIn(title) || (type == "strength" && (pct ?: 0.0) >= 85.0),
        gpxUrl = parseGpxLink(desc),
    )
}

/** A duration and class read from a calendar entry, with the reason shown on the plan. */
data class WorkoutEstimate(val durationMin: Double, val classified: Classified)

private const val FALLBACK_MIN_PER_KM_EFFORT = 6.5
private const val WARM_UP_MIN = 15.0
private const val COOL_DOWN_MIN = 10.0
private const val INTERVAL_RECOVERY_MIN = 4.0
private const val STRENGTH_SETUP_MIN = 10.0
private const val STRENGTH_MIN_PER_SET = 3.0
private const val DELOAD_MIN = 30.0

private fun hoursMinutes(min: Double): String {
    val m = min.roundToInt()
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else "$m min"
}

/**
 * The session's duration and class from [p], or null when the text says nothing the rules can use. [runs] are the
 * person's recent runs (for pace); easy runs use the slower half of their paces, intervals their faster quarter.
 */
fun estimateCalendarWorkout(p: ParsedWorkout, type: String, runs: List<RecentRun>): WorkoutEstimate? {
    if (p.deload) return WorkoutEstimate(DELOAD_MIN, Classified(Intensity.EASY, "deload: light activity, about ${DELOAD_MIN.toInt()} min"))
    if (type == "strength") {
        val sets = p.sets ?: return null
        val minutes = STRENGTH_SETUP_MIN + STRENGTH_MIN_PER_SET * sets
        val heavy = (p.loadPct ?: 0.0) >= 85.0 || (p.minReps != null && p.minReps <= 5 && (p.loadKg || p.loadPct != null))
        val load = p.loadPct?.let { " at ${it.roundToInt()} %" } ?: ""
        return WorkoutEstimate(
            minutes,
            Classified(if (heavy) Intensity.HARD else Intensity.MODERATE, "${sets.roundToInt()} working sets$load from the calendar, about ${hoursMinutes(minutes)}"),
        )
    }
    val effort = p.distanceKm?.let { kmEffort(it, p.ascentM ?: 0.0) }
    val est = p.distanceKm?.let { estimateRunTime(it, p.ascentM ?: 0.0, runs) }
    val rounds = p.rounds
    if (rounds != null && type == "run") {
        // Repetitions at the faster quarter of the person's paces, less a little for the shorter efforts.
        val fast = ((if (est != null && effort != null) est.lowMinutes / effort else null) ?: estimateRunTime(1.0, 0.0, runs)?.lowMinutes ?: 5.5) * 0.85
        val repMin = (p.repDistanceM ?: 800.0) / 1000.0 * fast
        val minutes = WARM_UP_MIN + rounds * (repMin + INTERVAL_RECOVERY_MIN) + COOL_DOWN_MIN
        val what = "${"%.1f".format(rounds).removeSuffix(".0")} × ${(p.repDistanceM ?: 800.0).roundToInt()} m"
        return WorkoutEstimate(minutes, Classified(Intensity.HARD, "$what intervals from the calendar, about ${hoursMinutes(minutes)} with warm-up and cool-down"))
    }
    val km = p.distanceKm ?: return null
    if (type !in setOf("run", "hike")) return null
    val climb = p.ascentM?.let { ", +${it.roundToInt()} m" } ?: ""
    val (minutes, basis) = when {
        est != null && p.easy -> (est.minutes + est.highMinutes) / 2 to "at your easy pace"
        est != null -> est.minutes to "at your recent pace"
        else -> FALLBACK_MIN_PER_KM_EFFORT * kmEffort(km, p.ascentM ?: 0.0) to "at a default pace (fewer than 5 recent runs)"
    }
    val km1 = "%.1f".format(km)
    val intensity = when {
        p.long || minutes > LONG_SESSION_MIN -> Intensity.LONG
        p.hard -> Intensity.HARD
        p.easy -> Intensity.EASY
        else -> Intensity.MODERATE
    }
    val word = when (intensity) {
        Intensity.LONG -> "long run"
        Intensity.HARD -> "hard run"
        Intensity.EASY -> "easy run"
        else -> "run"
    }
    return WorkoutEstimate(minutes, Classified(intensity, "$km1 km$climb from the calendar, about ${hoursMinutes(minutes)} $basis: $word"))
}
