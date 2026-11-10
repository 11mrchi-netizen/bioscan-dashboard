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
    /** The open illness on the scored morning, and the morning's read of the body's signals (domain/score/IllnessRead.kt). */
    val illness: com.bioscan.fieldterminal.domain.score.IllnessState? = null,
    val signals: com.bioscan.fieldterminal.domain.score.SignalsRead? = null,
    /** Tonight's sleep target and when to be in bed for it (domain/score/SleepDebt.kt). */
    val sleepTarget: com.bioscan.fieldterminal.domain.score.SleepTarget? = null,
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
        // Phone before sleep (Rhythm): read softly, like the HR dip; without it Rhythm is meal timing alone.
        val phoneBed = async { runCatching { loadPhoneBedMinutes() }.getOrDefault(emptyMap()) }
        val checkins = async { runCatching { EveningCheckinRepository(supabase).loadSince(today.minusDays(400)) }.getOrDefault(emptyList()) }
        // Logged drinks: read softly; without them alcohol and late caffeine fall back to the evening check-in.
        val drinks = async { runCatching { DrinksRepository(supabase).logged(days = 400, zone = zone) }.getOrDefault(emptyList()) }
        // The open illness and its check-ins: the ceiling follows how much it limits the person, not a flat 40.
        val illnessEpisode = async { runCatching { loadIllnessEpisode(healthFlag) }.getOrNull() }
        val nut = nutrition ?: runCatching { NutritionRepository(supabase).loadOverview() }.getOrNull()
        val goals = NutritionGoalsStore.getGoals(context)
        // FINAL mornings already kept stand unless rebuilt with something new (domain/score/ScoreSnapshots.kt).
        val keptD = async { runCatching { loadKept(today.minusDays(SCORE_STRIP_DAYS)) }.getOrDefault(emptyMap()) }

        val w = wearable.await()
        val sessionRows = sessions.await()
        val drinkRows = drinks.await()
        val sources = ScoreSources(
            wearable = w,
            sleep = sleep.await(),
            wellbeing = wellbeing.await(),
            sessionLoads = buildTrainingSessionLoads(sessionRows, restingSeries(w)),
            loadSpans = spans.await(),
            planned = planned.await(),
            nutrition = nut?.allDays.orEmpty(),
            // Water logged as water, plus what the other drinks count for (the Hydration page adds them the same way).
            hydrationMl = run {
                val water = nut?.hydrationHistory.orEmpty().mapNotNull { row ->
                    row.ml?.let { ml -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to ml.toDouble() } }
                }.toMap()
                val fromDrinks = com.bioscan.fieldterminal.domain.drinkHydrationByDay(drinkRows, zone)
                (water.keys + fromDrinks.keys).associateWith { (water[it] ?: 0.0) + (fromDrinks[it] ?: 0.0) }
            },
            // The saved nutrition goal for every past day: the fuel plan's day-by-day targets are not kept for past days.
            targets = { FuelTargets(goals.caloriesKcal, goals.proteinG, goals.carbsG, goals.hydrationMinMl ?: DEFAULT_HYDRATION_MIN_ML) },
            checkins = checkins.await(),
            drinks = drinkRows,
            stool = stool.await(),
            stressDays = stress.await(),
            hrDip = hrDip.await(),
            phoneBedMin = phoneBed.await(),
            // Meal times are logged times: the local wall clock with a zero offset (domain/WallClock.kt), not real instants.
            mealTimes = nut?.meals.orEmpty().mapNotNull { m -> com.bioscan.fieldterminal.domain.WallClock.instant(m.loggedAt, zone) },
            zone = zone,
        )
        val closedYesterday = sources.checkins.any { it.date == today.minusDays(1) }

        withContext(Dispatchers.Default) {
            val series = assembleScoreSeries(sources, today)
            // An acute injury keeps the flat ceiling for its first week; an illness is graded (domain/score/IllnessRead.kt).
            val episodes = listOfNotNull(healthFlag?.takeIf { it.isInjury }?.since?.let { since ->
                Triple(healthFlag.label, since, since.plusDays(ACUTE_INJURY_DAYS - 1))
            })
            val illness = illnessEpisode.await()
            // An open illness or acute injury first; else an acute gut day (two or more liquid stools, or nausea) caps the
            // morning after it.
            val healthCaps = capsFrom(episodes)
            val gutDays = acuteGutDays(sources.checkins, sources.stool, zone)
            val plannedDone = plannedDoneByDay(sources.planned)
            val key = scoreInputsKey(
                today, series, plannedDone,
                caps = episodes.map { it.toString() } + gutDays.map { "gut $it" } +
                    listOfNotNull(illness?.let { "illness-v3 ${it.label} ${it.start} ${it.checkIns}" }),
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
            // The lowest of the morning's ceilings: an acute injury, the open illness (graded), an acute gut day.
            val caps: (LocalDate) -> ChargeCap? = { morning ->
                listOfNotNull(
                    healthCaps(morning),
                    illness?.let { com.bioscan.fieldterminal.domain.score.illnessState(it, morning)?.cap },
                    if (morning.minusDays(1) in gutDays) ChargeCap("Acute gut day") else null,
                ).minByOrNull { it.ceiling }
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
            // The scoring history for the nightly relationship engine (score_history): once a day per model version.
            runCatching { uploadHistory(context, history, today) }
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
            val tonight = series[ScoreKeys.SLEEP_DEBT]?.get(today)?.let { debt ->
                val hours = sources.sleep.mapNotNull { r -> r.hours?.let { h -> runCatching { LocalDate.parse(r.date) }.getOrNull()?.let { it to h } } }.toMap()
                val wakes = sources.sleep.filter { runCatching { LocalDate.parse(it.date) }.getOrNull()?.let { d -> d.isAfter(today.minusDays(15)) } == true }
                    .mapNotNull { r -> r.wakeTime?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } }
                    .map { it.atZone(zone).let { z -> z.hour * 60 + z.minute } }
                com.bioscan.fieldterminal.domain.score.sleepTarget(com.bioscan.fieldterminal.domain.score.sleepNeed(hours, today.plusDays(1)), debt, wakes)
            }
            val todayIllness = illness?.let { com.bioscan.fieldterminal.domain.score.illnessState(it, today) }
            val signalsRead = com.bioscan.fieldterminal.domain.score.readSignals(
                com.bioscan.fieldterminal.domain.score.bodySignals(series, today), todayIllness,
                confoundersFor(today.minusDays(1), sources.checkins, sessionRows, zone),
            )
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
                    illness = todayIllness,
                    signals = signalsRead,
                    sleepTarget = tonight,
                ),
                key,
            )
        }
    }

    private suspend fun loadKept(since: LocalDate): Map<LocalDate, KeptMorning> =
        supabase.postgrest.from("score_snapshots").select(columns = io.github.jan.supabase.postgrest.query.Columns.list("day,model_version,inputs,scores")) { filter { gte("day", since.toString()) } }
            .decodeList<KeptMorning>().associateBy { it.day }

    private suspend fun uploadHistory(context: Context, history: com.bioscan.fieldterminal.domain.score.ScoreHistory, today: LocalDate) {
        val prefs = context.getSharedPreferences("score_history", Context.MODE_PRIVATE)
        val stamp = "$today:${com.bioscan.fieldterminal.domain.score.SCORE_MODEL_VERSION}"
        if (prefs.getString("uploaded", null) == stamp) return
        val days = (history.charge.keys + history.domains.values.flatMap { it.keys }).toSortedSet()
        val rows = days.map { d ->
            fun dom(k: DomainKey) = history.domains[k]?.get(d)
            ScoreHistoryRow(
                d.toString(), com.bioscan.fieldterminal.domain.score.SCORE_MODEL_VERSION, history.charge[d],
                dom(DomainKey.SLEEP), dom(DomainKey.RECOVERY), dom(DomainKey.LOAD), dom(DomainKey.FUEL), dom(DomainKey.BALANCE),
            )
        }
        if (rows.isEmpty()) return
        rows.chunked(200).forEach { chunk -> supabase.postgrest.from("score_history").upsert(chunk) { onConflict = "user_id,day" } }
        prefs.edit().putString("uploaded", stamp).apply()
    }

    private suspend fun loadPhoneBedMinutes(): Map<LocalDate, Double> =
        ReadCache.kept("scores.phone_bed", tables = setOf("phone_usage_daily")) {
            supabase.postgrest.from("phone_usage_daily").select(io.github.jan.supabase.postgrest.query.Columns.list("date,bed_window_min")) {
                filter { gte("date", LocalDate.now().minusDays(400).toString()) }
            }.decodeList<PhoneBedRow>().mapNotNull { r -> r.bedWindowMin?.let { m -> runCatching { LocalDate.parse(r.date) }.getOrNull()?.let { it.toString() to m } } }.toMap()
        }.mapKeys { LocalDate.parse(it.key) }

    /** The open illness: the newest open systemic concern and its check-ins, else a legacy open illness without them. */
    private suspend fun loadIllnessEpisode(flag: HealthFlag?): com.bioscan.fieldterminal.domain.score.IllnessEpisode? {
        val health = HealthConcernsRepository(supabase)
        val overview = runCatching { health.loadOverview() }.getOrNull()
        if (overview == null) {
            val since = flag?.takeIf { !it.isInjury }?.since ?: return null
            return com.bioscan.fieldterminal.domain.score.IllnessEpisode(flag.label, since, emptyList())
        }
        val open = overview.concerns
            .filter { it.kind == "systemic" && !it.archived && com.bioscan.fieldterminal.domain.health.isConcernOpen(it.state) }
            .mapNotNull { c -> c.currentEpisode?.start?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { c to it } }
            .maxByOrNull { it.second } ?: return null
        val (concern, start) = open
        return com.bioscan.fieldterminal.domain.score.IllnessEpisode(concern.title, start, health.loadIllnessCheckIns(concern.concernId, start))
    }
}

