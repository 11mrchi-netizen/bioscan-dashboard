package com.bioscan.fieldterminal.domain

import kotlin.math.round

// Analysis Layer 3 / R2 (docs/analysis-layer-3). Alcohol is recorded as GRAMS OF ETHANOL, computed from what a person
// actually knows -- the volume and the strength on the label -- and never from kcal (the competitor's `alcoholG` field held
// alcohol kcal under a grams name, which silently skews every alcohol statistic).
const val ETHANOL_DENSITY_G_PER_ML = 0.789

// Grams of pure ethanol in a drink: volume x ABV x density, rounded to 0.1 g. Null for a non-positive volume or an ABV
// outside (0, 100], so an unusable input is rejected instead of stored as a plausible-looking number.
fun ethanolGrams(volumeMl: Double, abvPercent: Double): Double? {
    if (!(volumeMl > 0.0) || !(abvPercent > 0.0) || abvPercent > 100.0) return null
    return round(volumeMl * abvPercent / 100.0 * ETHANOL_DENSITY_G_PER_ML * 10.0) / 10.0
}
