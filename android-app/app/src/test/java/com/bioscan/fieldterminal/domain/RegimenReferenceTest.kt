package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegimenReferenceTest {
    @Test
    fun namesBecomeTheWordsALabelWouldUse() {
        assertEquals(listOf("zinc picolinate", "zinc", "picolinate"), mentionTerms("Zinc Picolinate (Swanson)"))
        assertEquals(listOf("triple magnesium complex", "magnesium"), mentionTerms("Triple Magnesium Complex (Swanson)"))
        assertEquals(listOf("omega 3 fish oil", "omega", "fish"), mentionTerms("Omega 3 Fish Oil (Now Foods)"))
    }

    @Test
    fun aLabelNamingAnotherItemIsReportedWithItsSentence() {
        val sections = mapOf(
            "drug_interactions" to "Tadalafil can potentiate the hypotensive effects of nitrates, alpha-blockers or alcohol. " +
                "Antacids (magnesium hydroxide/aluminum hydroxide) reduce the rate of absorption.",
            "warnings" to "Nothing about zincite here.",
        )
        val m = labelMentions(sections, listOf(1L to "Triple Magnesium Complex (Swanson)", 2L to "Zinc Picolinate", 3L to "Creatine"))
        assertEquals(listOf(1L), m.map { it.itemId })
        assertEquals("Drug interactions", m.single().section)
        assertTrue(m.single().excerpt.contains("magnesium hydroxide"))
        val z = labelMentions(mapOf("warnings" to "Take apart from zinc supplements."), listOf(2L to "Zinc Picolinate"))
        assertEquals("zinc", z.single().term)
        assertTrue(labelMentions(mapOf("warnings" to "zincite"), listOf(2L to "Zinc Picolinate")).isEmpty())
    }

    @Test
    fun halfLivesReadInTheirNaturalUnit() {
        assertEquals("17.5 hours", halfLifeWords(17.5))
        assertEquals("8 days", halfLifeWords(192.0))
        assertEquals("1 week", halfLifeWords(168.0))
        assertEquals("30 minutes", halfLifeWords(0.5))
    }
}
