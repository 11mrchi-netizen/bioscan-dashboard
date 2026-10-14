package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementInteractionsTest {

    private val calciumIron = InteractionRecord(
        id = 1, aKeys = setOf("calcium"), aAliases = setOf("calcium"),
        bKeys = setOf("iron"), bAliases = setOf("iron", "ferrous"),
        effect = "Calcium cuts iron absorption", advice = "Space them", spacingHours = 2.0, severity = "minor",
    )
    private val fluoroquinolone = InteractionRecord(
        id = 2, aKeys = setOf("calcium", "iron", "magnesium"), aAliases = setOf("calcium", "iron", "magnesium"),
        bAliases = setOf("ciprofloxacin", "cipro"),
        effect = "Chelation lowers antibiotic absorption", advice = "6 h apart", spacingHours = 6.0, severity = "major",
    )
    private val zincNoCopper = InteractionRecord(
        id = 3, aKeys = setOf("zinc"), aAliases = setOf("zinc"), bKeys = setOf("copper"), bAliases = setOf("copper"),
        triggerMode = "a_without_b", effect = "Long-term zinc depletes copper", advice = "Check copper", severity = "moderate",
    )
    private val vitCIron = InteractionRecord(
        id = 4, aKeys = setOf("vitamin_c"), aAliases = setOf("vitamin c", "ascorbic"),
        bKeys = setOf("iron"), bAliases = setOf("iron"),
        preferTogether = true, effect = "Vitamin C helps iron absorption", advice = "Take together", severity = "info",
    )
    private val zincIron = InteractionRecord(
        id = 5, aKeys = setOf("zinc"), aAliases = setOf("zinc"), bKeys = setOf("iron"), bAliases = setOf("iron"),
        effect = "Zinc in excess lowers iron absorption", advice = "Mind the ratio", severity = "minor",
    )

    private fun item(id: Long, name: String, slot: String, n: Int? = null, keys: Set<String> = emptySet()) =
        RosterItem(id, name, slot, n, keys)

    @Test
    fun sameSlotPairIsFlagged() {
        val out = detectInteractions(listOf(item(1, "Calcium citrate", "morning"), item(2, "Iron bisglycinate", "morning")), listOf(calciumIron))
        assertEquals(1, out.size)
        assertEquals(InteractionStatus.SAME_SLOT, out[0].status)
        assertTrue(out[0].needsAttention)
    }

    @Test
    fun spacingIsRespectedWhenSlotsAreFarEnough() {
        // morning 08:00 vs afternoon 13:00 = 5 h >= 2 h
        val out = detectInteractions(listOf(item(1, "Calcium", "morning"), item(2, "Iron", "afternoon")), listOf(calciumIron))
        assertEquals(InteractionStatus.SPACED, out.single().status)
        assertFalse(out.single().needsAttention)
        assertEquals(5.0, out.single().gapHours!!, 1e-9)
    }

    @Test
    fun adjacentSlotsCloserThanTheAdvisedGapAreTooClose() {
        // morning vs afternoon = 5 h < 6 h advised for the antibiotic
        val out = detectInteractions(listOf(item(1, "Magnesium glycinate", "morning"), item(2, "Ciprofloxacin", "afternoon")), listOf(fluoroquinolone))
        assertEquals(InteractionStatus.TOO_CLOSE, out.single().status)
    }

    @Test
    fun gapWrapsAroundMidnight() {
        // night 21:00 to morning 08:00 is 11 h the short way, not 13 h.
        assertEquals(11.0, slotGapHours("night", "morning")!!, 1e-9)
        assertNull(slotGapHours("night", "as-needed"))
    }

    @Test
    fun asNeededItemsHaveUnknownTiming() {
        val out = detectInteractions(listOf(item(1, "Calcium", "morning"), item(2, "Iron", "as-needed")), listOf(calciumIron))
        assertEquals(InteractionStatus.TIMING_UNKNOWN, out.single().status)
    }

    @Test
    fun nameMatchingUsesWholeWordsOnly() {
        // "Ironwood" must not match iron; "Ferrous sulfate" matches the iron alias.
        val none = detectInteractions(listOf(item(1, "Calcium", "morning"), item(2, "Ironwood bark", "morning")), listOf(calciumIron))
        assertTrue(none.isEmpty())
        val hit = detectInteractions(listOf(item(1, "Calcium", "morning"), item(2, "Ferrous sulfate", "morning")), listOf(calciumIron))
        assertEquals(1, hit.size)
    }

    @Test
    fun ingredientKeysMatchAProductWhoseNameSaysNothing() {
        val out = detectInteractions(
            listOf(item(1, "Daily Multi", "morning", keys = setOf("iron")), item(2, "Bone Blend", "morning", keys = setOf("calcium"))),
            listOf(calciumIron),
        )
        assertEquals(1, out.size)
    }

    @Test
    fun oneProductHoldingBothSidesIsNotFlaggedAgainstItself() {
        val out = detectInteractions(listOf(item(1, "Multi", "morning", keys = setOf("calcium", "iron"))), listOf(calciumIron))
        assertTrue(out.isEmpty())
    }

    @Test
    fun aWithoutBFlagsOnlyWhenNoRosterItemCoversB() {
        val lonely = detectInteractions(listOf(item(1, "Zinc picolinate", "morning")), listOf(zincNoCopper))
        assertEquals(InteractionStatus.MISSING_PARTNER, lonely.single().status)
        assertNull(lonely.single().b)

        val covered = detectInteractions(listOf(item(1, "Zinc picolinate", "morning"), item(2, "Copper", "night")), listOf(zincNoCopper))
        assertTrue(covered.isEmpty())

        val oneFormula = detectInteractions(listOf(item(1, "ZMA", "night", keys = setOf("zinc", "copper"))), listOf(zincNoCopper))
        assertTrue(oneFormula.isEmpty())
    }

    @Test
    fun helpfulPairingShowsOnlyInTheSameSlot() {
        val together = detectInteractions(listOf(item(1, "Vitamin C 500 mg", "morning"), item(2, "Iron", "morning")), listOf(vitCIron))
        assertEquals(InteractionStatus.TOGETHER_OK, together.single().status)
        assertFalse(together.single().needsAttention)

        val apart = detectInteractions(listOf(item(1, "Vitamin C 500 mg", "morning"), item(2, "Iron", "night")), listOf(vitCIron))
        assertTrue(apart.isEmpty())
    }

    @Test
    fun noSpacingMeansTheWarningAppliesInAnySlot() {
        val out = detectInteractions(listOf(item(1, "Zinc", "morning"), item(2, "Iron", "night")), listOf(zincIron))
        assertEquals(InteractionStatus.ALWAYS, out.single().status)
    }

    @Test
    fun intermittentItemIsMarked() {
        val out = detectInteractions(listOf(item(1, "Calcium", "morning", n = 2), item(2, "Iron", "morning")), listOf(calciumIron))
        assertTrue(out.single().intermittent)
        assertTrue(interactionStatusText(out.single()).endsWith("(only on days you take both)"))
    }

    @Test
    fun attentionComesFirstThenSeverity() {
        val roster = listOf(
            item(1, "Calcium", "morning"), item(2, "Iron", "afternoon"), // SPACED (minor)
            item(3, "Magnesium", "morning"), item(4, "Ciprofloxacin", "afternoon"), // TOO_CLOSE (major)
        )
        val out = detectInteractions(roster, listOf(calciumIron, fluoroquinolone))
        // The antibiotic pair needs attention; calcium + iron is spaced. The antibiotic
        // rule also catches calcium and iron against the drug, so check the head only.
        assertTrue(out.first().needsAttention)
        assertEquals("major", out.first().record.severity)
        assertFalse(out.last().needsAttention)
    }

    @Test
    fun unreviewedEntriesAreAlwaysLabelled() {
        assertEquals("Calcium cuts iron absorption (unreviewed)", interactionEffectText(calciumIron))
        assertEquals("Calcium cuts iron absorption", interactionEffectText(calciumIron.copy(reviewed = true)))
    }
}
