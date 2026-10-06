package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SupplementEvidenceTest {

    private val claims = listOf(
        EvidenceClaim("boron", "Free-T ↑ ~10-15%*", reviewed = false),
        EvidenceClaim("zinc", "Supports T synthesis*", reviewed = false),
        EvidenceClaim("creatine", "Power output ↑", reviewed = true),
    )

    @Test
    fun onlyMatchingSubjectsAreIncluded() {
        assertEquals("Supports T synthesis* (unreviewed)", expectedOutcomeText(setOf("zinc"), claims))
    }

    @Test
    fun reviewedClaimsAreNotLabelledUnreviewed() {
        assertEquals("Power output ↑", expectedOutcomeText(setOf("creatine"), claims))
    }

    @Test
    fun multipleIngredientsJoinInRegistryOrder() {
        assertEquals(
            "Free-T ↑ ~10-15%* (unreviewed); Power output ↑",
            expectedOutcomeText(setOf("creatine", "boron"), claims),
        )
    }

    @Test
    fun duplicateClaimTextIsShownOnce() {
        val dup = claims + EvidenceClaim("boron", "Free-T ↑ ~10-15%*", reviewed = false)
        assertEquals("Free-T ↑ ~10-15%* (unreviewed)", expectedOutcomeText(setOf("boron"), dup))
    }

    @Test
    fun noKeysOrNoMatchIsEmptyNotInvented() {
        assertEquals("", expectedOutcomeText(emptySet(), claims))
        assertEquals("", expectedOutcomeText(setOf("magnesium"), claims))
    }
}
