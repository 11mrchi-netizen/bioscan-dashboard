package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class FitnessAgeTest {
    private val asOf = LocalDate.of(2026, 10, 31)

    @Test
    fun theReferencePersonHasTheirOwnAge() {
        assertEquals(40.0, fitnessAge(NES_MEN, 40.0, REFERENCE_PA, REFERENCE_RHR), 1e-9)
        // Fitter: lower resting HR and more activity read younger.
        assertEquals(40.0 - (0.226 * 10 + 0.155 * 15) / 0.296, fitnessAge(NES_MEN, 40.0, 15.0, 50.0), 1e-9)
    }

    @Test
    fun activityIsRebuiltFromLoggedSessions() {
        // Three 45-minute sessions a week at a breathless effort: 2.5 x 2 x 0.75.
        val s = (0 until 12).map { ActivitySession(asOf.minusDays(it * 2L), 45.0, 150.0, null) }
        val pa = paIndex(s, asOf, restingHr = 50.0, maxHr = 190.0)
        assertEquals(3.0, pa.perWeek, 1e-9)
        assertEquals(3.75, pa.value, 1e-9)
        assertEquals(0.0, paIndex(emptyList(), asOf, 50.0, 190.0).value, 1e-9)
    }

    @Test
    fun aWeekNeedsFourRestingHeartRates() {
        val birth = LocalDate.of(1985, 10, 31)
        val rhr = (0..2).associate { asOf.minusDays(it.toLong()) to 55.0 }
        assertNull(fitnessAgeOn(asOf, true, birth, rhr, emptyList(), null, null))
        val r = fitnessAgeOn(asOf, true, birth, rhr + (asOf.minusDays(3) to 55.0), emptyList(), null, 85.0)!!
        assertEquals(55.0, r.restingHr, 1e-9)
        assertEquals(nesVo2(NES_MEN, r.age, 0.0, 85.0, 55.0), r.vo2peak!!, 1e-9)
        assertEquals(asOf, fitnessAgeWeeks(asOf.plusDays(3), 1).single())
    }
}
