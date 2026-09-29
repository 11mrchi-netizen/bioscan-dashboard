package com.bioscan.fieldterminal.domain.aging

import com.bioscan.fieldterminal.domain.analysis.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PhenoAgeTest {

    private val provenance = Provenance("lab_results", null, null)
    private val observedAt = LocalDate.of(2026, 1, 14)

    // Fixtures hand-computed from the published Levine 2018 formula (see
    // PhenoAge.kt's header) via an independent script, not from this
    // implementation -- verifies the Kotlin matches the formula, not itself.
    @Test
    fun matchesHandComputedFixture() {
        val inputs = PhenoAgeInputs(
            albuminGDl = 4.5, creatinineMgDl = 1.0, glucoseMgDl = 95.0, crpMgDl = 0.1,
            lymphocytePercent = 30.0, mcvFl = 90.0, rdwPercent = 13.0, alkalinePhosphataseUL = 70.0, wbc10e3UL = 6.0,
        )
        val result = computePhenoAge(inputs, chronologicalAgeYears = 50.0, observedAt = observedAt, provenance = provenance)
        assertEquals(45.80, result.biologicalAge!!, 0.01)
        assertEquals(45.80 - 50.0, result.ageAcceleration!!, 0.01)
        assertTrue(result.isAvailable)
    }

    @Test
    fun matchesHandComputedFixtureForAHealthyYoungerProfile() {
        val inputs = PhenoAgeInputs(
            albuminGDl = 4.8, creatinineMgDl = 0.8, glucoseMgDl = 85.0, crpMgDl = 0.05,
            lymphocytePercent = 35.0, mcvFl = 88.0, rdwPercent = 12.5, alkalinePhosphataseUL = 60.0, wbc10e3UL = 5.5,
        )
        val result = computePhenoAge(inputs, chronologicalAgeYears = 25.0, observedAt = observedAt, provenance = provenance)
        assertEquals(14.98, result.biologicalAge!!, 0.01)
    }

    @Test
    fun missingAnyInputReturnsUnavailableNeverAGuess() {
        val incomplete = PhenoAgeInputs(
            albuminGDl = 4.7, creatinineMgDl = 1.15, glucoseMgDl = null /* missing */, crpMgDl = 0.287,
            lymphocytePercent = 18.9, mcvFl = 88.1, rdwPercent = 11.9, alkalinePhosphataseUL = 95.0, wbc10e3UL = 6.92,
        )
        val result = computePhenoAge(incomplete, chronologicalAgeYears = 40.0, observedAt = observedAt, provenance = provenance)
        assertNull(result.biologicalAge)
        assertNull(result.ageAcceleration)
        assertTrue(result.unavailableReason!!.contains("glucose"))
        assertEquals(0, result.confidence.have)
    }

    @Test
    fun allNineMarkersPresentGivesFullConfidence() {
        // This account's own real 2026-01-14 draw values (see
        // docs/user-profile-milestone -- audit confirmed this is the only
        // draw with all 9 markers together).
        val inputs = PhenoAgeInputs(
            albuminGDl = 4.7, creatinineMgDl = 1.15, glucoseMgDl = 84.0, crpMgDl = 0.287,
            lymphocytePercent = 18.9, mcvFl = 88.1, rdwPercent = 11.9, alkalinePhosphataseUL = 95.0, wbc10e3UL = 6.92,
        )
        val result = computePhenoAge(inputs, chronologicalAgeYears = 40.0, observedAt = observedAt, provenance = provenance)
        assertEquals(9, result.confidence.have)
        assertEquals(9, result.confidence.need)
        assertTrue(result.isAvailable)
    }
}
