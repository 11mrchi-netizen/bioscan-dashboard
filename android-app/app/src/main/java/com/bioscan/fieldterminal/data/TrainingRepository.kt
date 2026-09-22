package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.data.model.Vo2MaxRow
import com.bioscan.fieldterminal.domain.averagePaceMinPerKmSince
import com.bioscan.fieldterminal.domain.latestNonNullVo2Max
import com.bioscan.fieldterminal.domain.longestRunKm
import com.bioscan.fieldterminal.domain.dedupeRunSessions
import com.bioscan.fieldterminal.domain.sumDistanceKmSince
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
)

class TrainingRepository(private val supabase: SupabaseClient) {

    suspend fun loadOverview(): TrainingOverview {
        // 200 is a generous row-count margin, not a real 90-day date filter
        // -- same "bounded, not unbounded" tradeoff this query already made
        // when it only covered runs.
        val sessions = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("type,start_time,duration_min,distance_km,avg_hr,avg_speed_kmh,source,elevation_gain_m,details")) {
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
            hasAnyRunning = running.isNotEmpty(),
            runningSessions = running,
            hasAnyStrength = strength.isNotEmpty(),
            strengthSessionsThisWeek = strengthThisWeek.size,
            strengthMinutesThisWeek = strengthThisWeek.sumOf { it.durationMin ?: 0.0 }.toInt(),
            thisWeekTrailRunCount = trailRunsThisWeek.size,
            thisWeekTrailElevationGainM = trailRunsThisWeek.mapNotNull { it.elevationGainM }.takeIf { it.isNotEmpty() }?.sum(),
        )
    }
}
