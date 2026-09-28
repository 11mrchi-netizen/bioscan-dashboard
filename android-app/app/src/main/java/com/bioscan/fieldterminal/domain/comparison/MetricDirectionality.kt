package com.bioscan.fieldterminal.domain.comparison

import com.bioscan.fieldterminal.domain.analysis.Directionality

// DAV-195 (docs/analysis-layer-2/20-metric-normalization.md). Directionality
// is maintained by hand per canonical metric, never inferred from a metric's
// name or unit -- keyed by docs/analysis-layer-2/02-metric-registry.md's own
// canonical names, so this table extends that registry instead of forking a
// second naming scheme. Only metrics with real data behind them today (per
// doc 02's own coverage column) are populated; a metric with no entry here
// is a real gap to notice (ComparisonBands.kt reads a missing key as
// unsupported), not something to silently default.
val METRIC_DIRECTIONALITY: Map<String, Directionality> = mapOf(
    "hrv" to Directionality.HIGHER_BETTER,
    "resting_heart_rate" to Directionality.LOWER_BETTER,
    "vo2max" to Directionality.HIGHER_BETTER,
    "steps" to Directionality.HIGHER_BETTER,
    // Neither "more is better" nor a stored personal target exists for sleep
    // duration -- ~7-9h is the real physiological optimum, not a monotonic
    // higher-better metric, per real sleep-medicine convention.
    "sleep_duration" to Directionality.OPTIMAL_RANGE,
    "sleep_deep_minutes" to Directionality.HIGHER_BETTER,
    "sleep_rem_minutes" to Directionality.HIGHER_BETTER,
    "respiratory_rate_sleep" to Directionality.LOWER_BETTER,
    "sleep_regularity_index" to Directionality.HIGHER_BETTER,
    // No goal weight is stored anywhere in this app (confirmed against
    // body_metrics' own schema) -- TARGET_VALUE would need one; body weight
    // alone isn't inherently higher- or lower-better without that context.
    "body_weight" to Directionality.NON_DIRECTIONAL,
    "training_load_ctl" to Directionality.HIGHER_BETTER,
    "training_load_tsb" to Directionality.OPTIMAL_RANGE,
    "pace" to Directionality.LOWER_BETTER,
    "speed" to Directionality.HIGHER_BETTER,
    "power" to Directionality.HIGHER_BETTER,
)

// Sign-flips a lower-better raw value so every normalized value shares one
// "higher normalized = better" convention downstream -- the entire
// normalization scope here, since doc 02 already fixes one canonical unit
// per metric (no unit conversion needed). OPTIMAL_RANGE/TARGET_VALUE/
// NON_DIRECTIONAL metrics pass through unchanged: there is no single
// "better" direction to flip toward for those, by definition.
fun normalize(rawValue: Double, directionality: Directionality): Double = when (directionality) {
    Directionality.LOWER_BETTER -> -rawValue
    Directionality.HIGHER_BETTER,
    Directionality.OPTIMAL_RANGE,
    Directionality.TARGET_VALUE,
    Directionality.NON_DIRECTIONAL,
    -> rawValue
}
