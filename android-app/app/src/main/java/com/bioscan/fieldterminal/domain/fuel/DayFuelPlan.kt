package com.bioscan.fieldterminal.domain.fuel

import com.bioscan.fieldterminal.domain.KCAL_PER_G_CARBS
import com.bioscan.fieldterminal.domain.KCAL_PER_G_FAT
import com.bioscan.fieldterminal.domain.KCAL_PER_G_PROTEIN
import com.bioscan.fieldterminal.domain.MacroPlan
import com.bioscan.fieldterminal.domain.events.EventPriority
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

// A day's carbohydrate, protein, fat and energy targets, shaped by the training of the next 72 hours
// (docs/fuel-planning/README.md, phase 3; "fuel for the work required", Impey et al. 2016, 2018).
//
// Carbohydrate per kg of body weight follows the training of the day (Thomas, Erdman & Burke 2016): light days 3-5 g/kg,
// about an hour of moderate work 5-7, 1-3 hours 6-10, over 4 hours 8-12; within a band, harder days sit higher. The day
// before a hard, long or race morning moves up a step. Before an A or B race of more than 90 minutes the two days before
// carb-load at 10-12 g/kg (Burke 2011).
//
// The person's nutrition goal sets the week: its calories stay the weekly average, and each day gets the goal plus that
// day's session energy minus the week's mean session energy, so a long Sunday eats more and a rest day less while the
// deficit, surplus or maintenance the goal stands for is kept. Protein stays at the goal (1.8 g/kg without one, ISSN
// 2017). Fat is what remains, never under 20 % of energy (IOM): when the floor binds, carbohydrate gives way (first the
// step-up for tomorrow, then down to the low end of the day's own band); below that the day's energy rises and says so
// (loading days always may).

enum class DayClass(val label: String) { LOW("LOW"), MEDIUM("MEDIUM"), HIGH("HIGH"), LOADING("LOADING") }

data class CarbBand(val lowGPerKg: Double, val highGPerKg: Double, val name: String)

val BAND_LIGHT = CarbBand(3.0, 5.0, "light day")
val BAND_MODERATE = CarbBand(5.0, 7.0, "about an hour of training")
val BAND_HIGH = CarbBand(6.0, 10.0, "1-3 hours of training")
val BAND_VERY_HIGH = CarbBand(8.0, 12.0, "over 4 hours of training")
val BAND_LOADING = CarbBand(10.0, 12.0, "carbohydrate loading")
private val BAND_STEPS = listOf(BAND_LIGHT, BAND_MODERATE, BAND_HIGH, BAND_VERY_HIGH)

const val DEFAULT_PROTEIN_G_PER_KG = 1.8
const val FAT_FLOOR_SHARE = 0.20
const val LOADING_RACE_MIN = 90.0
const val LOW_ENERGY_AVAILABILITY = 30.0

/** What the nutrition goal (or, without one, the maintenance estimate) gives the week. */
data class WeekEnergy(
    /** The weekly average daily energy, kcal: the goal's calories, else estimated maintenance; null when neither is known. */
    val dailyKcal: Double?,
    /** Protein, g/day: the goal's, else [DEFAULT_PROTEIN_G_PER_KG] g/kg. */
    val proteinG: Double?,
    /** True when [dailyKcal] is the person's goal, false when it is the maintenance estimate. */
    val fromGoal: Boolean,
    /** True when the goal is a deficit (below maintenance beyond the maintenance band). */
    val deficit: Boolean = false,
    /** True when maintenance is the expenditure observed from intake and weight, false when it is the equation's. */
    val observedMaintenance: Boolean = false,
)

data class DayFuelPlan(
    val date: LocalDate,
    val dayClass: DayClass,
    val band: CarbBand,
    /** g/kg aimed at, within [band]. */
    val carbGPerKg: Double,
    val carbG: Double,
    val proteinG: Double,
    val fatG: Double?,
    val kcal: Double?,
    val reasons: List<String>,
    val notes: List<String>,
    val loading: Boolean,
    /** (kcal - session kcal) / fat-free mass, kcal/kg; null without body composition or energy. */
    val energyAvailability: Double?,
    val sessionKcal: Double,
) {
    val carbRangeG: ClosedFloatingPointRange<Double> get() = (carbG * 0.9)..(carbG * 1.1)
    val macroPlan: MacroPlan? get() = if (kcal != null && fatG != null) MacroPlan(kcal, proteinG, carbG, fatG) else null
}

