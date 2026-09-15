package com.bioscan.fieldterminal.data

import android.content.Context
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetailRow
import com.bioscan.fieldterminal.domain.SessionDetail
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.healthconnect.HealthConnectManager
import com.bioscan.fieldterminal.healthconnect.readAllRecords
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import java.time.Duration
import java.time.Instant

// Phase G4. loadHeader() reads the session's real aggregates from Supabase
// (same source Training/Log already use); loadTimeSeries() reads fresh from
// Health Connect on demand, every time this screen opens, nothing persisted
// -- the same fetch-on-demand principle Step 14's GPX route already
// established for Drive. Only sessions with a health_connect_record_id
// (i.e. not one of the 19 migrated pre-Health-Connect rows) have anything
// for loadTimeSeries() to find.
//
// Elevation-over-time is deliberately not read here: ElevationGainedRecord
// only gives interval deltas, not a continuous profile -- a real profile
// needs the session's ExerciseRoute, which needs a separate per-record
// consent flow beyond this app's bulk Health Connect grant (flagged, not
// solved, in the Phase G plan -- see ROADMAP.md).
class SessionDetailRepository(
    private val context: Context,
    private val supabase: SupabaseClient,
) {
    suspend fun loadHeader(id: Long): ExerciseSessionDetailRow =
        supabase.postgrest.from("exercise_sessions")
            .select(
                columns = Columns.list(
                    "id,type,start_time,end_time,duration_min,distance_km,calories_active," +
                        "calories_total,avg_hr,max_hr,elevation_gain_m,avg_power_w,avg_speed_kmh," +
                        "rpe,notes,health_connect_record_id",
                ),
            ) { filter { eq("id", id) } }
            .decodeList<ExerciseSessionDetailRow>()
            .first()

    suspend fun loadTimeSeries(startTime: Instant, endTime: Instant): SessionDetail {
        val client = HealthConnectManager.client(context)

        val heartRate = client.readAllRecords(HeartRateRecord::class, startTime, endTime)
            .flatMap { it.samples }
            .map { TimePoint(Duration.between(startTime, it.time).seconds, it.beatsPerMinute.toDouble()) }
            .sortedBy { it.offsetSeconds }

        val speed = client.readAllRecords(SpeedRecord::class, startTime, endTime)
            .flatMap { it.samples }
            .map { TimePoint(Duration.between(startTime, it.time).seconds, it.speed.inKilometersPerHour) }
            .sortedBy { it.offsetSeconds }

        val power = client.readAllRecords(PowerRecord::class, startTime, endTime)
            .flatMap { it.samples }
            .map { TimePoint(Duration.between(startTime, it.time).seconds, it.power.inWatts) }
            .sortedBy { it.offsetSeconds }

        return SessionDetail(heartRate, speed, power)
    }
}
