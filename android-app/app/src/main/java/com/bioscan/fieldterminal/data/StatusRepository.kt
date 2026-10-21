package com.bioscan.fieldterminal.data

import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.data.model.HealthEventRow
import com.bioscan.fieldterminal.domain.SLEEP_HOURS_RANGE
import com.bioscan.fieldterminal.domain.baselineStateWord
import com.bioscan.fieldterminal.domain.comparison.METRIC_DIRECTIONALITY
import com.bioscan.fieldterminal.domain.dedupeRunSessions
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import com.bioscan.fieldterminal.data.model.SleepDailyRow
import com.bioscan.fieldterminal.data.model.WearableDailyRow
import com.bioscan.fieldterminal.domain.HealthFlag
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.MorningReadout
import com.bioscan.fieldterminal.domain.morningReadout
import com.bioscan.fieldterminal.domain.SupplementsToday
import com.bioscan.fieldterminal.domain.computeHydrationIntelligence
import com.bioscan.fieldterminal.domain.pickHealthFlag
import com.bioscan.fieldterminal.domain.supplementsToday
import com.bioscan.fieldterminal.domain.evaluateHrv
import com.bioscan.fieldterminal.domain.evaluateRhr
import com.bioscan.fieldterminal.domain.evaluateSleepDuration
import com.bioscan.fieldterminal.domain.evaluateTrainingLoad
import com.bioscan.fieldterminal.domain.isHealthEventOpen
import com.bioscan.fieldterminal.domain.trainingLoadStateWord
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate

data class StatusOverview(
    val latestHrv: Double?,
    val latestRhr: Double?,
    val sleepHours: Double?,
    val activeHealthEvent: Boolean,
    // UX review F1: how many injuries / illnesses are open, and the one the body figure pins (domain/HealthFlag.kt).
    val openHealthFlags: Int = 0,
    val healthFlag: HealthFlag? = null,
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
    // Contract A5 freshness line under the hero: the day of the HRV reading the band is based on, and the last Health
    // Connect sync the app recorded (null when it never synced or the read failed).
    val hrvReadingDate: LocalDate? = null,
    val lastSyncAt: Instant? = null,
    // The TRAINING tile's load word (TSB band from the same evaluation as trainingState, e.g. "Loaded").
    val trainingLoadBand: String? = null,
    // Analysis review phase C: this morning's HRV against the band expected for it. Null before the first analysis run,
    // while the band is building, or with no reading yet today.
    val hrvReadout: MorningReadout? = null,
    // Analysis review phase G: how far the HRV band has got (mornings it has, mornings it needs), for the hero while it
    // builds. The old fallback, HRV against a 7-day z-score (LOW / REDUCED / NORMAL / PRIMED), is retired.
    val hrvBandProgress: Pair<Int, Int>? = null,
)

// Contract A5. A nightly reading (HRV, resting HR, sleep) is dated the day it was recorded, and before the phone's
// morning sync the latest one is yesterday's. Anything older means at least one night never arrived (watch, phone,
// Health Connect or the sync stalled), so the Status hero marks it STALE instead of presenting it as today's condition.
const val STALE_AFTER_DAYS = 1L

// UX review P1: the FUEL and LABS tile previews. Each part loads on its own and is null when it could not load, so a slow
// or failed read shows that line as missing instead of taking the whole hub down.
data class TodayPreviews(
    val fuel: FuelToday? = null,
    val supplements: SupplementsToday? = null,
    // The latest lab draw's date; null when there is none or the read failed (labDrawsLoaded tells the two apart).
    val lastLabDraw: LocalDate? = null,
    val labDrawsLoaded: Boolean = false,
)

data class FuelToday(
    // Calories logged today; null when no meal is logged yet.
    val kcal: Double?,
    // Plain water plus beverages' effective hydration, as the Fuel tile adds them; null when nothing is logged yet.
    val waterMl: Double?,
    // Today's inferred sweat loss, which the Fuel tile adds to the hydration range (hydrationBoundsMl).
    val exerciseDemandMl: Double,
)

// No user_id filter anywhere here -- RLS already scopes every query to the
// signed-in user, same as the web dashboard relies on (see ROADMAP.md
// Foundation section).
class StatusRepository(private val supabase: io.github.jan.supabase.SupabaseClient) {

