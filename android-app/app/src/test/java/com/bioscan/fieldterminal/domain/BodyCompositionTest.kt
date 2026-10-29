package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BodyCompositionTest {
    // The captured weigh-in: 75.0 kg, 503 ohm, with a 180 cm, 30-year-old man.
    private val man = BodyCompositionInput(weightKg = 75.0, impedanceOhm = 503.0, heightCm = 180.0, ageYears = 30, isMale = true)

    @Test
    fun bmiAndImpedanceIndex() {
        assertEquals(23.148, bmi(75.0, 180.0)!!, 0.001)
        assertEquals(64.4135, impedanceIndex(180.0, 503.0)!!, 0.001)
    }

    @Test
    fun outOfRangeInputsAreRejected() {
        assertNull(bmi(75.0, 90.0))
        assertNull(impedanceIndex(180.0, 10.0))
        assertNull(skeletalMuscleMassKg(man.copy(ageYears = 12)))
    }

    @Test
    fun healthyWeightRange() {
        val r = healthyWeightRangeKg(180.0)!!
        assertEquals(59.94, r.start, 0.01)
        assertEquals(81.0, r.endInclusive, 0.01)
    }

    // Janssen 2000 by hand: 32400/503 × 0.401 + 3.825 − 30 × 0.071 + 5.102 = 32.627
    @Test
    fun skeletalMuscleMassMatchesJanssen() {
        assertEquals(32.627, skeletalMuscleMassKg(man)!!, 0.01)
        // Woman: no sex term. 165 cm, 580 ohm, 40 y: 27225/580 × 0.401 − 2.84 + 5.102 = 21.085
        val woman = BodyCompositionInput(62.0, 580.0, 165.0, 40, isMale = false)
        assertEquals(21.085, skeletalMuscleMassKg(woman)!!, 0.01)
    }

    @Test
    fun splitFromFatFreeMass() {
        val s = compositionFromFatFreeMass(75.0, 180.0, 60.0)
        assertNotNull(s)
        assertEquals(15.0, s!!.fatKg, 1e-9)
        assertEquals(20.0, s.bodyFatPct, 1e-9)
        assertEquals(18.519, s.ffmi, 0.001)
        assertEquals(4.630, s.fmi, 0.001)
        // Fat-free mass above body weight, or an implausible body fat %, is not a result.
        assertNull(compositionFromFatFreeMass(75.0, 180.0, 76.0))
        assertNull(compositionFromFatFreeMass(75.0, 180.0, 20.0))
    }

    // Sun 2003 Table 5 by hand: man S = 32400/503 = 64.4135 -> FFM -10.68 + 41.869 + 19.5 + 10.06 = 60.749; TBW 1.2 + 28.986 + 13.5 = 43.686
    @Test
    fun sunEquationsMale() {
        assertEquals(60.749, fatFreeMassKg(man)!!, 0.01)
        assertEquals(43.686, totalBodyWaterL(man)!!, 0.01)
    }

    // Woman 165 cm, 580 ohm, 62 kg: S = 46.9397 -> FFM -9.53 + 32.388 + 10.54 + 11.6 = 44.998; TBW 3.75 + 21.123 + 6.82 = 31.693
    @Test
    fun sunEquationsFemale() {
        val woman = BodyCompositionInput(62.0, 580.0, 165.0, 40, isMale = false)
        assertEquals(44.998, fatFreeMassKg(woman)!!, 0.01)
        assertEquals(31.693, totalBodyWaterL(woman)!!, 0.01)
    }

    @Test
    fun fullCompositionForTheCapturedWeighIn() {
        val c = bodyComposition(man)
        assertEquals(19.0, c.split!!.bodyFatPct, 0.05)
        assertEquals(14.251, c.split!!.fatKg, 0.01)
        assertEquals(1682.2, c.bmrKcal!!, 0.5) // 370 + 21.6 * 60.749
        assertEquals(true, c.qualityOk) // water / FFM = 0.719
        assertNull(fatFreeMassKg(man.copy(heightCm = 90.0)))
    }

    @Test
    fun implausibleHydrationFailsQuality() {
        // A 50 ohm contact glitch makes the equation return a fat-free mass above body weight: no split, quality false.
        val glitch = bodyComposition(man.copy(impedanceOhm = 50.0))
        assertNull(glitch.split)
        assertEquals(false, glitch.qualityOk)
    }

    @Test
    fun weightChangeSplitUsesTheEndsOfTheWindow() {
        val day0 = java.time.LocalDate.of(2026, 9, 1)
        // 80 kg / 20 kg fat -> 78 kg / 17 kg fat over 40 days (fat-free 60 -> 61).
        fun r(d: Long, w: Double, fat: Double) = Triple(day0.plusDays(d), w, CompositionSplit(w - fat, fat, fat / w * 100, 0.0, 0.0))
        val readings = listOf(r(0, 80.0, 20.0), r(2, 80.0, 20.0), r(20, 79.0, 18.5), r(38, 78.0, 17.0), r(40, 78.0, 17.0))
        val s = weightChangeSplit(readings, windowDays = 45, asOf = day0.plusDays(40))!!
        assertEquals(-2.0, s.deltaWeightKg, 1e-9)
        assertEquals(-3.0, s.deltaFatKg, 1e-9)
        assertEquals(1.0, s.deltaFatFreeKg, 1e-9)
        assertNull(weightChangeSplit(readings.take(3), 45, day0.plusDays(40))) // too few readings
        assertNull(weightChangeSplit(readings, windowDays = 200, asOf = day0.plusDays(40))) // span under half the window
    }

    @Test
    fun katchMcArdle() {
        assertEquals(1666.0, katchMcArdleKcal(60.0), 1e-9)
    }
}
