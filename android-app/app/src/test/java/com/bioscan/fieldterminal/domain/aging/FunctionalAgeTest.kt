package com.bioscan.fieldterminal.domain.aging

import com.bioscan.fieldterminal.domain.analysis.Provenance
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionalAgeTest {

    private val provenance = Provenance("wearable_daily", null, null)
    private val observedAt = LocalDate.of(2026, 1, 14)

    @Test
    fun exactTableValueReturnsThatBracketsMidpoint() {
        // A man with exactly the 40-49 bracket's median VO2max (37.8) should
        // resolve to that bracket's midpoint age, 44.5.
        val result = cardioFunctionalAge(vo2max = 37.8, sex = "male", chronologicalAgeYears = 30.0, observedAt = observedAt, provenance = provenance)
        assertEquals(44.5, result.biologicalAge!!, 0.01)
        assertEquals(44.5 - 30.0, result.ageAcceleration!!, 0.01)
    }

    @Test
    fun interpolatesBetweenBrackets() {
        // Halfway between the 20-29 bracket (48.0 @ 24.5) and 30-39 (42.4 @
        // 34.5) in VO2max terms should land halfway in age terms too.
        val midpointVo2max = (48.0 + 42.4) / 2
        val result = cardioFunctionalAge(vo2max = midpointVo2max, sex = "male", chronologicalAgeYears = 30.0, observedAt = observedAt, provenance = provenance)
        assertEquals((24.5 + 34.5) / 2, result.biologicalAge!!, 0.01)
    }

    @Test
    fun fitterThanYoungestBracketClampsNotExtrapolates() {
        // 59.1 ml/kg/min is a real account's own measured VO2max -- well
        // above the men's 20-29 median (48.0), but far from a fabricated
        // "unavailable" case (a lot of trained runners/cyclists in their
        // 20s-30s land here). Clamps to the youngest tabulated age, 24.5.
        val result = cardioFunctionalAge(vo2max = 60.0, sex = "male", chronologicalAgeYears = 25.0, observedAt = observedAt, provenance = provenance)
        assertEquals(24.5, result.biologicalAge!!, 0.01)
    }

    @Test
    fun belowOldestBracketClampsNotExtrapolates() {
        val result = cardioFunctionalAge(vo2max = 10.0, sex = "female", chronologicalAgeYears = 80.0, observedAt = observedAt, provenance = provenance)
        assertEquals(74.5, result.biologicalAge!!, 0.01)
    }

    @Test
    fun unknownSexIsUnavailableNotGuessed() {
        val result = cardioFunctionalAge(vo2max = 40.0, sex = null, chronologicalAgeYears = 35.0, observedAt = observedAt, provenance = provenance)
        assertNull(result.biologicalAge)
        assertTrue(result.unavailableReason!!.contains("Sex"))
    }

    @Test
    fun menAndWomenTablesAreIndependent() {
        // Same VO2max, different sex -> different Cardio Age, since the
        // reference tables differ (never falls back to one table for both).
        val menResult = cardioFunctionalAge(vo2max = 30.2, sex = "male", chronologicalAgeYears = 35.0, observedAt = observedAt, provenance = provenance)
        val womenResult = cardioFunctionalAge(vo2max = 30.2, sex = "female", chronologicalAgeYears = 35.0, observedAt = observedAt, provenance = provenance)
        assertEquals(34.5, womenResult.biologicalAge!!, 0.01) // exact women's 30-39 median
        assertTrue(menResult.biologicalAge!! > womenResult.biologicalAge) // same VO2max reads far older for a man
    }
}
