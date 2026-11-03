package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusclePotentialTest {
    @Test
    fun theCeilingIsANormalisedFfmiOf25ForMen() {
        // At 1.80 m normalisation is zero: the ceiling is 25 × 1.8² = 81 kg of fat-free mass.
        val p = musclePotential(fatFreeKg = 64.8, heightCm = 180.0, isMale = true)!!
        assertEquals(81.0, p.ceilingFatFreeKg, 1e-9)
        assertEquals(0.8, p.fraction, 1e-9)
        assertEquals(20.0, p.normalizedFfmi, 1e-9)
    }

    @Test
    fun shorterPeopleHaveALowerRawCeilingAndWomenAnotherOne() {
        val short = musclePotential(58.6, 170.0, true)!!
        // (25 − 6.1 × 0.1) × 1.7² = 24.39 × 2.89
        assertEquals(24.39 * 2.89, short.ceilingFatFreeKg, 1e-6)
        val woman = musclePotential(45.0, 165.0, false)!!
        assertEquals(22.0, woman.ceilingFfmi, 1e-9)
        assertEquals(1.0, musclePotential(200.0, 180.0, true)!!.fraction, 1e-9) // capped
        assertNull(musclePotential(60.0, 0.0, true))
    }
}
