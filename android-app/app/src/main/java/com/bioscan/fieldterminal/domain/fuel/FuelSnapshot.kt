package com.bioscan.fieldterminal.domain.fuel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

// What a fuel plan said, kept compactly (supabase/migrations/20261026_fuel_plans.sql; docs/fuel-planning/README.md,
// phase 6): one row each time the plan for a day or an event changes, so the app can say what changed since it was last
// shown ("run moved: breakfast is now HIGH"; "forecast now 27 °C: fluid up 150 ml an hour") and the review after an event
// reads the plan as it stood.

@Serializable
data class MealSnapshot(val kind: String, val time: String, val code: String, @SerialName("carb_mid_g") val carbMidG: Int)

@Serializable
data class SessionSnapshot(val title: String, val start: String?, @SerialName("duration_min") val durationMin: Int, val intensity: String)

@Serializable
data class DaySnapshot(
    val date: String,
    @SerialName("day_class") val dayClass: String,
    @SerialName("carb_g") val carbG: Int,
    @SerialName("protein_g") val proteinG: Int,
    @SerialName("fat_g") val fatG: Int? = null,
    val kcal: Int? = null,
    val meals: List<MealSnapshot> = emptyList(),
    val sessions: List<SessionSnapshot> = emptyList(),
)

@Serializable
data class EventSnapshot(
    @SerialName("duration_min") val durationMin: Int,
    @SerialName("carbs_g_per_h") val carbsGPerH: Int,
    @SerialName("fluid_ml_per_h") val fluidMlPerH: Int,
    @SerialName("sodium_mg_per_l") val sodiumMgPerL: Int,
    @SerialName("max_temp_c") val maxTempC: Int? = null,
    @SerialName("weather_source") val weatherSource: String,
    @SerialName("total_carbs_g") val totalCarbsG: Int,
    @SerialName("total_fluid_ml") val totalFluidMl: Int,
    @SerialName("caffeine_mg") val caffeineMg: Int = 0,
    val steps: List<StepSnapshot> = emptyList(),
)

@Serializable
data class StepSnapshot(
    @SerialName("elapsed_min") val elapsedMin: Int,
    @SerialName("carbs_g") val carbsG: Int,
    @SerialName("fluid_ml") val fluidMl: Int,
    @SerialName("sodium_mg") val sodiumMg: Int,
    @SerialName("caffeine_mg") val caffeineMg: Int = 0,
    val items: List<String> = emptyList(),
)

fun snapshotOf(plan: DayFuelPlan, coded: CodedDay): DaySnapshot = DaySnapshot(
    date = plan.date.toString(),
    dayClass = plan.dayClass.name,
    carbG = plan.carbG.roundToInt(),
    proteinG = plan.proteinG.roundToInt(),
    fatG = plan.fatG?.roundToInt(),
    kcal = plan.kcal?.roundToInt(),
    meals = coded.meals.map { MealSnapshot(it.kind.name, it.time.toLocalTime().toString(), it.code.name, it.carbMidG.roundToInt()) },
    sessions = coded.sessions.map { SessionSnapshot(it.item.title, it.item.start?.toLocalTime()?.toString(), it.item.durationMin.roundToInt(), it.item.intensity.name) },
)

fun snapshotOf(plan: RaceFuelPlan): EventSnapshot = EventSnapshot(
    durationMin = plan.durationMin.roundToInt(),
    carbsGPerH = plan.carbsPerHourHigh.roundToInt(),
    fluidMlPerH = ((plan.fluidMlPerHourLow + plan.fluidMlPerHourHigh) / 2).roundToInt(),
    sodiumMgPerL = plan.sodiumMgPerL.roundToInt(),
    maxTempC = plan.heat?.maxTempC?.roundToInt(),
    weatherSource = plan.weatherSource.name,
    totalCarbsG = plan.totalCarbsG.roundToInt(),
    totalFluidMl = plan.totalFluidMl.roundToInt(),
    caffeineMg = plan.totalCaffeineMg.roundToInt(),
    steps = plan.steps.map { StepSnapshot(it.elapsedMin.roundToInt(), it.carbsG.roundToInt(), it.fluidMl.roundToInt(), it.sodiumMg.roundToInt(), it.caffeineMg.roundToInt(), it.items) },
)

/** What changed in a day's plan since [old], in a few short lines; empty when nothing a person would notice did. */
fun describeChanges(old: DaySnapshot, new: DaySnapshot): List<String> {
    val lines = mutableListOf<String>()
    new.sessions.forEach { n ->
        val was = old.sessions.firstOrNull { it.title == n.title }
        when {
            was == null -> lines += "Added: ${n.title}${n.start?.let { " at $it" } ?: ""}."
            was.start != n.start -> lines += "${n.title} moved to ${n.start ?: "no set time"} (was ${was.start ?: "no set time"})."
            was.intensity != n.intensity -> lines += "${n.title} is now ${n.intensity.lowercase()} (was ${was.intensity.lowercase()})."
        }
    }
    old.sessions.filter { o -> new.sessions.none { it.title == o.title } }.forEach { lines += "Dropped: ${it.title}." }
    if (old.dayClass != new.dayClass) lines += "The day is now ${new.dayClass} (was ${old.dayClass})."
    if (abs(new.carbG - old.carbG) >= 30) lines += "Carbohydrate ${if (new.carbG > old.carbG) "up" else "down"} to ${new.carbG} g (was ${old.carbG})."
    new.meals.forEach { m ->
        val was = old.meals.firstOrNull { it.kind == m.kind } ?: return@forEach
        if (was.code != m.code) lines += "${m.kind.replace('_', '-').lowercase().replaceFirstChar { it.uppercase() }} is now ${m.code} (was ${was.code})."
    }
    return lines
}

fun describeChanges(old: EventSnapshot, new: EventSnapshot): List<String> {
    val lines = mutableListOf<String>()
    if (old.weatherSource != new.weatherSource && new.weatherSource == WeatherSource.FORECAST.name) lines += "The forecast now covers the race."
    if (old.maxTempC != null && new.maxTempC != null && abs(new.maxTempC - old.maxTempC) >= 2) lines += "Expected up to ${new.maxTempC} °C (was ${old.maxTempC})."
    if (abs(new.fluidMlPerH - old.fluidMlPerH) >= 50) lines += "Fluid ${if (new.fluidMlPerH > old.fluidMlPerH) "up" else "down"} to ${new.fluidMlPerH} ml an hour (was ${old.fluidMlPerH})."
    if (abs(new.carbsGPerH - old.carbsGPerH) >= 5) lines += "Carbohydrate now ${new.carbsGPerH} g an hour (was ${old.carbsGPerH})."
    if (abs(new.durationMin - old.durationMin) >= 5) lines += "Planned duration ${new.durationMin} min (was ${old.durationMin})."
    if (new.sodiumMgPerL != old.sodiumMgPerL) lines += "Sodium ${new.sodiumMgPerL} mg per litre (was ${old.sodiumMgPerL})."
    return lines
}
