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
import com.bioscan.fieldterminal.healthconnect.readAllRecords
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
        if (byDate.isNotEmpty()) {
            supabase.postgrest.from("wearable_daily")
                .upsert(byDate.map { (date, steps) -> StepsUpsertRow(date.toString(), steps.toInt()) }) { onConflict = "user_id,date" }
        }
        return byDate.size
    }

    suspend fun syncActiveCalories(since: Instant, until: Instant): Int {
        val values = readAll(ActiveCaloriesBurnedRecord::class, since, until)
            .map { TimedValue(it.startTime, it.energy.inKilocalories) }
        val byDate = sumByLocalDate(values, zone)
        if (byDate.isNotEmpty()) {
            supabase.postgrest.from("wearable_daily")
                .upsert(byDate.map { (date, kcal) -> ActiveCaloriesUpsertRow(date.toString(), kcal.toInt()) }) { onConflict = "user_id,date" }
        }
        return byDate.size
    }

    suspend fun syncTotalCalories(since: Instant, until: Instant): Int {
        val values = readAll(TotalCaloriesBurnedRecord::class, since, until)
            .map { TimedValue(it.startTime, it.energy.inKilocalories) }
        val byDate = sumByLocalDate(values, zone)
        if (byDate.isNotEmpty()) {
            supabase.postgrest.from("wearable_daily")
                .upsert(byDate.map { (date, kcal) -> TotalCaloriesUpsertRow(date.toString(), kcal.toInt()) }) { onConflict = "user_id,date" }
        }
        return byDate.size
    }

    suspend fun syncVo2Max(since: Instant, until: Instant): Int {
        val values = readAll(Vo2MaxRecord::class, since, until)
            .map { TimedValue(it.time, it.vo2MillilitersPerMinuteKilogram) }
        val byDate = latestByLocalDate(values, zone)
        if (byDate.isNotEmpty()) {
            supabase.postgrest.from("wearable_daily")
                .upsert(byDate.map { (date, v) -> Vo2MaxUpsertRow(date.toString(), v) }) { onConflict = "user_id,date" }
        }
        return byDate.size
    }

    suspend fun syncBmr(since: Instant, until: Instant): Int {
        val values = readAll(BasalMetabolicRateRecord::class, since, until)
            .map { TimedValue(it.time, it.basalMetabolicRate.inKilocaloriesPerDay) }
        val byDate = latestByLocalDate(values, zone)
        if (byDate.isNotEmpty()) {
            supabase.postgrest.from("wearable_daily")
                .upsert(byDate.map { (date, v) -> BmrUpsertRow(date.toString(), v) }) { onConflict = "user_id,date" }
        }
        return byDate.size
    }

    suspend fun syncBodyComposition(since: Instant, until: Instant): Int {
        val weight = latestByLocalDate(readAll(WeightRecord::class, since, until).map { TimedValue(it.time, it.weight.inKilograms) }, zone)
        if (weight.isNotEmpty()) {
            supabase.postgrest.from("body_metrics")
                .upsert(weight.map { (date, v) -> WeightUpsertRow(date.toString(), v) }) { onConflict = "user_id,date" }
        }

        val bodyFat = latestByLocalDate(readAll(BodyFatRecord::class, since, until).map { TimedValue(it.time, it.percentage.value) }, zone)
        if (bodyFat.isNotEmpty()) {
            supabase.postgrest.from("body_metrics")
                .upsert(bodyFat.map { (date, v) -> BodyFatUpsertRow(date.toString(), v) }) { onConflict = "user_id,date" }
        }

        val height = latestByLocalDate(readAll(HeightRecord::class, since, until).map { TimedValue(it.time, it.height.inMeters * 100.0) }, zone)
        if (height.isNotEmpty()) {
            supabase.postgrest.from("body_metrics")
                .upsert(height.map { (date, v) -> HeightUpsertRow(date.toString(), v) }) { onConflict = "user_id,date" }
        }

        return weight.size + bodyFat.size + height.size
    }

    // Resting HR, HRV, SpO2 -- three independent instantaneous record types,
    // each written to its own wearable_daily column only (see
    // HealthConnectSyncModels.kt's header comment for why not one shared row).
    suspend fun syncVitals(since: Instant, until: Instant): Int {
        val rhr = averageByLocalDate(readAll(RestingHeartRateRecord::class, since, until).map { TimedValue(it.time, it.beatsPerMinute.toDouble()) }, zone)
        if (rhr.isNotEmpty()) {
            supabase.postgrest.from("wearable_daily")
                .upsert(rhr.map { (date, v) -> RestingHeartRateUpsertRow(date.toString(), v) }) { onConflict = "user_id,date" }
        }

        val hrv = averageByLocalDate(readAll(HeartRateVariabilityRmssdRecord::class, since, until).map { TimedValue(it.time, it.heartRateVariabilityMillis) }, zone)
        if (hrv.isNotEmpty()) {
            supabase.postgrest.from("wearable_daily")
                .upsert(hrv.map { (date, v) -> HrvUpsertRow(date.toString(), v) }) { onConflict = "user_id,date" }
        }

        val spo2 = averageByLocalDate(readAll(OxygenSaturationRecord::class, since, until).map { TimedValue(it.time, it.percentage.value) }, zone)
        if (spo2.isNotEmpty()) {
            supabase.postgrest.from("wearable_daily")
                .upsert(spo2.map { (date, v) -> Spo2UpsertRow(date.toString(), v) }) { onConflict = "user_id,date" }
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
    //
    // Real on-device data showed multiple SleepSessionRecord entries landing
    // on the same wake-up date -- short ones (a minute or two: brief
    // wake-ups, or the watch app logging an interruption as its own session)
    // alongside the real overnight one. Upserting every session
    // unconditionally meant "last one Health Connect returned" won via
    // onConflict=(user_id,date), sometimes silently overwriting a real ~7h
    // night with a ~1-minute noise session. Grouping by date and keeping
    // only the longest session per date fixes that without guessing at
    // which of several real Health Connect records is "the" sleep -- the
    // longest one is the one actually worth calling a night's sleep.
    suspend fun syncSleep(since: Instant, until: Instant): Int {
        val sessions = readAll(SleepSessionRecord::class, since, until)
        val respiratoryReadings = readAll(RespiratoryRateRecord::class, since, until)

        val mainSessionByDate = sessions
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
            .mapValues { (_, nights) -> nights.maxBy { it.endTime.epochSecond - it.startTime.epochSecond } }

        val sleepRows = mutableListOf<SleepUpsertRow>()
        val respiratoryRows = mutableListOf<RespiratoryRateUpsertRow>()

        mainSessionByDate.forEach { (date, session) ->
            val stages = session.stages.map {
                SleepStageInterval(it.startTime.epochSecond, it.endTime.epochSecond, it.stage)
            }
            val hours = (session.endTime.epochSecond - session.startTime.epochSecond) / 3600.0
            val respiratoryForNight = respiratoryReadings
                .filter { it.time >= session.startTime && it.time <= session.endTime }
                .map { it.rate }

            sleepRows += SleepUpsertRow(
                date = date.toString(),
                hours = hours,
                bedtime = session.startTime.toString(),
                wakeTime = session.endTime.toString(),
                deepMin = sumStageMinutes(stages, SleepSessionRecord.STAGE_TYPE_DEEP),
                remMin = sumStageMinutes(stages, SleepSessionRecord.STAGE_TYPE_REM),
                lightMin = sumStageMinutes(stages, SleepSessionRecord.STAGE_TYPE_LIGHT),
            )
            if (respiratoryForNight.isNotEmpty()) {
                respiratoryRows += RespiratoryRateUpsertRow(date.toString(), respiratoryForNight.average())
            }
        }

        if (sleepRows.isNotEmpty()) {
            supabase.postgrest.from("sleep_daily").upsert(sleepRows) { onConflict = "user_id,date" }
        }
        if (respiratoryRows.isNotEmpty()) {
            supabase.postgrest.from("sleep_daily").upsert(respiratoryRows) { onConflict = "user_id,date" }
        }
        return mainSessionByDate.size
    }

    // Delegates to the shared pagination loop in healthconnect/HealthConnectReading.kt
    // (also used by HealthConnectExerciseSyncRepository, Phase G3).
    private suspend fun <T : Record> readAll(type: KClass<T>, since: Instant, until: Instant): List<T> =
        HealthConnectManager.client(context).readAllRecords(type, since, until)
}
