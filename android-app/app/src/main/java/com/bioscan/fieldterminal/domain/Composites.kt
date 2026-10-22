package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.buildTrainingSessionLoads
import com.bioscan.fieldterminal.data.restingSeries
import com.bioscan.fieldterminal.data.model.PlannedOutcomeRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.stress.StressRhythmResult
import java.time.Instant
import java.time.LocalDate
import kotlin.math.ln

// Analysis review phase G (P11). The inputs of Today's recovery, READY FOR and the Sleep Index in one place. The Today
// page, the Heart tile and the Training tile each assembled them separately; they now build them here, so the three
// screens cannot disagree, and the scorecard recomputes each composite as of any past morning from the same inputs.
//
// What each composite should predict, and its inputs (each scored alone against the composite):
//   Today's recovery   tomorrow morning's HRV against its recent level   sleep index, HRV and resting HR (7-day against
//                                                                         60-day), training stress balance, energy,
//                                                                         stress, soreness (7-day medians)
//   READY FOR          whether the day's planned sessions were done       the same inputs as recovery
//   Sleep Index        that day's energy, and that morning's HRV against   last night's duration, and the index's own
//                      its recent level                                    components (duration, regularity, breathing,
//                                                                          stages)
// The history leaves out the Zepp physiological-stress contributor, which exists only for the last few days.

data class CompositeInputs(
    val hrv: List<Pair<LocalDate, Double>>,
    val rhr: List<Pair<LocalDate, Double>>,
    val sleepHours: List<Pair<LocalDate, Double>>,
    val deepMin: List<Pair<LocalDate, Double>>,
    val respiratory: List<Pair<LocalDate, Double>>,
    val nights: List<SleepNight>,
    val sessionLoads: List<Pair<LocalDate, Double>>,
    val energy: List<Pair<LocalDate, Double>>,
    val stress: List<Pair<LocalDate, Double>>,
    val soreness: List<Pair<LocalDate, Double>>,
    val morningWood: List<Pair<LocalDate, Double>>,
    val arousal: List<Pair<LocalDate, Double>>,
    val breakStarts: SeriesBreakStarts = SeriesBreakStarts.NONE,
    /** The fitness and fatigue spans training load is drawn with (the standard 42 and 7 unless the personal pair was adopted). */
    val loadSpans: LoadSpans = LoadSpans.STANDARD,
)

fun compositeInputs(
    wearable: List<WearableAnalysisRow>,
    sleep: List<SleepAnalysisRow>,
    wellbeing: List<WellbeingAnalysisRow>,
    sessions: List<TrainingLoadSessionRow>,
    breakStarts: SeriesBreakStarts,
    loadSpans: LoadSpans = LoadSpans.STANDARD,
): CompositeInputs {
    fun <T> List<T>.series(date: (T) -> String, value: (T) -> Double?) = mapNotNull { row -> value(row)?.let { LocalDate.parse(date(row)) to it } }
    return CompositeInputs(
        hrv = wearable.series({ it.date }) { it.hrv },
        rhr = wearable.series({ it.date }) { it.rhr },
        sleepHours = sleep.series({ it.date }) { it.hours },
        deepMin = sleep.series({ it.date }) { it.deepMin },
        respiratory = sleep.series({ it.date }) { it.respiratoryRate },
        nights = sleep.mapNotNull { row ->
            val bedtime = row.bedtime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val wake = row.wakeTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (bedtime != null && wake != null) SleepNight(LocalDate.parse(row.date), bedtime, wake) else null
        },
        sessionLoads = buildTrainingSessionLoads(sessions, restingSeries(wearable)),
        energy = wellbeing.series({ it.date }) { it.energy?.toDouble() },
        stress = wellbeing.series({ it.date }) { it.stress?.toDouble() },
        soreness = wellbeing.series({ it.date }) { it.soreness?.toDouble() },
        morningWood = wellbeing.series({ it.date }) { it.morningErectionQuality?.toDouble() },
        arousal = wellbeing.series({ it.date }) { it.arousalLevel?.toDouble() },
        breakStarts = breakStarts,
        loadSpans = loadSpans,
    )
}

