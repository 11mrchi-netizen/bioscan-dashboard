@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class, com.bioscan.fieldterminal.domain.InstantSerializer::class, com.bioscan.fieldterminal.domain.LocalTimeSerializer::class)
package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.score.scoreInputsKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import android.content.Context
import com.bioscan.fieldterminal.domain.HealthFlag
import com.bioscan.fieldterminal.domain.score.ChargeStatus
import com.bioscan.fieldterminal.domain.score.DayScores
import com.bioscan.fieldterminal.domain.score.DomainKey
import com.bioscan.fieldterminal.domain.score.FuelTargets
import com.bioscan.fieldterminal.domain.score.ScoreEngine
import com.bioscan.fieldterminal.domain.score.ScoreSources
import com.bioscan.fieldterminal.domain.score.Series
import com.bioscan.fieldterminal.domain.score.assembleScoreSeries
import com.bioscan.fieldterminal.domain.plannedDoneByDay
import com.bioscan.fieldterminal.domain.score.ChargeWeights
import com.bioscan.fieldterminal.domain.score.QUALITY_HISTORY_DAYS
import com.bioscan.fieldterminal.domain.score.ScoreKeys
import com.bioscan.fieldterminal.domain.score.ScoreQuality
import com.bioscan.fieldterminal.domain.score.acuteGutDays
import com.bioscan.fieldterminal.domain.score.defaultWeights
import com.bioscan.fieldterminal.domain.score.fitChargeWeights
import com.bioscan.fieldterminal.domain.score.history
import com.bioscan.fieldterminal.domain.score.scoreQuality
import com.bioscan.fieldterminal.domain.score.capsFrom
import com.bioscan.fieldterminal.domain.score.ChargeCap
import com.bioscan.fieldterminal.domain.score.chargeStatus
import com.bioscan.fieldterminal.domain.score.KeptMorning
import com.bioscan.fieldterminal.domain.score.settle
import io.github.jan.supabase.postgrest.postgrest
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.domain.stress.StressDay
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneId

// Scoring system: loads what the scores read and scores this morning (docs/scoring-system/README.md). Every read is one
// the app already makes elsewhere (most are cached by ReadCache); the engine runs off the main thread.

const val SCORE_STRIP_DAYS = 30L

/** How long an injury counts as acute and caps CHARGE. An illness caps for as long as it is open. */
const val ACUTE_INJURY_DAYS = 7L

@Serializable
data class ScoresView(
    val day: LocalDate,
    val scores: DayScores,
    val status: ChargeStatus,
    /** CHARGE for the last [SCORE_STRIP_DAYS] mornings, oldest first. */
    val chargeStrip: List<Pair<LocalDate, Double?>>,
    val domainStrips: Map<DomainKey, List<Pair<LocalDate, Double?>>>,
    /** The assembled series, for the metric overlays' charts. */
    val series: Map<String, Series>,
    /** Each score's scorecard (null if it could not be worked out), and CHARGE's weights in use. */
    val quality: ScoreQuality? = null,
    val weights: ChargeWeights = defaultWeights(),
    // The rows the full-picture views draw from (the stool hypnogram and bowel timing, last night's hypnogram, the day's
    // stress curve with exercise masked).
    val stool: List<StoolAnalysisRow> = emptyList(),
    val lastSleep: SleepAnalysisRow? = null,
    val stressDays: List<StressDay> = emptyList(),
    val exerciseWindows: List<Pair<Instant, Instant>> = emptyList(),
)

/** A scored morning and the key of what it was scored from (domain/score/ScoreKey.kt). */
data class KeyedScores(val view: ScoresView, val key: String)

class ScoresRepository(private val supabase: SupabaseClient) {

