package com.bioscan.fieldterminal.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import com.bioscan.fieldterminal.healthconnect.HealthConnectManager
import com.bioscan.fieldterminal.healthconnect.HealthConnectSyncStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

sealed interface HealthConnectWriteBackResult {
    data object Unavailable : HealthConnectWriteBackResult
    data object NotGranted : HealthConnectWriteBackResult
    data class Success(val mealsWritten: Int, val hydrationDaysWritten: Int) : HealthConnectWriteBackResult
    data class Failed(val message: String) : HealthConnectWriteBackResult
}

// DAV-154: Daily Health Connect write-back service.
// Replaces the one-off manual backfill utility with a robust, automated daily flow
// that runs during background sync.
//
// Writing stopped on Wednesday, September 16, 2026 because write-back was only ever
// wired to a manual "Run once" button in Settings. This repository provides:
// 1. Unattended, scheduled execution from HealthConnectSyncWorker
// 2. Fully idempotent writes via delete-before-insert for this package's records
// 3. Clear observability via sync_log records and HealthConnectSyncStatus
// 4. Safe idempotent backfill for missed days since September 16
class HealthConnectWriteBackRepository(
    private val context: Context,
    private val supabase: SupabaseClient,
) {
    companion object {
        private const val TAG = "HCWriteBack"
        val MISSED_WRITES_START_DATE: LocalDate = LocalDate.of(2026, 9, 16)
    }

    suspend fun writeBackDaily(lookbackDays: Long = 7): HealthConnectWriteBackResult {
        val today = LocalDate.now()
        val startDate = today.minusDays(lookbackDays)
        return executeWriteBack(startDate, today.plusDays(1))
    }

    suspend fun backfillMissedDays(startDate: LocalDate = MISSED_WRITES_START_DATE): HealthConnectWriteBackResult {
        val today = LocalDate.now()
        return executeWriteBack(startDate, today.plusDays(1))
    }

    private suspend fun executeWriteBack(startDate: LocalDate, endDate: LocalDate): HealthConnectWriteBackResult {
        if (!HealthConnectManager.isAvailable(context)) {
            val res = HealthConnectWriteBackResult.Unavailable
            HealthConnectSyncStatus.recordWriteBack(res)
            return res
        }
        if (!HealthConnectManager.hasAllPermissions(context)) {
            val res = HealthConnectWriteBackResult.NotGranted
            HealthConnectSyncStatus.recordWriteBack(res)
            return res
        }

        return try {
            val zone = ZoneId.systemDefault()
            Log.d(TAG, "Starting write-back from $startDate to $endDate")

            val startIso = startDate.atStartOfDay().toString()
            val endIso = endDate.atStartOfDay().toString()

            val meals = supabase.postgrest.from("meals")
                .select(columns = Columns.list("id,logged_at,description,calories,protein_g,carbs_g,fat_g,fiber_g,sugar_g,sodium_mg")) {
                    filter {
                        gte("logged_at", startIso)
                        lt("logged_at", endIso)
                    }
                    order("logged_at", Order.ASCENDING)
                }
                .decodeList<WriteBackMealRow>()

            // DAV-170: meal_items carries what the flat meals columns above
            // don't -- per-item caffeine and beverage water content, from
            // DAV-164's resolver. Queried once for every meal already
            // fetched, not once per meal.
            val mealItems = if (meals.isNotEmpty()) {
                supabase.postgrest.from("meal_items")
                    .select(columns = Columns.list("meal_id,is_beverage,water_ml,caffeine_mg")) {
                        filter { isIn("meal_id", meals.map { it.id }) }
                    }
                    .decodeList<WriteBackMealItemRow>()
            } else {
                emptyList()
            }

            val caffeineMgByMealId: Map<Long, Double> = mealItems
                .mapNotNull { item -> item.caffeineMg?.let { item.mealId to it } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> values.sum() }

            val nutritionRecords = meals.map { meal ->
                val instant = OffsetDateTime.parse(meal.loggedAt).toLocalDateTime().atZone(zone).toInstant()
                val offset = zone.rules.getOffset(instant)
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
                    dietaryFiber = meal.fiberG?.let { Mass.grams(it) },
                    sugar = meal.sugarG?.let { Mass.grams(it) },
                    sodium = meal.sodiumMg?.let { Mass.milligrams(it) },
                    caffeine = caffeineMgByMealId[meal.id]?.let { Mass.milligrams(it) },
                    metadata = Metadata.manualEntryWithId("bioscan-meal-${meal.id}"),
                )
            }

            val hydrationRows = supabase.postgrest.from("hydration_daily")
                .select(columns = Columns.list("id,date,ml")) {
                    filter {
                        gte("date", startDate.toString())
                        lt("date", endDate.toString())
                    }
                    order("date", Order.ASCENDING)
                }
                .decodeList<WriteBackHydrationRow>()
            val hydrationDailyMlByDate: Map<LocalDate, Double> = hydrationRows
                .mapNotNull { row -> row.ml?.let { LocalDate.parse(row.date) to it.toDouble() } }
                .toMap()

            // DAV-170/181: a beverage logged as part of a meal (Coca-Cola
            // with lunch, coffee with breakfast) is real fluid intake too --
            // added to, not instead of, hydration_daily's own manual "just
            // water" log for the same day, since they're genuinely separate
            // logged events rather than duplicates of the same fact.
            val mealDateById: Map<Long, LocalDate> = meals.associate { meal ->
                meal.id to OffsetDateTime.parse(meal.loggedAt).toLocalDateTime().atZone(zone).toLocalDate()
            }
            val beverageWaterMlByDate: Map<LocalDate, Double> = mealItems
                .filter { it.isBeverage }
                .mapNotNull { item -> item.waterMl?.let { ml -> mealDateById[item.mealId]?.let { date -> date to ml } } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> values.sum() }

            val hydrationRecords = (hydrationDailyMlByDate.keys + beverageWaterMlByDate.keys).map { day ->
                val totalMl = (hydrationDailyMlByDate[day] ?: 0.0) + (beverageWaterMlByDate[day] ?: 0.0)
                val start = day.atTime(LocalTime.MIDNIGHT).atZone(zone)
                val end = day.plusDays(1).atTime(LocalTime.MIDNIGHT).atZone(zone).minusSeconds(1)
                HydrationRecord(
                    startTime = start.toInstant(),
                    startZoneOffset = start.offset,
                    endTime = end.toInstant(),
                    endZoneOffset = end.offset,
                    volume = Volume.milliliters(totalMl),
                    metadata = Metadata.manualEntryWithId("bioscan-hydration-$day"),
                )
            }

            val client = HealthConnectManager.client(context)
            val rangeStart = startDate.atStartOfDay(zone).toInstant()
            val rangeEnd = endDate.atStartOfDay(zone).toInstant()
            val rangeFilter = TimeRangeFilter.between(rangeStart, rangeEnd)

            // Read existing records written by this app to ensure idempotent rewrite
            val existingNutrition = client.readRecords(
                ReadRecordsRequest(NutritionRecord::class, rangeFilter)
            ).records.filter { it.metadata.dataOrigin.packageName == context.packageName }

            val existingHydration = client.readRecords(
                ReadRecordsRequest(HydrationRecord::class, rangeFilter)
            ).records.filter { it.metadata.dataOrigin.packageName == context.packageName }

            if (existingNutrition.isNotEmpty()) {
                client.deleteRecords(NutritionRecord::class, existingNutrition.map { it.metadata.id }, emptyList())
            }
            if (existingHydration.isNotEmpty()) {
                client.deleteRecords(HydrationRecord::class, existingHydration.map { it.metadata.id }, emptyList())
            }

            if (nutritionRecords.isNotEmpty()) {
                client.insertRecords(nutritionRecords)
            }
            if (hydrationRecords.isNotEmpty()) {
                client.insertRecords(hydrationRecords)
            }

            writeWatermark(Instant.now(), nutritionRecords.size, hydrationRecords.size)

            val result = HealthConnectWriteBackResult.Success(
                mealsWritten = nutritionRecords.size,
                hydrationDaysWritten = hydrationRecords.size,
            )
            Log.d(TAG, "Write-back succeeded: ${nutritionRecords.size} meals, ${hydrationRecords.size} hydration days")
            HealthConnectSyncStatus.recordWriteBack(result)
            result
        } catch (e: Exception) {
            val msg = e.message ?: "Write-back failed with unknown exception"
            Log.e(TAG, "Write-back error: $msg", e)
            val result = HealthConnectWriteBackResult.Failed(msg)
            HealthConnectSyncStatus.recordWriteBack(result)
            result
        }
    }

    private suspend fun writeWatermark(at: Instant, mealsCount: Int, hydrationCount: Int) {
        val summary: JsonObject = buildJsonObject {
            put("meals_written", JsonPrimitive(mealsCount))
            put("hydration_days_written", JsonPrimitive(hydrationCount))
        }
        try {
            supabase.postgrest.from("sync_log").insert(
                NewWriteBackSyncLogRow(
                    syncedAt = at.toString(),
                    syncSource = "health_connect_writeback",
                    changesSummary = summary,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log write-back watermark to Supabase", e)
        }
    }

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
}

@Serializable
private data class WriteBackMealRow(
    val id: Long,
    @SerialName("logged_at") val loggedAt: String,
    val description: String? = null,
    val calories: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
)

@Serializable
private data class WriteBackHydrationRow(
    val id: Long,
    val date: String,
    val ml: Int? = null,
)

@Serializable
private data class WriteBackMealItemRow(
    @SerialName("meal_id") val mealId: Long,
    @SerialName("is_beverage") val isBeverage: Boolean = false,
    @SerialName("water_ml") val waterMl: Double? = null,
    @SerialName("caffeine_mg") val caffeineMg: Double? = null,
)

@Serializable
private data class NewWriteBackSyncLogRow(
    @SerialName("synced_at") val syncedAt: String,
    @SerialName("sync_source") val syncSource: String,
    @SerialName("changes_summary") val changesSummary: JsonObject,
)