// The regime a past day belongs to: a confirmed break after that day had not happened yet.
private fun SeriesBreakStarts.asOf(metric: String, day: LocalDate): LocalDate? = start(metric)?.takeIf { !it.isAfter(day) }

fun CompositeInputs.sleepIndex(asOf: LocalDate = LocalDate.now()): SleepIndexResult =
    computeSleepIndex(
        sleepHours, deepMin, respiratory, nights, asOf = asOf,
        durationWindowStart = breakStarts.asOf("sleep_hours", asOf), stageWindowStart = breakStarts.asOf("deep_min", asOf),
    )

data class RecoveryEvaluations(
    val sleepIndex: SleepIndexResult,
    val hrv: SwcEvaluation,
    val rhr: SwcEvaluation,
    val load: TrainingLoadEvaluation,
    val energy: SubjectiveEvaluation,
    val stress: SubjectiveEvaluation,
    val soreness: SubjectiveEvaluation,
    val morningWood: SubjectiveEvaluation,
    val arousal: SubjectiveEvaluation,
)

fun CompositeInputs.evaluations(asOf: LocalDate = LocalDate.now(), sleepIndex: SleepIndexResult = sleepIndex(asOf)): RecoveryEvaluations =
    RecoveryEvaluations(
        sleepIndex = sleepIndex,
        hrv = evaluateHrv(hrv, asOf, breakStarts.asOf("hrv", asOf)),
        rhr = evaluateRhr(rhr, asOf, breakStarts.asOf("rhr", asOf)),
        load = evaluateTrainingLoad(sessionLoads, asOf, loadSpans),
        energy = evaluateSubjective(energy, asOf),
        stress = evaluateSubjective(stress, asOf),
        soreness = evaluateSubjective(soreness, asOf),
        morningWood = evaluateSubjective(morningWood, asOf),
        arousal = evaluateSubjective(arousal, asOf),
    )

fun recoveryFrom(ev: RecoveryEvaluations, physiologicalStress: StressRhythmResult? = null): DynamicRecoveryResult =
    computeDynamicRecovery(
        sleepIndex = ev.sleepIndex, hrvEval = ev.hrv, rhrEval = ev.rhr, trainingLoadEval = ev.load,
        energyEval = ev.energy, stressEval = ev.stress, sorenessEval = ev.soreness, physiologicalStress = physiologicalStress,
        morningWoodEval = ev.morningWood, arousalEval = ev.arousal,
    )

fun CompositeInputs.recovery(asOf: LocalDate = LocalDate.now(), physiologicalStress: StressRhythmResult? = null): DynamicRecoveryResult =
    recoveryFrom(evaluations(asOf), physiologicalStress)

/** Recovery's state as a number a forecast can use: above expected 1, as expected or mixed 0, below expected -1. */
fun recoveryOrdinal(state: EvalState): Double? = when (state) {
    EvalState.ShiftUp -> 1.0
    EvalState.Stable, EvalState.Unstable -> 0.0
    EvalState.ShiftDown -> -1.0
    EvalState.Building, EvalState.NoData -> null
}

// ---- outcomes ----

/** Each morning's ln HRV against the mean of the 28 days before it (at least 14 readings). */
fun hrvResidualByDay(hrv: List<Pair<LocalDate, Double>>): Map<LocalDate, Double> {
    val logs = hrv.filter { it.second > 0 }.associate { it.first to ln(it.second) }
    return logs.mapNotNull { (day, value) ->
        val prior = (1..28).mapNotNull { logs[day.minusDays(it.toLong())] }
        if (prior.size < 14) null else day to (value - prior.average())
    }.toMap()
}

