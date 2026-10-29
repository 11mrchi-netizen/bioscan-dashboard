package com.bioscan.fieldterminal.domain

// Body composition from a scale weigh-in (weight + one impedance value) plus profile (height, age, sex).
// Only equations checked against their published source live here; see docs/body-composition/README.md.
//
// The scale reports a single impedance, not the whole-body resistance the papers use, so every impedance-based output
// is an estimate whose absolute level is biased by the device. Read trends, not single readings.

data class BodyCompositionInput(
    val weightKg: Double,
    val impedanceOhm: Double,
    val heightCm: Double,
    val ageYears: Int,
    val isMale: Boolean,
)

// Height² / impedance in cm²/ohm. Model-free: it needs no population equation, so it is the cleanest trend signal.
fun impedanceIndex(heightCm: Double, impedanceOhm: Double): Double? =
    if (heightCm in 100.0..230.0 && impedanceOhm in 50.0..3000.0) heightCm * heightCm / impedanceOhm else null

fun bmi(weightKg: Double, heightCm: Double): Double? =
    if (weightKg in 5.0..400.0 && heightCm in 100.0..230.0) weightKg / ((heightCm / 100.0) * (heightCm / 100.0)) else null

// Weight range for BMI 18.5..25 at this height.
fun healthyWeightRangeKg(heightCm: Double): ClosedFloatingPointRange<Double>? {
    if (heightCm !in 100.0..230.0) return null
    val m2 = (heightCm / 100.0) * (heightCm / 100.0)
    return 18.5 * m2..25.0 * m2
}

// Janssen et al. 2000, J Appl Physiol 89:465-471 (doi 10.1152/jappl.2000.89.2.465):
// SM (kg) = Ht²/R × 0.401 + sex × 3.825 − age × 0.071 + 5.102, Ht in cm, R in ohm, men = 1, women = 0.
// r² 0.86, SEE 2.7 kg (9%); derived from whole-body hand-to-foot resistance, underestimates in Asians.
fun skeletalMuscleMassKg(input: BodyCompositionInput): Double? {
    val index = impedanceIndex(input.heightCm, input.impedanceOhm) ?: return null
    if (input.ageYears !in 18..99) return null
    val sm = index * 0.401 + (if (input.isMale) 3.825 else 0.0) - input.ageYears * 0.071 + 5.102
    return sm.takeIf { it > 0.0 && it < input.weightKg }
}

// Fat-free mass → fat mass, body fat %, indices. Callers supply the fat-free mass from the verified FFM equation.
data class CompositionSplit(val fatFreeKg: Double, val fatKg: Double, val bodyFatPct: Double, val ffmi: Double, val fmi: Double)

fun compositionFromFatFreeMass(weightKg: Double, heightCm: Double, fatFreeKg: Double): CompositionSplit? {
    if (heightCm !in 100.0..230.0 || weightKg !in 5.0..400.0) return null
    val fat = weightKg - fatFreeKg
    val pct = fat / weightKg * 100.0
    if (fatFreeKg <= 0.0 || pct !in 1.0..70.0) return null // same plausibility range as body_metrics.body_fat_pct
    val m2 = (heightCm / 100.0) * (heightCm / 100.0)
    return CompositionSplit(fatFreeKg, fat, pct, fatFreeKg / m2, fat / m2)
}

// Katch-McArdle resting energy from fat-free mass: 370 + 21.6 × FFM(kg).
fun katchMcArdleKcal(fatFreeKg: Double): Double = 370.0 + 21.6 * fatFreeKg
