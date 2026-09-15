package com.bioscan.fieldterminal.data

import android.content.Context
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.bioscan.fieldterminal.data.model.ActiveCaloriesUpsertRow
import com.bioscan.fieldterminal.data.model.BmrUpsertRow
import com.bioscan.fieldterminal.data.model.BodyFatUpsertRow
import com.bioscan.fieldterminal.data.model.HeightUpsertRow
import com.bioscan.fieldterminal.data.model.HrvUpsertRow
import com.bioscan.fieldterminal.data.model.RespiratoryRateUpsertRow
import com.bioscan.fieldterminal.data.model.RestingHeartRateUpsertRow
import com.bioscan.fieldterminal.data.model.SleepUpsertRow
import com.bioscan.fieldterminal.data.model.Spo2UpsertRow
import com.bioscan.fieldterminal.data.model.StepsUpsertRow
import com.bioscan.fieldterminal.data.model.TotalCaloriesUpsertRow
import com.bioscan.fieldterminal.data.model.Vo2MaxUpsertRow
import com.bioscan.fieldterminal.data.model.WeightUpsertRow
import com.bioscan.fieldterminal.domain.SleepStageInterval
import com.bioscan.fieldterminal.domain.TimedValue
import com.bioscan.fieldterminal.domain.averageByLocalDate
import com.bioscan.fieldterminal.domain.latestByLocalDate
import com.bioscan.fieldterminal.domain.sumByLocalDate
import com.bioscan.fieldterminal.domain.sumStageMinutes
import com.bioscan.fieldterminal.healthconnect.HealthConnectManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

