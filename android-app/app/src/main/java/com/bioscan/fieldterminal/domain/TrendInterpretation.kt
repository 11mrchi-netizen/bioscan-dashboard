package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.analysis.Directionality
import kotlin.math.abs

// Cardio chart rework: a short plain-language caption under every EvalCard
// chart, built only from fields SwcEvaluation already carries (state,
// baseline7d, mean60d, cv7d, confidence) -- never a separate judgment call,
// so it can't say anything the card's own pill/range indicator don't already
// support. Generic over any SwcEvaluation, so one function covers HRV, RHR,
// SpO2 and Sleep Duration -- every EvalCard caller.
fun interpretSwcEvaluation(label: String, eval: SwcEvaluation, directionality: Directionality): String? = when (eval.state) {
    EvalState.NoData -> null
    EvalState.Building -> "Still building a 60-day baseline — ${eval.confidence.label} days of history so far."
    EvalState.Stable -> "$label is steady, close to its 60-day baseline."
    EvalState.Unstable -> {
        val cv = eval.cv7d
        if (cv != null) "$label's day-to-day swings are larger than usual (7-day CV ${"%.0f".format(cv)}%) — no clear direction yet."
        else "$label's day-to-day swings are larger than usual — no clear direction yet."
    }
    EvalState.ShiftUp, EvalState.ShiftDown -> {
        val baseline7d = eval.baseline7d
        val mean60d = eval.mean60d
        if (baseline7d == null || mean60d == null || mean60d == 0.0) null
        else {
            val pct = abs((baseline7d - mean60d) / mean60d * 100)
            val direction = if (eval.state == EvalState.ShiftUp) "above" else "below"
            val qualifier = when (directionality) {
                Directionality.HIGHER_BETTER, Directionality.LOWER_BETTER ->
                    when (directionFromShift(eval.state, directionality)) {
                        1 -> " — trending favorably"
                        -1 -> " — worth keeping an eye on"
                        else -> ""
                    }
                Directionality.OPTIMAL_RANGE, Directionality.TARGET_VALUE, Directionality.NON_DIRECTIONAL -> ""
            }
            "$label is ${"%.0f".format(pct)}% $direction its 60-day baseline$qualifier."
        }
    }
}
