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

// Analysis review A1: the single generic EvalState -> MetricState mapping (DAV-210) is gone. It read Stable as OPTIMAL
// and every shift as ATTENTION whatever the metric's direction. Each kind of evaluation now maps through its own
// function in StateWords.kt (baseline, recovery, training load, trend, data gate), which also supplies the pill's word.
