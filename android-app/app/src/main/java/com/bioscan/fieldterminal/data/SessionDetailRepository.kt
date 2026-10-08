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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

// DAV-115/123: detail.json's decoded per-second series (see
// docs/zepp-integration/03-workout-detail-field-decode.md), stored server-side
// by zepp-extract in the same {offsetSeconds, value} shape TimePoint already
// uses -- no client-side decoding needed, just a straight field-name match.
@Serializable
data class ZeppDecodedPoint(@SerialName("offsetSeconds") val offsetSeconds: Long, val value: Double)

// Workout-level averages (not a per-second series) -- lives in Zepp's own
// workout summary, not detail.json, so it can't be plotted against
// offsetSeconds like the fields above. lactateThreshold* is Zepp's own
// rolling estimate, recomputed per qualifying run -- "as of this workout",
// not tied to one dedicated test.
@Serializable
data class ZeppWorkoutSummary(
    val avgCadenceSpm: Double? = null,
    val maxCadenceSpm: Double? = null,
    val avgStrideLengthCm: Double? = null,
    val avgGroundContactMs: Double? = null,
    val avgVerticalStrideRatioPct: Double? = null,
    val lactateThresholdHrBpm: Double? = null,
    val lactateThresholdPaceSecPerKm: Double? = null,
    // DAV-272, computed server-side (zepp-extract effort.ts). EF only exists
    // for aerobic runs >= 20 min; decoupling only alongside it.
    val gapMinPerKm: Double? = null,
    val efficiencyFactor: Double? = null,
    val hrDecouplingPct: Double? = null,
    val smoothedAscentM: Double? = null,
)

// v11: per-set movement evaluation scores from strengthAssess.eq[].
// Positional order (eq[0..4]) confirmed against Zepp app radar 2026-10-08;
// clockwise from top = Stability, Consistency, Speed Decay, Rhythm, Continuity.
@Serializable
data class ZeppMovementScores(
    val stability: Int,
    val consistency: Int,
    val speedDecay: Int,
    val rhythm: Int,
    val continuity: Int,
)

@Serializable
data class ZeppStrengthSetData(
    val idx: Int,
    val startOffsetSec: Int,
    val durationSec: Int,
    val exerciseCode: Int,
    val scores: ZeppMovementScores? = null,
)

@Serializable
data class ZeppStrengthData(
    val sets: List<ZeppStrengthSetData> = emptyList(),
)

@Serializable
data class ZeppRoutePoint(
    val offsetSeconds: Long,
    val lat: Double,
    val lon: Double,
)

@Serializable
data class ZeppDecodedSeries(
    val heartRate: List<ZeppDecodedPoint> = emptyList(),
    val speedKmh: List<ZeppDecodedPoint> = emptyList(),
    val altitudeM: List<ZeppDecodedPoint> = emptyList(),
    val distanceKm: List<ZeppDecodedPoint> = emptyList(),
    val cadenceSpm: List<ZeppDecodedPoint> = emptyList(),
    val verticalStrideRatioPct: List<ZeppDecodedPoint> = emptyList(),
    val routePoints: List<ZeppRoutePoint> = emptyList(),
    val summary: ZeppWorkoutSummary? = null,
    val strengthData: ZeppStrengthData? = null,
)

@Serializable
data class ZeppWorkoutDetailRow(val decoded: ZeppDecodedSeries? = null)

