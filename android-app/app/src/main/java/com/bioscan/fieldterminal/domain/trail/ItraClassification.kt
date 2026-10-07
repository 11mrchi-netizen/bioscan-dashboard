package com.bioscan.fieldterminal.domain.trail

// 28/9: ITRA's published race-distance classification, applied here to a
// completed run's own KM-effort (StateDimension.KM_EFFORT, already computed
// as distance(km) + elevation_gain(m)/100 -- see
// docs/trail-intelligence/02-trail-metric-conventions.md's "official FFA/ITRA
// classification formula"). A pure band-from-number function, same pattern
// as TrainingLoadEvaluation.kt's tsbBandLabel() -- not a new StateDimension,
// since a categorical label isn't a numeric scalar and no other band in this
// codebase is modeled that way.
//
// Thresholds: itra.run itself is a JS SPA WebFetch can't read, so cited to
// two independent secondary sources that agree exactly and match this app's
// own already-ITRA-sourced km-effort formula:
// https://trailia.run/tools/itra-index-calculator
// https://findracepace.com/en/glossary/itra-distance-categories
private val ITRA_BANDS = listOf(
    25.0 to "XXS",
    45.0 to "XS",
    75.0 to "S",
    115.0 to "M",
    155.0 to "L",
    210.0 to "XL",
)

fun itraCategory(kmEffort: Double): String? {
    if (kmEffort < 0) return null
    for ((upTo, label) in ITRA_BANDS) if (kmEffort < upTo) return label
    return "XXL"
}
