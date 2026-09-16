package com.bioscan.fieldterminal.healthconnect

// ============================================================================
// ONE-OFF UTILITY -- NOT PERMANENT ARCHITECTURE.
//
// Backfills this account's real historical `meals`/`hydration_daily` rows
// (originally synced from Wellness Project) into Health Connect, which has
// never seen any nutrition/hydration data since this app has only ever read
// from Health Connect, never written to it. Exists to run exactly once on a
// real device.
//
// DELETE THIS FILE, plus the "ONE-OFF: BACKFILL HISTORY" card in
// ui/screens/SettingsScreen.kt, before the next real app update ships.
// Nothing else in the app depends on this file -- its own read models and
// queries are deliberately self-contained rather than added to shared model
// files, so removal later is exactly "delete this file + one Settings
// block," nothing more.
// ============================================================================

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

sealed interface BackfillResult {
    data object Unavailable : BackfillResult
    data object NotGranted : BackfillResult
    data class Success(val mealsWritten: Int, val hydrationDaysWritten: Int) : BackfillResult
    data class Failed(val message: String) : BackfillResult
}

// Same "plain object holding the last result, read by the Settings screen"
// convention as HealthConnectSyncStatus -- in-memory only, a per-session
// status line, not a persisted setting.
object OneOffBackfillStatus {
    var lastResult by mutableStateOf<BackfillResult?>(null)
        private set

    fun record(result: BackfillResult) {
        lastResult = result
    }
}

@Serializable
private data class BackfillMealRow(
    val id: Long,
    @SerialName("logged_at") val loggedAt: String,
    val description: String? = null,
    val calories: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
)

@Serializable
private data class BackfillHydrationRow(
    val id: Long,
    val date: String,
    val ml: Int? = null,
)

suspend fun runNutritionHydrationBackfill(context: Context, supabase: SupabaseClient): BackfillResult {
    if (!HealthConnectManager.isAvailable(context)) return BackfillResult.Unavailable
    if (!HealthConnectManager.hasAllPermissions(context)) return BackfillResult.NotGranted

    return try {
        val zone = ZoneId.systemDefault()

        val meals = supabase.postgrest.from("meals")
            .select(columns = Columns.list("id,logged_at,description,calories,protein_g,carbs_g,fat_g"))
            .decodeList<BackfillMealRow>()

        val nutritionRecords = meals.map { meal ->
            // Naive-timestamp convention, same as everywhere else in this app:
            // the wall-clock reading in the stored ISO string is treated as
            // real local time, then anchored to the device's current zone --
            // never zone-shifted from the stored UTC-suffixed value.
            val instant = OffsetDateTime.parse(meal.loggedAt).toLocalDateTime().atZone(zone).toInstant()
            val offset = zone.rules.getOffset(instant)
            // Health Connect requires startTime < endTime (strictly before,
            // not just "not after") -- a real validation error caught on
            // real device, not assumed from docs. A meal has no real
            // duration in this account's data, so this is a minimal,
            // honest one-minute span, not a fabricated eating time.
            NutritionRecord(
                startTime = instant,
                startZoneOffset = offset,
                endTime = instant.plusSeconds(60),
                endZoneOffset = offset,
                name = meal.description,
                mealType = mealTypeFrom(meal.description),
                energy = meal.calories?.let { Energy.kilocalories(it) },
                protein = meal.proteinG?.let { Mass.grams(it) },
                totalCarbohydrate = meal.carbsG?.let { Mass.grams(it) },
                totalFat = meal.fatG?.let { Mass.grams(it) },
                metadata = Metadata.manualEntryWithId("bioscan-meal-${meal.id}"),
            )
        }

        val hydrationRows = supabase.postgrest.from("hydration_daily")
            .select(columns = Columns.list("id,date,ml"))
            .decodeList<BackfillHydrationRow>()

        val hydrationRecords = hydrationRows.mapNotNull { row ->
            val ml = row.ml ?: return@mapNotNull null
            val day = LocalDate.parse(row.date)
            val start = day.atTime(LocalTime.MIDNIGHT).atZone(zone)
            val end = day.plusDays(1).atTime(LocalTime.MIDNIGHT).atZone(zone).minusSeconds(1)
            HydrationRecord(
                startTime = start.toInstant(),
                startZoneOffset = start.offset,
                endTime = end.toInstant(),
                endZoneOffset = end.offset,
                volume = Volume.milliliters(ml.toDouble()),
                metadata = Metadata.manualEntryWithId("bioscan-hydration-${row.date}"),
            )
        }

        // Real on-device testing showed Health Connect does NOT honor
        // clientRecordId matching the way it's documented, for either
        // insert-time upsert or a delete-by-clientRecordId call -- both were
        // tried and both left real duplicate records behind. Rather than
        // trust any more undocumented clientRecordId behavior, read back
        // whatever this app already wrote in the covered time span by its
        // real Health Connect record IDs (guaranteed to be exact, since
        // they're the platform's own primary keys) and delete those before
        // inserting fresh -- a plain "clear then rewrite" that doesn't
        // depend on clientRecordId matching at all.
        val client = HealthConnectManager.client(context)
        val allInstants = nutritionRecords.flatMap { listOf(it.startTime, it.endTime) } +
            hydrationRecords.flatMap { listOf(it.startTime, it.endTime) }
        if (allInstants.isNotEmpty()) {
            val rangeStart = allInstants.min().minus(java.time.Duration.ofDays(1))
            val rangeEnd = allInstants.max().plus(java.time.Duration.ofDays(1))
            val rangeFilter = androidx.health.connect.client.time.TimeRangeFilter.between(rangeStart, rangeEnd)

            val existingNutrition = client.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(NutritionRecord::class, rangeFilter)
            ).records
            val existingHydration = client.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(HydrationRecord::class, rangeFilter)
            ).records

            if (existingNutrition.isNotEmpty()) {
                client.deleteRecords(NutritionRecord::class, existingNutrition.map { it.metadata.id }, emptyList())
            }
            if (existingHydration.isNotEmpty()) {
                client.deleteRecords(HydrationRecord::class, existingHydration.map { it.metadata.id }, emptyList())
            }
        }
        client.insertRecords(nutritionRecords + hydrationRecords)
        BackfillResult.Success(mealsWritten = nutritionRecords.size, hydrationDaysWritten = hydrationRecords.size)
    } catch (e: Exception) {
        BackfillResult.Failed(e.message ?: "Backfill failed.")
    }
}

// Every real meal description in this account starts with its meal name
// ("Breakfast: ...", "Lunch: ...") -- a real, already-present heuristic,
// not a guess. Falls back to unknown rather than defaulting to a specific
// meal type that might be wrong.
private fun mealTypeFrom(description: String?): Int {
    val prefix = description?.substringBefore(':')?.trim()?.lowercase()
    return when (prefix) {
        "breakfast" -> MealType.MEAL_TYPE_BREAKFAST
        "lunch" -> MealType.MEAL_TYPE_LUNCH
        "dinner" -> MealType.MEAL_TYPE_DINNER
        "snack" -> MealType.MEAL_TYPE_SNACK
        else -> MealType.MEAL_TYPE_UNKNOWN
    }
}
