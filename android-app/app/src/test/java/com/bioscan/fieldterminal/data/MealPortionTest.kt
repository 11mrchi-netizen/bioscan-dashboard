package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.MealItemRow
import com.bioscan.fieldterminal.data.model.MealRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MealPortionTest {
    private val meal = MealRow(loggedAt = "2026-11-18T12:30:00+00:00", description = "Rice bowl", calories = 600.0, proteinG = 30.0, carbsG = 80.0, fatG = 12.0, sodiumMg = 900.0)
    private val item = MealItemRow(
        id = 5, mealId = 1, foodId = 42, description = "Rice", quantity = 200.0, quantityUnit = "g", servingCount = 1.0,
        calories = 260.0, carbsG = 56.0, waterMl = 120.0, caffeineMg = null, confidence = 0.8, source = "cronometer",
        cronometerNutrients = JsonObject(mapOf("iron" to JsonPrimitive(1.2), "unit" to JsonPrimitive("mg"))),
    )

    @Test fun `a half portion halves every amount and keeps the rest`() {
        val m = meal.scaled(0.5)
        assertEquals(300.0, m.calories!!, 0.0)
        assertEquals(15.0, m.proteinG!!, 0.0)
        assertEquals(450.0, m.sodiumMg!!, 0.0)
        assertNull(m.fiberG)
        assertEquals("Rice bowl", m.description)
        val i = item.scaled(0.5)
        assertEquals(100.0, i.quantity!!, 0.0)
        assertEquals(0.5, i.servingCount!!, 0.0)
        assertEquals(130.0, i.calories!!, 0.0)
        assertEquals(60.0, i.waterMl!!, 0.0)
        assertNull(i.caffeineMg)
        assertEquals(0.8, i.confidence!!, 0.0)
        assertEquals(42L, i.foodId)
        assertEquals("g", i.quantityUnit)
        assertEquals(0.6, i.cronometerNutrients!!["iron"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("mg", i.cronometerNutrients!!["unit"]!!.jsonPrimitive.content)
    }

    @Test fun `one portion is the meal unchanged`() {
        assertSame(meal, meal.scaled(1.0))
        assertSame(item, item.scaled(1.0))
    }

    @Test fun `portion labels`() {
        assertEquals(listOf("½", "¾", "1", "1¼", "1½", "2"), MEAL_PORTIONS.map(::portionLabel))
    }
}
