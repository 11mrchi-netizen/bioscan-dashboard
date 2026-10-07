package com.bioscan.fieldterminal.domain

// Phase A2 (Analysis Layer), from the Evaluation Method Spec's global rules
// (section 0). Every per-stream evaluation in domain/*Evaluation.kt resolves
// to exactly one of these six -- nothing else gets rendered. Explicitly
// within-person: no state here ever compares against a population norm.
enum class EvalState { NoData, Building, Stable, ShiftUp, ShiftDown, Unstable }

// The spec's own "confidence chip" rule (0.5): every card carries an
// explicit "n of N" so the user never has to guess how much data backs a
// statement. `met` is what gates whether a real state (vs. NoData/Building)
// gets rendered at all -- see each Evaluation.kt's own gate logic.
data class Confidence(val have: Int, val need: Int) {
    val met: Boolean get() = have >= need
    val label: String get() = "$have/$need"
}

// DAV-210/DAV-212: single shared mapping, replacing 4 byte-identical private
// copies (SessionDetailScreen/FuelTileScreen/HeartTileScreen/TrainingTileScreen).
// Unstable maps to Warning, not Critical -- it flags elevated week-over-week
// variability, a milder signal than a genuine ShiftUp/ShiftDown, so it shouldn't
// read as more alarming than a real shift.
fun EvalState.toMetricState(): MetricState = when (this) {
    EvalState.NoData -> MetricState.Unavailable
    EvalState.Building -> MetricState.Building
    EvalState.Stable -> MetricState.Optimal
    EvalState.ShiftUp, EvalState.ShiftDown -> MetricState.Warning
    EvalState.Unstable -> MetricState.Warning
}
