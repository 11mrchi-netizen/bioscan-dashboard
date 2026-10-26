package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.MealRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealGlycaemicLoadTest {
    private fun meal(at: String, carbs: Double?, fibre: Double? = 0.0, sugar: Double? = 0.0, protein: Double? = 0.0, fat: Double? = 0.0) =
        MealRow(loggedAt = "2026-10-26T${at}:00+08:00", carbsG = carbs, fiberG = fibre, sugarG = sugar, proteinG = protein, fatG = fat, description = "m$at")

    @Test
    fun mealsWithinNinetyMinutesShareOneRise() {
        val groups = groupMeals(listOf(meal("12:00", 10.0), meal("07:00", 10.0), meal("07:50", 10.0), meal("13:30", 10.0), meal("15:01", 10.0)))
        assertEquals(listOf(2, 2, 1), groups.map { it.size })
    }

    @Test
    fun aCarbHeavyBareMealScoresHigherThanABalancedOne() {
        val bare = estimateMealResponses(listOf(meal("08:00", 80.0, fibre = 2.0, sugar = 50.0, protein = 5.0, fat = 2.0))).single()
        val balanced = estimateMealResponses(listOf(meal("08:00", 80.0, fibre = 12.0, sugar = 10.0, protein = 35.0, fat = 25.0))).single()
        assertEquals(GlycaemicBand.High, bare.band)
        assertTrue(balanced.score!! < bare.score!!)
        assertEquals(GlycaemicBand.Moderate, balanced.band)
        assertEquals(ConfidenceLevel.Medium, balanced.confidence)
    }

    @Test
    fun unloggedFibreIsUnknownNotZero() {
        val e = estimateMealResponses(listOf(meal("08:00", 30.0, fibre = null, sugar = 0.0, protein = 0.0, fat = 0.0))).single()
        assertEquals(listOf("fibre"), e.missing)
        assertEquals(30.0 * ASSUMED_GI, e.score!!, 1e-9)  // fibre not subtracted, no fibre credit
        assertEquals(ConfidenceLevel.Low, e.confidence)
    }

    @Test
    fun noCarbsMeansNoEstimate() {
        val e = estimateMealResponses(listOf(meal("08:00", null))).single()
        assertNull(e.band)
        assertNull(e.score)
        assertTrue("carbs" in e.missing)
    }

    @Test
    fun anOrdinaryMixedMealIsModerateNotHigh() {
        // Replica over the 272 logged meals with carbs: 14 % LOW, 49 % MODERATE, 37 % HIGH (median score 17.5).
        // 62 g carbs (the average logged meal), 5 g fibre, 12 g sugar, 25 g protein, 20 g fat.
        val e = estimateMealResponses(listOf(meal("12:30", 62.0, fibre = 5.0, sugar = 12.0, protein = 25.0, fat = 20.0))).single()
        assertEquals(GlycaemicBand.Moderate, e.band)
        // The same carbs on their own, mostly sugar, climb to High.
        val bare = estimateMealResponses(listOf(meal("12:30", 62.0, fibre = 1.0, sugar = 40.0, protein = 2.0, fat = 1.0))).single()
        assertEquals(GlycaemicBand.High, bare.band)
    }

    @Test
    fun lowCarbMealIsLow() {
        assertEquals(GlycaemicBand.Low, estimateMealResponses(listOf(meal("08:00", 9.0))).single().band)
    }

    @Test
    fun noMealsNoEstimates() {
        assertEquals(emptyList<MealGlycaemicEstimate>(), estimateMealResponses(emptyList()))
    }
}
