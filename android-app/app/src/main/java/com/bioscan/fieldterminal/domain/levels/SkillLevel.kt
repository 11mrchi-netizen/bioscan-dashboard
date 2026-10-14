package com.bioscan.fieldterminal.domain.levels

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.comparison.PercentileBand
import com.bioscan.fieldterminal.domain.comparison.percentileBand
import com.bioscan.fieldterminal.domain.healthspan.cardioAxisScore
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// User tab skill levels (Running / Strength / Conditioning). No population benchmark exists for any of
// these skills, so a level is computed from the best reference that is actually available and says
// which one it is (`basis`), and is marked `provisional` while the evidence is thin. A level appears
// from the first usable data point instead of waiting for a benchmark that does not exist:
//
//   AGE_NORM  - Running: VO2max against the FRIEND/ACSM age-and-sex medians (via Cardio Age).
//   STANDARD  - Strength: estimated 1RM against the app-reference bodyweight-multiple ladder.
//   PERSONAL  - anything else: the person's own history ("vs you"), always provisional.
//
// Position is a 0-100 number cut with the same thresholds as percentile bands (comparison/ComparisonBands.kt),
// so labels line up with the rest of the app. Levels are never aggregated across skills.
enum class LevelBasis(val chip: String) {
    AGE_NORM("VS AGE NORM"),
    STANDARD("VS APP REFERENCE"),
    PERSONAL("VS YOU"),
}

data class SkillLevel(
    val label: String,
    val position: Double?,
    val basis: LevelBasis?,
    val provisional: Boolean,
    // One short, already-formatted supporting figure ("VO2max 52", "4 lifts rated", "5 sessions / 4 wks").
    val headline: String?,
    // Why there is no level (null whenever there is one).
    val reason: String?,
    val confidence: Confidence,
) {
    val available: Boolean get() = position != null
}

fun levelLabel(position: Double, basis: LevelBasis): String = when (percentileBand(position)) {
    PercentileBand.TOP_DECILE -> if (basis == LevelBasis.PERSONAL) "At your best" else "Elite"
    PercentileBand.ABOVE_AVERAGE -> "Strong"
    PercentileBand.AVERAGE -> if (basis == LevelBasis.PERSONAL) "Typical" else "Solid"
    PercentileBand.BELOW_AVERAGE -> if (basis == LevelBasis.PERSONAL) "Below usual" else "Developing"
    PercentileBand.BOTTOM_DECILE -> if (basis == LevelBasis.PERSONAL) "Low" else "Building"
}

private fun unavailable(reason: String, have: Int = 0, need: Int = 1) =
    SkillLevel("Not enough data yet", null, null, false, null, reason, Confidence(have, need))

private fun available(position: Double, basis: LevelBasis, provisional: Boolean, headline: String?, confidence: Confidence): SkillLevel {
    val p = position.coerceIn(0.0, 100.0)
    return SkillLevel(levelLabel(p, basis), p, basis, provisional, headline, null, confidence)
}

// Running: VO2max vs the age-and-sex norm when sex and date of birth are known (ageAccelerationYears is
// cardioFunctionalAge's output); otherwise the best recent road pace against the person's own run history.
// Lower pace is better. `paceHistoryMinPerKm` is road, comparable runs only (the caller filters trail and
// implausible paces); `recentPaceMinPerKm` is the person's current best-recent pace.
private const val MIN_PACE_HISTORY = 3
fun runningSkillLevel(
    vo2max: Double?,
    ageAccelerationYears: Double?,
    recentPaceMinPerKm: Double?,
    paceHistoryMinPerKm: List<Double>,
): SkillLevel {
    if (vo2max != null && ageAccelerationYears != null) {
        return available(
            position = cardioAxisScore(ageAccelerationYears),
            basis = LevelBasis.AGE_NORM,
            provisional = false,
            headline = "VO2max ${"%.0f".format(vo2max)}",
            confidence = Confidence(1, 1),
        )
    }
    if (recentPaceMinPerKm != null && paceHistoryMinPerKm.size >= MIN_PACE_HISTORY) {
        val slower = paceHistoryMinPerKm.count { it > recentPaceMinPerKm }
        val position = slower.toDouble() / paceHistoryMinPerKm.size * 100.0
        return available(position, LevelBasis.PERSONAL, true, "Pace ${formatPace(recentPaceMinPerKm)}", Confidence(paceHistoryMinPerKm.size, MIN_PACE_HISTORY))
    }
    val reason = when {
        vo2max != null -> "Set sex and date of birth (Setup > User) to rate VO2max against an age norm"
        else -> "Needs a VO2max reading, or $MIN_PACE_HISTORY road runs with distance"
    }
    return unavailable(reason, paceHistoryMinPerKm.size, MIN_PACE_HISTORY)
}

fun formatPace(minPerKm: Double): String {
    val totalSec = Math.round(minPerKm * 60.0).toInt()
    return "%d:%02d /km".format(totalSec / 60, totalSec % 60)
}

// Strength: mean ladder position over every lift that has a standard and a usable e1RM. One lift is
// enough to show a level; under three lifts it is provisional.
private const val STRENGTH_FULL_EVIDENCE_LIFTS = 3
fun strengthSkillLevel(rated: List<LiftTierResult>, bodyWeightMissing: Boolean): SkillLevel {
    if (rated.isEmpty()) {
        val reason = if (bodyWeightMissing) "Add a body weight (Log) so lifts can be rated against bodyweight standards"
        else "Log sets for a standard lift (squat, deadlift, bench, press, row, weighted pull-up)"
        return unavailable(reason, 0, 1)
    }
    val position = rated.map { it.ladderFraction }.average() * 100.0
    return available(
        position, LevelBasis.STANDARD, rated.size < STRENGTH_FULL_EVIDENCE_LIFTS,
        "${rated.size} ${if (rated.size == 1) "lift" else "lifts"} rated", Confidence(rated.size, STRENGTH_FULL_EVIDENCE_LIFTS),
    )
}

// Conditioning: how the last 4 weeks of sessions compare with the person's own best rolling 4 weeks over
// the last 90 days. A consistency level against yourself -- not a fitness claim, no intervals or power are
// assumed. Provisional until 8 sessions exist in the window.
private const val CONDITIONING_WINDOW_DAYS = 28L
private const val CONDITIONING_LOOKBACK_DAYS = 90L
private const val CONDITIONING_FULL_EVIDENCE_SESSIONS = 8
fun conditioningSkillLevel(sessionDates: List<LocalDate>, asOf: LocalDate): SkillLevel {
    val inLookback = sessionDates.filter { !it.isAfter(asOf) && ChronoUnit.DAYS.between(it, asOf) < CONDITIONING_LOOKBACK_DAYS }
    if (inLookback.isEmpty()) return unavailable("No conditioning sessions in the last 90 days", 0, CONDITIONING_FULL_EVIDENCE_SESSIONS)

    fun countEnding(day: LocalDate) = inLookback.count { !it.isAfter(day) && ChronoUnit.DAYS.between(it, day) < CONDITIONING_WINDOW_DAYS }
    val recent = countEnding(asOf)
    val best = (0 until CONDITIONING_LOOKBACK_DAYS).maxOf { countEnding(asOf.minusDays(it)) }
    val position = if (best > 0) recent.toDouble() / best * 100.0 else 0.0
    return available(
        position, LevelBasis.PERSONAL, inLookback.size < CONDITIONING_FULL_EVIDENCE_SESSIONS,
        "$recent ${if (recent == 1) "session" else "sessions"} / 4 wks", Confidence(inLookback.size, CONDITIONING_FULL_EVIDENCE_SESSIONS),
    )
}
