package com.bioscan.fieldterminal.data

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
import com.bioscan.fieldterminal.domain.score.capsFrom
import com.bioscan.fieldterminal.domain.score.chargeStatus
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// Scoring system: loads what the scores read and scores this morning (docs/scoring-system/README.md). Every read is one
// the app already makes elsewhere (most are cached by ReadCache); the engine runs off the main thread.

const val SCORE_STRIP_DAYS = 30L

/** How long an injury counts as acute and caps CHARGE. An illness caps for as long as it is open. */
const val ACUTE_INJURY_DAYS = 7L

data class ScoresView(
    val day: LocalDate,
    val scores: DayScores,
    val status: ChargeStatus,
    /** CHARGE for the last [SCORE_STRIP_DAYS] mornings, oldest first. */
    val chargeStrip: List<Pair<LocalDate, Double?>>,
    val domainStrips: Map<DomainKey, List<Pair<LocalDate, Double?>>>,
    /** The assembled series, for the metric overlays' charts. */
    val series: Map<String, Series>,
)

class ScoresRepository(private val supabase: SupabaseClient) {

    suspend fun load(
        context: Context,
        nutrition: NutritionOverview?,
        healthFlag: HealthFlag?,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ScoresView = coroutineScope {
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
        val checkins = async { runCatching { EveningCheckinRepository(supabase).loadSince(today.minusDays(400)) }.getOrDefault(emptyList()) }
        val nut = nutrition ?: runCatching { NutritionRepository(supabase).loadOverview() }.getOrNull()
        val goals = NutritionGoalsStore.getGoals(context)

        val w = wearable.await()
        val sources = ScoreSources(
            wearable = w,
            sleep = sleep.await(),
            wellbeing = wellbeing.await(),
            sessionLoads = buildTrainingSessionLoads(sessions.await(), restingSeries(w)),
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
            zone = zone,
        )
        val closedYesterday = sources.checkins.any { it.date == today.minusDays(1) }

        withContext(Dispatchers.Default) {
            val series = assembleScoreSeries(sources, today)
            val episodes = listOfNotNull(healthFlag?.since?.let { since ->
                if (healthFlag.isInjury) Triple(healthFlag.label, since, since.plusDays(ACUTE_INJURY_DAYS - 1)) else Triple(healthFlag.label, since, null)
            })
            val engine = ScoreEngine(series, caps = capsFrom(episodes))
            val from = today.minusDays(SCORE_STRIP_DAYS - 1)
            ScoresView(
                day = today,
                scores = engine.score(today),
                status = chargeStatus(today, closedYesterday, now),
                chargeStrip = engine.chargeStrip(from, today),
                domainStrips = DomainKey.entries.associateWith { engine.domainStrip(it, from, today) },
                series = series,
            )
        }
    }
}
