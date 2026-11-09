package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiSlotInteractionTest {
    private val iron = InteractionRecord(id = 1, aKeys = emptySet(), aAliases = setOf("iron"), bKeys = emptySet(), bAliases = setOf("zinc"),
        triggerMode = "both_present", preferTogether = false, effect = "e", advice = "a", spacingHours = 2.0, severity = "minor",
        source = "s", pmidDoi = null, caveats = null)

    @Test
    fun theClosestPairOfTimesDecides() {
        val ironMorning = RosterItem(1, "Iron", "morning")
        val zincNight = RosterItem(2, "Zinc", "night")
        assertEquals(InteractionStatus.SPACED, detectInteractions(listOf(ironMorning, zincNight), listOf(iron)).single().status)
        // Zinc now also in the morning: the morning pair is in the same slot.
        val zincTwice = RosterItem(2, "Zinc", "morning", slots = listOf("morning", "night"))
        assertEquals(InteractionStatus.SAME_SLOT, detectInteractions(listOf(ironMorning, zincTwice), listOf(iron)).single().status)
    }
}