// Phase G2. Reads raw Health Connect records over [since, until], groups
// them by local calendar date (domain/DailyAggregation.kt), and upserts one
// column at a time into the existing wearable_daily/body_metrics/sleep_daily
// tables -- all three already have a real unique(user_id, date) index
// (confirmed live against the Supabase schema, not assumed), so every write
// here is a plain onConflict="user_id,date" upsert, same pattern already
// used elsewhere in this app for daily tables.
class HealthConnectDailySyncRepository(
    private val context: Context,
    private val supabase: SupabaseClient,
) {
    private val zone = ZoneId.systemDefault()

    suspend fun syncSteps(since: Instant, until: Instant): Int {
        val values = readAll(StepsRecord::class, since, until).map { TimedValue(it.startTime, it.count.toDouble()) }
        val byDate = sumByLocalDate(values, zone)
        byDate.forEach { (date, steps) ->
            supabase.postgrest.from("wearable_daily")
                .upsert(StepsUpsertRow(date.toString(), steps.toInt())) { onConflict = "user_id,date" }
        }
        return byDate.size
    }

    suspend fun syncActiveCalories(since: Instant, until: Instant): Int {
        val values = readAll(ActiveCaloriesBurnedRecord::class, since, until)
            .map { TimedValue(it.startTime, it.energy.inKilocalories) }
        val byDate = sumByLocalDate(values, zone)
        byDate.forEach { (date, kcal) ->
            supabase.postgrest.from("wearable_daily")
                .upsert(ActiveCaloriesUpsertRow(date.toString(), kcal.toInt())) { onConflict = "user_id,date" }
        }
        return byDate.size
    }

    suspend fun syncTotalCalories(since: Instant, until: Instant): Int {
        val values = readAll(TotalCaloriesBurnedRecord::class, since, until)
            .map { TimedValue(it.startTime, it.energy.inKilocalories) }
        val byDate = sumByLocalDate(values, zone)
        byDate.forEach { (date, kcal) ->
            supabase.postgrest.from("wearable_daily")
                .upsert(TotalCaloriesUpsertRow(date.toString(), kcal.toInt())) { onConflict = "user_id,date" }
        }
        return byDate.size
    }

    suspend fun syncVo2Max(since: Instant, until: Instant): Int {
        val values = readAll(Vo2MaxRecord::class, since, until)
            .map { TimedValue(it.time, it.vo2MillilitersPerMinuteKilogram) }
        val byDate = latestByLocalDate(values, zone)
        byDate.forEach { (date, v) ->
            supabase.postgrest.from("wearable_daily")
                .upsert(Vo2MaxUpsertRow(date.toString(), v)) { onConflict = "user_id,date" }
        }
        return byDate.size
    }

    suspend fun syncBmr(since: Instant, until: Instant): Int {
        val values = readAll(BasalMetabolicRateRecord::class, since, until)
            .map { TimedValue(it.time, it.basalMetabolicRate.inKilocaloriesPerDay) }
        val byDate = latestByLocalDate(values, zone)
        byDate.forEach { (date, v) ->
            supabase.postgrest.from("wearable_daily")
                .upsert(BmrUpsertRow(date.toString(), v)) { onConflict = "user_id,date" }
        }
        return byDate.size
    }

    suspend fun syncBodyComposition(since: Instant, until: Instant): Int {
        val weight = latestByLocalDate(readAll(WeightRecord::class, since, until).map { TimedValue(it.time, it.weight.inKilograms) }, zone)
        weight.forEach { (date, v) ->
            supabase.postgrest.from("body_metrics")
                .upsert(WeightUpsertRow(date.toString(), v)) { onConflict = "user_id,date" }
        }

        val bodyFat = latestByLocalDate(readAll(BodyFatRecord::class, since, until).map { TimedValue(it.time, it.percentage.value) }, zone)
        bodyFat.forEach { (date, v) ->
            supabase.postgrest.from("body_metrics")
                .upsert(BodyFatUpsertRow(date.toString(), v)) { onConflict = "user_id,date" }
        }

        val height = latestByLocalDate(readAll(HeightRecord::class, since, until).map { TimedValue(it.time, it.height.inMeters * 100.0) }, zone)
        height.forEach { (date, v) ->
            supabase.postgrest.from("body_metrics")
                .upsert(HeightUpsertRow(date.toString(), v)) { onConflict = "user_id,date" }
        }

        return weight.size + bodyFat.size + height.size
    }

    // Resting HR, HRV, SpO2 -- three independent instantaneous record types,
    // each written to its own wearable_daily column only (see
    // HealthConnectSyncModels.kt's header comment for why not one shared row).
    suspend fun syncVitals(since: Instant, until: Instant): Int {
        val rhr = averageByLocalDate(readAll(RestingHeartRateRecord::class, since, until).map { TimedValue(it.time, it.beatsPerMinute.toDouble()) }, zone)
        rhr.forEach { (date, v) ->
            supabase.postgrest.from("wearable_daily")
                .upsert(RestingHeartRateUpsertRow(date.toString(), v)) { onConflict = "user_id,date" }
        }

        val hrv = averageByLocalDate(readAll(HeartRateVariabilityRmssdRecord::class, since, until).map { TimedValue(it.time, it.heartRateVariabilityMillis) }, zone)
        hrv.forEach { (date, v) ->
            supabase.postgrest.from("wearable_daily")
                .upsert(HrvUpsertRow(date.toString(), v)) { onConflict = "user_id,date" }
        }

        val spo2 = averageByLocalDate(readAll(OxygenSaturationRecord::class, since, until).map { TimedValue(it.time, it.percentage.value) }, zone)
        spo2.forEach { (date, v) ->
            supabase.postgrest.from("wearable_daily")
                .upsert(Spo2UpsertRow(date.toString(), v)) { onConflict = "user_id,date" }
        }

        return rhr.size + hrv.size + spo2.size
    }

    // Sleep sessions carry their own coherent set of fields per night (no
    // cross-record-type merge risk the way vitals do), keyed by the wake-up
    // date -- e.g. a session running 23:40 -> 06:10 is "today's sleep" on
    // the morning date, matching the common wearable-app convention.
    // Respiratory rate readings that fall inside a sleep session's own
    // window are folded in here (sleep_daily.respiratory_rate already
    // existed for exactly this, pre-Health-Connect) rather than given a
    // separate wearable_daily column, to avoid two places meaning the same
    // thing.
    suspend fun syncSleep(since: Instant, until: Instant): Int {
        val sessions = readAll(SleepSessionRecord::class, since, until)
        val respiratoryReadings = readAll(RespiratoryRateRecord::class, since, until)

        sessions.forEach { session ->
            val stages = session.stages.map {
                SleepStageInterval(it.startTime.epochSecond, it.endTime.epochSecond, it.stage)
            }
            val hours = (session.endTime.epochSecond - session.startTime.epochSecond) / 3600.0
            val respiratoryForNight = respiratoryReadings
                .filter { it.time >= session.startTime && it.time <= session.endTime }
                .map { it.rate }

            supabase.postgrest.from("sleep_daily").upsert(
                SleepUpsertRow(
                    date = session.endTime.atZone(zone).toLocalDate().toString(),
                    hours = hours,
                    bedtime = session.startTime.toString(),
                    wakeTime = session.endTime.toString(),
                    deepMin = sumStageMinutes(stages, SleepSessionRecord.STAGE_TYPE_DEEP),
                    remMin = sumStageMinutes(stages, SleepSessionRecord.STAGE_TYPE_REM),
                    lightMin = sumStageMinutes(stages, SleepSessionRecord.STAGE_TYPE_LIGHT),
                ),
            ) { onConflict = "user_id,date" }

            if (respiratoryForNight.isNotEmpty()) {
                supabase.postgrest.from("sleep_daily").upsert(
                    RespiratoryRateUpsertRow(session.endTime.atZone(zone).toLocalDate().toString(), respiratoryForNight.average()),
                ) { onConflict = "user_id,date" }
            }
        }
        return sessions.size
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, since: Instant, until: Instant): List<T> {
        val client = HealthConnectManager.client(context)
        val results = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(since, until),
                    pageToken = pageToken,
                ),
            )
            results += response.records
            pageToken = response.pageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return results
    }
}
