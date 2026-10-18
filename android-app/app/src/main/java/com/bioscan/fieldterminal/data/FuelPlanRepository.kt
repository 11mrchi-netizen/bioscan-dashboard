package com.bioscan.fieldterminal.data

import android.content.Context
import com.bioscan.fieldterminal.domain.ActivityLevel
import com.bioscan.fieldterminal.domain.EnergyBalanceKind
import com.bioscan.fieldterminal.domain.energyBalance
import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.events.EventPriority
import com.bioscan.fieldterminal.domain.events.EventSport
import com.bioscan.fieldterminal.domain.fuel.CodedDay
import com.bioscan.fieldterminal.domain.fuel.DayFuelPlan
import com.bioscan.fieldterminal.domain.fuel.DaySnapshot
import com.bioscan.fieldterminal.domain.fuel.EventSnapshot
import com.bioscan.fieldterminal.domain.fuel.FuelProductOption
import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.IntakeHistoryResult
import com.bioscan.fieldterminal.domain.fuel.LoggedDay
import com.bioscan.fieldterminal.domain.fuel.MealAdherence
import com.bioscan.fieldterminal.domain.fuel.PlannedDay
import com.bioscan.fieldterminal.domain.fuel.PlannedItem
import com.bioscan.fieldterminal.domain.fuel.RaceFuelInput
import com.bioscan.fieldterminal.domain.fuel.RaceFuelPlan
import com.bioscan.fieldterminal.domain.fuel.RecentRun
import com.bioscan.fieldterminal.domain.fuel.WeatherSeries
import com.bioscan.fieldterminal.domain.fuel.WeekEnergy
import com.bioscan.fieldterminal.domain.fuel.adherence
import com.bioscan.fieldterminal.domain.fuel.checkIntakeHistory
import com.bioscan.fieldterminal.domain.fuel.codeMeals
import com.bioscan.fieldterminal.domain.fuel.describeChanges
import com.bioscan.fieldterminal.domain.fuel.durationRange
import com.bioscan.fieldterminal.domain.fuel.estimateRunTime
import com.bioscan.fieldterminal.domain.fuel.fatFreeMass
import com.bioscan.fieldterminal.domain.fuel.loadingFor
import com.bioscan.fieldterminal.domain.fuel.mealForLog
import com.bioscan.fieldterminal.domain.fuel.planFuelDays
import com.bioscan.fieldterminal.domain.fuel.planRaceFuel
import com.bioscan.fieldterminal.domain.fuel.snapshotOf
import com.bioscan.fieldterminal.domain.fuel.withCatchUp
import com.bioscan.fieldterminal.domain.maintenanceEstimate
import com.bioscan.fieldterminal.domain.restingEnergyKcal
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime

// Puts the fuel engine together for the screens (docs/fuel-planning/README.md, phase 6): the planned days, the goal and
// maintenance, body weight and composition, the days before, the weather, the sweat profile, the person's products and
// settings; and keeps a copy of each plan when it changes (fuel_plans) so the screens can say what changed.

const val FUEL_ENGINE_VERSION = "1"

data class TodayFuel(
    val today: DayFuelPlan,
    val todayCoded: CodedDay,
    val tomorrow: DayFuelPlan?,
    val tomorrowCoded: CodedDay?,
    val history: IntakeHistoryResult,
    val changes: List<String>,
    val energyFromGoal: Boolean,
    val adherence: Map<String, Pair<Double, MealAdherence>>,
    val longSessions: List<Pair<PlannedItem, RaceFuelPlan>>,
    /** An A or B race within 14 days that the plan loads for, with the date loading starts. */
    val loadingCountdown: Pair<PlannedItem, LocalDate>?,
    val weightKg: Double,
)

sealed interface TodayFuelResult {
    data class Ready(val fuel: TodayFuel) : TodayFuelResult
    data object NeedsWeight : TodayFuelResult
}

data class EventFuel(val event: AthleteEvent, val plan: RaceFuelPlan, val changes: List<String>, val loadedAt: ZonedDateTime)

@Serializable
private data class BodyCompRow(
    val date: String,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("lean_mass_kg") val leanMassKg: Double? = null,
    @SerialName("body_fat_pct") val bodyFatPct: Double? = null,
)

@Serializable
private data class FuelPlanRow(
    val kind: String,
    @SerialName("ref_key") val refKey: String,
    @SerialName("engine_version") val engineVersion: String,
    @SerialName("inputs_hash") val inputsHash: String,
    val plan: JsonObject,
)

