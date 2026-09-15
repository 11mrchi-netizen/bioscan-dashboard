package com.bioscan.fieldterminal.data

import android.content.Context
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import com.bioscan.fieldterminal.data.model.NewExerciseSessionRow
import com.bioscan.fieldterminal.healthconnect.HealthConnectManager
import com.bioscan.fieldterminal.healthconnect.mapHealthConnectExerciseType
import com.bioscan.fieldterminal.healthconnect.readAllRecords
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.time.Duration
import java.time.Instant

// Phase G3. Reads ExerciseSessionRecord, then every metric type Health
// Connect associates with a session by time-overlap rather than a foreign
// key (Distance/ElevationGained/ActiveCalories/TotalCalories -- interval
// records, summed; Power/Speed/HeartRate -- series records with samples,
// averaged/maxed), scoped to that exact session's own [startTime, endTime].
//
// Unlike the daily-aggregate sync (G2), a full-row upsert here is safe: the
// conflict key (user_id, health_connect_record_id) is stable per session,
// and every re-sync of the same session re-reads the same underlying
// Health Connect data -- no cross-source null-overwrite risk. Getting
// fresher data on a later re-sync (e.g. a GPS-derived metric that finished
// uploading late) is the desired behavior, not a bug.
class HealthConnectExerciseSyncRepository(
    private val context: Context,
    private val supabase: SupabaseClient,
) {
    suspend fun syncSessions(since: Instant, until: Instant): Int {
        val client = HealthConnectManager.client(context)
        val sessions = client.readAllRecords(ExerciseSessionRecord::class, since, until)

        sessions.forEach { session ->
            val start = session.startTime
            val end = session.endTime

            val distanceKm = client.readAllRecords(DistanceRecord::class, start, end)
                .sumOf { it.distance.inMeters } / 1000.0
            val elevationM = client.readAllRecords(ElevationGainedRecord::class, start, end)
                .sumOf { it.elevation.inMeters }
            val caloriesActive = client.readAllRecords(ActiveCaloriesBurnedRecord::class, start, end)
                .sumOf { it.energy.inKilocalories }
            val caloriesTotal = client.readAllRecords(TotalCaloriesBurnedRecord::class, start, end)
                .sumOf { it.energy.inKilocalories }

            val heartRateSamples = client.readAllRecords(HeartRateRecord::class, start, end).flatMap { it.samples }
            val avgHr = heartRateSamples.map { it.beatsPerMinute.toDouble() }.average().takeIf { heartRateSamples.isNotEmpty() }
            val maxHr = heartRateSamples.maxOfOrNull { it.beatsPerMinute }?.toDouble()

            val powerSamples = client.readAllRecords(PowerRecord::class, start, end).flatMap { it.samples }
            val avgPowerW = powerSamples.map { it.power.inWatts }.average().takeIf { powerSamples.isNotEmpty() }

            val speedSamples = client.readAllRecords(SpeedRecord::class, start, end).flatMap { it.samples }
            val avgSpeedKmh = speedSamples.map { it.speed.inKilometersPerHour }.average().takeIf { speedSamples.isNotEmpty() }

            val durationMin = Duration.between(start, end).toMinutes().toDouble()

            supabase.postgrest.from("exercise_sessions").upsert(
                NewExerciseSessionRow(
                    type = mapHealthConnectExerciseType(session.exerciseType),
                    startTime = start.toString(),
                    endTime = end.toString(),
                    durationMin = durationMin,
                    distanceKm = distanceKm.takeIf { it > 0 },
                    caloriesActive = caloriesActive.takeIf { it > 0 },
                    caloriesTotal = caloriesTotal.takeIf { it > 0 },
                    avgHr = avgHr,
                    maxHr = maxHr,
                    elevationGainM = elevationM.takeIf { it > 0 },
                    avgPowerW = avgPowerW,
                    avgSpeedKmh = avgSpeedKmh,
                    healthConnectRecordId = session.metadata.id,
                ),
            ) { onConflict = "user_id,health_connect_record_id" }
        }
        return sessions.size
    }
}
