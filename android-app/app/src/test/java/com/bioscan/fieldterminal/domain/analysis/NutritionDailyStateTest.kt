package com.bioscan.fieldterminal.domain.analysis

import com.bioscan.fieldterminal.data.model.MealItemRow
import com.bioscan.fieldterminal.domain.EvalState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionDailyStateTest {

    private val date = LocalDate.of(2026, 9, 22)

    @Test
    fun testTier_fullPartialMinimal() {
        assertEquals(CompletenessTier.FULL, InputCompleteness(setOf("a", "b"), setOf("a", "b")).tier)
        assertEquals(CompletenessTier.PARTIAL, InputCompleteness(setOf("a", "b"), setOf("a", "b", "c")).tier)
        assertEquals(CompletenessTier.MINIMAL, InputCompleteness(emptySet(), setOf("a")).tier)
        assertEquals(CompletenessTier.MINIMAL, InputCompleteness(setOf("a"), setOf("a", "b")).tier)
    }

    // Hand-verified: 165 + 220 = 385 kcal, matching a real two-item meal.
    @Test
    fun testEnergyDimension_reconcilesWithMealItemTotals() {
        val chicken = MealItemRow(id = 1, mealId = 10, foodId = 2, calories = 165.0, isEstimated = false)
        val rice = MealItemRow(id = 2, mealId = 10, foodId = 3, calories = 220.0, isEstimated = true)
        val state = buildNutritionDailyState(date, listOf(chicken, rice), mapOf(10L to "2026-09-22T12:00:00+02:00"), hydrationMl = null, beverageClassByFoodId = emptyMap())

        val energy = state.daily.dimensions.getValue(StateDimension.ENERGY_INTAKE)
        assertEquals(385.0, energy.value!!, 0.001)
        assertEquals(EvalState.Stable, energy.evalState)
        assertEquals(CompletenessTier.FULL, energy.breadth.tier)
        assertEquals(1, state.mealCount)
        assertEquals(1, state.estimatedItemCount)
        assertEquals(1, state.confirmedItemCount)
    }

    @Test
    fun testEnergyDimension_noDataWhenNoItems() {
        val state = buildNutritionDailyState(date, emptyList(), emptyMap(), hydrationMl = null, beverageClassByFoodId = emptyMap())
        val energy = state.daily.dimensions.getValue(StateDimension.ENERGY_INTAKE)
        assertNull(energy.value)
        assertEquals(EvalState.NoData, energy.evalState)
        assertEquals(CompletenessTier.MINIMAL, energy.breadth.tier)
    }

    // A beverage's water_ml plus hydration_daily's manual log are additive,
    // matching HealthConnectWriteBackRepository's own merge rule -- and the
    // breakdown map must sum back to the same total (the "reconciles exactly"
    // acceptance criterion, extended to this breakdown).
    @Test
    fun testFluidDimension_mergesBeverageAndManualLog() {
        val coffee = MealItemRow(id = 1, mealId = 10, foodId = 3, isBeverage = true, waterMl = 99.9)
        val state = buildNutritionDailyState(date, listOf(coffee), mapOf(10L to "2026-09-22T08:00:00+02:00"), hydrationMl = 1900, beverageClassByFoodId = mapOf(3L to "coffee"))

        val fluid = state.daily.dimensions.getValue(StateDimension.FLUID_INTAKE)
        assertEquals(1999.9, fluid.value!!, 0.001)
        assertEquals(CompletenessTier.FULL, fluid.breadth.tier)
        assertEquals(1999.9, state.fluidMlByBeverageClass.values.sum(), 0.001)
        assertEquals(99.9, state.fluidMlByBeverageClass.getValue("coffee"), 0.001)
        assertEquals(1900.0, state.fluidMlByBeverageClass.getValue("manual_log"), 0.001)
    }

    @Test
    fun testEffectiveHydration_onlyFromBeverageModel() {
        val coffee = MealItemRow(id = 1, mealId = 10, foodId = 3, isBeverage = true, waterMl = 99.9, effectiveHydrationMl = 89.9, hydrationModelVersion = "v1")
        val state = buildNutritionDailyState(date, listOf(coffee), mapOf(10L to "2026-09-22T08:00:00+02:00"), hydrationMl = null, beverageClassByFoodId = mapOf(3L to "coffee"))

        val effective = state.daily.dimensions.getValue(StateDimension.EFFECTIVE_HYDRATION)
        assertEquals(89.9, effective.value!!, 0.001)
        assertEquals("v1", effective.provenance.algorithmVersion)
        assertTrue(state.items.single().loggedAt.startsWith("2026-09-22"))
    }
}
