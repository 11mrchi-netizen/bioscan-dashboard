package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.data.model.Vo2MaxRow
import com.bioscan.fieldterminal.domain.averagePaceMinPerKmSince
import com.bioscan.fieldterminal.domain.latestNonNullVo2Max
import com.bioscan.fieldterminal.domain.longestRunKm
import com.bioscan.fieldterminal.domain.sumDistanceKmSince
import com.bioscan.fieldterminal.domain.vo2MaxSeries
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

// Phase G3: reads the generic `exercise_sessions` table (was the run-only
// `runs`) -- "endurance" is any of run/walk/hike/ride, matching
// healthconnect/HealthConnectExerciseTypes.kt's closed vocabulary.
// Strength finally gets real data here too (Step 7's own finding was that
// no strength data source existed anywhere -- Health Connect is the first).
private val ENDURANCE_TYPES = setOf("run", "walk", "hike", "ride")

// Health Connect's own auto-detected "walk" sessions are dominated by
// incidental movement, not deliberate training -- confirmed against this
// account's real data: 4,655 walk sessions averaging 10 minutes / 0.7km
// each (3,734 of them under 1km), totaling 3,257km of pure noise across the
// account's history. That's what inflated the Endurance Total to a real,
// reported ~160km for a window nowhere close to that in reality. run/hike/
// ride sessions show no equivalent noise floor (real averages: 14.3km/run,
// 10.5km/hike, 7.0km/ride) and don't need this filter. A 20-minute + 1.5km
// floor keeps the 214 walk sessions that clear both (real deliberate
// walks, ~684km total) and drops the rest.
private const val MIN_WALK_DURATION_MIN = 20.0
private const val MIN_WALK_DISTANCE_KM = 1.5
private fun isRealWalk(session: ExerciseSessionRow): Boolean =
    (session.durationMin ?: 0.0) >= MIN_WALK_DURATION_MIN && (session.distanceKm ?: 0.0) >= MIN_WALK_DISTANCE_KM

// Separate, real bug found via live on-device verification of the walk-noise
// fix above: the current week's inflated total (still ~164km after that fix)
// turned out to come from individual "run" sessions with a physically
// impossible pace -- e.g. 38.49km in 82 minutes (2:13/km, faster than any
// human has ever sustained for that distance) and 58.28km in 256 minutes
// (4:23/km for an ultra distance, also implausible). These are real GPS/
// tracking errors (likely multiple real activities merged into one session,
// or a GPS drift spike), not deliberate training, and the walk-only filter
// above never touched them since they're typed "run". 22 km/h (2:44/km,
// just under the actual marathon world record pace) is a real, generous
// ceiling for sustained foot-based movement -- nobody recreational or
// amateur exceeds it, and even elite marathoners don't sustain faster than
// that outside a WR attempt. Cycling ("ride") is deliberately excluded --
// its own real speed norms are entirely different.
private const val MAX_FOOT_SPEED_KMH = 22.0
private fun hasPlausiblePace(session: ExerciseSessionRow): Boolean {
    if (session.type == "ride") return true
    val distance = session.distanceKm ?: return true
    val durationHours = (session.durationMin ?: return true) / 60.0
    if (durationHours <= 0) return true
    return distance / durationHours <= MAX_FOOT_SPEED_KMH
}

data class TrainingOverview(
    val thisWeekDistanceKm: Double,
    val fourWeekAvgKmPerWeek: Double,
    val longestRunKm: Double?,
    val avgPaceThisWeek: Double?,
    val latestVo2Max: Double?,
    val vo2MaxSeries: List<Pair<LocalDate, Double>>, // DAV-80: raw series backing the Training tile's chart
    val hasAnyEndurance: Boolean,
    val enduranceSessions: List<ExerciseSessionRow>, // raw rows, kept for the 1D/7D/30D/90D distance-totals widget
    val hasAnyStrength: Boolean,
    val strengthSessionsThisWeek: Int,
    val strengthMinutesThisWeek: Int,
)

class TrainingRepository(private val supabase: SupabaseClient) {

    suspend fun loadOverview(): TrainingOverview {
        // 200 is a generous row-count margin, not a real 90-day date filter
        // -- same "bounded, not unbounded" tradeoff this query already made
        // when it only covered runs.
        val sessions = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("type,start_time,duration_min,distance_km,avg_hr")) {
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

        val endurance = sessions.filter {
            it.type in ENDURANCE_TYPES && (it.type != "walk" || isRealWalk(it)) && hasPlausiblePace(it)
        }
        val strength = sessions.filter { it.type == "strength" }

        val today = LocalDate.now()
        val fourWeekTotal = sumDistanceKmSince(endurance, today, 28)
        val strengthThisWeek = strength.filter {
            ChronoUnit.DAYS.between(OffsetDateTime.parse(it.startTime).toLocalDate(), today) < 7
        }

        return TrainingOverview(
            thisWeekDistanceKm = sumDistanceKmSince(endurance, today, 7),
            fourWeekAvgKmPerWeek = fourWeekTotal / 4.0,
            longestRunKm = longestRunKm(endurance),
            avgPaceThisWeek = averagePaceMinPerKmSince(endurance, today, 7),
            latestVo2Max = latestNonNullVo2Max(vo2Rows),
            vo2MaxSeries = vo2MaxSeries(vo2Rows),
            hasAnyEndurance = endurance.isNotEmpty(),
            enduranceSessions = endurance,
            hasAnyStrength = strength.isNotEmpty(),
            strengthSessionsThisWeek = strengthThisWeek.size,
            strengthMinutesThisWeek = strengthThisWeek.sumOf { it.durationMin ?: 0.0 }.toInt(),
        )
    }
}
