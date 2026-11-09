package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class MealSlotSuggestionTest {
    private val times = MealTimes() // 09:00, 12:30, 16:00, 19:30
    private fun at(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test
    fun aLateBreakfastStaysBreakfast() {
        // 4 Nov: 10:46, 106 min after breakfast and 104 before lunch; breakfast not logged yet.
        assertEquals(MealSlotKind.BREAKFAST, suggestMealSlot(at(10, 46), times, emptySet()))
        assertEquals(MealSlotKind.LUNCH, suggestMealSlot(at(12, 40), times, setOf(MealSlotKind.BREAKFAST)))
    }

    @Test
    fun aMealBeforeTheNextOneOnceThePreviousIsDone() {
        assertEquals(MealSlotKind.LUNCH, suggestMealSlot(at(11, 45), times, setOf(MealSlotKind.BREAKFAST)))
        assertEquals(MealSlotKind.SNACK, suggestMealSlot(at(10, 0), times, setOf(MealSlotKind.BREAKFAST))) // 2.5 h before lunch
        assertEquals(MealSlotKind.BREAKFAST, suggestMealSlot(at(6, 30), times, emptySet())) // before the day's first meal
        assertEquals(MealSlotKind.SNACK, suggestMealSlot(at(22, 0), times, setOf(MealSlotKind.DINNER)))
        assertEquals(MealSlotKind.DINNER, suggestMealSlot(at(18, 45), times, setOf(MealSlotKind.BREAKFAST, MealSlotKind.LUNCH, MealSlotKind.SNACK)))
    }

    @Test
    fun storedLabelsReadBack() {
        assertEquals(MealSlotKind.PRE_SESSION, mealSlotOf("pre_session"))
        assertEquals(null, mealSlotOf("brunch"))
        assertEquals("recovery", MealSlotKind.RECOVERY.storedName())
    }
}
