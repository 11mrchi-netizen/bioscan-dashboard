package com.bioscan.fieldterminal.data

import android.content.Context
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetailRow
import com.bioscan.fieldterminal.domain.RouteAvailability
import com.bioscan.fieldterminal.domain.RoutePoint
import com.bioscan.fieldterminal.domain.SessionDetail
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.healthconnect.HealthConnectManager
import com.bioscan.fieldterminal.healthconnect.readAllRecords
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import java.time.Duration
import java.time.Instant

// Phase G4/G5. loadHeader() reads the session's real aggregates from Supabase
// (same source Training/Log already use); loadTimeSeries() reads fresh from
// Health Connect on demand, every time this screen opens, nothing persisted
// -- the same fetch-on-demand principle Step 14's GPX route already
// established for Drive. Only sessions with a health_connect_record_id
// (i.e. not one of the 19 migrated pre-Health-Connect rows) have anything
// for loadTimeSeries() or checkRouteAvailability() to find.
//
// Elevation-over-time isn't part of loadTimeSeries(): ElevationGainedRecord
// only gives interval deltas, not a continuous profile -- a real profile
// needs the session's ExerciseRoute, which (Phase G5) checkRouteAvailability()
// reads separately, since it needs its own per-session consent flow rather
// than this app's bulk Health Connect grant.
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

        var runningKcal = 0.0
        val calories = client.readAllRecords(ActiveCaloriesBurnedRecord::class, startTime, endTime)
            .sortedBy { it.startTime }
            .map {
                runningKcal += it.energy.inKilocalories
                TimePoint(Duration.between(startTime, it.endTime).seconds, runningKcal)
            }

        var runningKm = 0.0
        val distance = listOf(TimePoint(0, 0.0)) +
            client.readAllRecords(DistanceRecord::class, startTime, endTime)
                .sortedBy { it.startTime }
                .map {
                    runningKm += it.distance.inKilometers
                    TimePoint(Duration.between(startTime, it.endTime).seconds, runningKm)
                }

        return SessionDetail(heartRate, speed, power, calories, distance)
    }

    // Phase G5. A session's exercise route isn't covered by this app's bulk
    // Health Connect grant -- ExerciseSessionRecord.exerciseRouteResult tells
    // us directly which of Health Connect's three states this session is in
    // (real data already attached, a one-time consent screen is needed, or
    // there's simply no route), so no guessing or separate probe call is
    // needed to find out which case applies before deciding what to show.
    suspend fun checkRouteAvailability(recordId: String, sessionStart: Instant): RouteAvailability {
        val client = HealthConnectManager.client(context)
        val record = client.readRecord(ExerciseSessionRecord::class, recordId).record
        return when (val result = record.exerciseRouteResult) {
            is ExerciseRouteResult.Data -> RouteAvailability.Available(toRoutePoints(result.exerciseRoute, sessionStart))
            is ExerciseRouteResult.ConsentRequired -> RouteAvailability.ConsentRequired
            else -> RouteAvailability.NoRoute
        }
    }
}

// Also used directly by SessionDetailScreen to convert the ExerciseRoute
// Health Connect's own consent screen hands back via ExerciseRouteRequestContract
// -- a plain function rather than a repository method since it needs no
// Context/SupabaseClient, just the route and the session's start time.
fun toRoutePoints(route: ExerciseRoute, sessionStart: Instant): List<RoutePoint> =
    route.route.map {
        RoutePoint(
            offsetSeconds = Duration.between(sessionStart, it.time).seconds,
            lat = it.latitude,
            lon = it.longitude,
            elevationM = it.altitude?.inMeters,
        )
    }
