package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.BodyMetricsAnalysisRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.model.OstrcAnalysisRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.aggregateMealsByDay
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order

// Phase A2 (Analysis Layer). Backs the Status tab's new ANALYSIS sub-tab --
// separate from StatusRepository, which caps at limit(10)/limit(1) for the
// BodyConsole readiness display. The Evaluation Method Spec's Category 1/2/5
// formulas need a real 60-day lookback (Category 1's SWC baseline, Category
// 2's sleep sub-metrics), so this fetches a generous row-count margin
// (limit(200), matching TrainingRepository's own "generous margin, not
// exact" precedent) and lets the domain layer's own date-based window
// filtering (ChronoUnit.DAYS.between(...) < days, this project's established
// convention) decide what actually falls inside each window -- no server-
// side date-range filter, consistent with how every other repository in
// this app reads a daily table.
class AnalysisRepository(private val supabase: SupabaseClient) {

    suspend fun loadWearableDaily(): List<WearableAnalysisRow> =
        supabase.postgrest.from("wearable_daily")
            .select(columns = Columns.list("date,hrv,rhr")) {
                order("date", Order.DESCENDING)
                limit(200)
            }
            .decodeList<WearableAnalysisRow>()
            .reversed()

    suspend fun loadSleepDaily(): List<SleepAnalysisRow> =
        supabase.postgrest.from("sleep_daily")
            .select(columns = Columns.list("date,hours,bedtime,wake_time,respiratory_rate")) {
                order("date", Order.DESCENDING)
                limit(200)
            }
            .decodeList<SleepAnalysisRow>()
            .reversed()

    suspend fun loadBodyMetrics(): List<BodyMetricsAnalysisRow> =
        supabase.postgrest.from("body_metrics")
            .select(columns = Columns.list("date,weight_kg,body_fat_pct")) {
                order("date", Order.DESCENDING)
                limit(200)
            }
            .decodeList<BodyMetricsAnalysisRow>()
            .reversed()

    // Phase A4 (Category 6). CTL/ATL's 42-day EWMA needs the same generous
    // margin as everything else here -- exercise_sessions is smaller today
    // (19 real rows) than the other tables, but this stays consistent with
    // this repository's own "don't special-case the query, let the domain
    // layer's date filtering decide" convention.
    suspend fun loadExerciseSessionsForTrainingLoad(): List<TrainingLoadSessionRow> =
        supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("start_time,duration_min,rpe")) {
                order("start_time", Order.DESCENDING)
                limit(200)
            }
            .decodeList<TrainingLoadSessionRow>()
            .reversed()

    // Phase A4 (Category 3). 30-day baseline gate, same margin reasoning as
    // everything else here.
    suspend fun loadWellbeingDaily(): List<WellbeingAnalysisRow> =
        supabase.postgrest.from("wellbeing_daily")
            .select(columns = Columns.list("date,energy,mood,stress,soreness")) {
                order("date", Order.DESCENDING)
                limit(200)
            }
            .decodeList<WellbeingAnalysisRow>()
            .reversed()

    // Phase A4 (Category 4). Reuses domain/Nutrition.kt's own
    // aggregateMealsByDay() (Step 6) rather than re-deriving daily totals a
    // second way -- same MealRow model NutritionRepository already reads,
    // just fetched here for the Evaluation Method Spec's own consumer.
    suspend fun loadDailyNutrition(): List<DailyNutrition> {
        val meals = supabase.postgrest.from("meals")
            .select(columns = Columns.list("logged_at,calories,protein_g,fat_g,carbs_g")) {
                order("logged_at", Order.DESCENDING)
                limit(500)
            }
            .decodeList<MealRow>()
        return aggregateMealsByDay(meals)
    }

    // Phase A4 (Category 8, Bristol half). Same generous margin as every
    // other read here.
    suspend fun loadStoolLog(): List<StoolAnalysisRow> =
        supabase.postgrest.from("stool_log")
            .select(columns = Columns.list("occurred_at,bristol_type")) {
                order("occurred_at", Order.DESCENDING)
                limit(200)
            }
            .decodeList<StoolAnalysisRow>()
            .reversed()

    // Phase A4 (Category 8, OSTRC-H2 half).
    suspend fun loadOstrcCheckins(): List<OstrcAnalysisRow> =
        supabase.postgrest.from("ostrc_checkins")
            .select(columns = Columns.list("check_date,body_area,severity_score")) {
                order("check_date", Order.DESCENDING)
                limit(200)
            }
            .decodeList<OstrcAnalysisRow>()
            .reversed()
}
