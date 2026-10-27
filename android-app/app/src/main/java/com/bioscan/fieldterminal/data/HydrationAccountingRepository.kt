package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.fuel.DayHydration
import com.bioscan.fieldterminal.domain.fuel.HydrationDayInputs
import com.bioscan.fieldterminal.domain.fuel.HydrationWorkout
import com.bioscan.fieldterminal.domain.fuel.hydrationAccounting
import com.bioscan.fieldterminal.domain.localDateIn
import com.bioscan.fieldterminal.data.model.BeverageItemRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// Hydration accounting inputs (domain/fuel/HydrationAccounting.kt): per day the logged intake (the water log plus the
// effective hydration of logged drinks), the sessions, and the temperature each session day had at home (the archive, then
// the forecast endpoint for days the archive has not caught up with; unknown otherwise). 28 days, because every session day
// is one weather request. Every timestamp is placed on its local day (domain/LocalDates.kt), the same days the chart uses:
// Postgres returns them in UTC, and a timestamp's own date put a morning session or breakfast on the day before in UTC+8.

private const val MIN_SESSION_MINUTES = 10.0

private data class Session(val start: OffsetDateTime, val day: LocalDate, val minutes: Double, val rpe: Int?, val type: String)
private const val FALLBACK_WEIGHT_KG = 70.0

class HydrationAccountingRepository(private val supabase: SupabaseClient) {

    suspend fun load(
        overview: NutritionOverview,
        home: Pair<Double, Double>?,
        goals: NutritionGoals,
        days: Int = 28,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DayHydration> {
        val today = LocalDate.now(zone)
        val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val first = dates.first()

        val water = overview.hydrationHistory.mapNotNull { r -> r.ml?.let { LocalDate.parse(r.date) to it.toDouble() } }.toMap()
        val mealDay = overview.meals.mapNotNull { m -> m.id?.let { id -> localDateIn(m.loggedAt, zone)?.let { id to it } } }.toMap().filterValues { it >= first }
        // Optional reads: a failure leaves that input out, cancellation still cancels (orNullUnlessCancelled, not runCatching).
        val drinks = if (mealDay.isEmpty()) emptyList() else orNullUnlessCancelled {
            supabase.postgrest.from("meal_items")
                .select(columns = Columns.list("meal_id,description,water_ml,caffeine_mg,effective_hydration_ml")) {
                    filter {
                        isIn("meal_id", mealDay.keys.toList())
                        eq("is_beverage", true)
                    }
                }
                .decodeList<BeverageItemRow>()
        } ?: emptyList()
        val drinkMl = drinks.mapNotNull { r -> r.effectiveHydrationMl?.let { ml -> mealDay[r.mealId]?.let { d -> d to ml } } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sum() }

        val bodyRows = orNullUnlessCancelled { AnalysisRepository(supabase).loadBodyMetrics() } ?: emptyList()
        val weight = bodyRows.lastOrNull { it.weightKg != null }?.weightKg ?: FALLBACK_WEIGHT_KG
        val profile = SweatTestsRepository(supabase).profile(weight)

        val sessions = (orNullUnlessCancelled { AnalysisRepository(supabase).loadExerciseSessionsForTrainingLoad() } ?: emptyList())
            .mapNotNull { s ->
                val start = runCatching { OffsetDateTime.parse(s.startTime) }.getOrNull() ?: return@mapNotNull null
                val day = start.atZoneSameInstant(zone).toLocalDate()
                val minutes = s.durationMin ?: return@mapNotNull null
                if (day < first || minutes < MIN_SESSION_MINUTES) null else Session(start, day, minutes, s.rpe, s.type ?: "run")
            }
        val weather = WeatherRepository()
        // One request per session day, together, and remembered: the archive does not change for a past day.
        val tempByDay = coroutineScope {
            sessions.groupBy { it.day }.map { (day, list) ->
                async {
                    day to home?.let { (lat, lon) ->
                        val at = list.first().start.atZoneSameInstant(zone)
                        orNullUnlessCancelled {
                            ReadCache.get("hydration.temp.$day.$lat,$lon", tables = setOf("exercise_sessions")) {
                                // The archive first; the forecast endpoint for days the archive has not caught up with.
                                orNullUnlessCancelled { weather.fetchHistoricalAt(lat, lon, at).temperatureC }
                                    ?: weather.fetchAt(lat, lon, at).temperatureC
                            }
                        }
                    }
                }
            }.awaitAll().toMap()
        }

        val inputs = dates.map { d ->
            val water1 = water[d]
            val drink1 = drinkMl[d]
            HydrationDayInputs(
                date = d,
                intakeMl = if (water1 == null && drink1 == null) null else (water1 ?: 0.0) + (drink1 ?: 0.0),
                workouts = sessions.filter { it.day == d }.map { HydrationWorkout(it.minutes, it.rpe, it.type) },
                tempC = tempByDay[d],
            )
        }
        val bounds = hydrationBoundsMl(goals, 0.0)
        return hydrationAccounting(inputs, profile, weight, bounds.start, bounds.endInclusive)
    }
}
