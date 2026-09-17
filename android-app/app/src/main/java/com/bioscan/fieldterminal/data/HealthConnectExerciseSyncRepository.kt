package com.bioscan.fieldterminal.data

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

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
    private val zone = ZoneId.systemDefault()

    suspend fun syncSessions(since: Instant, until: Instant): Int {
        val client = HealthConnectManager.client(context)
        val sessions = client.readAllRecords(ExerciseSessionRecord::class, since, until)

        // Real on-device data has thousands of real sessions once history
        // isn't capped to 30 days -- doing this sequentially (one session's
        // worth of Health Connect reads, then its own network upsert, then
        // the next) made a real full sync effectively never finish. Health
        // Connect reads are local IPC, not network, so reading several
        // sessions' metrics concurrently is a real win -- but launching all
        // of them at once (tried first) just moved the bottleneck to Health
        // Connect's own IPC layer under thousands of simultaneous requests,
        // with no visible progress for minutes. Bounded chunks give the
        // concurrency win without that pileup, and each chunk's Supabase
        // write lands as its own batch instead of waiting for everything.
        var written = 0
        sessions.chunked(READ_CONCURRENCY).forEach { chunk ->
            val rows = coroutineScope {
                chunk.map { session -> async { buildRow(client, session) } }.map { it.await() }
            }
            supabase.postgrest.from("exercise_sessions").upsert(rows) {
                onConflict = "user_id,health_connect_record_id"
            }
            written += rows.size
            Log.d("HealthConnectSync", "exercise sessions: $written/${sessions.size}")
        }
        return sessions.size
    }

    private suspend fun buildRow(
        client: androidx.health.connect.client.HealthConnectClient,
        session: ExerciseSessionRecord,
    ): NewExerciseSessionRow {
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

        // Every other real-time-of-day column in this app stores the
        // device's local wall-clock reading labeled with a FAKE zero/UTC
        // offset -- readers everywhere (domain/Log.kt's parseTimestamp,
        // Training.kt, etc.) take the digits as-is and never apply a real
        // zone conversion. Health Connect's start/end are real UTC Instants;
        // storing them verbatim (start.toString()) broke that convention.
        // The first fix attempt here used the device's REAL offset
        // (atZone(zone).toOffsetDateTime()) instead of a fake one -- still
        // wrong, because Postgres normalizes a timestamptz to true UTC on
        // write regardless of the offset sent, silently shifting the real
        // local wall-clock reading by the zone offset in storage, which
        // every naive reader then misread as local again -- the exact same
        // bug in different clothes (confirmed on real device: still showed
        // the wrong date/time after that "fix"). Re-labeling with UTC after
        // converting to local wall-clock time, instead of keeping the real
        // offset, is what actually stops Postgres from touching the digits.
        val startLocal = start.atZone(zone).toLocalDateTime().atOffset(ZoneOffset.UTC).toString()
        val endLocal = end.atZone(zone).toLocalDateTime().atOffset(ZoneOffset.UTC).toString()

        return NewExerciseSessionRow(
            type = mapHealthConnectExerciseType(session.exerciseType),
            startTime = startLocal,
            endTime = endLocal,
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
        )
    }

    companion object {
        // How many sessions' worth of Health Connect reads run concurrently
        // (and land in one Supabase upsert) per chunk -- large enough to
        // meaningfully overlap local IPC latency, small enough not to flood
        // Health Connect's own request queue when there are thousands of
        // real sessions.
        private const val READ_CONCURRENCY = 50
    }
}
