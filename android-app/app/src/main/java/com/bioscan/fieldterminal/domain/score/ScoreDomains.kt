@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class, com.bioscan.fieldterminal.domain.InstantSerializer::class, com.bioscan.fieldterminal.domain.LocalTimeSerializer::class)
package com.bioscan.fieldterminal.domain.score

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate

// Scoring system: the five domains, what each reads and how much each input weighs.
//
// Every metric belongs to exactly one domain. Night-time physiology is RECOVERY's, daytime physiology BALANCE's. The
// weights are documented starting opinions (docs/scoring-system/README.md), not fitted values; they are re-fitted only
// once 120 scored days exist. An input whose data source isn't assembled yet (ScoreAssembly.kt) is simply absent, and
// its weight is shared among the others, exactly as for a missing reading.

enum class DomainKey(val label: String, val upside: Boolean) {
    SLEEP("SLEEP", upside = true),
    RECOVERY("RECOVERY", upside = true),
    LOAD("LOAD", upside = false),
    FUEL("FUEL", upside = false),
    BALANCE("BALANCE", upside = true),
}

/** Series keys the engine reads. Each is keyed by the morning the score is published on. */
object ScoreKeys {
    // SLEEP (last night, keyed by the wake date)
    const val SLEEP_HOURS = "sleep.hours"
    const val SLEEP_DEBT = "sleep.debt_7d"
    const val SLEEP_REGULARITY = "sleep.regularity_min"
    const val SLEEP_MIDPOINT = "sleep.midpoint_min"
    const val SLEEP_DEEP_REM = "sleep.deep_rem_min"
    const val SLEEP_BREATHING = "sleep.respiratory"

    // RECOVERY (night and morning)
    const val HRV = "recovery.hrv"
    const val RHR = "recovery.rhr"
    const val NIGHT_STRESS = "recovery.night_stress"
    const val SORENESS = "recovery.soreness"
    const val ENERGY = "recovery.energy"
    const val MORNING_ERECTION = "recovery.morning_erection"
    const val AROUSAL = "recovery.arousal"

    // LOAD (training through yesterday)
    const val TSB = "load.tsb"
    const val DAYS_WITHOUT_REST = "load.days_without_rest"
    const val MONOTONY = "load.monotony"
    const val PLAN_DONE = "load.plan_done_7d"

    // FUEL (yesterday)
    const val FUEL_LOGGED_SHARE = "fuel.logged_share"
    const val ENERGY_RATIO = "fuel.energy_ratio"
    const val PROTEIN_RATIO = "fuel.protein_ratio"
    const val CARB_RATIO = "fuel.carb_ratio"
    const val WATER_RATIO = "fuel.water_ratio"
    const val DRINKS = "fuel.drinks"
    const val LATE_CAFFEINE = "fuel.late_caffeine"
    const val GUT_SYMPTOMS = "gut.symptoms"
    const val GUT_TIMING = "gut.timing_min"
    const val GUT_TIMING_SPREAD = "gut.timing_spread_min"
    const val GUT_FREQUENCY = "gut.frequency"
    const val GUT_FORM = "gut.form_3d"
    const val GUT_SESSION = "gut.session"

    // BALANCE (yesterday's daytime)
    const val STRESSED_SHARE = "ans.stressed_pct"
    const val RELAXED_SHARE = "ans.relaxed_pct"
    const val WIND_DOWN = "ans.wind_down"
    const val HR_DIP = "ans.hr_dip_pct"
    const val HRV_CV = "ans.hrv_cv_7d"
    const val FELT_STRESS = "mind.stress"
    const val MOOD = "mind.mood"
    const val LATE_SCREEN = "rhythm.late_screen"
    const val MEAL_TIMING_SPREAD = "rhythm.meal_timing_spread_min"
}

/** A group of inputs inside a domain (FUEL's intake and gut, BALANCE's nervous system, mind and rhythm). */
@Serializable
data class DomainGroup(
    val key: String,
    val label: String,
    val weight: Double,
    val inputs: List<InputSpec>,
    /** Only scored on days where this series is at least [gateMin] (FUEL's intake needs a complete enough food log). */
    val gateKey: String? = null,
    val gateMin: Double = 0.0,
)

data class DomainSpec(val key: DomainKey, val groups: List<DomainGroup>)