    // Perf pass: this used to be nine requests one after another, fetching wearable_daily (with the heavy hr_timeseries
    // column) and exercise_sessions twice. Now every independent read starts at once on a background thread, the 60-row
    // HRV and latest-sleep views are slices of the 200-row lists the tiles use anyway (so ReadCache shares them), and
    // the numbers are computed exactly as before from the same rows.
    suspend fun loadOverview(): StatusOverview = kotlinx.coroutines.coroutineScope {
        val analysis = AnalysisRepository(supabase)
        val today = LocalDate.now()

        val wearableD = async(Dispatchers.Default) { analysis.loadWearableDaily() }
        val sleepD = async(Dispatchers.Default) { analysis.loadSleepDaily() }
        val breaksD = async(Dispatchers.Default) { analysis.loadSeriesBreakStarts() }
        val bandsD = async(Dispatchers.Default) { analysis.loadExpectedBands() }
        val loadSessionsD = async(Dispatchers.Default) { analysis.loadExerciseSessionsForTrainingLoad() }
        val loadSpansD = async(Dispatchers.Default) { analysis.loadLoadSpans() }
        val injuriesD = async(Dispatchers.Default) {
            supabase.postgrest.from("injuries").select(columns = Columns.list("status,end_date,part,start_date")).decodeList<HealthEventRow>()
        }
        val illnessesD = async(Dispatchers.Default) {
            supabase.postgrest.from("illnesses").select(columns = Columns.list("status,end_date,name,start_date")).decodeList<HealthEventRow>()
        }
        val lastSyncD = async(Dispatchers.Default) { softly { loadLastHealthConnectSync(supabase) } }
        // Small dedicated fetch (10 days of margin, filtered to 7 by date below)
        // rather than reusing TrainingLoadSessionRow, which carries no type/source.
        val recentD = async(Dispatchers.Default) {
            ReadCache.get("status.recent_sessions", tables = setOf("exercise_sessions")) {
                supabase.postgrest.from("exercise_sessions")
                    .select(columns = Columns.list("id,type,start_time,duration_min,distance_km,avg_hr,source")) {
                        order("start_time", Order.DESCENDING)
                        limit(60)
                    }
                    .decodeList<ExerciseSessionRow>()
            }
        }

        val wearableAnalysis = wearableD.await()
        val wearable = wearableAnalysis.takeLast(60)

        val sleepRows = sleepD.await()
        val latestSleep = sleepRows.lastOrNull()

        // Open means active or monitoring, the Injury list's rule: a resolved event leaves the figure (pin and count) the
        // day it is resolved.
        fun HealthEventRow.isOpen() = isHealthEventOpen(status)
        fun HealthEventRow.toFlag(isInjury: Boolean) = HealthFlag(
            label = (if (isInjury) part else name)?.takeIf { it.isNotBlank() } ?: if (isInjury) "Injury" else "Illness",
            isInjury = isInjury,
            since = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
        val openInjuries = injuriesD.await().filter { it.isOpen() }
        val openIllnesses = illnessesD.await().filter { it.isOpen() }
        val activeHealthEvent = openInjuries.isNotEmpty() || openIllnesses.isNotEmpty()
        val healthFlag = pickHealthFlag(openInjuries.map { it.toFlag(true) }, openIllnesses.map { it.toFlag(false) })

        // DAV-203: reuse the exact same evaluations the Health/Training tabs already compute so the figure's zone colors
        // never disagree with what tapping into that tile shows.
        val breakStarts = breaksD.await()
        val hrvPoints = wearableAnalysis.mapNotNull { row -> row.hrv?.let { LocalDate.parse(row.date) to it } }
        val rhrPoints = wearableAnalysis.mapNotNull { row -> row.rhr?.let { LocalDate.parse(row.date) to it } }
        val cardioState = worstOf(
            baselineStateWord(evaluateHrv(hrvPoints, windowStart = breakStarts.start("hrv")).state, METRIC_DIRECTIONALITY.getValue("hrv")).state,
            baselineStateWord(evaluateRhr(rhrPoints, windowStart = breakStarts.start("rhr")).state, METRIC_DIRECTIONALITY.getValue("resting_heart_rate")).state,
        )

        val sleepHoursPoints = sleepRows.mapNotNull { row -> row.hours?.let { LocalDate.parse(row.date) to it } }
        val sleepEval = evaluateSleepDuration(sleepHoursPoints, windowStart = breakStarts.start("sleep_hours"))
        val recoveryState = baselineStateWord(sleepEval.state, METRIC_DIRECTIONALITY.getValue("sleep_duration"), SLEEP_HOURS_RANGE, sleepEval.baseline7d).state

        // DAV-205: TRIMP-fallback session loads, same shared builder TrainingTileScreen uses -- keeps the figure's leg
        // color and the Training tab's own Load card from ever disagreeing.
        val sessionLoads = buildTrainingSessionLoads(loadSessionsD.await(), rhrPoints)
        val trainingLoad = evaluateTrainingLoad(sessionLoads, spans = loadSpansD.await())
        val trainingState = trainingLoadStateWord(trainingLoad.state, trainingLoad.tsbBand).state

        val recentSessions = recentD.await()
            .filter { ChronoUnit.DAYS.between(OffsetDateTime.parse(it.startTime).toLocalDate(), today) < 7 }
        val sessionsLast7Days = dedupeRunSessions(recentSessions.filter { it.type == "run" }).size +
            recentSessions.count { it.type != "run" }

        val hrvBand = bandsD.await()["hrv"]
        val hrvReadout = hrvBand?.let { morningReadout(it.payload, today, hrvPoints.lastOrNull()) }

        StatusOverview(
            latestHrv = wearable.lastOrNull()?.hrv,
            latestRhr = wearable.lastOrNull()?.rhr,
            sleepHours = latestSleep?.hours,
            activeHealthEvent = activeHealthEvent,
            openHealthFlags = openInjuries.size + openIllnesses.size,
            healthFlag = healthFlag,
            cardioState = cardioState,
            trainingState = trainingState,
            recoveryState = recoveryState,
            sessionsLast7Days = sessionsLast7Days,
            hrvReadingDate = wearableAnalysis.lastOrNull { it.hrv != null }?.let { runCatching { LocalDate.parse(it.date) }.getOrNull() },
            lastSyncAt = lastSyncD.await(),
            trainingLoadBand = trainingLoad.tsbBand,
            hrvReadout = hrvReadout,
            hrvBandProgress = hrvBand?.payload?.let { it.have to it.need },
        )
    }

    // The FUEL and LABS previews, read in parallel with loadOverview(). Same sources and math as the tiles themselves:
    // NutritionRepository's today totals, the Fuel tile's exercise-demand rule, the reminder's supplements-due rule.
    suspend fun loadTodayPreviews(home: Pair<Double, Double>? = null): TodayPreviews = kotlinx.coroutines.coroutineScope {
        val analysis = AnalysisRepository(supabase)
        val today = LocalDate.now()
        val fuelD = async(Dispatchers.Default) {
            softly {
                val nutrition = NutritionRepository(supabase).loadOverview()
                val exerciseMinutes = analysis.loadExerciseSessionsForTrainingLoad()
                    .filter { runCatching { OffsetDateTime.parse(it.startTime).toLocalDate() == today }.getOrDefault(false) }
                    .sumOf { it.durationMin ?: 0.0 }
                val anyWater = nutrition.todayHydrationMl != null || nutrition.todayBeverageItems.isNotEmpty()
                FuelToday(
                    kcal = nutrition.today?.calories,
                    waterMl = if (!anyWater) null else (nutrition.todayHydrationMl ?: 0) + nutrition.todayBeverageItems.mapNotNull { it.effectiveHydrationMl }.sum(),
                    exerciseDemandMl = (if (exerciseMinutes > 0) todaySweatRate(supabase, home) else null).let { sweat ->
                        computeHydrationIntelligence(null, null, exerciseMinutes, sweat?.mlPerHour, sweat?.environmentKnown ?: false).inferredDemandMl ?: 0.0
                    },
                )
            }
        }
        val supplementsD = async(Dispatchers.Default) {
            softly {
                val repo = SupplementsRepository(supabase)
                val active = repo.loadOverview().active
                supplementsToday(active, repo.loadRecentTakenDates(active.map { it.id }), today, LocalTime.now())
            }
        }
        val drawsD = async(Dispatchers.Default) { softly { analysis.loadLabDraws() } }
        val draws = drawsD.await()
        TodayPreviews(
            fuel = fuelD.await(),
            supplements = supplementsD.await(),
            lastLabDraw = draws?.lastOrNull()?.let { runCatching { LocalDate.parse(it.drawDate.take(10)) }.getOrNull() },
            labDrawsLoaded = draws != null,
        )
    }
}

// A preview or enrichment that cannot load is shown as missing; cancellation still propagates.
private suspend fun <T> softly(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
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
