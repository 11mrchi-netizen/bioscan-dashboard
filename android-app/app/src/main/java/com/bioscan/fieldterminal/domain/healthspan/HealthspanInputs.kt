package com.bioscan.fieldterminal.domain.healthspan

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.NutritionDayClass
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.aging.BiologicalAgeResult
import com.bioscan.fieldterminal.domain.classifyNutritionDay
import com.bioscan.fieldterminal.domain.computeMovementIndex
import com.bioscan.fieldterminal.domain.computeSleepIndex
import com.bioscan.fieldterminal.domain.evaluateHrv
import com.bioscan.fieldterminal.domain.evaluateNutrition
import com.bioscan.fieldterminal.domain.evaluateRhr
import com.bioscan.fieldterminal.domain.levels.LiftTierResult
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// Builders that turn the app's existing evaluator outputs into Healthspan axis inputs. Pure and
// side-effect free: the repository loads the raw series, these decide score vs. "why not".

fun sleepAxisInput(
    hours: List<Pair<LocalDate, Double>>,
    deepMinutes: List<Pair<LocalDate, Double>>,
    respiratory: List<Pair<LocalDate, Double>>,
    nights: List<SleepNight>,
    asOf: LocalDate,
): HealthspanAxisInput {
    val r = computeSleepIndex(hours, deepMinutes, respiratory, nights, asOf = asOf)
    val score = r.score
    return if (score != null) HealthspanAxisInput(HealthspanAxis.SLEEP, score, r.confidence)
    else HealthspanAxisInput.unavailable(HealthspanAxis.SLEEP, "Needs at least 2 sleep signals with history (${r.confidence.label})")
}

fun movementAxisInput(steps: List<Pair<LocalDate, Double>>, sessionStarts: List<OffsetDateTime>, asOf: LocalDate): HealthspanAxisInput {
    val r = computeMovementIndex(steps, sessionStarts, asOf)
    val score = r.score
    return if (score != null) HealthspanAxisInput(HealthspanAxis.MOVEMENT, score, r.confidence)
    else HealthspanAxisInput.unavailable(HealthspanAxis.MOVEMENT, "Needs a step baseline (5+ days of steps)")
}

fun recoveryAxisInput(hrv: List<Pair<LocalDate, Double>>, rhr: List<Pair<LocalDate, Double>>, asOf: LocalDate): HealthspanAxisInput {
    val (score, confidence) = recoveryAxisScore(evaluateHrv(hrv, asOf), evaluateRhr(rhr, asOf))
    return if (score != null) HealthspanAxisInput(HealthspanAxis.RECOVERY, score, confidence)
    else HealthspanAxisInput.unavailable(HealthspanAxis.RECOVERY, "HRV and resting HR need a 60-day baseline")
}

fun cardioAxisInput(cardioAge: BiologicalAgeResult?): HealthspanAxisInput {
    val accel = cardioAge?.ageAcceleration
    return if (cardioAge != null && accel != null) HealthspanAxisInput(HealthspanAxis.CARDIO, cardioAxisScore(accel), cardioAge.confidence)
    else HealthspanAxisInput.unavailable(HealthspanAxis.CARDIO, cardioAge?.unavailableReason ?: "Needs VO2max, sex and date of birth")
}

fun strengthAxisInput(rated: List<LiftTierResult>, bodyWeightMissing: Boolean): HealthspanAxisInput =
    if (rated.isNotEmpty()) HealthspanAxisInput(HealthspanAxis.STRENGTH, rated.map { it.ladderFraction }.average() * 100.0, Confidence(rated.size, 3))
    else HealthspanAxisInput.unavailable(
        HealthspanAxis.STRENGTH,
        if (bodyWeightMissing) "Needs a body weight to rate lifts" else "Needs logged sets for a standard lift",
    )

private const val BODY_FAT_MAX_AGE_DAYS = 90L
fun bodyCompositionAxisInput(bodyFat: List<Pair<LocalDate, Double>>, sex: String?, asOf: LocalDate): HealthspanAxisInput {
    if (sex != "male" && sex != "female") return HealthspanAxisInput.unavailable(HealthspanAxis.BODY_COMPOSITION, "Needs sex (Setup > User) for the body-fat reference band")
    val latest = bodyFat.filter { !it.first.isAfter(asOf) }.maxByOrNull { it.first }
        ?: return HealthspanAxisInput.unavailable(HealthspanAxis.BODY_COMPOSITION, "No body-fat reading yet")
    if (ChronoUnit.DAYS.between(latest.first, asOf) > BODY_FAT_MAX_AGE_DAYS) {
        return HealthspanAxisInput.unavailable(HealthspanAxis.BODY_COMPOSITION, "Last body-fat reading is older than 90 days")
    }
    val score = bodyCompositionAxisScore(latest.second, sex)
        ?: return HealthspanAxisInput.unavailable(HealthspanAxis.BODY_COMPOSITION, "Needs sex for the body-fat reference band")
    return HealthspanAxisInput(HealthspanAxis.BODY_COMPOSITION, score, Confidence(1, 1))
}

// Nutrition: protein inside the AMDR band (evaluateNutrition) plus, when a calorie target is set in
// Setup > User, how many complete days landed within +/-10% of it. Hydration is intentionally not part
// of this axis: no daily hydration series is loaded centrally.
private const val CALORIE_TOLERANCE = 0.10
private const val MIN_COMPLETE_DAYS_FOR_ADHERENCE = 7
fun calorieAdherencePercent(daily: List<DailyNutrition>, goalKcal: Double, asOf: LocalDate): Double? {
    if (goalKcal <= 0) return null
    val parsed = daily.mapNotNull { d -> runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { it to d } }
        .filter { !it.first.isAfter(asOf) }.sortedBy { it.first }
    val complete = parsed.filter { (date, day) ->
        ChronoUnit.DAYS.between(date, asOf) < 14 &&
            classifyNutritionDay(day, parsed.filter { it.first >= date.minusDays(28) && it.first < date }.map { it.second }) == NutritionDayClass.Complete
    }
    if (complete.size < MIN_COMPLETE_DAYS_FOR_ADHERENCE) return null
    return complete.count { abs(it.second.calories - goalKcal) <= goalKcal * CALORIE_TOLERANCE }.toDouble() / complete.size * 100.0
}

fun nutritionAxisInput(daily: List<DailyNutrition>, calorieGoalKcal: Double?, asOf: LocalDate): HealthspanAxisInput {
    val eval = evaluateNutrition(daily, asOf)
    if (!eval.confidence.met) {
        return HealthspanAxisInput.unavailable(HealthspanAxis.NUTRITION, "Needs 10 complete logged days in the last 14 (${eval.confidence.label})")
    }
    val score = nutritionAxisScore(listOf(eval.proteinAdherence14d, calorieGoalKcal?.let { calorieAdherencePercent(daily, it, asOf) }))
        ?: return HealthspanAxisInput.unavailable(HealthspanAxis.NUTRITION, "Needs 7 complete days for protein adherence")
    return HealthspanAxisInput(HealthspanAxis.NUTRITION, score, eval.confidence)
}