private fun single(label: String, vararg inputs: InputSpec) = listOf(DomainGroup("main", label, 1.0, inputs.toList()))

object ScoreSpecs {
    /** A domain with less than this share of its weight present is PARTIAL: left out of CHARGE, never scored low. */
    const val MIN_COVERAGE = 0.4

    /** FUEL's intake is scored only when the day's food log reaches this share of the person's usual intake. */
    const val MIN_LOGGED_SHARE = 0.6

    val SLEEP = DomainSpec(DomainKey.SLEEP, single(
        "Sleep",
        InputSpec(ScoreKeys.SLEEP_HOURS, "Duration", 30.0, InputKind.Baseline(betterHigher = true, minSd = 0.25), critical = true),
        InputSpec(ScoreKeys.SLEEP_DEBT, "Sleep debt (carried)", 20.0, InputKind.AtMost(limit = 0.5, scale = 1.5)),
        InputSpec(ScoreKeys.SLEEP_DEEP_REM, "Deep + REM", 15.0, InputKind.Baseline(betterHigher = true, minSd = 10.0)),
        InputSpec(ScoreKeys.SLEEP_REGULARITY, "Regularity", 15.0, InputKind.Baseline(betterHigher = false, upside = false, minSd = 8.0)),
        InputSpec(ScoreKeys.SLEEP_MIDPOINT, "Timing", 10.0, InputKind.Stability(minSd = 20.0)),
        InputSpec(ScoreKeys.SLEEP_BREATHING, "Breathing", 10.0, InputKind.Stability(minSd = 0.3)),
    ))

    val RECOVERY = DomainSpec(DomainKey.RECOVERY, single(
        "Recovery",
        InputSpec(ScoreKeys.HRV, "HRV", 35.0, InputKind.Baseline(betterHigher = true, log = true, minSd = 0.03), critical = true),
        InputSpec(ScoreKeys.RHR, "Resting HR", 25.0, InputKind.Baseline(betterHigher = false, minSd = 1.0), critical = true),
        InputSpec(ScoreKeys.NIGHT_STRESS, "Night stress", 15.0, InputKind.Baseline(betterHigher = false, minSd = 2.0)),
        InputSpec(ScoreKeys.SORENESS, "Soreness", 15.0, InputKind.Baseline(betterHigher = false, minSd = 0.5, minHistory = 7)),
        InputSpec(ScoreKeys.ENERGY, "Morning energy", 5.0, InputKind.Baseline(betterHigher = true, minSd = 0.5, minHistory = 7)),
        InputSpec(ScoreKeys.MORNING_ERECTION, "Morning erection", 2.5, InputKind.Baseline(betterHigher = true, minSd = 0.5, minHistory = 7)),
        InputSpec(ScoreKeys.AROUSAL, "Arousal", 2.5, InputKind.Baseline(betterHigher = true, minSd = 0.5, minHistory = 7)),
    ))

    // The productive TSB band and the cut-offs below are opinions in the same spirit as TrainingLoadEvaluation's bands:
    // -25..+5 spans "loaded" to just "freshened"; Foster's monotony above 2 is the classic warning line.
    const val TSB_BAND_LO = -25.0
    const val TSB_BAND_HI = 5.0
    const val MAX_DAYS_WITHOUT_REST = 3.0

    val LOAD = DomainSpec(DomainKey.LOAD, single(
        "Load",
        InputSpec(ScoreKeys.TSB, "Fatigue (TSB)", 40.0, InputKind.Band(TSB_BAND_LO, TSB_BAND_HI, scale = 10.0), critical = true),
        InputSpec(ScoreKeys.DAYS_WITHOUT_REST, "Days without rest", 20.0, InputKind.AtMost(MAX_DAYS_WITHOUT_REST, scale = 1.5)),
        InputSpec(ScoreKeys.MONOTONY, "Monotony", 15.0, InputKind.AtMost(2.0, scale = 0.5)),
        InputSpec(ScoreKeys.PLAN_DONE, "Plan done", 15.0, InputKind.AtLeast(0.8, scale = 0.25)),
    ))