    suspend fun load(
        context: Context,
        nutrition: NutritionOverview?,
        healthFlag: HealthFlag?,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ScoresView = loadKeyed(context, nutrition, healthFlag, now, zone).view

    /**
     * Scores this morning. When [previous] was scored from the same inputs ([previousKey] matches), its scores, strips,
     * quality and weights are kept as they are and only the rows the views draw from are refreshed: the engine (the slow
     * part) runs once per change of data, not once per open.
     */
    suspend fun loadKeyed(
        context: Context,
        nutrition: NutritionOverview?,
        healthFlag: HealthFlag?,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        previous: ScoresView? = null,
        previousKey: String? = null,
    ): KeyedScores = coroutineScope {
        val today = now.toLocalDate()
        val analysis = AnalysisRepository(supabase)
        val wearable = async { analysis.loadWearableDaily() }
        val sleep = async { analysis.loadSleepDaily() }
        val wellbeing = async { runCatching { analysis.loadWellbeingDaily() }.getOrDefault(emptyList()) }
        val sessions = async { analysis.loadExerciseSessionsForTrainingLoad() }
        val spans = async { runCatching { analysis.loadLoadSpans() }.getOrDefault(com.bioscan.fieldterminal.domain.LoadSpans.STANDARD) }
        val planned = async { runCatching { analysis.loadPlannedOutcomes() }.getOrDefault(emptyList()) }
        val stool = async { runCatching { analysis.loadStoolLog() }.getOrDefault(emptyList()) }
        val stress = async { runCatching { ZeppStressRepository(supabase).loadStressDays(limit = 240) }.getOrDefault(emptyList()) }
        // The night HR dip is enrichment: a failed or slow read leaves that one input out, never the scores.
        val hrDip = async { runCatching { analysis.loadHrDip() }.getOrDefault(emptyMap()) }
        val checkins = async { runCatching { EveningCheckinRepository(supabase).loadSince(today.minusDays(400)) }.getOrDefault(emptyList()) }
        val nut = nutrition ?: runCatching { NutritionRepository(supabase).loadOverview() }.getOrNull()
        val goals = NutritionGoalsStore.getGoals(context)
        // FINAL mornings already kept stand unless rebuilt with something new (domain/score/ScoreSnapshots.kt).
        val keptD = async { runCatching { loadKept(today.minusDays(SCORE_STRIP_DAYS)) }.getOrDefault(emptyMap()) }

        val w = wearable.await()
        val sessionRows = sessions.await()
        val sources = ScoreSources(
            wearable = w,
            sleep = sleep.await(),
            wellbeing = wellbeing.await(),
            sessionLoads = buildTrainingSessionLoads(sessionRows, restingSeries(w)),
            loadSpans = spans.await(),
            planned = planned.await(),
            nutrition = nut?.allDays.orEmpty(),
            hydrationMl = nut?.hydrationHistory.orEmpty().mapNotNull { row ->
                row.ml?.let { ml -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to ml.toDouble() } }
            }.toMap(),
            // The saved nutrition goal for every past day: the fuel plan's day-by-day targets are not kept for past days.
            targets = { FuelTargets(goals.caloriesKcal, goals.proteinG, goals.carbsG, goals.hydrationMinMl ?: DEFAULT_HYDRATION_MIN_ML) },
            checkins = checkins.await(),
            stool = stool.await(),
            stressDays = stress.await(),
            hrDip = hrDip.await(),
            // Meal times are logged times: the local wall clock with a zero offset (domain/WallClock.kt), not real instants.
            mealTimes = nut?.meals.orEmpty().mapNotNull { m -> com.bioscan.fieldterminal.domain.WallClock.instant(m.loggedAt, zone) },
            zone = zone,
        )
        val closedYesterday = sources.checkins.any { it.date == today.minusDays(1) }

        withContext(Dispatchers.Default) {
            val series = assembleScoreSeries(sources, today)
            val episodes = listOfNotNull(healthFlag?.since?.let { since ->
                if (healthFlag.isInjury) Triple(healthFlag.label, since, since.plusDays(ACUTE_INJURY_DAYS - 1)) else Triple(healthFlag.label, since, null)
            })
            // An open illness or acute injury first; else an acute gut day (two or more liquid stools, or nausea) caps the
            // morning after it.
            val healthCaps = capsFrom(episodes)
            val gutDays = acuteGutDays(sources.checkins, sources.stool, zone)
            val plannedDone = plannedDoneByDay(sources.planned)
            val key = scoreInputsKey(
                today, series, plannedDone,
                caps = episodes.map { it.toString() } + gutDays.map { "gut $it" },
                closedYesterday = closedYesterday,
                afterNoon = now.toLocalTime() >= java.time.LocalTime.NOON,
            )
            val lastSleep = sources.sleep.maxByOrNull { it.date }
            val exerciseWindows = sessionRows.mapNotNull { row ->
                // Session starts are logged times (local digits); stress samples are real instants.
                val start = com.bioscan.fieldterminal.domain.WallClock.instant(row.startTime, zone) ?: return@mapNotNull null
                val minutes = row.durationMin ?: return@mapNotNull null
                start to start.plusSeconds((minutes * 60).toLong())
            }
            // The views draw the last week by zone and the day's curve against the weeks before it; older days are not kept.
            val stressShown = sources.stressDays.filter { !it.date.isBefore(today.minusDays(30)) }
            if (previous != null && previousKey == key && previous.day == today) {
                return@withContext KeyedScores(
                    previous.copy(series = series, stool = sources.stool, lastSleep = lastSleep, stressDays = stressShown, exerciseWindows = exerciseWindows),
                    key,
                )
            }
            val caps: (LocalDate) -> ChargeCap? = { morning ->
                healthCaps(morning) ?: if (morning.minusDays(1) in gutDays) ChargeCap("Acute gut day") else null
            }
            // Score quality: CHARGE's weights are the person's own once fitted and shown to forecast better (re-fitted
            // monthly); every score carries its scorecard (domain/score/ScoreQuality.kt).
            val historyFrom = today.minusDays(QUALITY_HISTORY_DAYS - 1L)
            val defaultEngine = ScoreEngine(series, caps = caps)
            val defaultHistory = defaultEngine.history(historyFrom, today)
            val weights = fitChargeWeights(defaultHistory.chargeInputs, series[ScoreKeys.HRV].orEmpty(), today)
            val engine = if (weights.personal) ScoreEngine(series, caps = caps, weights = weights.weights) else defaultEngine
            val history = if (weights.personal) engine.history(historyFrom, today) else defaultHistory
            val quality = runCatching { scoreQuality(history, series, plannedDone, weights) }.getOrNull()
            val from = today.minusDays(SCORE_STRIP_DAYS - 1)
            val status = chargeStatus(today, closedYesterday, now)
            val kept = keptD.await()
            val toKeep = mutableListOf<KeptMorning>()
            fun settled(d: LocalDate): DayScores = settle(engine.score(d), kept[d]).let { (s, keep) -> keep?.let(toKeep::add); s }
            // Past mornings are always FINAL; this morning once it is.
            val past = generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(today) }.associateWith(::settled)
            val fresh = if (status == ChargeStatus.FINAL) settled(today) else engine.score(today)
            // The arrows compare with yesterday as it is shown.
            val yesterday = past[today.minusDays(1)]
            val scores = if (yesterday == null) fresh else fresh.copy(
                charge = fresh.charge.copy(previous = yesterday.charge.score),
                domains = fresh.domains.mapValues { (k, d) -> d.copy(previous = yesterday.domains[k]?.score) },
            )
            if (toKeep.isNotEmpty()) runCatching { supabase.postgrest.from("score_snapshots").upsert(toKeep) { onConflict = "user_id,day" } }
            val shown = past + (today to scores)
            KeyedScores(
                ScoresView(
                    day = today,
                    scores = scores,
                    status = status,
                    chargeStrip = shown.map { (d, s) -> d to s.charge.score },
                    domainStrips = DomainKey.entries.associateWith { k -> shown.map { (d, s) -> d to s.domains[k]?.score } },
                    series = series,
                    quality = quality,
                    weights = weights,
                    stool = sources.stool,
                    lastSleep = lastSleep,
                    stressDays = stressShown,
                    exerciseWindows = exerciseWindows,
                ),
                key,
            )
        }
    }

    private suspend fun loadKept(since: LocalDate): Map<LocalDate, KeptMorning> =
        supabase.postgrest.from("score_snapshots").select { filter { gte("day", since.toString()) } }
            .decodeList<KeptMorning>().associateBy { it.day }

}
