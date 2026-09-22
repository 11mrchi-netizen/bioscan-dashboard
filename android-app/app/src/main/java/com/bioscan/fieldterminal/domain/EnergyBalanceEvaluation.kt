package com.bioscan.fieldterminal.domain

// Category 10 (DAV-104, folding in DAV-34). Post-fact adaptive TDEE:
// within-person energy balance from logged intake (Category 4's own
// 14-complete-day gate) and the established weight EMA trend (Category 5's
// own gate) -- never a predictive Harris-Benedict/Mifflin-St-Jeor style
// formula, which knows nothing about this specific person's real intake or
// real weight response and carries validated individual error of several
// hundred kcal/day. Both real inputs (energyTrend14d, rateKgPerWeek) are
// reused verbatim from the two evaluations that already compute them --
// this file adds only the energy-balance combination and its own gate
// propagation, no new data pipeline.
//
// Single-compartment energy-density model -- the "weight-only" fallback the
// ticket's own gates section calls for, not full two-compartment/body-fat-
// aware estimation. That fancier model needs dense body-fat-% history this
// account (id est most real accounts) doesn't log; deliberately not built
// until that data exists to support it.
data class TdeeEvaluation(
    val state: EvalState,
    val confidence: Confidence,
    val tdeeKcal: Double?,
    val rangeLowKcal: Double?,
    val rangeHighKcal: Double?,
)

// Wishnofsky's rule (1958) -- still the standard simplification behind
// MacroFactor, RP Diet Coach, and most consumer adaptive-TDEE tools. A
// documented, versioned constant (same category as this app's own Banister
// TRIMP coefficients or Epley 1RM formula), not a personalized measurement.
// Real known limitation: treats all tissue change as one uniform energy
// density, when early-phase change is more water/glycogen (lower density)
// and gain vs. loss differ in macronutrient composition -- a full two-
// compartment model would correct for this but needs the body-fat history
// noted above.
private const val KCAL_PER_KG_TISSUE_CHANGE = 7700.0

fun evaluateTdee(nutrition: NutritionEvaluation, weight: WeightEvaluation): TdeeEvaluation {
    val have = minOf(nutrition.confidence.have, weight.confidence.have)
    val need = maxOf(nutrition.confidence.need, weight.confidence.need)

    val avgIntake = nutrition.energyTrend14d
    val intakeSd = nutrition.energySd14d
    val rateKgPerWeek = weight.rateKgPerWeek

    // Gate propagation, same pattern RestCadenceEvaluation already uses for
    // its own dependency on TrainingLoadEvaluation's gate: TDEE can't be
    // more confident than its weakest real input. Gates on confidence.met,
    // not state == Stable -- real bug found live on-device: WeightEvaluation
    // legitimately resolves to ShiftUp/ShiftDown/Unstable once its own gate
    // is met (a real, moving trend is not "not ready"), so checking for
    // Stable specifically kept a real, usable weight trend stuck on
    // Building. NutritionEvaluation only ever resolves Stable once its gate
    // is met (no ShiftUp/Down/Unstable exist for it), so this generalizes
    // that check correctly for both without special-casing either.
    if (!nutrition.confidence.met || !weight.confidence.met ||
        avgIntake == null || intakeSd == null || rateKgPerWeek == null
    ) {
        return TdeeEvaluation(EvalState.Building, Confidence(have, need), null, null, null)
    }

    val impliedDailyDeficit = rateKgPerWeek / 7.0 * KCAL_PER_KG_TISSUE_CHANGE
    val tdee = avgIntake - impliedDailyDeficit

    // Credible range from this account's own real day-to-day logging
    // variability (the dominant real source of uncertainty once weight is
    // EMA-smoothed) rather than an invented +/- percentage -- never present
    // a single fake-precise number for a self-reported-intake estimate.
    return TdeeEvaluation(
        state = EvalState.Stable,
        confidence = Confidence(have, need),
        tdeeKcal = tdee,
        rangeLowKcal = tdee - intakeSd,
        rangeHighKcal = tdee + intakeSd,
    )
}