    /** LOAD's fatigue side only, the part CHARGE reads: too little training makes you fresher, not more tired. */
    val LOAD_FATIGUE = DomainSpec(DomainKey.LOAD, single(
        "Load (fatigue)",
        InputSpec(ScoreKeys.TSB, "Fatigue (TSB)", 40.0, InputKind.AtLeast(TSB_BAND_LO, scale = 10.0), critical = true),
        InputSpec(ScoreKeys.DAYS_WITHOUT_REST, "Days without rest", 20.0, InputKind.AtMost(MAX_DAYS_WITHOUT_REST, scale = 1.5)),
        InputSpec(ScoreKeys.MONOTONY, "Monotony", 15.0, InputKind.AtMost(2.0, scale = 0.5)),
    ))

    val FUEL = DomainSpec(DomainKey.FUEL, listOf(
        DomainGroup(
            "intake", "Intake", 0.7,
            listOf(
                InputSpec(ScoreKeys.ENERGY_RATIO, "Energy", 35.0, InputKind.Band(0.9, 1.1, scale = 0.15), critical = true),
                InputSpec(ScoreKeys.PROTEIN_RATIO, "Protein", 20.0, InputKind.AtLeast(1.0, scale = 0.2)),
                InputSpec(ScoreKeys.CARB_RATIO, "Carbs", 20.0, InputKind.AtLeast(1.0, scale = 0.2)),
                InputSpec(ScoreKeys.WATER_RATIO, "Water", 15.0, InputKind.AtLeast(1.0, scale = 0.2)),
                InputSpec(ScoreKeys.DRINKS, "Alcohol", 5.0, InputKind.AtMost(1.0, scale = 1.5)),
                InputSpec(ScoreKeys.LATE_CAFFEINE, "Late caffeine", 5.0, InputKind.AtMost(50.0, scale = 100.0)),
            ),
            gateKey = ScoreKeys.FUEL_LOGGED_SHARE, gateMin = MIN_LOGGED_SHARE,
        ),
        DomainGroup(
            "gut", "Gut", 0.3,
            listOf(
                InputSpec(ScoreKeys.GUT_SYMPTOMS, "Symptoms", 35.0, InputKind.AtMost(0.5, scale = 1.0), critical = true),
                InputSpec(ScoreKeys.GUT_TIMING, "Timing", 8.0, InputKind.Stability(minSd = 30.0, minHistory = 10)),
                InputSpec(ScoreKeys.GUT_TIMING_SPREAD, "Timing regularity", 7.0, InputKind.Baseline(betterHigher = false, upside = false, minSd = 10.0, minHistory = 10)),
                InputSpec(ScoreKeys.GUT_FREQUENCY, "Frequency", 15.0, InputKind.Stability(minSd = 0.5, minHistory = 10)),
                InputSpec(ScoreKeys.GUT_FORM, "Form", 15.0, InputKind.Stability(minSd = 0.5, minHistory = 10)),
                InputSpec(ScoreKeys.GUT_SESSION, "In-session gut", 20.0, InputKind.AtMost(2.0, scale = 2.0)),
            ),
        ),
    ))

    val BALANCE = DomainSpec(DomainKey.BALANCE, listOf(
        DomainGroup(
            "ans", "Nervous system", 0.5,
            listOf(
                InputSpec(ScoreKeys.STRESSED_SHARE, "Stressed time", 30.0, InputKind.Baseline(betterHigher = false, minSd = 3.0)),
                InputSpec(ScoreKeys.RELAXED_SHARE, "Relaxed time", 20.0, InputKind.Baseline(betterHigher = true, minSd = 3.0)),
                InputSpec(ScoreKeys.WIND_DOWN, "Wind-down", 15.0, InputKind.Baseline(betterHigher = false, minSd = 3.0)),
                InputSpec(ScoreKeys.HR_DIP, "Night HR dip", 20.0, InputKind.Baseline(betterHigher = true, minSd = 1.0)),
                InputSpec(ScoreKeys.HRV_CV, "HRV stability", 15.0, InputKind.Baseline(betterHigher = false, minSd = 0.5)),
            ),
        ),
        DomainGroup(
            "mind", "Mind", 0.3,
            listOf(
                InputSpec(ScoreKeys.FELT_STRESS, "Stress (felt)", 50.0, InputKind.Baseline(betterHigher = false, minSd = 0.5, minHistory = 7)),
                InputSpec(ScoreKeys.MOOD, "Mood", 50.0, InputKind.Baseline(betterHigher = true, minSd = 0.5, minHistory = 7)),
            ),
        ),
        DomainGroup(
            "rhythm", "Rhythm", 0.2,
            listOf(
                InputSpec(ScoreKeys.MEAL_TIMING_SPREAD, "Meal timing", 50.0, InputKind.Baseline(betterHigher = false, upside = false, minSd = 10.0)),
                // Minutes on the phone in the hour before sleep, measured by the phone (phone_usage_daily). Up to 10 minutes
                // scores the top of normal; each 20 minutes more costs one standard deviation (about 76 at 38 minutes, 42
                // at a full hour). A documented opinion, after the evening-light and pre-sleep screen studies
                // (Chang 2015; Hale 2015): less is better, there is no personal "usual" to aim at.
                InputSpec(ScoreKeys.LATE_SCREEN, "Phone before sleep", 50.0, InputKind.AtMost(10.0, scale = 20.0)),
            ),
        ),
    ))

