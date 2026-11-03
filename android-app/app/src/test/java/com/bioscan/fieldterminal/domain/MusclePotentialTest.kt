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

    @Test
    fun withWristAndAnkleAManGetsButtsFrameCeiling() {
        // 180 cm, wrist 17.5 cm, ankle 22.5 cm at 10 % body fat: 181.2 lb = 82.19 kg (worked by hand in inches and pounds).
        assertEquals(82.1938, buttMaxLeanKg(180.0, 17.5, 22.5, 10.0)!!, 1e-3)
        val p = musclePotential(65.0, 180.0, true, frame = 17.5 to 22.5, bodyFatPct = 10.0)!!
        assertEquals(PotentialMethod.FRAME, p.method)
        assertEquals(65.0 / 82.1938, p.fraction, 1e-4)
        // Body fat is clamped to 25 %: at 40 % the ceiling is the 25 % one, not more.
        assertEquals(87.4626, musclePotential(65.0, 180.0, true, 17.5 to 22.5, 40.0)!!.ceilingFatFreeKg, 1e-3)
        // Unknown body fat: Butt's 10 % reference.
        assertEquals(82.1938, musclePotential(65.0, 180.0, true, 17.5 to 22.5, null)!!.ceilingFatFreeKg, 1e-3)
    }

    @Test
    fun withoutAFrameOrForWomenItFallsBackToFfmi() {
        assertEquals(PotentialMethod.FFMI, musclePotential(65.0, 180.0, true)!!.method)
        assertEquals(PotentialMethod.FFMI, musclePotential(45.0, 165.0, false, frame = 15.0 to 20.0, bodyFatPct = 25.0)!!.method)
        // Implausible girths: back to the FFMI ceiling.
        assertEquals(PotentialMethod.FFMI, musclePotential(65.0, 180.0, true, frame = 5.0 to 22.5)!!.method)
        assertNull(buttMaxLeanKg(180.0, 17.5, 60.0, 10.0))
    }

    @Test
    fun latestFrameTakesEachGirthFromTheCheckInThatLastHadIt() {
        val t = java.time.LocalDateTime.of(2026, 10, 1, 8, 0)
        fun rec(id: Long, days: Long, vararg m: Pair<BodySite, Double>) =
            BodyCheckinRecord(id, t.plusDays(days), measurements = m.map { (s, v) -> BodyMeasurement(s, BodySide.NONE, listOf(v)) })
        assertNull(latestFrame(listOf(rec(1, 0, BodySite.WRIST to 17.0))))
        val h = listOf(rec(1, 0, BodySite.WRIST to 17.0, BodySite.ANKLE to 22.0), rec(2, 30, BodySite.WRIST to 17.4), rec(3, 60))
        assertEquals(17.4 to 22.0, latestFrame(h))
    }

    @Test
    fun theFrameSitesAreAskedOnlyWhenWanted() {
        val off = activeSlots(tape = true, calipers = false, flexedArms = false, chestSkinfold = false)
        val on = activeSlots(tape = true, calipers = false, flexedArms = false, chestSkinfold = false, frame = true)
        assertEquals(emptyList<BodySite>(), off.map { it.site }.filter { it in FRAME_SITES })
        assertEquals(listOf(BodySite.WRIST, BodySite.ANKLE), on.map { it.site }.filter { it in FRAME_SITES })
    }
}