/** The band a day's training puts it in, before the 72-hour adjustment. */
fun bandForTraining(day: PlannedDay): CarbBand {
    val minutes = day.trainingMin
    val hardest = day.hardest
    return when {
        day.isRest -> BAND_LIGHT
        minutes > 240 -> BAND_VERY_HIGH
        minutes > 75 -> BAND_HIGH
        minutes < 45 && hardest == Intensity.EASY -> BAND_LIGHT
        else -> BAND_MODERATE
    }
}

private fun stepUp(band: CarbBand): CarbBand = BAND_STEPS.getOrNull(BAND_STEPS.indexOf(band) + 1) ?: band

/** Where in its band a day sits: easy days low, hard days high. */
private fun positionInBand(day: PlannedDay): Double = when (day.hardest) {
    null, Intensity.EASY -> 0.25
    Intensity.MODERATE -> 0.5
    Intensity.HARD, Intensity.LONG -> 0.75
    Intensity.RACE -> 0.9
}

/** The A or B race over [LOADING_RACE_MIN] minutes on [date + 1] or [date + 2] that [date] loads for, if any. */
fun loadingFor(date: LocalDate, days: List<PlannedDay>): PlannedItem? =
    days.filter { it.date == date.plusDays(1) || it.date == date.plusDays(2) }
        .flatMap { it.items }
        .firstOrNull {
            it.intensity == Intensity.RACE && it.eventPriority != EventPriority.C && it.durationMin > LOADING_RACE_MIN
        }

private fun keyMorningSession(day: PlannedDay?): PlannedItem? =
    day?.items?.firstOrNull { it.intensity.isKey && (it.start?.hour ?: 12) < 12 }

/**
 * The plan for [day]. [week] holds the days whose session energy averages out to the goal (normally the 7 days from
 * today); [next] is the day after [day], for the 72-hour step. [fatFreeMassKg] for energy availability; optional.
 */
fun planFuelDay(
    day: PlannedDay,
    next: PlannedDay?,
    week: List<PlannedDay>,
    energy: WeekEnergy,
    weightKg: Double,
    fatFreeMassKg: Double? = null,
    allDays: List<PlannedDay> = week,
): DayFuelPlan {
    val reasons = mutableListOf<String>()
    val notes = mutableListOf<String>()

    val loadingRace = loadingFor(day.date, allDays)
    var band = bandForTraining(day)
    // The day's own band: carbohydrate may give way down to its low end (the 72-hour step-up is the first to go).
    val ownBand = band
    reasons += when {
        day.isRest -> "Rest day: ${BAND_LIGHT.lowGPerKg.toInt()}-${BAND_LIGHT.highGPerKg.toInt()} g/kg."
        else -> "${day.items.joinToString(" + ") { "${it.title} (${it.durationMin.roundToInt()} min, ${it.intensity.label.lowercase()})" }}: ${band.name}."
    }
    val keyTomorrow = keyMorningSession(next)
    if (loadingRace == null && keyTomorrow != null && band != BAND_VERY_HIGH) {
        band = stepUp(band)
        reasons += "Tomorrow morning: ${keyTomorrow.title} (${keyTomorrow.intensity.label.lowercase()}), so a step higher today."
    }
    var position = positionInBand(day)
    if (loadingRace != null) {
        band = BAND_LOADING
        position = 0.5
        reasons += "Carbohydrate loading for ${loadingRace.title}: ${BAND_LOADING.lowGPerKg.toInt()}-${BAND_LOADING.highGPerKg.toInt()} g/kg."
        notes += "Low in fibre and fat the day before the race: easier on the gut."
    }
    val dayClass = when {
        loadingRace != null -> DayClass.LOADING
        else -> {
            val g = band.lowGPerKg + (band.highGPerKg - band.lowGPerKg) * position
            when {
                g < 5.0 -> DayClass.LOW
                g < 8.0 -> DayClass.MEDIUM
                else -> DayClass.HIGH
            }
        }
    }
    var carbPerKg = band.lowGPerKg + (band.highGPerKg - band.lowGPerKg) * position
    var carbG = carbPerKg * weightKg
    val proteinG = energy.proteinG ?: (DEFAULT_PROTEIN_G_PER_KG * weightKg)
    val sessionKcal = day.sessionKcal

    // The day's energy: the week's average plus this day's sessions minus the week's mean session energy.
    var kcal = energy.dailyKcal?.let { base ->
        val meanSession = if (week.isEmpty()) sessionKcal else week.sumOf { it.sessionKcal } / week.size
        base + sessionKcal - meanSession
    }
    var fatG: Double? = null
    if (kcal != null) {
        val fatFloorG = FAT_FLOOR_SHARE * kcal / KCAL_PER_G_FAT
        val left = kcal - proteinG * KCAL_PER_G_PROTEIN - carbG * KCAL_PER_G_CARBS
        if (left / KCAL_PER_G_FAT >= fatFloorG) {
            fatG = left / KCAL_PER_G_FAT
        } else {
            // Carbohydrate gives way to the fat floor, down to its band's low end; below that the day's energy rises.
            val carbRoom = (kcal * (1 - FAT_FLOOR_SHARE) - proteinG * KCAL_PER_G_PROTEIN) / KCAL_PER_G_CARBS
            val bandLowG = (if (loadingRace != null) band else ownBand).lowGPerKg * weightKg
            if (carbRoom >= bandLowG && loadingRace == null) {
                carbG = carbRoom
                carbPerKg = carbG / weightKg
                fatG = FAT_FLOOR_SHARE * kcal / KCAL_PER_G_FAT
                notes += "Carbohydrate kept at %.1f g/kg so fat stays at 20 %% of energy.".format(carbPerKg)
            } else {
                val needed = (proteinG * KCAL_PER_G_PROTEIN + carbG * KCAL_PER_G_CARBS) / (1 - FAT_FLOOR_SHARE)
                notes += if (loadingRace != null) {
                    "Loading day: %d kcal, above the usual %d. Planned, not a surplus.".format(needed.roundToInt(), kcal.roundToInt())
                } else {
                    "Energy raised to %d kcal so the day's training is fuelled (the goal's %d leave too little).".format(needed.roundToInt(), kcal.roundToInt())
                }
                kcal = needed
                fatG = FAT_FLOOR_SHARE * needed / KCAL_PER_G_FAT
            }
        }
    }

    if (energy.deficit && loadingRace == null && day.hardest?.isKey == true) {
        notes += "Deficit goal on a key training day: the calories come off fat, not the training's carbohydrate."
    }
    val ea = if (kcal != null && fatFreeMassKg != null && fatFreeMassKg > 0) (kcal - sessionKcal) / fatFreeMassKg else null
    if (ea != null && ea < LOW_ENERGY_AVAILABILITY) {
        notes += "Energy left after training is %.0f kcal per kg of fat-free mass, under 30: kept there for long, it costs health and adaptation (IOC REDs consensus 2023).".format(ea)
    }
    if (energy.dailyKcal == null) notes += "No calorie goal or maintenance estimate: carbohydrate and protein only."

    return DayFuelPlan(
        date = day.date, dayClass = dayClass, band = band, carbGPerKg = carbPerKg, carbG = carbG, proteinG = proteinG,
        fatG = fatG, kcal = kcal, reasons = reasons, notes = notes, loading = loadingRace != null,
        energyAvailability = ea, sessionKcal = sessionKcal,
    )
}