/** What the day before offers as a plainer explanation of the body's signals: drinks, a late or long session, felt stress. */
internal fun confoundersFor(
    day: LocalDate,
    checkins: List<com.bioscan.fieldterminal.domain.EveningCheckin>,
    sessions: List<com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow>,
    zone: ZoneId,
): com.bioscan.fieldterminal.domain.score.Confounders {
    val evening = checkins.firstOrNull { it.date == day }
    val lateOrLong = sessions.any { row ->
        // Session starts are logged times (local digits with a zero offset, domain/WallClock.kt).
        val start = com.bioscan.fieldterminal.domain.WallClock.zoned(row.startTime, zone) ?: return@any false
        val minutes = row.durationMin ?: 0.0
        val end = start.plusSeconds((minutes * 60).toLong())
        (start.toLocalDate() == day || end.toLocalDate() == day) &&
            (minutes >= com.bioscan.fieldterminal.domain.score.LONG_SESSION_MIN || (end.toLocalDate() == day && end.hour >= com.bioscan.fieldterminal.domain.score.LATE_SESSION_HOUR))
    }
    return com.bioscan.fieldterminal.domain.score.Confounders(
        drinks = evening?.drinks,
        lateOrLongSession = lateOrLong,
        stressfulDay = (evening?.stress ?: 0) >= com.bioscan.fieldterminal.domain.score.STRESSFUL_DAY,
    )
}

@Serializable
private data class ScoreHistoryRow(
    val day: String,
    @kotlinx.serialization.SerialName("model_version") val modelVersion: String,
    val charge: Double?,
    val sleep: Double?,
    val recovery: Double?,
    val load: Double?,
    val fuel: Double?,
    val balance: Double?,
)

@Serializable
private data class PhoneBedRow(val date: String, @kotlinx.serialization.SerialName("bed_window_min") val bedWindowMin: Double? = null)
