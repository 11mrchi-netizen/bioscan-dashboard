package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.data.model.Vo2MaxRow
import com.bioscan.fieldterminal.domain.averagePaceMinPerKmSince
import com.bioscan.fieldterminal.domain.latestNonNullVo2Max
import com.bioscan.fieldterminal.domain.longestRunKm
import com.bioscan.fieldterminal.domain.sumDistanceKmSince
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

data class TrainingOverview(
    val thisWeekDistanceKm: Double,
    val fourWeekAvgKmPerWeek: Double,
    val longestRunKm: Double?,
    val avgPaceThisWeek: Double?,
    val latestVo2Max: Double?,
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

        val vo2Rows = supabase.postgrest.from("wearable_daily")
            .select(columns = Columns.list("date,vo2max")) {
                order("date", Order.DESCENDING)
                limit(10)
            }
            .decodeList<Vo2MaxRow>()
            .reversed()

        val endurance = sessions.filter { it.type in ENDURANCE_TYPES }
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
            hasAnyEndurance = endurance.isNotEmpty(),
            enduranceSessions = endurance,
            hasAnyStrength = strength.isNotEmpty(),
            strengthSessionsThisWeek = strengthThisWeek.size,
            strengthMinutesThisWeek = strengthThisWeek.sumOf { it.durationMin ?: 0.0 }.toInt(),
        )
    }
}