// start_time + only decoded->summary (aliased) -- used by TrainingRepository
// for the lactate-threshold and efficiency trend series (one point per synced
// Zepp workout). Selecting the summary alone avoids pulling every workout's
// per-second series just to read a few scalars.
@Serializable
data class ZeppWorkoutDetailSummaryRow(
    @SerialName("start_time") val startTime: String,
    val summary: ZeppWorkoutSummary? = null,
)

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
                        "rpe,notes,health_connect_record_id,details",
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
            .sortedBy { it.startTime }
            .flatMap { record ->
                retimedOffsets(startTime, record.startTime, record.endTime, record.samples) { it.time }
                    .zip(record.samples) { offset, sample -> TimePoint(offset, sample.speed.inKilometersPerHour) }
            }
            .sortedBy { it.offsetSeconds }

        val power = client.readAllRecords(PowerRecord::class, startTime, endTime)
            .sortedBy { it.startTime }
            .flatMap { record ->
                retimedOffsets(startTime, record.startTime, record.endTime, record.samples) { it.time }
                    .zip(record.samples) { offset, sample -> TimePoint(offset, sample.power.inWatts) }
            }
            .sortedBy { it.offsetSeconds }

        var runningKcal = 0.0
        val calories = client.readAllRecords(ActiveCaloriesBurnedRecord::class, startTime, endTime)
            .sortedBy { it.startTime }
            .map {
                runningKcal += it.energy.inKilocalories
                TimePoint(Duration.between(startTime, it.endTime).seconds, runningKcal)
            }

        // Same multi-source overcount HealthConnectExerciseSyncRepository.buildRow() already
        // found and fixed for the stored distance_km aggregate (see that file's own comment):
        // summing every DistanceRecord in the window double/triple-counts a run when more than
        // one app/device reports distance for it. Group by source and build the cumulative curve
        // from only the single largest-total source, matching that fix exactly.
        var runningKm = 0.0
        val distanceRecords = client.readAllRecords(DistanceRecord::class, startTime, endTime)
        val bySource = distanceRecords.groupBy { it.metadata.dataOrigin.packageName }
        val chosenSource = bySource.maxByOrNull { (_, records) -> records.sumOf { it.distance.inKilometers } }
        val distance = listOf(TimePoint(0, 0.0)) +
            chosenSource?.value.orEmpty()
                .sortedBy { it.startTime }
                .map {
                    runningKm += it.distance.inKilometers
                    TimePoint(Duration.between(startTime, it.endTime).seconds, runningKm)
                }

        return SessionDetail(heartRate, speed, power, calories, distance)
    }

    // DAV-115/123: real per-second data from Zepp's own detail.json, already
    // decoded server-side -- returns null when this session has no matched
    // Zepp workout (the common case today) or that workout's raw payload
    // hasn't been decoded yet. speedKmh/distanceKm/heartRate here are the
    // real recorded series, not a Health-Connect reconstruction.
    suspend fun loadZeppDetail(sessionId: Long): SessionDetail? {
        val row = supabase.postgrest.from("zepp_workout_detail")
            .select(columns = Columns.list("decoded")) { filter { eq("exercise_session_id", sessionId) } }
            .decodeSingleOrNull<ZeppWorkoutDetailRow>() ?: return null
        val decoded = row.decoded ?: return null

        fun toPoints(points: List<ZeppDecodedPoint>) = points.map { TimePoint(it.offsetSeconds, it.value) }
        return SessionDetail(
            heartRate = toPoints(decoded.heartRate),
            speedKmh = toPoints(decoded.speedKmh),
            powerW = emptyList(),
            caloriesKcal = emptyList(),
            distanceKm = toPoints(decoded.distanceKm),
            cadenceSpm = toPoints(decoded.cadenceSpm),
            verticalRatioPct = toPoints(decoded.verticalStrideRatioPct),
        )
    }

    // v11: per-set strength data (timing + movement scores) from decoded.strengthData.
    suspend fun loadZeppStrengthData(sessionId: Long): ZeppStrengthData? {
        val row = supabase.postgrest.from("zepp_workout_detail")
            .select(columns = Columns.list("decoded")) { filter { eq("exercise_session_id", sessionId) } }
            .decodeSingleOrNull<ZeppWorkoutDetailRow>() ?: return null
        return row.decoded?.strengthData
    }

    // Workout-level averages (cadence, ground contact, stride length,
    // vertical ratio, lactate threshold) -- not a TimePoint series, so kept
    // separate from loadZeppDetail rather than force-fitting scalars into it.
    suspend fun loadZeppSummary(sessionId: Long): ZeppWorkoutSummary? {
        val row = supabase.postgrest.from("zepp_workout_detail")
            .select(columns = Columns.list("decoded")) { filter { eq("exercise_session_id", sessionId) } }
            .decodeSingleOrNull<ZeppWorkoutDetailRow>() ?: return null
        return row.decoded?.summary
    }

    // v12: Zepp GPS route, stored as decoded.routePoints. Used as fallback when
    // Health Connect returns ConsentRequired/NoRoute or the session has no HC record.
    suspend fun loadZeppRoute(sessionId: Long): List<RoutePoint>? {
        val row = supabase.postgrest.from("zepp_workout_detail")
            .select(columns = Columns.list("decoded")) { filter { eq("exercise_session_id", sessionId) } }
            .decodeSingleOrNull<ZeppWorkoutDetailRow>() ?: return null
        val pts = row.decoded?.routePoints?.takeIf { it.isNotEmpty() } ?: return null
        val altMap = row.decoded?.altitudeM?.associate { it.offsetSeconds to it.value } ?: emptyMap()
        return pts.map { RoutePoint(it.offsetSeconds, it.lat, it.lon, altMap[it.offsetSeconds]) }
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

// DAV-201: confirmed against real device data (Huami/Amazfit-sourced
// SpeedRecord/PowerRecord) -- the RECORD's own startTime/endTime correctly
// spans the whole session, but every internal sample's own `time` is bugged,
// crammed into roughly the record's final 1% (34s of a 2974s run, confirmed
// live). Rather than trust that per-sample time, redistribute the samples
// evenly across the record's real interval whenever their own reported span
// is suspiciously narrow relative to it -- real, well-behaved sources whose
// samples already span close to the record's full duration are left as-is.
private const val DEGENERATE_SPAN_FRACTION = 5L // reported span < 1/5 of the record's real duration

private fun <T> retimedOffsets(
    sessionStart: Instant,
    recordStart: Instant,
    recordEnd: Instant,
    samples: List<T>,
    sampleTime: (T) -> Instant,
): List<Long> {
    if (samples.size < 2) return samples.map { Duration.between(sessionStart, sampleTime(it)).seconds }

    val recordStartOffset = Duration.between(sessionStart, recordStart).seconds
    val recordDurationSec = Duration.between(recordStart, recordEnd).seconds
    val reportedSpanSec = Duration.between(sampleTime(samples.first()), sampleTime(samples.last())).seconds
    val degenerate = recordDurationSec > 0 && reportedSpanSec < recordDurationSec / DEGENERATE_SPAN_FRACTION

    return if (degenerate) {
        samples.indices.map { i -> recordStartOffset + (i.toLong() * recordDurationSec) / (samples.size - 1) }
    } else {
        samples.map { Duration.between(sessionStart, sampleTime(it)).seconds }
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
