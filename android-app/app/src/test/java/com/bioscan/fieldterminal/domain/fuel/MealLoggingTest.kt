package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

class MealLoggingTest {
    private val day = LocalDate.of(2026, 11, 3)
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(day.atTime(h, m), ZoneOffset.UTC)

    private fun meal(kind: MealSlotKind, h: Int, fat: Double? = 15.0) =
        CodedMeal(kind, at(h, 30), CarbCode.MEDIUM, 60.0, 75.0, 30.0, fat, "reason")

    private val rice = FoodOption("staple:rice", "cooked rice", FoodRole.CARB, setOf(MealSlotKind.LUNCH), 0.28, 0.027, 0.003, 0.004, 1.3, 180.0, 50.0, 400.0)
    private fun plate(g: Double) = Plate(listOf(Portion(rice, g)), emptyList())

    private val breakfast = meal(MealSlotKind.BREAKFAST, 7)
    private val lunch = meal(MealSlotKind.LUNCH, 12)
    private val snack = meal(MealSlotKind.SNACK, 16)
    private val day3 = listOf(breakfast, lunch, snack)

    @Test
    fun loggingInsideTheSlotUsesNow() {
        assertEquals(at(13, 10), logTimeFor(lunch, day3, at(13, 10)))
    }

    @Test
    fun loggingLateKeepsThePlannedTimeSoItLandsInItsSlot() {
        // 16:00 is nearest the snack; the lunch logged then keeps lunch's 12:30.
        assertEquals(lunch.time, logTimeFor(lunch, day3, at(16, 0)))
    }

    @Test
    fun suggestionsBecomeChoicesInOrder() {
        val choices = plateChoices(lunch, MealSuggestion(listOf(plate(200.0), plate(150.0)), null), null)
        assertEquals(listOf("ON THE PLATE", "OR"), choices.map { it.heading })
    }

    @Test
    fun aPreppedContainerReplacesTheSuggestions() {
        val target = PlateTarget(MealSlotKind.LUNCH, 60.0, 75.0, 30.0, 15.0, false)
        val prep = PreppedDay(day, null, Container(day, PrepSlot.LUNCH, 'B', plate(220.0), target), null, emptySet(), Remainder(0.0, 0.0, null), emptyList())
        val choices = plateChoices(lunch, MealSuggestion(listOf(plate(200.0)), null), prep)
        assertEquals(listOf("CONTAINER B"), choices.map { it.heading })
        // No prepped breakfast: breakfast falls back to its suggestions.
        assertEquals(listOf("ON THE PLATE"), plateChoices(breakfast, MealSuggestion(listOf(plate(100.0)), null), prep).map { it.heading })
    }

    @Test
    fun descriptionAndTargets() {
        assertTrue(plannedMealDescription(lunch, plate(180.0)).startsWith("Lunch: "))
        assertEquals("Carbs 60-75 g · protein 25-35 g · fat ~15 g · ~525 kcal", mealTargetLine(lunch))
        assertEquals("Carbs 60-75 g · protein 25-35 g", mealTargetLine(meal(MealSlotKind.LUNCH, 12, fat = null)))
    }
}