    val ALL: Map<DomainKey, DomainSpec> = listOf(SLEEP, RECOVERY, LOAD, FUEL, BALANCE).associateBy { it.key }
}

@Serializable
data class GroupScore(val group: DomainGroup, val score: Double?, val inputs: List<InputScore>, val coverage: Double)

/** A domain's combination of its inputs for one morning, before it is ranked against its own history. */
@Serializable
data class DomainRaw(
    val key: DomainKey,
    /** Null when PARTIAL (coverage under [ScoreSpecs.MIN_COVERAGE]). */
    val raw: Double?,
    val groups: List<GroupScore>,
    val coverage: Double,
    /** False when this morning's value may not go above the top of normal (a no-upside domain, or a guard). */
    val upside: Boolean = true,
) {
    val inputs: List<InputScore> get() = groups.flatMap { it.inputs }
}

private fun weightedMean(items: List<Pair<Double, Double>>): Double? {
    val w = items.sumOf { it.second }
    return if (w <= 0) null else items.sumOf { it.first * it.second } / w
}

/**
 * Combines a domain's inputs for [day]: a weighted mean per group, then across groups, both over what is present.
 * A domain can't read more than [CRITICAL_HEADROOM] above its worst critical input. [upside] false caps it at 100.
 * [inputCap] lets the caller hold one input at the top of normal (the SATURATED guard on HRV).
 */
fun combineDomain(
    spec: DomainSpec,
    series: Map<String, Series>,
    day: LocalDate,
    upside: Boolean = spec.key.upside,
    inputCap: Map<String, Double> = emptyMap(),
): DomainRaw {
    val groups = spec.groups.map { group ->
        val gated = group.gateKey != null && (series[group.gateKey]?.get(day) ?: 0.0) < group.gateMin
        val scored = if (gated) emptyList() else group.inputs.mapNotNull { input ->
            val s = scoreInput(input, series[input.key].orEmpty(), day) ?: return@mapNotNull null
            inputCap[input.key]?.let { cap -> if (s.score > cap) s.copy(score = cap) else s } ?: s
        }
        val total = group.inputs.sumOf { it.weight }
        val present = scored.sumOf { it.spec.weight }
        GroupScore(group, weightedMean(scored.map { it.score to it.spec.weight }), scored, if (total > 0) present / total else 0.0)
    }
    val totalGroupWeight = spec.groups.sumOf { it.weight }
    val coverage = groups.sumOf { it.group.weight * it.coverage } / totalGroupWeight
    if (coverage < ScoreSpecs.MIN_COVERAGE) return DomainRaw(spec.key, null, groups, coverage, upside)
    var raw = weightedMean(groups.mapNotNull { g -> g.score?.let { it to g.group.weight * g.coverage } }) ?: return DomainRaw(spec.key, null, groups, coverage, upside)
    groups.flatMap { it.inputs }.filter { it.spec.critical }.minOfOrNull { it.score }?.let { worst -> raw = minOf(raw, worst + CRITICAL_HEADROOM) }
    if (!upside) raw = minOf(raw, ScoreScale.TOP_OF_NORMAL)
    return DomainRaw(spec.key, raw.coerceIn(0.0, ScoreScale.MAX), groups, coverage, upside)
}
