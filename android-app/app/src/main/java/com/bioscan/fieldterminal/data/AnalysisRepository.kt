package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.HrPoint
import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.domain.HeartRateDay
import com.bioscan.fieldterminal.domain.plausibleMinutes
import com.bioscan.fieldterminal.data.model.FitnessFatigueRow
import com.bioscan.fieldterminal.domain.SCORECARD_HISTORY_DAYS
import com.bioscan.fieldterminal.data.model.PlannedOutcomeRow
import com.bioscan.fieldterminal.data.model.BodyMetricsAnalysisRow
import com.bioscan.fieldterminal.data.model.CaseCrossoverResultRow
import com.bioscan.fieldterminal.data.model.EventCurveRow
import com.bioscan.fieldterminal.data.model.ExpectedBandRow
import com.bioscan.fieldterminal.data.model.LagCurveRow
import com.bioscan.fieldterminal.data.model.LedgerEntry
import com.bioscan.fieldterminal.data.model.NewRegisteredQuestion
import com.bioscan.fieldterminal.data.model.RegisteredQuestion
import com.bioscan.fieldterminal.data.model.LabDrawAnalysisRow
import com.bioscan.fieldterminal.data.model.LabResultAnalysisRow
import com.bioscan.fieldterminal.data.model.LogSexualActivityRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.model.OstrcAnalysisRow
import com.bioscan.fieldterminal.data.model.RelationshipResultRow
import com.bioscan.fieldterminal.data.model.SeriesBreakResultRow
import com.bioscan.fieldterminal.data.model.TypicalityResultRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.EnduranceSessionInput
import com.bioscan.fieldterminal.domain.SeriesBreakStarts
import com.bioscan.fieldterminal.domain.aggregateMealsByDay
import com.bioscan.fieldterminal.domain.seriesBreakStartsFrom
import com.bioscan.fieldterminal.domain.INTENSITY_SPORTS
import com.bioscan.fieldterminal.domain.IntensitySession
import com.bioscan.fieldterminal.domain.SRPE_PER_TRIMP
import com.bioscan.fieldterminal.domain.TrimpAnchors
import com.bioscan.fieldterminal.domain.mondayOf
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.async
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
// Redesign: the range selector reaches ALL (years of daily rows), so the daily histories are not cut at 200 days. The hosted API
// returns at most 1000 rows a request whatever limit() says (wearable_daily has 3,600+ days), so these are read a page at a time.
private const val DAILY_HISTORY_ROWS = 3650L
private const val PAGE_ROWS = 1000L

class AnalysisRepository(private val supabase: SupabaseClient) {

