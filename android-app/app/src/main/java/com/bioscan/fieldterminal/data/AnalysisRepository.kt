package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.BodyMetricsAnalysisRow
import com.bioscan.fieldterminal.data.model.LabDrawAnalysisRow
import com.bioscan.fieldterminal.data.model.LabResultAnalysisRow
import com.bioscan.fieldterminal.data.model.LogArousalRow
import com.bioscan.fieldterminal.data.model.LogMasturbationRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.model.OstrcAnalysisRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.EnduranceSessionInput
import com.bioscan.fieldterminal.domain.aggregateMealsByDay
import com.bioscan.fieldterminal.domain.trimp
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate
import java.time.OffsetDateTime

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
            .select(columns = Columns.list("date,hrv,rhr,steps,spo2_avg")) {
                order("date", Order.DESCENDING)
                limit(200)
            }
            .decodeList<WearableAnalysisRow>()
            .reversed()

    suspend fun loadSleepDaily(): List<SleepAnalysisRow> =
        supabase.postgrest.from("sleep_daily")
            .select(columns = Columns.list("date,hours,bedtime,wake_time,respiratory_rate,deep_min,rem_min,light_min")) {
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
            .select(columns = Columns.list("start_time,duration_min,rpe,avg_hr,max_hr")) {
                // 25/9 rework: the Load tab charts up to 1Y of CTL/ATL/TSB, and a
                // 200-row cap (newest first) only reached ~7 weeks once Health
                // Connect noise landed (913 rows/400 days). A date window keeps
                // the EWMA's warm-up intact (400d = 1Y + ~35d of warm-up).
                filter { gte("start_time", java.time.LocalDate.now().minusDays(400).toString()) }
                order("start_time", Order.DESCENDING)
                limit(3000)
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

    // DAV-215 (24/9 fixes): previously an inline raw query in
    // HeartTileScreen.kt -- moved here so arousal history goes through the
    // same repository as every other Heart data source.
    suspend fun loadArousalDaily(): List<LogArousalRow> =
        supabase.postgrest.from("arousal_daily")
            .select(columns = Columns.list("id,date,morning_erection_quality,arousal_level")) {
                order("date", Order.DESCENDING)
                limit(200)
            }
            .decodeList<LogArousalRow>()
            .reversed()

    // DAV-215 (24/9 fixes): masturbation entries fold into the same Arousal
    // history timeline (see HeartTileScreen.kt's ArousalHistory).
    suspend fun loadMasturbationLog(): List<LogMasturbationRow> =
        supabase.postgrest.from("masturbation_log")
            .select(columns = Columns.list("id,occurred_at,watched_porn,load_size,orgasm_intensity,notes")) {
                order("occurred_at", Order.DESCENDING)
                limit(200)
            }
            .decodeList<LogMasturbationRow>()
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

    // Phase A4 (Category 9). Two small real tables (2 draws, 77 results
    // today) -- no server-side date filtering needed at this account's real
    // volume, same "generous margin" reasoning as everywhere else here.
    suspend fun loadLabDraws(): List<LabDrawAnalysisRow> =
        supabase.postgrest.from("lab_draws")
            .select(columns = Columns.list("id,draw_date")) {
                order("draw_date", Order.DESCENDING)
                limit(200)
            }
            .decodeList<LabDrawAnalysisRow>()
            .reversed()

    suspend fun loadLabResults(): List<LabResultAnalysisRow> =
        supabase.postgrest.from("lab_results")
            .select(columns = Columns.list("draw_id,marker_name,value,unit,ref_low,ref_high")) {
                limit(500)
            }
            .decodeList<LabResultAnalysisRow>()
}

// DAV-205 (24/9 fixes): shared by StatusRepository (figure's leg zone) and
// TrainingTileScreen (Load tab + Injury tab's OSTRC context) so all three
// always agree on the same session_load numbers instead of drifting apart
// like the toMetricState() mapping did before DAV-210. RPE-based load takes
// priority when a session has one (a direct subjective-effort number);
// TRIMP (domain/EnduranceLoad.kt, already implemented, never wired in
// before this) is the fallback for the near-total majority of sessions with
// real HR data but no RPE.
// ponytail: one current resting-HR value applied to every session, not a
// per-session-date lookup -- resting HR doesn't swing enough day-to-day for
// that gap to matter here; revisit if it ever does.
fun buildTrainingSessionLoads(sessions: List<TrainingLoadSessionRow>, latestRestingHr: Double?): List<Pair<LocalDate, Double>> =
    sessions.mapNotNull { row ->
        val duration = row.durationMin ?: return@mapNotNull null
        val date = OffsetDateTime.parse(row.startTime).toLocalDateTime().toLocalDate()
        val load = row.rpe?.let { duration * it }
            ?: trimp(EnduranceSessionInput(duration, null, row.avgHr, row.maxHr, null, null, null), latestRestingHr)
            ?: return@mapNotNull null
        date to load
    }