/** Plans for every day of [days] (ordered by date), each with the next day for the 72-hour step and all of them as the week. */
fun planFuelDays(
    days: List<PlannedDay>,
    energy: WeekEnergy,
    weightKg: Double,
    fatFreeMassKg: Double? = null,
    week: List<PlannedDay> = days.take(7),
): List<DayFuelPlan> {
    return days.mapIndexed { i, d -> planFuelDay(d, days.getOrNull(i + 1), week, energy, weightKg, fatFreeMassKg, days) }
}

/**
 * Plans for [days] when [todayIdx] is today: the goal holds as the average of each block of seven days counted from
 * today (days 0-6, 7-13, ...), so a long horizon does not borrow the first week's mean; days before today use the first
 * block.
 */
fun planInWeekBlocks(
    days: List<PlannedDay>,
    todayIdx: Int,
    energy: WeekEnergy,
    weightKg: Double,
    fatFreeMassKg: Double? = null,
): List<DayFuelPlan> = days.indices.map { i ->
    val block = (i - todayIdx).coerceAtLeast(0) / 7
    val week = days.drop(todayIdx + 7 * block).take(7)
    planFuelDay(days[i], days.getOrNull(i + 1), week, energy, weightKg, fatFreeMassKg, days)
}

/** Fat-free mass from a lean-mass reading, else from weight and body fat; null without either. */
fun fatFreeMass(weightKg: Double?, leanMassKg: Double?, bodyFatPct: Double?): Double? =
    leanMassKg?.takeIf { it > 0 } ?: weightKg?.let { w -> bodyFatPct?.takeIf { it in 2.0..60.0 }?.let { w * (1 - it / 100) } }

/** Carbohydrate in g/kg rounded for display ("6.5 G/KG"). */
fun formatGPerKg(v: Double): String = "%.1f G/KG".format(max(v, 0.0))