@Serializable
private data class RunRow(
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
    @SerialName("duration_min") val durationMin: Double? = null,
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class FuelPlanRepository(private val supabase: SupabaseClient) {

    private suspend fun bodyComposition(): List<BodyCompRow> = ReadCache.get("fuel.body_comp", tables = setOf("body_metrics")) {
        supabase.postgrest.from("body_metrics")
            .select(Columns.list("date,weight_kg,lean_mass_kg,body_fat_pct")) { order("date", Order.DESCENDING); limit(30) }
            .decodeList<BodyCompRow>()
    }

    private suspend fun weekEnergy(context: Context, inputs: MaintenanceInputs): WeekEnergy {
        val goals = NutritionGoalsStore.getGoals(context)
        val resting = restingEnergyKcal(inputs.sex, inputs.ageYears, inputs.heightCm, inputs.weightKg)
        val maintenance = maintenanceEstimate(resting, ActivityLevel.of(goals.activityLevel), inputs.weeklyTrainingMin)?.kcal
        val goalKcal = goals.caloriesKcal
        val deficit = goalKcal != null && maintenance != null && energyBalance(goalKcal, maintenance).kind == EnergyBalanceKind.DEFICIT
        return WeekEnergy(dailyKcal = goalKcal ?: maintenance, proteinG = goals.proteinG, fromGoal = goalKcal != null, deficit = deficit)
    }

    suspend fun loadToday(context: Context, overview: NutritionOverview?, now: ZonedDateTime = ZonedDateTime.now()): TodayFuelResult = coroutineScope {
        val today = now.toLocalDate()
        val zone = now.zone
        val settings = FuelSettingsStore.get(context)
        val daysD = async { PlannedDayRepository(supabase).load(today.minusDays(3), days = 17, now = now) }
        val inputsD = async { runCatching { NutritionTargetsRepository(supabase).loadMaintenanceInputs(today) }.getOrDefault(MaintenanceInputs()) }
        val bodyD = async { runCatching { bodyComposition() }.getOrDefault(emptyList()) }
        val inputs = inputsD.await()
        val body = bodyD.await()
        val kg = inputs.weightKg ?: body.firstNotNullOfOrNull { it.weightKg } ?: return@coroutineScope TodayFuelResult.NeedsWeight
        val latest = body.firstOrNull { it.leanMassKg != null || it.bodyFatPct != null }
        val ffm = fatFreeMass(kg, latest?.leanMassKg, latest?.bodyFatPct)
        val energy = weekEnergy(context, inputs)
        val days = daysD.await()
        val todayIdx = days.indexOfFirst { it.date == today }
        val week = days.drop(todayIdx).take(7)
        val plans = planFuelDays(days, energy, kg, ffm, week = week)

        // The days before, against what was planned for them.
        val logged = overview?.allDays.orEmpty().mapNotNull { d ->
            runCatching { LoggedDay(LocalDate.parse(d.date), d.mealCount, d.carbsG, d.calories) }.getOrNull()
        }
        val raceSoon = days.filter { it.date == today || it.date == today.plusDays(1) }.flatMap { it.items }
            .any { it.intensity == Intensity.RACE && it.eventPriority != EventPriority.C }
        val weights = body.filter { it.weightKg != null }
        val morning = weights.firstOrNull { it.date == today.toString() }?.weightKg
        val usual = weights.filter { it.date != today.toString() }.take(7).mapNotNull { it.weightKg }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val history = checkIntakeHistory(plans.take(todayIdx), logged, kg, raceSoon, morning, usual)

        val todayPlan = withCatchUp(plans[todayIdx], if (raceSoon) history.catchUpG else 0.0)
        val tomorrowPlan = plans.getOrNull(todayIdx + 1)
        fun code(i: Int, p: DayFuelPlan) = codeMeals(
            p, days[i], days.getOrNull(i + 1), kg, zone, settings.meals, settings.trainLow, energy.deficit, settings.toleratedGPerHour,
        )
        val todayCoded = code(todayIdx, todayPlan)
        val tomorrowCoded = tomorrowPlan?.let { code(todayIdx + 1, it) }

        // Logged meals today, placed in their slots.
        val bySlot = mutableMapOf<String, Double>()
        overview?.todaysMeals.orEmpty().forEach { m ->
            val at = runCatching { OffsetDateTime.parse(m.loggedAt).atZoneSameInstant(zone) }.getOrNull() ?: return@forEach
            val slot = mealForLog(todayCoded.meals, at) ?: return@forEach
            bySlot[slot.kind.name] = (bySlot[slot.kind.name] ?: 0.0) + (m.carbsG ?: 0.0)
        }
        val adherenceMap = todayCoded.meals.associate { meal -> meal.kind.name to ((bySlot[meal.kind.name] ?: 0.0) to adherence(meal, bySlot[meal.kind.name])) }

        // Long sessions in the next three days (events have their own page).
        val sweat = runCatching { SweatTestsRepository(supabase).profile(kg) }.getOrNull() ?: com.bioscan.fieldterminal.domain.fuel.sweatProfile(emptyList(), kg)
        val home = MapSettingsStore.getHome(context)
        val longSessions = days.filter { !it.date.isBefore(today) && it.date.isBefore(today.plusDays(3)) }
            .flatMap { it.items }
            .filter { it.durationMin >= 90 && it.eventId == null && !it.recorded && it.start != null }
            .map { item ->
                val weather = home?.let { (lat, lon) ->
                    runCatching { WeatherRepository().fetchHourly(lat, lon, item.start!!.minusHours(1), item.start.plusMinutes(item.durationMin.toLong() + 60)) }.getOrNull()
                } ?: WeatherSeries.NONE
                item to planRaceFuel(
                    RaceFuelInput(
                        item.title, item.type, item.intensity, item.start!!, item.durationMin, item.durationMin * 0.9, item.durationMin * 1.1,
                        "the planned session length", weather = weather, sweat = sweat, weightKg = kg, settings = settings.race,
                        products = runCatching { products() }.getOrDefault(emptyList()),
                    ),
                )
            }

        val countdown = (0 until days.size).asSequence()
            .mapNotNull { i -> loadingFor(days[i].date, days)?.let { race -> race to days[i].date } }
            .firstOrNull { (_, start) -> !start.isBefore(today) && start.isBefore(today.plusDays(14)) }

        val snapshot = snapshotOf(todayPlan, todayCoded)
        val changes = changesSince("day", today.toString(), json.encodeToJsonElement(snapshot).jsonObject) { old ->
            describeChanges(json.decodeFromJsonElement<DaySnapshot>(old), snapshot)
        }

        TodayFuelResult.Ready(
            TodayFuel(todayPlan, todayCoded, tomorrowPlan, tomorrowCoded, history, changes, energy.fromGoal, adherenceMap, longSessions, countdown, kg),
        )
    }

    /** The race or long-day plan for an event. */
    suspend fun loadEvent(context: Context, eventId: Long, now: ZonedDateTime = ZonedDateTime.now()): EventFuel? = coroutineScope {
        val event = AthleteEventsRepository(supabase).load(eventId) ?: return@coroutineScope null
        val settings = FuelSettingsStore.get(context)
        val zone = now.zone
        val inputs = runCatching { NutritionTargetsRepository(supabase).loadMaintenanceInputs() }.getOrDefault(MaintenanceInputs())
        val body = runCatching { bodyComposition() }.getOrDefault(emptyList())
        val kg = inputs.weightKg ?: body.firstNotNullOfOrNull { it.weightKg } ?: 70.0
        val start = (event.start ?: event.date.atTime(9, 0).atZone(zone)).withZoneSameInstant(zone)
        val runs = if (event.sport == EventSport.RUN || event.sport == EventSport.TRAIL_RUN) runCatching { recentRuns() }.getOrDefault(emptyList()) else emptyList()
        val estimate = event.distanceKm?.let { estimateRunTime(it, event.ascentM ?: 0.0, runs) }
        val (duration, range, basis) = durationRange(event.targetTimeMin, event.expectedDurationMin, estimate) ?: return@coroutineScope null
        val place = event.startLat?.let { lat -> event.startLon?.let { lat to it } } ?: MapSettingsStore.getHome(context)
        val weather = place?.let { (lat, lon) ->
            runCatching { WeatherRepository().fetchHourly(lat, lon, start.minusHours(1), start.plusMinutes(duration.toLong() + 60)) }.getOrNull()
        } ?: WeatherSeries.NONE
        val sweat = runCatching { SweatTestsRepository(supabase).profile(kg) }.getOrNull() ?: com.bioscan.fieldterminal.domain.fuel.sweatProfile(emptyList(), kg)
        val intensity = when {
            event.kind == com.bioscan.fieldterminal.domain.events.EventKind.RACE && event.priority != EventPriority.C -> Intensity.RACE
            duration > 90 -> Intensity.LONG
            else -> Intensity.HARD
        }
        // The days before, when the event is today or tomorrow.
        val daysToGo = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), event.date)
        val history = if (daysToGo in 0..1) {
            runCatching { (loadToday(context, NutritionRepository(supabase).loadOverview(), now) as? TodayFuelResult.Ready)?.fuel?.history }.getOrNull()
        } else {
            null
        }
        val plan = planRaceFuel(
            RaceFuelInput(
                title = event.name, type = event.sport.sessionTypes.first(), intensity = intensity, start = start,
                durationMin = duration, durationLowMin = range.first, durationHighMin = range.second, durationBasis = basis,
                course = event.course, distanceKm = event.distanceKm, aidStations = event.aidStations, weather = weather,
                sweat = sweat, weightKg = kg, settings = settings.race, products = runCatching { products() }.getOrDefault(emptyList()),
                history = history, loadingDays = intensity == Intensity.RACE && duration > 90,
            ),
        )
        val snapshot = snapshotOf(plan)
        val changes = changesSince("event", eventId.toString(), json.encodeToJsonElement(snapshot).jsonObject) { old ->
            describeChanges(json.decodeFromJsonElement<EventSnapshot>(old), snapshot)
        }
        EventFuel(event, plan, changes, now)
    }

    /** The person's fuel products with their newest recipe. */
    suspend fun products(): List<FuelProductOption> = ReadCache.get("fuel.products", tables = setOf("fueling_products", "fueling_recipe_versions")) {
        FuelingRepository(supabase).loadProducts().mapNotNull { p ->
            val v = p.latest ?: return@mapNotNull null
            val carbs = v.carbsG ?: return@mapNotNull null
            FuelProductOption(p.product.id, listOfNotNull(p.product.brand, p.product.name).joinToString(" "), p.product.kind, carbs, v.fluidMl, v.sodiumMg, v.caffeineMg, v.fructoseG)
        }
    }

    private suspend fun recentRuns(): List<RecentRun> =
        supabase.postgrest.from("exercise_sessions")
            .select(Columns.list("distance_km,elevation_gain_m,duration_min")) {
                filter {
                    eq("type", "run")
                    gte("start_time", LocalDate.now().minusDays(120).toString())
                }
                order("start_time", Order.DESCENDING)
                limit(60)
            }
            .decodeList<RunRow>()
            .mapNotNull { r -> if (r.distanceKm == null || r.durationMin == null) null else RecentRun(r.distanceKm, r.elevationGainM ?: 0.0, r.durationMin) }

    /** The lines last shown for each plan, kept until the plan changes again (a reload of the same plan keeps them). */
    private companion object {
        val lastChanges = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    }

    private suspend fun changesSince(kind: String, ref: String, plan: JsonObject, describe: (JsonObject) -> List<String>): List<String> {
        val key = "$kind:$ref"
        val result = runCatching { remember(kind, ref, plan) }.getOrNull() ?: return lastChanges[key].orEmpty()
        val lines = if (result.second != null) runCatching { describe(result.second!!) }.getOrDefault(emptyList()) else emptyList()
        if (result.first) lastChanges[key] = lines
        return lastChanges[key].orEmpty()
    }

    /**
     * Stores [plan] when it differs from the last one kept for ([kind], [ref]). Returns whether it was stored and the plan
     * it replaced (null when there was none, or it came from another engine version).
     */
    private suspend fun remember(kind: String, ref: String, plan: JsonObject): Pair<Boolean, JsonObject?> {
        val hash = plan.toString().hashCode().toString(16)
        val last = supabase.postgrest.from("fuel_plans")
            .select(Columns.list("kind,ref_key,engine_version,inputs_hash,plan")) {
                filter { eq("kind", kind); eq("ref_key", ref) }
                order("created_at", Order.DESCENDING)
                limit(1)
            }
            .decodeList<FuelPlanRow>().firstOrNull()
        if (last?.inputsHash == hash) return false to null
        supabase.postgrest.from("fuel_plans").insert(FuelPlanRow(kind, ref, FUEL_ENGINE_VERSION, hash, plan))
        return true to last?.takeIf { it.engineVersion == FUEL_ENGINE_VERSION }?.plan
    }
}
