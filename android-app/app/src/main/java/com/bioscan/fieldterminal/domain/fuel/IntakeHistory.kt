package com.bioscan.fieldterminal.domain.fuel

import com.bioscan.fieldterminal.domain.LOG_COMPLETE_MEALS
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// What was eaten in the days before, against what was planned (docs/fuel-planning/README.md, phase 3). Only days with
// full meal coverage (the Log tab's rule: at least LOG_COMPLETE_MEALS meals) are read as eaten; a day with fewer is NOT
// LOGGED and is assumed to have gone as planned, never read as a deficit (the complaint reviewers make of Hexis).
//
// Before a race the check matters: carbohydrate loading logged short means today's meals carry more (within 4 g/kg at
// breakfast and 2 g/kg extra in a day) and the race plan starts fuelling earlier. A morning weight well under the week's
// usual (more than 1 %) points to fluid, not fat, and raises pre-hydration (Sawka 2007).

data class LoggedDay(val date: LocalDate, val mealsLogged: Int, val carbG: Double, val kcal: Double?)

enum class IntakeStatus(val label: String) { MET("ON PLAN"), UNDER("UNDER"), OVER("OVER"), NOT_LOGGED("NOT LOGGED") }

data class DayIntakeCheck(val date: LocalDate, val plannedCarbG: Double, val loggedCarbG: Double?, val status: IntakeStatus, val loading: Boolean)

data class IntakeHistoryResult(
    val days: List<DayIntakeCheck>,
    /** Planned minus logged carbohydrate on logged loading days, g (0 when on plan or nothing was logged). */
    val loadingShortfallG: Double,
    /** Carbohydrate today's remaining meals should add, g. */
    val catchUpG: Double,
    val earlyFuelling: Boolean,
    val preHydrationBoost: Boolean,
    val notes: List<String>,
)

const val CATCH_UP_MAX_G_PER_KG = 2.0
const val WEIGHT_DROP_FOR_FLUID = 0.01

fun isFullyLogged(day: LoggedDay?): Boolean = day != null && day.mealsLogged >= LOG_COMPLETE_MEALS

/**
 * [planned] are the plans of the last days (oldest first), [logged] what the Log holds for them, [raceSoon] whether an
 * A or B race falls today or tomorrow. [morningWeightKg] and [usualWeightKg] (the 7-day median) are optional.
 */
fun checkIntakeHistory(
    planned: List<DayFuelPlan>,
    logged: List<LoggedDay>,
    weightKg: Double,
    raceSoon: Boolean,
    morningWeightKg: Double? = null,
    usualWeightKg: Double? = null,
): IntakeHistoryResult {
    val byDate = logged.associateBy { it.date }
    val checks = planned.map { p ->
        val l = byDate[p.date]
        if (!isFullyLogged(l)) {
            DayIntakeCheck(p.date, p.carbG, null, IntakeStatus.NOT_LOGGED, p.loading)
        } else {
            val status = when {
                l!!.carbG < p.carbRangeG.start -> IntakeStatus.UNDER
                l.carbG > p.carbRangeG.endInclusive -> IntakeStatus.OVER
                else -> IntakeStatus.MET
            }
            DayIntakeCheck(p.date, p.carbG, l.carbG, status, p.loading)
        }
    }
    val notes = mutableListOf<String>()
    val shortfall = checks.filter { it.loading && it.loggedCarbG != null }.sumOf { max(0.0, it.plannedCarbG - it.loggedCarbG!!) }
    val catchUp = if (raceSoon) min(shortfall, CATCH_UP_MAX_G_PER_KG * weightKg) else 0.0
    if (shortfall > 0 && raceSoon) {
        notes += "Loading so far is ${shortfall.roundToInt()} g short of plan: today's meals carry ${catchUp.roundToInt()} g more, and fuelling starts in the first 20 minutes of the race."
    }
    checks.filter { it.loading && it.status == IntakeStatus.NOT_LOGGED }.forEach {
        notes += "${it.date}: not logged, assumed loaded as planned."
    }
    val dropped = morningWeightKg != null && usualWeightKg != null && usualWeightKg > 0 &&
        (usualWeightKg - morningWeightKg) / usualWeightKg > WEIGHT_DROP_FOR_FLUID
    if (dropped) {
        notes += "This morning's weight is %.1f kg under your usual: likely fluid. Drink a little more before the start.".format(usualWeightKg!! - morningWeightKg!!)
    }
    return IntakeHistoryResult(checks, shortfall, catchUp, earlyFuelling = shortfall > 0 && raceSoon, preHydrationBoost = dropped, notes = notes)
}

/** [plan] with [extraG] of carbohydrate added for catching up on loading (energy rises with it). */
fun withCatchUp(plan: DayFuelPlan, extraG: Double): DayFuelPlan {
    if (extraG <= 0) return plan
    return plan.copy(
        carbG = plan.carbG + extraG,
        carbGPerKg = plan.carbGPerKg + extraG / (plan.carbG / plan.carbGPerKg).coerceAtLeast(1.0),
        kcal = plan.kcal?.plus(extraG * 4),
        notes = plan.notes + "Catching up on loading: +${extraG.roundToInt()} g today.",
    )
}
