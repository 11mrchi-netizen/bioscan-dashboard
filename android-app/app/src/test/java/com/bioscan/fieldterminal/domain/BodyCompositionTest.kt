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

    @Test
    fun katchMcArdle() {
        assertEquals(1666.0, katchMcArdleKcal(60.0), 1e-9)
    }
}
