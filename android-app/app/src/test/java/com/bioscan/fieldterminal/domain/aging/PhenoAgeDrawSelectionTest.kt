package com.bioscan.fieldterminal.domain.aging

import com.bioscan.fieldterminal.domain.analysis.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Mirrors exactly what AgingProfileRepository.loadOverview() does: compute
// PhenoAge per draw, then take the latest *available* one as the headline --
// not simply the latest draw.
class PhenoAgeDrawSelectionTest {

    private fun fullDraw(id: Long, date: LocalDate) = LabDraw(
        id = id, date = date,
        markerValues = PHENOAGE_REQUIRED_MARKERS.associateWith { 1.0 },
        markerIds = emptyMap(),
    )

    @Test
    fun realAccountCaseSkipsTheIncompleteLaterDrawForTheHeadline() {
        // This account's real history: 2026-01-14 has all 9 markers,
        // 2026-04-25 is missing alkaline phosphatase/glucose/WBC. The
        // headline must be the earlier, complete draw -- picking "latest"
        // naively would wrongly surface an unavailable result.
        val complete = fullDraw(1, LocalDate.of(2026, 1, 14))
        val incomplete = LabDraw(
            id = 2, date = LocalDate.of(2026, 4, 25),
            markerValues = (PHENOAGE_REQUIRED_MARKERS - setOf("Alkaline phosphatase", "Fasting blood sugar", "WBC")).associateWith { 1.0 },
            markerIds = emptyMap(),
        )

        val history = listOf(complete, incomplete).sortedBy { it.date }.map {
            computePhenoAge(it.toPhenoAgeInputs(), chronologicalAgeYears = 40.0, observedAt = it.date, provenance = Provenance("lab_results", null, null))
        }

        assertFalse(history.last().isAvailable) // the later draw is genuinely incomplete
        val headline = history.lastOrNull { it.isAvailable } ?: history.lastOrNull()
        assertEquals(complete.date, headline!!.observedAt)
        assertTrue(headline.isAvailable)
    }

    @Test
    fun mostRecentCompleteDrawWinsWhenMultipleAreComplete() {
        val older = fullDraw(1, LocalDate.of(2025, 1, 1))
        val newer = fullDraw(2, LocalDate.of(2026, 1, 1))
        val history = listOf(older, newer).map {
            computePhenoAge(it.toPhenoAgeInputs(), chronologicalAgeYears = 40.0, observedAt = it.date, provenance = Provenance("lab_results", null, null))
        }
        val headline = history.lastOrNull { it.isAvailable }
        assertEquals(newer.date, headline!!.observedAt)
    }
}
