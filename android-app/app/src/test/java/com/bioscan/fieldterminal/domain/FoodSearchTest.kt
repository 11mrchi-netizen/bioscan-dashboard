package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FoodSearchTest {

    @Test
    fun testSingleWordQueryUnaffected() {
        // Manual single-word searches already worked -- must stay identical.
        assertEquals(listOf("egg"), foodSearchTokens("egg"))
    }

    @Test
    fun testAiDescriptionSplitsIntoSignificantWords() {
        assertEquals(
            listOf("Grilled", "chicken", "breast"),
            foodSearchTokens("Grilled chicken breast with steamed broccoli"),
        )
    }

    @Test
    fun testStopwordsDroppedEvenWhenLongEnough() {
        // "with" (4 chars) and "and" (3 chars) both clear the length floor --
        // must be dropped by the stopword list specifically, not by length.
        assertEquals(listOf("chicken", "rice", "beans"), foodSearchTokens("chicken with rice and beans"))
    }

    @Test
    fun testShortWordsDropped() {
        assertEquals(listOf("egg"), foodSearchTokens("a an egg"))
    }

    @Test
    fun testBlankQueryReturnsNoTokens() {
        assertEquals(emptyList<String>(), foodSearchTokens("   "))
    }
}
