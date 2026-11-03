package com.bioscan.fieldterminal.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RegistryReviewStateTest {
    private fun review(verdict: String, version: Int?) = RegistryReviewRow("interaction", 1, version, verdict, null, "2026-11-03T10:00:00Z")

    @Test
    fun theOwnersVerdictDecidesAndAnEditedRowAsksAgain() {
        assertEquals(ReviewState.UNREVIEWED, reviewState("unreviewed", 1, null))
        assertEquals(ReviewState.CONFIRMED, reviewState("reviewed", 1, null))
        assertEquals(ReviewState.CONFIRMED, reviewState("unreviewed", 1, review("confirmed", 1)))
        assertEquals(ReviewState.DISPUTED, reviewState("reviewed", 1, review("disputed", 1)))
        // The row changed since it was confirmed: back to unreviewed.
        assertEquals(ReviewState.UNREVIEWED, reviewState("unreviewed", 2, review("confirmed", 1)))
    }
}
