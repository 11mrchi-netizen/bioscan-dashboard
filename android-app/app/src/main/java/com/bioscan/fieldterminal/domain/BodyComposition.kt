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

// Sun et al. 2003, Am J Clin Nutr 77:331-340 (doi 10.1093/ajcn/77.2.331), Table 5, final race-combined equations.
// Stature in cm, resistance in ohm, weight in kg; S = stature² / resistance.
//   Males   TBW = 1.20 + 0.45 S + 0.18 W (R² 0.84, RMSE 3.8 L)    FFM = -10.68 + 0.65 S + 0.26 W + 0.02 R (R² 0.90, RMSE 3.9 kg)
//   Females TBW = 3.75 + 0.45 S + 0.11 W (R² 0.79, RMSE 2.6 L)    FFM =  -9.53 + 0.69 S + 0.17 W + 0.02 R (R² 0.83, RMSE 2.9 kg)
// Derived from adults 12-94 y on whole-body resistance, so a scale's impedance biases the absolute level.
fun totalBodyWaterL(input: BodyCompositionInput): Double? {
    val s = impedanceIndex(input.heightCm, input.impedanceOhm) ?: return null
    if (input.ageYears !in 18..99 || input.weightKg !in 5.0..400.0) return null
    return if (input.isMale) 1.20 + 0.45 * s + 0.18 * input.weightKg else 3.75 + 0.45 * s + 0.11 * input.weightKg
}

fun fatFreeMassKg(input: BodyCompositionInput): Double? {
    val s = impedanceIndex(input.heightCm, input.impedanceOhm) ?: return null
    if (input.ageYears !in 18..99 || input.weightKg !in 5.0..400.0) return null
    return if (input.isMale) {
        -10.68 + 0.65 * s + 0.26 * input.weightKg + 0.02 * input.impedanceOhm
    } else {
        -9.53 + 0.69 * s + 0.17 * input.weightKg + 0.02 * input.impedanceOhm
    }
}

// Everything the weigh-in can say. Null parts mean the input was out of range or the result was implausible; qualityOk is
// false when water is far from ~73% of fat-free mass (a sign of a bad contact or a hydration outlier).
data class BodyComposition(
    val bmi: Double?,
    val impedanceIndex: Double?,
    val totalBodyWaterL: Double?,
    val split: CompositionSplit?,
    val skeletalMuscleKg: Double?,
    val bmrKcal: Double?,
    val qualityOk: Boolean,
)

fun bodyComposition(input: BodyCompositionInput): BodyComposition {
    val ffm = fatFreeMassKg(input)
    val tbw = totalBodyWaterL(input)
    val split = ffm?.let { compositionFromFatFreeMass(input.weightKg, input.heightCm, it) }
    val hydration = if (tbw != null && ffm != null && ffm > 0.0) tbw / ffm else null
    return BodyComposition(
        bmi = bmi(input.weightKg, input.heightCm),
        impedanceIndex = impedanceIndex(input.heightCm, input.impedanceOhm),
        totalBodyWaterL = tbw,
        split = split,
        skeletalMuscleKg = skeletalMuscleMassKg(input),
        bmrKcal = split?.let { katchMcArdleKcal(it.fatFreeKg) },
        qualityOk = split != null && hydration != null && hydration in 0.60..0.80,
    )
}

// Fat-free mass → fat mass, body fat %, indices.
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

// How much of a weight change over the last [windowDays] was fat and how much fat-free mass. Single impedance readings
// are noisy, so each end is the mean of the two readings nearest that end of the window; needs 4+ readings spanning at least
// half the window. [readings] are (date, weight, split) for weigh-ins that passed the quality gate.
data class WeightChangeSplit(val deltaWeightKg: Double, val deltaFatKg: Double, val deltaFatFreeKg: Double, val spanDays: Long)

fun weightChangeSplit(
    readings: List<Triple<java.time.LocalDate, Double, CompositionSplit>>,
    windowDays: Long,
    asOf: java.time.LocalDate,
): WeightChangeSplit? {
    val inWindow = readings.filter { !it.first.isAfter(asOf) && !it.first.isBefore(asOf.minusDays(windowDays)) }.sortedBy { it.first }
    if (inWindow.size < 4) return null
    val span = java.time.temporal.ChronoUnit.DAYS.between(inWindow.first().first, inWindow.last().first)
    if (span < windowDays / 2) return null
    val start = inWindow.take(2)
    val end = inWindow.takeLast(2)
    fun mean(rows: List<Triple<java.time.LocalDate, Double, CompositionSplit>>, f: (Triple<java.time.LocalDate, Double, CompositionSplit>) -> Double) = rows.map(f).average()
    return WeightChangeSplit(
        deltaWeightKg = mean(end) { it.second } - mean(start) { it.second },
        deltaFatKg = mean(end) { it.third.fatKg } - mean(start) { it.third.fatKg },
        deltaFatFreeKg = mean(end) { it.third.fatFreeKg } - mean(start) { it.third.fatFreeKg },
        spanDays = span,
    )
}