/** An outcome measured the next morning, keyed on the day before (the day whose composite should foresee it). */
fun nextMorning(outcome: Map<LocalDate, Double>): Map<LocalDate, Double> = outcome.mapKeys { it.key.minusDays(1) }

// ---- the histories and the scorecards ----

const val SCORECARD_HISTORY_DAYS = 200

/** Each composite and each of its inputs, day by day, as it would have read that morning. */
data class CompositeHistory(
    val recovery: Map<LocalDate, Double>,
    val recoveryInputs: List<ScorePredictor>,
    val sleepIndex: Map<LocalDate, Double>,
    val sleepInputs: List<ScorePredictor>,
    val hrvResidual: Map<LocalDate, Double>,
    val energy: Map<LocalDate, Double>,
)

fun CompositeInputs.history(today: LocalDate = LocalDate.now(), days: Int = SCORECARD_HISTORY_DAYS): CompositeHistory {
    val recovery = HashMap<LocalDate, Double>()
    val sleep = HashMap<LocalDate, Double>()
    val r = HashMap<String, HashMap<LocalDate, Double>>()
    val s = HashMap<String, HashMap<LocalDate, Double>>()
    fun put(into: HashMap<String, HashMap<LocalDate, Double>>, key: String, day: LocalDate, v: Double?) {
        if (v != null && v.isFinite()) into.getOrPut(key) { HashMap() }[day] = v
    }
    val hoursByDay = sleepHours.toMap()
    val first = listOfNotNull(hrv.firstOrNull()?.first, sleepHours.firstOrNull()?.first).minOrNull() ?: today
    var day = maxOf(today.minusDays(days - 1L), first)
    while (!day.isAfter(today)) {
        val ev = evaluations(day)
        recoveryOrdinal(recoveryFrom(ev).state)?.let { recovery[day] = it }
        ev.sleepIndex.score?.let { sleep[day] = it }
        put(r, "sleep", day, ev.sleepIndex.score)
        put(r, "hrv", day, if (ev.hrv.confidence.met) diffOrNull(ev.hrv.baseline7d, ev.hrv.mean60d) else null)
        put(r, "rhr", day, if (ev.rhr.confidence.met) diffOrNull(ev.rhr.baseline7d, ev.rhr.mean60d) else null)
        put(r, "load", day, if (ev.load.confidence.met) ev.load.tsb else null)
        put(r, "energy", day, if (ev.energy.confidence.met) ev.energy.median7d else null)
        put(r, "stress", day, if (ev.stress.confidence.met) ev.stress.median7d else null)
        put(r, "soreness", day, if (ev.soreness.confidence.met) ev.soreness.median7d else null)
        put(s, "hours", day, hoursByDay[day])
        ev.sleepIndex.components.forEach { (component, score) -> put(s, component.name, day, score.score) }
        day = day.plusDays(1)
    }
    fun predictors(from: Map<String, Map<LocalDate, Double>>, labels: List<Pair<String, String>>) =
        labels.mapNotNull { (key, label) -> from[key]?.let { ScorePredictor(key, label, it) } }
    return CompositeHistory(
        recovery = recovery,
        recoveryInputs = predictors(r, RECOVERY_INPUTS),
        sleepIndex = sleep,
        sleepInputs = predictors(s, SLEEP_INPUTS),
        hrvResidual = hrvResidualByDay(hrv),
        energy = energy.toMap(),
    )
}

private fun diffOrNull(a: Double?, b: Double?): Double? = if (a == null || b == null) null else a - b

private val RECOVERY_INPUTS = listOf(
    "sleep" to "The Sleep Index",
    "hrv" to "HRV's week against its baseline",
    "rhr" to "Resting heart rate's week against its baseline",
    "load" to "Training stress balance",
    "energy" to "Energy",
    "stress" to "Stress",
    "soreness" to "Soreness",
)

