package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.data.model.Vo2MaxRow
import com.bioscan.fieldterminal.domain.averagePaceMinPerKmSince
import com.bioscan.fieldterminal.domain.lactateThresholdPaceMinPerKmSeries
import com.bioscan.fieldterminal.domain.latestNonNullVo2Max
import com.bioscan.fieldterminal.domain.longestRunKm
import com.bioscan.fieldterminal.domain.dedupeRunSessions
import com.bioscan.fieldterminal.domain.sumDistanceKmSince
import com.bioscan.fieldterminal.domain.suspectedTrailReason
import com.bioscan.fieldterminal.domain.vo2MaxSeries
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

// DAV-79 re-check: the "distance moved" card previously summed run/walk/
// hike/ride together as "endurance". Walk/hike/ride are no longer part of
// this figure at all, per direct user instruction -- only real running
// distance should count here (the card's own "longest run"/"avg pace"
// sub-stats only ever meant running anyway).

// Real root cause of the week-of-2026-09-17 overcount (confirmed against
// live Supabase data, not the walk-noise floor this file used to blame):
// every one of this account's 19 manual `type='run'` rows (migrated 1:1
// from the old `runs` table, itself this user's own originally-recorded
// distances for 2026-08-14..09-15) has a same-day Health-Connect `run` row
// with an almost-identical duration (e.g. 50.52 vs 50.0 min) but a distance
// 1.6-3.2x larger -- e.g. manual 7.45km/50.52min vs HC 14.89km/50.0min for
// the same real run. HealthConnectExerciseSyncRepository.buildRow() sums
// *every* DistanceRecord Health Connect returns for a session's time window
// with no dedup by data source (see that file's own fix) -- when more than
// one app/device reports distance for the same real workout, summing them
// double- or triple-counts it. The manual row is this account's own
// pre-bug ground truth for that period, so when a manual/HC pair for the
// same real run is found, the HC copy is dropped in favor of the manual
// one. One exact-duplicate manual row (id 1 == id 17, a leftover double
// insert from before manual creation was retired 2026-09-15) is also
// collapsed to one.

data class TrainingOverview(
    val thisWeekDistanceKm: Double,
    val fourWeekAvgKmPerWeek: Double,
    val longestRunKm: Double?,
    val avgPaceThisWeek: Double?,
    val latestVo2Max: Double?,
    val vo2MaxSeries: List<Pair<LocalDate, Double>>, // DAV-80: raw series backing the Training tile's chart
    // DAV-115/123: one point per synced Zepp workout that had a real
    // lactate-threshold estimate attached (Zepp recomputes it per qualifying
    // run, not from one dedicated test) -- min/km, same unit every other
    // pace figure on this tab uses.
    val latestLactateThresholdPaceMinPerKm: Double?,
    val lactateThresholdPaceSeries: List<Pair<LocalDate, Double>>,
    val hasAnyRunning: Boolean,
    val runningSessions: List<ExerciseSessionRow>, // raw rows, kept for the 1D/7D/30D/90D distance-totals widget
    val hasAnyStrength: Boolean,
    val strengthSessionsThisWeek: Int,
    val strengthMinutesThisWeek: Int,
    // DAV-144. No session list exists on this tab (see docs/trail-intelligence/
    // 05-trail-metrics-ui-presentation.md) -- "trail metrics per run" here
    // means the same weekly-rollup shape every other figure on this card uses.
    val thisWeekTrailRunCount: Int,
    val thisWeekTrailElevationGainM: Double?,
    // Live check: no session list existed anywhere reachable from the first
    // screen's Training tile -- reuses the same 200-row fetch below rather
    // than a second query, sessions are already start_time-descending.
    val recentSessions: List<ExerciseSessionRow>,
    // 25/9 rework: untagged runs that look like trail runs (see suspectedTrailReason).
    val suspectedTrailRuns: List<SuspectedTrailRun>,
    // DAV-272: per-run efficiency factor (aerobic runs only) and the latest
    // run's grade-adjusted pace / decoupling, all computed server-side.
    val efficiencyPoints: List<Pair<LocalDate, Double>>,
    val latestGapMinPerKm: Double?,
    val latestHrDecouplingPct: Double?,
)

// sessionIds = every exercise_sessions row of this real run (HC/manual/Zepp
// duplicates), so tagging it as trail sticks whichever copy a screen reads.
data class SuspectedTrailRun(val row: ExerciseSessionRow, val reason: String, val sessionIds: List<Long>)

@kotlinx.serialization.Serializable
private data class ZeppSportRow(
    @kotlinx.serialization.SerialName("exercise_session_id") val exerciseSessionId: Long? = null,
    @kotlinx.serialization.SerialName("sport_type") val sportType: String? = null,
)

class TrainingRepository(private val supabase: SupabaseClient) {

    suspend fun loadOverview(): TrainingOverview {
        // 200 is a generous row-count margin, not a real 90-day date filter
        // -- same "bounded, not unbounded" tradeoff this query already made
        // when it only covered runs.
        val sessions = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,type,start_time,duration_min,distance_km,avg_hr,avg_speed_kmh,source,elevation_gain_m,details")) {
                order("start_time", Order.DESCENDING)
                limit(200)
            }
            .decodeList<ExerciseSessionRow>()

        // 180 covers roughly 6 months of daily wearable rows -- enough real
        // history for a 28-day rolling average to actually show movement,
        // unlike the old limit(10) (fine for "just the latest value," far
        // too small once this data backs a chart).
        val vo2Rows = supabase.postgrest.from("wearable_daily")
            .select(columns = Columns.list("date,vo2max")) {
                order("date", Order.DESCENDING)
                limit(180)
            }
            .decodeList<Vo2MaxRow>()
            .reversed()

