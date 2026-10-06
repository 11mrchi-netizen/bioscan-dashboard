package com.bioscan.fieldterminal.domain

// 09.5 Hydration Intelligence (DAV-182, 257-258). Keeps three quantities
// explicitly distinct per DAV-257's own rule: measured intake and modeled
// effective hydration are already real (Nutrition Intelligence) and reused
// as-is here, never re-derived; inferred physiological demand is new and
// approximate. environmentalContextAvailable is always false today -- there
// is no historical weather persistence pipeline anywhere in this app
// (WeatherRepository.kt only calls a live forecast API from the unmounted
// Map screen, nothing stored) -- present in the contract, per DAV-257's own
// "keep three quantities distinct" rule, never silently dropped.
const val HYDRATION_INTELLIGENCE_MODEL_VERSION = "v1"

data class HydrationIntelligenceResult(
    val measuredIntakeMl: Double?,
    val effectiveHydrationMl: Double?,
    val inferredDemandMl: Double?,
    val environmentalContextAvailable: Boolean,
    val confidence: Confidence,
    val modelVersion: String = HYDRATION_INTELLIGENCE_MODEL_VERSION,
)

// A documented, deliberately simple sweat-rate-by-duration heuristic --
// exercise duration only, no HR-based intensity banding or environmental
// temperature yet (real future refinements once this proves useful, not
// blocking a v1). 500ml/hour is a commonly-cited moderate sweat-rate
// approximation, not a fitted or personalized number.
private const val ASSUMED_SWEAT_RATE_ML_PER_HOUR = 500.0
private const val CONTRIBUTORS_TRACKED = 3

fun computeHydrationIntelligence(
    measuredIntakeMl: Double?,
    effectiveHydrationMl: Double?,
    exerciseDurationMinutesToday: Double,
): HydrationIntelligenceResult {
    val inferredDemandMl = if (exerciseDurationMinutesToday > 0) (exerciseDurationMinutesToday / 60.0) * ASSUMED_SWEAT_RATE_ML_PER_HOUR else null
    val have = listOfNotNull(measuredIntakeMl, effectiveHydrationMl, inferredDemandMl).size
    return HydrationIntelligenceResult(
        measuredIntakeMl = measuredIntakeMl,
        effectiveHydrationMl = effectiveHydrationMl,
        inferredDemandMl = inferredDemandMl,
        environmentalContextAvailable = false,
        confidence = Confidence(have, CONTRIBUTORS_TRACKED),
    )
}
