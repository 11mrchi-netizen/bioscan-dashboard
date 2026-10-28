package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.MealRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class NutritionRedesignTest {
    private val d = LocalDate.of(2026, 10, 1)

    @Test
    fun movesNeedFourPointsAndBeyondTolerance() {
        assertNull(macroMove(listOf(1.0, 2.0, 3.0)))
        assertEquals(MacroMove.Growing, macroMove(listOf(1.0, 1.0, 1.5, 1.5)))
        assertEquals(MacroMove.Shrinking, macroMove(listOf(2.0, 2.0, 1.0, 1.0)))
        assertEquals(MacroMove.Steady, macroMove(listOf(2.0, 2.0, 2.05, 2.05)))
    }

    @Test
    fun weightInForceIsTheLatestOnOrBefore() {
        val w = listOf(d to 80.0, d.plusDays(5) to 78.0)
        assertEquals(80.0, weightOn(d.plusDays(2), w)!!, 0.0)
        assertEquals(78.0, weightOn(d.plusDays(5), w)!!, 0.0)
        assertEquals(80.0, weightOn(d.minusDays(3), w)!!, 0.0)   // before the first weigh-in: the first
        assertNull(weightOn(d, emptyList()))
    }

    @Test
    fun perKgDividesByTheWeightInForce() {
        val day = DailyNutrition(d.toString(), 2000.0, 160.0, 60.0, 200.0, 3)
        assertEquals(2.0, perKg(listOf(day), listOf(d to 80.0)) { it.proteinG }.single().second, 1e-9)
    }

    @Test
    fun proteinIsListedInMealOrder() {
        val meals = listOf(
            MealRow(loggedAt = "2026-10-01T19:00:00Z", proteinG = 40.0),
            MealRow(loggedAt = "2026-10-01T08:00:00Z", proteinG = 25.0),
            MealRow(loggedAt = "2026-10-02T08:00:00Z"),
        )
        val out = proteinByMeal(meals)
        assertEquals(listOf(25.0, 40.0), out[d])
        assertEquals(listOf(0.0), out[d.plusDays(1)])
    }
}
