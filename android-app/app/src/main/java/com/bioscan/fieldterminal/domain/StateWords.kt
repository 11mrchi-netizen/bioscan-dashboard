package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.analysis.Directionality

// Analysis review A1 (design/ANALYTICAL_PRESENTATION_CONTRACT.md, "State words" amendment). An evaluation's semantic
// state plus the word its pill shows. The state sets colour and symbol; the word names what was compared. Before this,
// one generic mapping turned Stable into OPTIMAL and every shift into ATTENTION, so a week of higher HRV got the same
// amber pill as a falling one (while its caption said "trending favorably") and a flat HRV read OPTIMAL at any level.
// Each kind of evaluation now has its own mapping, and Optimal is reserved for a change in the favorable direction.
data class StateWord(val state: MetricState, val word: String? = null)

// Sleep duration has a known healthy range (7-9 h for adults) even though it is not monotonic, so a shift that moves
// the weekly mean out of that range, or further from it, warrants attention; a shift inside it is just a change.
val SLEEP_HOURS_RANGE: ClosedFloatingPointRange<Double> = 7.0..9.0

// A metric judged against its own 60-day baseline (HRV, RHR, SpO2, sleep duration and stages). `weeklyMean` is the
// 7-day mean in display units; it only matters for OPTIMAL_RANGE metrics with a known `range`.
fun baselineStateWord(
    state: EvalState,
    directionality: Directionality,
    range: ClosedFloatingPointRange<Double>? = null,
    weeklyMean: Double? = null,
): StateWord = when (state) {
    EvalState.NoData -> StateWord(MetricState.Unavailable)
    EvalState.Building -> StateWord(MetricState.Building)
    EvalState.Stable -> StateWord(MetricState.Neutral, "STEADY")
    EvalState.ShiftUp -> StateWord(shiftState(+1, directionality, range, weeklyMean), "ABOVE BASELINE")
    EvalState.ShiftDown -> StateWord(shiftState(-1, directionality, range, weeklyMean), "BELOW BASELINE")
    // Day-to-day swings well beyond this person's usual spread: worth a look, but no direction to judge.
    EvalState.Unstable -> StateWord(MetricState.Warning, "UNSETTLED")
}

private fun shiftState(direction: Int, directionality: Directionality, range: ClosedFloatingPointRange<Double>?, weeklyMean: Double?): MetricState =
    when (directionality) {
        Directionality.HIGHER_BETTER -> if (direction > 0) MetricState.Optimal else MetricState.Warning
        Directionality.LOWER_BETTER -> if (direction < 0) MetricState.Optimal else MetricState.Warning
        Directionality.OPTIMAL_RANGE ->
            if (range != null && weeklyMean != null && weeklyMean !in range) MetricState.Warning else MetricState.Neutral
        Directionality.TARGET_VALUE, Directionality.NON_DIRECTIONAL -> MetricState.Neutral
    }

// Today's recovery (DynamicRecovery.kt): every directional contributor up, every one down, a balance, or a split. Since
// analysis review phase G in the vocabulary every composite shares with the morning band (AS / ABOVE / BELOW EXPECTED).
fun recoveryStateWord(state: EvalState): StateWord = when (state) {
    EvalState.NoData -> StateWord(MetricState.Unavailable)
    EvalState.Building -> StateWord(MetricState.Building)
    EvalState.Stable -> StateWord(MetricState.Neutral, "AS EXPECTED")
    EvalState.ShiftUp -> StateWord(MetricState.Optimal, "ABOVE EXPECTED")
    EvalState.ShiftDown -> StateWord(MetricState.Warning, "BELOW EXPECTED")
    // Contributors point both ways: no clean verdict, which is information, not missing data.
    EvalState.Unstable -> StateWord(MetricState.Neutral, "MIXED SIGNALS")
}

// Training load (TrainingLoadEvaluation.kt): its states are TSB bands, not baseline shifts. Being loaded or fresh is
// what training does, so only "heavily loaded" asks for attention. `band` is the TSB band label when known.
fun trainingLoadStateWord(state: EvalState, band: String? = null): StateWord = when (state) {
    EvalState.NoData -> StateWord(MetricState.Unavailable)
    EvalState.Building -> StateWord(MetricState.Building)
    EvalState.Stable -> StateWord(MetricState.Neutral, band?.uppercase() ?: "NEUTRAL")
    EvalState.ShiftUp -> StateWord(MetricState.Neutral, band?.uppercase() ?: "FRESH")
    EvalState.ShiftDown -> StateWord(MetricState.Neutral, band?.uppercase() ?: "LOADED")
    EvalState.Unstable -> StateWord(MetricState.Warning, band?.uppercase() ?: "HEAVILY LOADED")
}

// A change with no stored goal behind it (weight, body fat): rising or falling is described, never judged.
fun trendStateWord(state: EvalState): StateWord = when (state) {
    EvalState.NoData -> StateWord(MetricState.Unavailable)
    EvalState.Building -> StateWord(MetricState.Building)
    EvalState.Stable -> StateWord(MetricState.Neutral, "STEADY")
    EvalState.ShiftUp -> StateWord(MetricState.Neutral, "RISING")
    EvalState.ShiftDown -> StateWord(MetricState.Neutral, "FALLING")
    EvalState.Unstable -> StateWord(MetricState.Warning, "UNSETTLED")
}

// An evaluation that only gates on data (Nutrition, the trail Mountain Index): enough data is not a verdict.
fun gateStateWord(state: EvalState): StateWord = when (state) {
    EvalState.NoData -> StateWord(MetricState.Unavailable)
    EvalState.Building -> StateWord(MetricState.Building)
    else -> StateWord(MetricState.Neutral, "TRACKED")
}