private val SLEEP_INPUTS = listOf(
    "hours" to "Last night's duration",
    SleepIndexComponent.DURATION.name to "The duration score",
    SleepIndexComponent.REGULARITY.name to "Regularity",
    SleepIndexComponent.RESPIRATORY_STABILITY.name to "Breathing",
    SleepIndexComponent.STAGE_COMPOSITION.name to "The stages score",
)

data class CompositeScorecards(
    val recovery: ScorecardResult,
    val sleepEnergy: ScorecardResult,
    val sleepHrv: ScorecardResult,
) {
    /** The Sleep Index across its two outcomes: it earns its place by beating its best input on either, and is demoted
     *  only when it is no better on both. */
    val sleepIndex: ScorecardResult
        get() {
            val both = listOf(sleepEnergy, sleepHrv)
            both.firstOrNull { it.verdict == ScorecardVerdict.EARNS_ITS_PLACE }?.let { return it }
            if (both.all { it.verdict == ScorecardVerdict.NO_BETTER }) return sleepEnergy
            val settled = both.filter { it.verdict == ScorecardVerdict.NO_BETTER || it.verdict == ScorecardVerdict.UNCLEAR }.maxByOrNull { it.days }
                ?: return both.maxBy { it.days }
            if (settled.verdict == ScorecardVerdict.UNCLEAR) return settled
            // No better on one outcome, not settled on the other: not a demotion yet.
            val best = settled.best?.label?.lowercase() ?: "its best input"
            return settled.copy(
                verdict = ScorecardVerdict.UNCLEAR,
                sentence = "Forecast ${settled.targetText} no better than $best alone over ${settled.days} days; the other outcome is not settled yet.",
            )
        }
}

fun CompositeHistory.scorecards(): CompositeScorecards = CompositeScorecards(
    recovery = scorecard(
        ScorecardSpec(
            key = "dynamic_recovery", label = "Today's recovery", targetText = "tomorrow morning's HRV against its recent level",
            composite = ScorePredictor("recovery", "Today's recovery", recovery), inputs = recoveryInputs,
            target = nextMorning(hrvResidual), rule = ScoreRule.LOG, lagDays = 1,
        ),
    ),
    sleepEnergy = scorecard(
        ScorecardSpec(
            key = "sleep_index", label = "Sleep Index", targetText = "the day's energy",
            composite = ScorePredictor("sleep_index", "Sleep Index", sleepIndex), inputs = sleepInputs,
            target = energy, rule = ScoreRule.LOG,
        ),
    ),
    sleepHrv = scorecard(
        ScorecardSpec(
            key = "sleep_index", label = "Sleep Index", targetText = "that morning's HRV against its recent level",
            composite = ScorePredictor("sleep_index", "Sleep Index", sleepIndex), inputs = sleepInputs,
            target = hrvResidual, rule = ScoreRule.LOG,
        ),
    ),
)

/** The share of each day's planned sessions that were done (matched to a recorded session, or confirmed by hand). */
fun plannedDoneByDay(rows: List<PlannedOutcomeRow>): Map<LocalDate, Double> =
    rows.mapNotNull { row -> runCatching { LocalDate.parse(row.scheduledDate.take(10)) }.getOrNull()?.let { it to row } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, day) -> day.count { it.exerciseSessionId != null || it.status == "confirmed" || it.status == "completed" }.toDouble() / day.size }

/** READY FOR: whether the day's planned sessions were done (the share done, 0 to 1), from recovery's state that morning. */
fun CompositeHistory.readyForScorecard(plannedDone: Map<LocalDate, Double>): ScorecardResult = scorecard(
    ScorecardSpec(
        key = "training_readiness", label = "READY FOR", targetText = "whether the day's planned sessions were done",
        composite = ScorePredictor("ready_for", "READY FOR", recovery), inputs = recoveryInputs,
        target = plannedDone, rule = ScoreRule.BRIER,
    ),
)
