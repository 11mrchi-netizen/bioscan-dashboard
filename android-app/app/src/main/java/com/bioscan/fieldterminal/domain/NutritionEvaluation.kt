package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Phase A4 (Analysis Layer). Evaluation Method Spec, Category 4 (Nutrition):
// MacroFactor's classify-then-exclude pattern, plus variance (energy CV) as
// its own first-class displayed metric, not a diagnostic. Reuses this
// project's existing `DailyNutrition`/`aggregateMealsByDay` (domain/
// Nutrition.kt, Step 6) for daily meal totals rather than re-deriving them a
// second way.
//
// Real scope limit, stated rather than silently assumed: the spec never
// defines a STABLE/SHIFT_UP/SHIFT_DOWN/UNSTABLE resolution rule for this
// category the way Categories 1/2/3/5/6 do -- its own text only ever asks
// for classify, then compute-and-display (energy trend, CV, protein
// adherence), never a threshold to call a move a "shift." Once this
// category's gate is met, state resolves to `Stable` unconditionally -- not
// because nothing here ever moves, but because the spec gives no rule for
// judging that movement, and inventing one wasn't asked for.
enum class NutritionDayClass { Complete, Partial, Missing }

data class NutritionEvaluation(
    val state: EvalState,
    val confidence: Confidence,
    val completeDaysInLast14: Int,
    val energyTrend14d: Double?,
    val energyCv28d: Double?,
    val proteinAdherence14d: Double?,
)

private const val COMPLETE_MEALS_MIN = 3
private const val CLASSIFICATION_MEDIAN_WINDOW_DAYS = 28
private const val ENERGY_TREND_DAYS = 14
private const val ENERGY_CV_DAYS = 28
private const val MIN_COMPLETE_IN_14 = 10
private const val MIN_COMPLETE_FOR_PROTEIN = 7

// AMDR (NASEM Dietary Reference Intakes) macro distribution range for
// protein -- 10-35% of energy -- used as the adherence band instead of a
// personal g/kg target. This project deliberately stores no personal
// nutrition targets anywhere (the existing Nutrition sub-tab already
// carries its own "no personal targets are stored anywhere in this project
// yet" disclaimer); picking a body-weight-based ISSN target here would be
// the first one, breaking that established stance.
// Not private -- the Weight/TDEE tab's protein-on-target trend chart
// (DAV-76) draws this same band as its reference range, rather than
// duplicating the AMDR bounds as a second pair of magic numbers.
const val PROTEIN_PCT_LOW = 0.10
const val PROTEIN_PCT_HIGH = 0.35

// A day only ever gets compared against days strictly before it -- never its
// own value, and never an imputed one. `priorDays` should already be scoped
// to the trailing 28-day window by the caller.
fun classifyNutritionDay(day: DailyNutrition, priorDays: List<DailyNutrition>): NutritionDayClass {
    if (day.mealCount == 0) return NutritionDayClass.Missing
    if (day.mealCount < COMPLETE_MEALS_MIN) return NutritionDayClass.Partial

    val qualifying = priorDays.filter { it.mealCount >= COMPLETE_MEALS_MIN }
    // Bootstrap case: no prior qualifying days yet to derive a median from
    // (real for this account's own early history) -- "meals_logged >= 3"
    // alone is a meaningful enough bar to call the day Complete rather than
    // block classification entirely on a threshold that doesn't exist yet.
    if (qualifying.isEmpty()) return NutritionDayClass.Complete

    val medianKcal = median(qualifying.map { it.calories })
    return if (day.calories < 0.5 * medianKcal) NutritionDayClass.Partial else NutritionDayClass.Complete
}

fun evaluateNutrition(dailyTotals: List<DailyNutrition>, asOf: LocalDate = LocalDate.now()): NutritionEvaluation {
    val parsed = dailyTotals
        .mapNotNull { d -> runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { it to d } }
        .filter { !it.first.isAfter(asOf) }
        .sortedBy { it.first }

    if (parsed.isEmpty()) return NutritionEvaluation(EvalState.NoData, Confidence(0, MIN_COMPLETE_IN_14), 0, null, null, null)

    val classifications = mutableMapOf<LocalDate, NutritionDayClass>()
    for ((date, day) in parsed) {
        val windowStart = date.minusDays(CLASSIFICATION_MEDIAN_WINDOW_DAYS.toLong())
        val priorDays = parsed.filter { it.first >= windowStart && it.first < date }.map { it.second }
        classifications[date] = classifyNutritionDay(day, priorDays)
    }

    val last14Dates = parsed.map { it.first }.filter { ChronoUnit.DAYS.between(it, asOf) < ENERGY_TREND_DAYS }
    val completeInLast14 = last14Dates.count { classifications[it] == NutritionDayClass.Complete }

    if (completeInLast14 < MIN_COMPLETE_IN_14) {
        return NutritionEvaluation(EvalState.Building, Confidence(completeInLast14, MIN_COMPLETE_IN_14), completeInLast14, null, null, null)
    }

    val completeDays = parsed.filter { classifications[it.first] == NutritionDayClass.Complete }
    val last14Complete = completeDays.filter { ChronoUnit.DAYS.between(it.first, asOf) < ENERGY_TREND_DAYS }
    val last28Complete = completeDays.filter { ChronoUnit.DAYS.between(it.first, asOf) < ENERGY_CV_DAYS }

    val energyTrend14d = last14Complete.takeIf { it.isNotEmpty() }?.let { mean(it.map { d -> d.second.calories }) }

    val energyCv28d = last28Complete.takeIf { it.size >= 2 }?.let {
        val kcalValues = it.map { d -> d.second.calories }
        val m = mean(kcalValues)
        if (m != 0.0) populationStdDev(kcalValues) / m * 100 else null
    }

    val proteinAdherence14d = if (completeInLast14 >= MIN_COMPLETE_FOR_PROTEIN && last14Complete.isNotEmpty()) {
        val withinBand = last14Complete.count { (_, d) ->
            d.calories > 0 && (d.proteinG * 4.0 / d.calories) in PROTEIN_PCT_LOW..PROTEIN_PCT_HIGH
        }
        withinBand.toDouble() / last14Complete.size * 100
    } else {
        null
    }

    return NutritionEvaluation(
        state = EvalState.Stable,
        confidence = Confidence(completeInLast14, MIN_COMPLETE_IN_14),
        completeDaysInLast14 = completeInLast14,
        energyTrend14d = energyTrend14d,
        energyCv28d = energyCv28d,
        proteinAdherence14d = proteinAdherence14d,
    )
}

// DAV-76: per-day protein-%-of-calories, for the Weight/TDEE tab's "on-target"
// trend line -- same AMDR band and same COMPLETE_MEALS_MIN gate
// proteinAdherence14d itself uses, just exposed as a series instead of one
// rolled-up percentage so it can be charted day-by-day.
fun proteinPercentSeries(dailyTotals: List<DailyNutrition>, asOf: LocalDate = LocalDate.now(), days: Int = 90): List<Pair<LocalDate, Double>> =
    dailyTotals
        .mapNotNull { d -> runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { it to d } }
        .filter { (date, day) -> !date.isAfter(asOf) && ChronoUnit.DAYS.between(date, asOf) < days && day.mealCount >= COMPLETE_MEALS_MIN && day.calories > 0 }
        .map { (date, day) -> date to (day.proteinG * 4.0 / day.calories * 100) }
        .sortedBy { it.first }