    // Analysis Layer 3 / R1. The start of each metric's current regime = its last CONFIRMED series break, published by the
    // analysis-run Edge Function into analysis_results. Optional by design: before the first run, with no confirmed break,
    // or if the read fails, every baseline behaves exactly as before (NONE), never blocks a screen.
    suspend fun loadSeriesBreakStarts(): SeriesBreakStarts =
        try {
            ReadCache.get("analysis.series_break", tables = setOf("analysis_results")) {
                val rows = supabase.postgrest.from("analysis_results")
                    .select(columns = Columns.list("key,payload")) { filter { eq("kind", "series_break") }; newestFirst() }
                    .decodeList<SeriesBreakResultRow>()
                    .distinctBy { it.key }
                seriesBreakStartsFrom(rows.associate { it.key to it.payload.lastConfirmed })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SeriesBreakStarts.NONE
        }

    // Analysis Layer 3 / Phase 3. The latest published relationship results; empty before the first run or if the read fails.
    suspend fun loadRelationships(): List<RelationshipResultRow> =
        try {
            ReadCache.get("analysis.relationships", tables = setOf("analysis_results")) {
                supabase.postgrest.from("analysis_results")
                    .select(columns = Columns.list("key,state,payload")) { filter { eq("kind", "relationship") }; newestFirst() }
                    .decodeList<RelationshipResultRow>()
                    .distinctBy { it.key }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    // Analysis Layer 3 / Phase 4. The latest "is today typical for you" result, or null before the first run / on failure.
    suspend fun loadTypicality(): TypicalityResultRow? =
        try {
            ReadCache.get("analysis.typicality", tables = setOf("analysis_results")) {
                supabase.postgrest.from("analysis_results")
                    .select(columns = Columns.list("key,state,payload")) { filter { eq("kind", "typicality") }; newestFirst() }
                    .decodeList<TypicalityResultRow>()
                    .firstOrNull()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    // Analysis review phase D. The evidence board's other views: event curves, case-crossover tables, lag curves. Each
    // list is empty before the first run or if its read fails.
    suspend fun loadEvidence(): EvidenceRows = kotlinx.coroutines.coroutineScope {
        val events = async { softList("analysis.event_curve") { latestOf<EventCurveRow>("event_curve") } }
        val crossover = async { softList("analysis.case_crossover") { latestOf<CaseCrossoverResultRow>("case_crossover") } }
        val lags = async { softList("analysis.lag_curve") { latestOf<LagCurveRow>("lag_curve") } }
        EvidenceRows(events.await(), crossover.await(), lags.await())
    }

    private suspend inline fun <reified T : Any> latestOf(kind: String): List<T> =
        supabase.postgrest.from("analysis_results")
            .select(columns = Columns.list("key,state,payload")) { filter { eq("kind", kind) }; newestFirst() }
            .decodeList<T>()

    private suspend fun <T> softList(cacheKey: String, block: suspend () -> List<T>): List<T> =
        try {
            ReadCache.get(cacheKey, tables = setOf("analysis_results")) { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    // Analysis review phase E. Registered questions' results (kind registered_question), newest per key; empty before
    // the first one is registered or if the read fails.
    suspend fun loadRegisteredResults(): List<RelationshipResultRow> =
        softList("analysis.registered_question") { latestOf<RelationshipResultRow>("registered_question").distinctBy { it.key } }

    // The live registrations themselves (the result appears after the next nightly run).
    suspend fun loadRegisteredQuestions(): List<RegisteredQuestion> =
        softList("analysis.registered_questions") {
            supabase.postgrest.from("registered_questions")
                .select(columns = Columns.list("id,exposure,outcome,lag,registered_on,withdrawn_at"))
                .decodeList<RegisteredQuestion>()
                .filter { it.withdrawnAt == null }
        }

    // Registers a hint as a question tested only on days after today. Throws on failure (the caller shows it). Any write
    // clears the read cache (ReadCache's CacheInvalidation), so the lists above reload.
    suspend fun registerQuestion(q: NewRegisteredQuestion) {
        supabase.postgrest.from("registered_questions").insert(q)
    }

    // Withdrawing keeps the row (and its ledger history); it just stops being tested.
    suspend fun withdrawQuestion(id: Long) {
        supabase.postgrest.from("registered_questions").update(mapOf("withdrawn_at" to java.time.Instant.now().toString())) { filter { eq("id", id) } }
    }

    // The verdict history of one question from the append-only ledger, newest first (empty if the read fails).
    suspend fun loadLedger(questionId: String, limit: Long = 120): List<LedgerEntry> =
        try {
            supabase.postgrest.from("evidence_ledger")
                .select(columns = Columns.list("question_id,as_of,verdict,estimate,lo,hi,unit")) {
                    filter { eq("question_id", questionId) }
                    order("as_of", Order.DESCENDING)
                    limit(limit)
                }
                .decodeList<LedgerEntry>()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    // Analysis review phase C. The latest expected band per metric (hrv, rhr, sleep_hours); empty before the first run
    // or if the read fails, and every screen then falls back to what it showed before.
    suspend fun loadExpectedBands(): Map<String, ExpectedBandRow> =
        try {
            ReadCache.get("analysis.expected_band", tables = setOf("analysis_results")) {
                supabase.postgrest.from("analysis_results")
                    .select(columns = Columns.list("key,state,payload")) { filter { eq("kind", "expected_band") }; newestFirst() }
                    .decodeList<ExpectedBandRow>()
                    .distinctBy { it.key }
                    .associateBy { it.key }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }

    // Oldest first, like the single-request reads they replace; pages of PAGE_ROWS newest-first until one comes back short.
    // Keyset, not offset (PR #24 review): each page asks for dates before the last one read, so a day synced between two
    // requests cannot shift the window and read a row twice or skip one. date is unique per user in every table read here.
    private suspend inline fun <reified T : Any> pagedByDate(table: String, columns: String, crossinline dateOf: (T) -> String): List<T> =
        keysetPagesDescending(PAGE_ROWS, DAILY_HISTORY_ROWS, { dateOf(it) }) { before ->
            supabase.postgrest.from(table)
                .select(columns = Columns.list(columns)) {
                    if (before != null) filter { lt("date", before) }
                    order("date", Order.DESCENDING)
                    limit(PAGE_ROWS)
                }
                .decodeList<T>()
        }.reversed()

    suspend fun loadWearableDaily(): List<WearableAnalysisRow> = ReadCache.get("analysis.WearableDaily", tables = setOf("wearable_daily")) { fetchWearableDaily() }

    private suspend fun fetchWearableDaily(): List<WearableAnalysisRow> =
        pagedByDate<WearableAnalysisRow>("wearable_daily", "date,hrv,rhr,steps,spo2_avg") { it.date }

    // The intraday heart-rate series the Heart tab charts: the NEWEST day that has one, with its date. It used to be the
    // oldest day of the 200-row window (an ascending list's first row), so the card showed a day from months ago
    // without saying which. Only plausible minutes are kept (MinuteHeartRate.kt). Fetched on its own because the column
    // is ~50 kB of JSON per day and the other screens never read it.
    suspend fun loadHeartRateDay(): HeartRateDay? = ReadCache.get("analysis.hr_day", tables = setOf("wearable_daily")) {
        supabase.postgrest.from("wearable_daily")
            .select(columns = Columns.list("date,hr_timeseries")) {
                filter {
                    filterNot("hr_timeseries", io.github.jan.supabase.postgrest.query.filter.FilterOperator.IS, "null")
                }
                order("date", Order.DESCENDING)
                limit(3)
            }
            .decodeList<HrDayRow>()
            .firstNotNullOfOrNull { row ->
                plausibleMinutes(row.hrTimeseries.orEmpty()).takeIf { it.isNotEmpty() }?.let { HeartRateDay(LocalDate.parse(row.date), it) }
            }
    }

    // Redesign: each day's lowest and highest heart rate for the Range Trend card, from the wearable_hr_daily_range view
    // (migration 20261104). Empty until that view exists, so the card says "no readings" instead of failing the page.
    suspend fun loadHeartRateRanges(): List<Pair<LocalDate, com.bioscan.fieldterminal.domain.DayRange>> =
        orNullUnlessCancelled {
            ReadCache.get("analysis.hr_ranges", tables = setOf("wearable_daily")) {
                pagedByDate<HrRangeRow>("wearable_hr_daily_range", "date,hr_min,hr_max") { it.date }
                    .map { LocalDate.parse(it.date) to com.bioscan.fieldterminal.domain.DayRange(it.low, it.high) }
            }
        } ?: emptyList()

    // Redesign: one night's timed stages and the overnight heart rate (the evening on the day before, the morning on the day), for
    // the hypnogram. Null when the night has no stored stage intervals (older than the Zepp backfill, or a Health Connect night).
    suspend fun loadHypnogramNight(date: LocalDate): com.bioscan.fieldterminal.domain.HypnogramNight? {
        val row = supabase.postgrest.from("sleep_daily")
            .select(columns = Columns.list("date,bedtime,stage_intervals,tz_offset_min")) {
                filter { eq("date", date.toString()) }
                limit(1)
            }
            .decodeList<HypnogramSleepRow>()
            .firstOrNull() ?: return null
        val raw = row.stageIntervals ?: return null
        val bedtime = row.bedtime?.let { runCatching { java.time.OffsetDateTime.parse(it.replace(' ', 'T')).toInstant() }.getOrNull() } ?: return null
        val blocks = com.bioscan.fieldterminal.domain.parseStageBlocks(raw)
        if (blocks.isEmpty()) return null
        val tz = row.tzOffsetMin ?: 0
        val hrRows = orNullUnlessCancelled {
            supabase.postgrest.from("wearable_daily")
                .select(columns = Columns.list("date,hr_timeseries")) {
                    filter { isIn("date", listOf(date.minusDays(1).toString(), date.toString())) }
                }
                .decodeList<HrDayRow>()
        } ?: emptyList()
        val span = blocks.maxOf { it.endMin }
        val hr = com.bioscan.fieldterminal.domain.nightHeartRate(bedtime, tz, span, hrRows.map { LocalDate.parse(it.date) to it.hrTimeseries.orEmpty() })
        return com.bioscan.fieldterminal.domain.HypnogramNight(date, bedtime, tz, blocks, com.bioscan.fieldterminal.domain.estimateCycles(blocks), hr)
    }

    suspend fun loadSleepDaily(): List<SleepAnalysisRow> = ReadCache.get("analysis.SleepDaily", tables = setOf("sleep_daily")) { fetchSleepDaily() }

    private suspend fun fetchSleepDaily(): List<SleepAnalysisRow> =
        pagedByDate<SleepAnalysisRow>("sleep_daily", "date,hours,bedtime,wake_time,respiratory_rate,deep_min,rem_min,light_min") { it.date }

    suspend fun loadBodyMetrics(): List<BodyMetricsAnalysisRow> = ReadCache.get("analysis.BodyMetrics", tables = setOf("body_metrics")) { fetchBodyMetrics() }

    private suspend fun fetchBodyMetrics(): List<BodyMetricsAnalysisRow> =
        pagedByDate<BodyMetricsAnalysisRow>("body_metrics", "date,weight_kg,body_fat_pct") { it.date }

    // Phase A4 (Category 6). CTL/ATL's 42-day EWMA needs the same generous
    // margin as everything else here -- exercise_sessions is smaller today
    // (19 real rows) than the other tables, but this stays consistent with
    // this repository's own "don't special-case the query, let the domain
    // layer's date filtering decide" convention.
    suspend fun loadExerciseSessionsForTrainingLoad(): List<TrainingLoadSessionRow> = ReadCache.get("analysis.ExerciseSessionsForTrainingLoad", tables = setOf("exercise_sessions")) { fetchExerciseSessionsForTrainingLoad() }

    private suspend fun fetchExerciseSessionsForTrainingLoad(): List<TrainingLoadSessionRow> =
        supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("start_time,duration_min,rpe,avg_hr,max_hr,type")) {
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

    // Weekly intensity (domain/IntensityDistribution.kt): the endurance sessions of the last [weeks] weeks, each with its
    // Zepp heart-rate histogram when it has one (zepp_workout_detail.hr_histogram, migration 20261022).
    suspend fun loadIntensitySessions(weeks: Int = 12): List<IntensitySession> = ReadCache.get("analysis.intensity.$weeks", tables = setOf("exercise_sessions")) {
        val from = mondayOf(LocalDate.now()).minusWeeks(weeks - 1L)
        supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.raw("start_time,type,zepp_workout_detail(hr_histogram)")) {
                filter { gte("start_time", from.toString()); gt("duration_min", 0); isIn("type", INTENSITY_SPORTS.toList()) }
                order("start_time", Order.ASCENDING)
            }
            .decodeList<IntensitySessionRow>()
            .map { row ->
                IntensitySession(
                    // The local day, not the UTC timestamp's own date (a 07:00 session in UTC+8 is the day before in UTC).
                    OffsetDateTime.parse(row.startTime).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate(),
                    row.type,
                    row.zepp.firstNotNullOfOrNull { it.histogram }?.mapNotNull { (bpm, secs) -> bpm.toIntOrNull()?.let { it to secs } }?.toMap(),
                )
            }
    }

    // Analysis review phase G: the personal fitness-fatigue result, and the spans every load chart uses (the standard 42 and
    // 7 unless the personal pair was adopted; standard too when nothing is published or the read fails).
    suspend fun loadFitnessFatigue(): FitnessFatigueRow? = ReadCache.get("analysis.FitnessFatigue", tables = setOf("analysis_results")) {
        orNullUnlessCancelled {
            supabase.postgrest.from("analysis_results")
                .select(columns = Columns.list("key,state,payload")) { filter { eq("kind", "fitness_fatigue"); eq("key", "hrv") }; newestFirst() }
                .decodeList<FitnessFatigueRow>()
                .firstOrNull()
        }
    }

    suspend fun loadLoadSpans(): LoadSpans = loadFitnessFatigue()?.payload?.let { p ->
        val personal = p.personal
        if (p.adopted && personal != null) LoadSpans(personal.ctl, personal.atl, personal = true) else null
    } ?: LoadSpans.STANDARD

    // Analysis review phase G: planned sessions of the scorecard's window that are already in the past, for READY FOR.
    suspend fun loadPlannedOutcomes(): List<PlannedOutcomeRow> = ReadCache.get("analysis.PlannedOutcomes", tables = setOf("planned_sessions")) {
        val today = java.time.LocalDate.now()
        supabase.postgrest.from("planned_sessions")
            .select(columns = Columns.list("scheduled_date,status,exercise_session_id")) {
                filter {
                    gte("scheduled_date", today.minusDays(SCORECARD_HISTORY_DAYS.toLong()).toString())
                    lt("scheduled_date", today.toString())
                    neq("status", "superseded")
                }
                limit(2000)
            }
            .decodeList<PlannedOutcomeRow>()
    }

    // Phase A4 (Category 3). 30-day baseline gate, same margin reasoning as
    // everything else here.
    suspend fun loadWellbeingDaily(): List<WellbeingAnalysisRow> = ReadCache.get("analysis.WellbeingDaily", tables = setOf("wellbeing_daily")) { fetchWellbeingDaily() }

    private suspend fun fetchWellbeingDaily(): List<WellbeingAnalysisRow> =
        pagedByDate<WellbeingAnalysisRow>("wellbeing_daily", "date,energy,mood,stress,soreness,morning_erection_quality,arousal_level") { it.date }

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
    suspend fun loadStoolLog(): List<StoolAnalysisRow> = ReadCache.get("analysis.StoolLog", tables = setOf("stool_log")) { fetchStoolLog() }

    private suspend fun fetchStoolLog(): List<StoolAnalysisRow> =
        supabase.postgrest.from("stool_log")
            .select(columns = Columns.list("occurred_at,bristol_type")) {
                order("occurred_at", Order.DESCENDING)
                limit(200)
            }
            .decodeList<StoolAnalysisRow>()
            .reversed()

    // Arousal folded into wellbeing_daily -- see loadWellbeingDaily() above;
    // morning-wood/arousal history now reads from the same wellbeing rows.
    suspend fun loadSexualActivityDaily(): List<LogSexualActivityRow> = ReadCache.get("analysis.SexualActivityDaily", tables = setOf("sexual_activity_daily")) { fetchSexualActivityDaily() }

    private suspend fun fetchSexualActivityDaily(): List<LogSexualActivityRow> =
        supabase.postgrest.from("sexual_activity_daily")
            .select(columns = Columns.list("id,date,activity_type,instances,notes")) {
                order("date", Order.DESCENDING)
                limit(200)
            }
            .decodeList<LogSexualActivityRow>()
            .reversed()

    // Phase A4 (Category 8, OSTRC-H2 half).
    suspend fun loadOstrcCheckins(): List<OstrcAnalysisRow> = ReadCache.get("analysis.OstrcCheckins", tables = setOf("ostrc_checkins")) { fetchOstrcCheckins() }

    private suspend fun fetchOstrcCheckins(): List<OstrcAnalysisRow> =
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
    suspend fun loadLabDraws(): List<LabDrawAnalysisRow> = ReadCache.get("analysis.LabDraws", tables = setOf("lab_draws")) { fetchLabDraws() }

    private suspend fun fetchLabDraws(): List<LabDrawAnalysisRow> =
        supabase.postgrest.from("lab_draws")
            .select(columns = Columns.list("id,draw_date")) {
                order("draw_date", Order.DESCENDING)
                limit(200)
            }
            .decodeList<LabDrawAnalysisRow>()
            .reversed()

    suspend fun loadLabResults(): List<LabResultAnalysisRow> = ReadCache.get("analysis.LabResults", tables = setOf("lab_results")) { fetchLabResults() }

    private suspend fun fetchLabResults(): List<LabResultAnalysisRow> =
        supabase.postgrest.from("lab_results")
            .select(columns = Columns.list("draw_id,marker_name,value,unit,ref_low,ref_high")) {
                limit(500)
            }
            .decodeList<LabResultAnalysisRow>()
}

// DAV-205 (24/9 fixes): shared by StatusRepository (figure's leg zone) and
// TrainingTileScreen (Load tab + Injury tab's OSTRC context) so all three
// always agree on the same session_load numbers instead of drifting apart
// like the toMetricState() mapping did before DAV-210. TRIMP (domain/EnduranceLoad.kt)
// comes first when the heart-rate inputs allow it; a session with only an RPE is
// scored RPE x minutes / SRPE_PER_TRIMP, which puts it on the TRIMP scale. (RPE x
// minutes used to take priority, but it is about 3.8 times TRIMP on the sessions
// that have both, so a day with an RPE counted about four times heavier; analysis
// review 2026-10-29.) TRIMP's anchors are taken as of
// each session's date (TrimpAnchors): the person's robust maximum from these
// same rows' session maxima and the 30-day median resting heart rate from
// [restingDaily]. Same rules as the server: an RPE counts when above 0, a session
// needs a duration above 0.
fun buildTrainingSessionLoads(sessions: List<TrainingLoadSessionRow>, restingDaily: List<Pair<LocalDate, Double>>): List<Pair<LocalDate, Double>> {
    fun dateOf(row: TrainingLoadSessionRow) = OffsetDateTime.parse(row.startTime).toLocalDateTime().toLocalDate()
    val anchors = TrimpAnchors(sessions.mapNotNull { row -> row.maxHr?.let { dateOf(row) to it } }, restingDaily)
    return sessions.mapNotNull { row ->
        val duration = row.durationMin?.takeIf { it > 0 } ?: return@mapNotNull null
        val date = dateOf(row)
        val load = anchors.trimpOn(date, EnduranceSessionInput(duration, null, row.avgHr, row.maxHr, null, null, null))
            ?: row.rpe?.takeIf { it > 0 }?.let { duration * it / SRPE_PER_TRIMP }
            ?: return@mapNotNull null
        date to load
    }
}

/** The daily resting heart rates of wearable rows, for buildTrainingSessionLoads. */
fun restingSeries(wearable: List<WearableAnalysisRow>): List<Pair<LocalDate, Double>> =
    wearable.mapNotNull { row -> row.rhr?.let { LocalDate.parse(row.date) to it } }

@kotlinx.serialization.Serializable
private data class IntensitySessionRow(
    @kotlinx.serialization.SerialName("start_time") val startTime: String,
    val type: String? = null,
    @kotlinx.serialization.SerialName("zepp_workout_detail") val zepp: List<HrHistogramRow> = emptyList(),
)

@kotlinx.serialization.Serializable
private data class HrHistogramRow(@kotlinx.serialization.SerialName("hr_histogram") val histogram: Map<String, Double>? = null)

@kotlinx.serialization.Serializable
private data class HrRangeRow(val date: String, @kotlinx.serialization.SerialName("hr_min") val low: Double, @kotlinx.serialization.SerialName("hr_max") val high: Double)

@kotlinx.serialization.Serializable
private data class HypnogramSleepRow(
    val bedtime: String? = null,
    @kotlinx.serialization.SerialName("stage_intervals") val stageIntervals: List<List<Int>>? = null,
    @kotlinx.serialization.SerialName("tz_offset_min") val tzOffsetMin: Int? = null,
)

@kotlinx.serialization.Serializable
private data class HrDayRow(
    val date: String,
    @kotlinx.serialization.SerialName("hr_timeseries") val hrTimeseries: List<HrPoint>? = null,
)

// Analysis review A8 (docs/analysis-layer-3/contract-v3.md: "read the latest as_of for the current version"). Publishers
// delete older rows today, but nothing guarantees that across an engine-version bump or a failed delete, and the readers
// took whichever row came back first. Newest night first, then the newest run of that night; callers keep the first
// row per key.
private fun io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder.newestFirst() {
    order("as_of", Order.DESCENDING)
    order("calculated_at", Order.DESCENDING)
}

// Analysis review phase D: the newest row per key of each evidence view (the reader keeps the first row per key).
data class EvidenceRows(
    val events: List<EventCurveRow> = emptyList(),
    val crossover: List<CaseCrossoverResultRow> = emptyList(),
    val lags: List<LagCurveRow> = emptyList(),
)
