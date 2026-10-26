package com.bioscan.fieldterminal.domain.fuel

import java.time.LocalDate

// Hydration accounting (Nutrition › Hydration): each day's fluid intake against an inferred demand RANGE. Demand is the
// person's daily baseline range (Setup, else the generic 2.5 to 3.5 L) plus the sweat their sessions are expected to have
// cost (predictSweatRate: weight, effort, temperature, their own sweat tests; low to high, not a point). A range, never a
// target to hit: intake under or over it is not a dehydration test, and a day with nothing logged is a gap, not zero.
// docs/ft-redesign/model-plans.md, section 3.

data class HydrationWorkout(val minutes: Double, val rpe: Int?)

/** What is known about one day before the model runs. [intakeMl] is null when nothing was logged. */
data class HydrationDayInputs(val date: LocalDate, val intakeMl: Double?, val workouts: List<HydrationWorkout>, val tempC: Double?)

enum class HydrationStatus(val label: String) {
    Below("▽ BELOW RANGE"), Within("● WITHIN RANGE"), Above("△ ABOVE RANGE"), NoIntake("NOTHING LOGGED")
}

data class DayHydration(
    val date: LocalDate,
    val intakeMl: Double?,
    val baselineLowMl: Double,
    val baselineHighMl: Double,
    val exerciseLowMl: Double,
    val exerciseHighMl: Double,
    val workoutMinutes: Double,
    /** False when a session day's temperature could not be read and the mild-day fallback was used. */
    val temperatureKnown: Boolean,
) {
    val demandLowMl: Double get() = baselineLowMl + exerciseLowMl
    val demandHighMl: Double get() = baselineHighMl + exerciseHighMl
    val status: HydrationStatus
        get() = when {
            intakeMl == null -> HydrationStatus.NoIntake
            intakeMl < demandLowMl -> HydrationStatus.Below
            intakeMl > demandHighMl -> HydrationStatus.Above
            else -> HydrationStatus.Within
        }
}

/** Effort of a session from its RPE (the only effort measure every session has); 2 h or more is a long session. */
fun hydrationIntensity(minutes: Double, rpe: Int?): Intensity = when {
    minutes >= 120 -> Intensity.LONG
    rpe == null -> Intensity.MODERATE
    rpe <= 3 -> Intensity.EASY
    rpe >= 7 -> Intensity.HARD
    else -> Intensity.MODERATE
}

/** Temperature assumed for a session day whose weather could not be read, as the day's fluid range elsewhere assumes. */
const val HYDRATION_FALLBACK_TEMP_C = 18.0

fun hydrationAccounting(
    days: List<HydrationDayInputs>,
    profile: SweatProfile,
    weightKg: Double,
    baselineLowMl: Double,
    baselineHighMl: Double,
): List<DayHydration> = days.sortedBy { it.date }.map { d ->
    var low = 0.0
    var high = 0.0
    d.workouts.forEach { w ->
        val sweat = predictSweatRate(profile, hydrationIntensity(w.minutes, w.rpe), "run", d.tempC ?: HYDRATION_FALLBACK_TEMP_C, weightKg)
        low += sweat.lowMlPerHour * w.minutes / 60.0
        high += sweat.highMlPerHour * w.minutes / 60.0
    }
    DayHydration(d.date, d.intakeMl, baselineLowMl, baselineHighMl, low, high, d.workouts.sumOf { it.minutes }, d.workouts.isEmpty() || d.tempC != null)
}
