package com.bioscan.fieldterminal.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

// Fitness Age (ME › BODY): a fitness comparison in years, not a biological age. Built on the HUNT non-exercise VO2peak
// model (Nes 2011, Med Sci Sports Exerc 43:2024; waist variant, coefficients as reproduced in PMC7428991 and used by NOOP):
//
//   men    VO2peak = 100.27 - 0.296 age + 0.226 PA - 0.369 waist(cm) - 0.155 RHR    (SEE 5.70 ml/kg/min)
//   women  VO2peak =  74.74 - 0.247 age + 0.198 PA - 0.259 waist(cm) - 0.114 RHR    (SEE 5.14)
//
// PA is HUNT's physical-activity index, frequency x intensity x duration (Kurtze 2008 coding: frequency 0 / 1 / 2.5 / 5
// for under weekly / weekly / 2-3 a week / almost daily, intensity 1 / 2 / 3 for easy / breathless / near exhaustion,
// duration 0.1 / 0.38 / 0.75 / 1.0 for under 15 / 15-29 / 30-60 / over 60 minutes). It is rebuilt here from the
// sessions actually logged in the 28 days before, not asked.
//
// Fitness Age is the age at which a reference person (resting HR 65, PA index 5, the same waist) would have your
// estimated VO2peak, so the waist term cancels and the age needs only resting HR and activity. The VO2peak figure itself
// needs a waist measurement. Shown in a band of +/- 5 years, weekly (each Saturday), from 7-day median resting HR.

data class NesCoefficients(val intercept: Double, val age: Double, val pa: Double, val waist: Double, val rhr: Double, val see: Double)

val NES_MEN = NesCoefficients(100.27, 0.296, 0.226, 0.369, 0.155, 5.70)
val NES_WOMEN = NesCoefficients(74.74, 0.247, 0.198, 0.259, 0.114, 5.14)

const val REFERENCE_RHR = 65.0
const val REFERENCE_PA = 5.0
const val FITNESS_AGE_BAND_YEARS = 5

data class ActivitySession(val date: LocalDate, val minutes: Double, val avgHr: Double?, val rpe: Int?)

/** The PA index from the 28 days before [asOf], with how each part was read. */
data class PaIndex(val value: Double, val perWeek: Double, val frequency: Double, val intensity: Double, val duration: Double)

fun paIndex(sessions: List<ActivitySession>, asOf: LocalDate, restingHr: Double, maxHr: Double): PaIndex {
    val window = sessions.filter { it.date.isAfter(asOf.minusDays(28)) && !it.date.isAfter(asOf) && it.minutes >= 10 }
    val perWeek = window.size / 4.0
    val frequency = when {
        perWeek < 0.75 -> 0.0
        perWeek < 1.75 -> 1.0
        perWeek < 4.5 -> 2.5
        else -> 5.0
    }
    if (window.isEmpty()) return PaIndex(0.0, 0.0, 0.0, 1.0, 0.1)
    val intensities = window.map { s ->
        val hrr = s.avgHr?.takeIf { maxHr > restingHr }?.let { (it - restingHr) / (maxHr - restingHr) }
        when {
            hrr != null -> if (hrr < 0.5) 1.0 else if (hrr < 0.75) 2.0 else 3.0
            s.rpe != null -> if (s.rpe <= 3) 1.0 else if (s.rpe <= 6) 2.0 else 3.0
            else -> 2.0
        }
    }.sorted()
    val intensity = intensities[intensities.size / 2]
    val minutes = window.map { it.minutes }.sorted()[window.size / 2]
    val duration = when {
        minutes < 15 -> 0.1
        minutes < 30 -> 0.38
        minutes <= 60 -> 0.75
        else -> 1.0
    }
    return PaIndex(frequency * intensity * duration, perWeek, frequency, intensity, duration)
}

data class FitnessAgeReading(
    val asOf: LocalDate,
    val fitnessAge: Double,
    val age: Double,
    val restingHr: Double,
    val pa: PaIndex,
    /** Only with a waist measurement. */
    val vo2peak: Double?,
    val see: Double,
)

fun nesVo2(c: NesCoefficients, age: Double, pa: Double, waistCm: Double, rhr: Double): Double =
    c.intercept - c.age * age + c.pa * pa - c.waist * waistCm - c.rhr * rhr

/** The age at which the reference person matches your estimate: the waist term cancels. */
fun fitnessAge(c: NesCoefficients, age: Double, pa: Double, rhr: Double): Double =
    age - (c.pa * (pa - REFERENCE_PA) - c.rhr * (rhr - REFERENCE_RHR)) / c.age

/**
 * Fitness Age on [asOf]: needs resting HR on at least 4 of the 7 days before. [maxHr] is the highest session maximum of
 * the year, else 208 - 0.7 x age (Tanaka 2001).
 */
fun fitnessAgeOn(
    asOf: LocalDate,
    isMale: Boolean,
    birth: LocalDate,
    restingHr: Map<LocalDate, Double>,
    sessions: List<ActivitySession>,
    maxHr: Double?,
    waistCm: Double?,
): FitnessAgeReading? {
    val week = (0..6).mapNotNull { restingHr[asOf.minusDays(it.toLong())] }.sorted()
    if (week.size < 4) return null
    val rhr = if (week.size % 2 == 1) week[week.size / 2] else (week[week.size / 2 - 1] + week[week.size / 2]) / 2
    val age = java.time.temporal.ChronoUnit.DAYS.between(birth, asOf) / 365.25
    val c = if (isMale) NES_MEN else NES_WOMEN
    val hrMax = maxHr ?: (208 - 0.7 * age)
    val pa = paIndex(sessions, asOf, rhr, hrMax)
    return FitnessAgeReading(asOf, fitnessAge(c, age, pa.value, rhr), age, rhr, pa, waistCm?.let { nesVo2(c, age, pa.value, it, rhr) }, c.see)
}

/** The Saturdays from [weeks] weeks back to the latest one on or before [today]. */
fun fitnessAgeWeeks(today: LocalDate, weeks: Int): List<LocalDate> {
    val last = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
    return (weeks - 1 downTo 0).map { last.minusWeeks(it.toLong()) }
}

/** "34 (29-39)". */
fun fitnessAgeWords(r: FitnessAgeReading): String {
    val a = r.fitnessAge.roundToInt()
    return "$a (${a - FITNESS_AGE_BAND_YEARS}–${a + FITNESS_AGE_BAND_YEARS})"
}