        // One row per synced Zepp workout; most won't have a lactate-threshold
        // estimate (only running workouts that qualify get one from Zepp),
        // filtered out client-side rather than in the query since decoded is
        // jsonb.
        val lactateRows = supabase.postgrest.from("zepp_workout_detail")
            .select(columns = Columns.raw("start_time,summary:decoded->summary")) {
                order("start_time", Order.ASCENDING)
            }
            .decodeList<ZeppWorkoutDetailSummaryRow>()
        val lactateThresholdPaceSecPerKmSeries = lactateRows.mapNotNull { row ->
            row.summary?.lactateThresholdPaceSecPerKm?.let { pace ->
                OffsetDateTime.parse(row.startTime).toLocalDate() to pace
            }
        }
        val lactateThresholdPaceSeries = lactateThresholdPaceMinPerKmSeries(lactateThresholdPaceSecPerKmSeries)
        val efficiencyRuns = lactateRows.mapNotNull { row ->
            row.summary?.efficiencyFactor?.let { OffsetDateTime.parse(row.startTime).toLocalDate() to it }
        }
        val latestRunDynamics = lactateRows.lastOrNull { it.summary?.gapMinPerKm != null }?.summary

        val zeppSportBySession = supabase.postgrest.from("zepp_workout_detail")
            .select(columns = Columns.list("exercise_session_id,sport_type"))
            .decodeList<ZeppSportRow>()
            .mapNotNull { r -> r.exerciseSessionId?.let { it to r.sportType } }
            .toMap()

        val running = dedupeRunSessions(sessions.filter { it.type == "run" })
        val strength = sessions.filter { it.type == "strength" }

        val today = LocalDate.now()
        val fourWeekTotal = sumDistanceKmSince(running, today, 28)
        val strengthThisWeek = strength.filter {
            ChronoUnit.DAYS.between(OffsetDateTime.parse(it.startTime).toLocalDate(), today) < 7
        }
        val trailRunsThisWeek = running.filter {
            it.details.routeType == "trail" && ChronoUnit.DAYS.between(OffsetDateTime.parse(it.startTime).toLocalDate(), today) < 7
        }

        return TrainingOverview(
            thisWeekDistanceKm = sumDistanceKmSince(running, today, 7),
            fourWeekAvgKmPerWeek = fourWeekTotal / 4.0,
            longestRunKm = longestRunKm(running),
            avgPaceThisWeek = averagePaceMinPerKmSince(running, today, 7),
            latestVo2Max = latestNonNullVo2Max(vo2Rows),
            vo2MaxSeries = vo2MaxSeries(vo2Rows),
            latestLactateThresholdPaceMinPerKm = lactateThresholdPaceSeries.lastOrNull()?.second,
            lactateThresholdPaceSeries = lactateThresholdPaceSeries,
            hasAnyRunning = running.isNotEmpty(),
            runningSessions = running,
            hasAnyStrength = strength.isNotEmpty(),
            strengthSessionsThisWeek = strengthThisWeek.size,
            strengthMinutesThisWeek = strengthThisWeek.sumOf { it.durationMin ?: 0.0 }.toInt(),
            thisWeekTrailRunCount = trailRunsThisWeek.size,
            thisWeekTrailElevationGainM = trailRunsThisWeek.mapNotNull { it.elevationGainM }.takeIf { it.isNotEmpty() }?.sum(),
            // Live check found reusing `running` here (built to prefer the
            // trustworthy-DISTANCE row) sent list taps into a dead end: the
            // manual row it prefers has no health_connect_record_id, so
            // Session Detail has no route/time series to show at all --
            // "nothing loads below Summary" was this, not a crash. A list
            // meant to be tapped into detail needs the row that actually
            // carries that id instead, now that the known wrong-distance HC
            // rows have already been corrected in the database (see DAV-190).
            recentSessions = (
                dedupeRunSessions(
                    sessions.filter { it.type == "run" },
                    preferred = compareBy<ExerciseSessionRow> { it.source == "health_connect" }
                        .thenBy { it.avgHr != null }
                        .thenBy { it.distanceKm ?: 0.0 }
                        .thenBy { it.durationMin ?: 0.0 },
                ) + sessions.filter { it.type != "run" }
            ).sortedByDescending { OffsetDateTime.parse(it.startTime) },
            // Checked over ALL run rows, not just the deduped list: the Zepp
            // sport code hangs off the HC row the Zepp workout was linked to,
            // which dedupe may not have kept. Mark-as-trail is then applied to
            // every row of the cluster below so the tag survives whichever wins.
            efficiencyPoints = efficiencyRuns,
            latestGapMinPerKm = latestRunDynamics?.gapMinPerKm,
            latestHrDecouplingPct = latestRunDynamics?.hrDecouplingPct,
            suspectedTrailRuns = running.mapNotNull { r ->
                val cluster = sessions.filter { s ->
                    s.type == "run" && s.id != r.id &&
                        kotlin.math.abs(java.time.Duration.between(OffsetDateTime.parse(s.startTime), OffsetDateTime.parse(r.startTime)).toMinutes()) <= 5
                } + r
                val zeppCode = cluster.firstNotNullOfOrNull { zeppSportBySession[it.id] }
                suspectedTrailReason(r, zeppCode)?.let { SuspectedTrailRun(r, it, cluster.map { c -> c.id }) }
            },
        )
    }
}
