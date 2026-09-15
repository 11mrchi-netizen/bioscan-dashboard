package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.RunRow
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

data class TrainingOverview(
    val thisWeekDistanceKm: Double,
    val fourWeekAvgKmPerWeek: Double,
    val longestRunKm: Double?,
    val avgPaceThisWeek: Double?,
    val latestVo2Max: Double?,
    val hasAnyRuns: Boolean,
    val runs: List<RunRow>, // raw rows, kept for the 1D/7D/30D/90D distance-totals widget
)

class TrainingRepository(private val supabase: SupabaseClient) {

    suspend fun loadOverview(): TrainingOverview {
        // 200 is a generous row-count margin, not a real 90-day date filter
        // -- fine while this account's real run volume is ~20 total, same
        // "bounded, not unbounded" tradeoff LogRepository already makes.
        val runs = supabase.postgrest.from("runs")
            .select(columns = Columns.list("date,distance_km,pace_min_per_km")) {
                order("date", Order.DESCENDING)
                limit(200)
            }
            .decodeList<RunRow>()

        val vo2Rows = supabase.postgrest.from("wearable_daily")
            .select(columns = Columns.list("date,vo2max")) {
                order("date", Order.DESCENDING)
                limit(10)
            }
            .decodeList<Vo2MaxRow>()
            .reversed()

        val today = LocalDate.now()
        val fourWeekTotal = sumDistanceKmSince(runs, today, 28)

        return TrainingOverview(
            thisWeekDistanceKm = sumDistanceKmSince(runs, today, 7),
            fourWeekAvgKmPerWeek = fourWeekTotal / 4.0,
            longestRunKm = longestRunKm(runs),
            avgPaceThisWeek = averagePaceMinPerKmSince(runs, today, 7),
            latestVo2Max = latestNonNullVo2Max(vo2Rows),
            hasAnyRuns = runs.isNotEmpty(),
            runs = runs,
        )
    }
}
