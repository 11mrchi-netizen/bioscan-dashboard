package com.bioscan.fieldterminal.ui.components

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.ConfidenceLevel
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.comparison.PercentileBand

// The one place threshold -> presentation-state decisions live (Analytical
// Presentation Contract, 2026-10 amendment). Each mapping is deterministic and
// documents its thresholds here; screens and components never inline their
// own. Callers render the result as a pill/chip with a label, never color
// alone.

// Confidence is evidence have/need (an observation count against what the
// model asks for): >= 80% of need is High, >= 50% Medium, otherwise Low.
fun confidenceLevel(confidence: Confidence): ConfidenceLevel {
    val ratio = if (confidence.need > 0) confidence.have.toDouble() / confidence.need else 0.0
    return when {
        ratio >= 0.8 -> ConfidenceLevel.High
        ratio >= 0.5 -> ConfidenceLevel.Medium
        else -> ConfidenceLevel.Low
    }
}

// An estimate that carries its own 0..1 confidence (e.g. Epley 1RM).
fun confidenceLevel(score: Double): ConfidenceLevel = when {
    score >= 0.8 -> ConfidenceLevel.High
    score >= 0.5 -> ConfidenceLevel.Medium
    else -> ConfidenceLevel.Low
}

// A percentile band is a position in a population, not a health verdict:
// only the top decile reads as Optimal; a low band is "Building", never
// Warning/Critical (low percentile != something is wrong).
fun percentileBandState(band: PercentileBand?): MetricState = when (band) {
    PercentileBand.TOP_DECILE -> MetricState.Optimal
    PercentileBand.ABOVE_AVERAGE, PercentileBand.AVERAGE -> MetricState.Neutral
    PercentileBand.BELOW_AVERAGE, PercentileBand.BOTTOM_DECILE -> MetricState.Building
    null -> MetricState.Unavailable
}

// Biological age minus chronological age, in years: <= -2 clearly younger
// (Optimal), within +/-2 Neutral, > +2 Warning. The +/-2 year band is a
// pragmatic noise margin for the PhenoAge/Cardio-Age estimates, not a
// clinical cut-off.
fun ageAccelerationState(accelerationYears: Double?): MetricState = when {
    accelerationYears == null -> MetricState.Unavailable
    accelerationYears <= -2.0 -> MetricState.Optimal
    accelerationYears <= 2.0 -> MetricState.Neutral
    else -> MetricState.Warning
}
