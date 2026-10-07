package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.data.model.HealthEventRow
import com.bioscan.fieldterminal.domain.dedupeRunSessions
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import com.bioscan.fieldterminal.data.model.SleepDailyRow
import com.bioscan.fieldterminal.data.model.WearableDailyRow
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.ReadinessBand
import com.bioscan.fieldterminal.domain.computeHrvReadinessSeries
import com.bioscan.fieldterminal.domain.evaluateHrv
import com.bioscan.fieldterminal.domain.evaluateRhr
import com.bioscan.fieldterminal.domain.evaluateSleepDuration
import com.bioscan.fieldterminal.domain.evaluateTrainingLoad
import com.bioscan.fieldterminal.domain.isHealthEventActive
import com.bioscan.fieldterminal.domain.readinessBand
import com.bioscan.fieldterminal.domain.toMetricState
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate

data class StatusOverview(
    val readiness: ReadinessBand,
    val latestHrv: Double?,
    val latestRhr: Double?,
    val sleepHours: Double?,
    val activeHealthEvent: Boolean,
    // DAV-203 (24/9 fixes): per-region states for the zone-mapped body
    // figure -- chest=cardio (worst of HRV/RHR SWC evaluation, same math the
    // Health tab's Cardio subtab uses, so the figure never disagrees with
    // what tapping into HEALTH shows), legs=training load (CTL/ATL/TSB
    // gate), head=recovery (sleep duration SWC evaluation).
    val cardioState: MetricState,
    val trainingState: MetricState,
    val recoveryState: MetricState,
    // Opening-page Training tile: distinct sessions in the last 7 days. Runs go
    // through dedupeRunSessions so a Health Connect + manual/Zepp pair counts once.
    val sessionsLast7Days: Int,
)

// No user_id filter anywhere here -- RLS already scopes every query to the
// signed-in user, same as the web dashboard relies on (see ROADMAP.md
// Foundation section).
class StatusRepository(private val supabase: io.github.jan.supabase.SupabaseClient) {

    suspend fun loadOverview(): StatusOverview {
        // 60 most recent days, most-recent-first from Postgrest, then
        // reversed to chronological order -- computeHrvReadinessSeries()
        // needs index i-1 to mean "the day before index i", matching how
        // index.html's own wearable.hrv array is built. 10 was too few:
        // readiness returned Unknown when only 3 of 10 days had valid HRV,
        // even with 60+ valid days in the DB.
        val wearable = supabase.postgrest.from("wearable_daily")
            .select(columns = Columns.list("date,rhr,hrv")) {
                order("date", Order.DESCENDING)
                limit(60)
            }
            .decodeList<WearableDailyRow>()
            .reversed()

        val hrvSeries = computeHrvReadinessSeries(wearable.map { it.hrv })
        val readiness = readinessBand(hrvSeries.lastOrNull())

        val latestSleep = supabase.postgrest.from("sleep_daily")
            .select(columns = Columns.list("date,hours")) {
                order("date", Order.DESCENDING)
                limit(1)
            }
            .decodeList<SleepDailyRow>()
            .firstOrNull()

        val today = LocalDate.now()
        val injuries = supabase.postgrest.from("injuries")
            .select(columns = Columns.list("status,end_date"))
            .decodeList<HealthEventRow>()
        val illnesses = supabase.postgrest.from("illnesses")
            .select(columns = Columns.list("status,end_date"))
            .decodeList<HealthEventRow>()
        val activeHealthEvent = (injuries + illnesses).any { row ->
            isHealthEventActive(row.status, row.endDate?.let(LocalDate::parse), today)
        }

        // DAV-203: reuse the exact same evaluations the Health/Training tabs
        // already compute (AnalysisRepository's own loaders, 200-row windows
        // reversed to chronological order -- the same shape every other
        // caller of these functions uses) rather than a separate lightweight
        // heuristic, so the figure's zone colors never disagree with what
        // tapping into that tile shows.
        val analysis = AnalysisRepository(supabase)

        val wearableAnalysis = analysis.loadWearableDaily()
        val hrvPoints = wearableAnalysis.mapNotNull { row -> row.hrv?.let { LocalDate.parse(row.date) to it } }
        val rhrPoints = wearableAnalysis.mapNotNull { row -> row.rhr?.let { LocalDate.parse(row.date) to it } }
        val cardioState = worstOf(evaluateHrv(hrvPoints).state.toMetricState(), evaluateRhr(rhrPoints).state.toMetricState())

        val sleepHoursPoints = analysis.loadSleepDaily().mapNotNull { row -> row.hours?.let { LocalDate.parse(row.date) to it } }
        val recoveryState = evaluateSleepDuration(sleepHoursPoints).state.toMetricState()

        // DAV-205: TRIMP-fallback session loads, same shared builder
        // TrainingTileScreen uses -- keeps the figure's leg color and the
        // Training tab's own Load card from ever disagreeing.
        val sessionLoads = buildTrainingSessionLoads(analysis.loadExerciseSessionsForTrainingLoad(), wearableAnalysis.lastOrNull()?.rhr)
        val trainingState = evaluateTrainingLoad(sessionLoads).state.toMetricState()

        // Small dedicated fetch (10 days of margin, filtered to 7 by date below)
        // rather than reusing TrainingLoadSessionRow, which carries no type/source.
        val recentSessions = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,type,start_time,duration_min,distance_km,avg_hr,source")) {
                order("start_time", Order.DESCENDING)
                limit(60)
            }
            .decodeList<ExerciseSessionRow>()
            .filter { ChronoUnit.DAYS.between(OffsetDateTime.parse(it.startTime).toLocalDate(), today) < 7 }
        val sessionsLast7Days = dedupeRunSessions(recentSessions.filter { it.type == "run" }).size +
            recentSessions.count { it.type != "run" }

        return StatusOverview(
            readiness = readiness,
            latestHrv = wearable.lastOrNull()?.hrv,
            latestRhr = wearable.lastOrNull()?.rhr,
            sleepHours = latestSleep?.hours,
            activeHealthEvent = activeHealthEvent,
            cardioState = cardioState,
            trainingState = trainingState,
            recoveryState = recoveryState,
            sessionsLast7Days = sessionsLast7Days,
        )
    }
}

private fun MetricState.severity(): Int = when (this) {
    MetricState.Critical -> 4
    MetricState.Warning -> 3
    MetricState.Neutral -> 2
    MetricState.Optimal -> 1
    MetricState.Building -> 0
    MetricState.Unavailable -> -1
}

private fun worstOf(a: MetricState, b: MetricState): MetricState = if (a.severity() >= b.severity()) a else b
