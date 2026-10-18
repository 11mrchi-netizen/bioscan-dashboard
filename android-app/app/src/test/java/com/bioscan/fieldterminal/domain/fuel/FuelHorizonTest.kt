package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class FuelHorizonTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val start = LocalDate.of(2026, 11, 2)

    private fun day(offset: Int, minutes: Double?, kcal: Double = 0.0): PlannedDay {
        val d = start.plusDays(offset.toLong())
        if (minutes == null) return PlannedDay(d, emptyList())
        val item = PlannedItem(PlanSource.PLANNED_SESSION, "s$offset", "run", "run", ZonedDateTime.of(d.atTime(17, 0), zone), minutes, Intensity.MODERATE, "test", kcal)
        return PlannedDay(d, listOf(item))
    }

    @Test
    fun eachBlockOfSevenDaysAveragesToTheGoal() {
        // An easy first week and a heavy second one: each week keeps the goal's average on its own.
        val days = (0 until 3).map { day(it - 3, null) } + (0 until 14).map { k ->
            if (k < 7) day(k, if (k % 2 == 0) 45.0 else null, 400.0) else day(k, 120.0, 1200.0)
        }
        val energy = WeekEnergy(3000.0, 140.0, true)
        val plans = planInWeekBlocks(days, 3, energy, 70.0)
        val first = plans.subList(3, 10)
        val second = plans.subList(10, 17)
        // A day's energy is the goal plus its own session energy minus its block's mean; with no fat floor binding, each
        // block's energy averages to the goal.
        val firstMean = first.map { it.kcal!! }.average()
        val secondMean = second.map { it.kcal!! }.average()
        assertEquals(3000.0, firstMean, 60.0)
        assertEquals(3000.0, secondMean, 60.0)
        // One shared window would have given the second week the first week's mean session energy: far above the goal.
        val shared = planFuelDays(days, energy, 70.0, week = days.subList(3, 10)).subList(10, 17).map { it.kcal!! }.average()
        assertTrue(shared > 3500)
    }

    @Test
    fun aMealTimeGoesToTheNearestSlotWithinTwoHours() {
        val t = MealTimes()
        assertEquals(MealSlotKind.BREAKFAST, slotForTime(LocalTime.of(8, 10), t))
        assertEquals(MealSlotKind.LUNCH, slotForTime(LocalTime.of(13, 40), t))
        assertEquals(MealSlotKind.SNACK, slotForTime(LocalTime.of(15, 45), t))
        assertEquals(MealSlotKind.DINNER, slotForTime(LocalTime.of(20, 15), t))
        assertEquals(MealSlotKind.SNACK, slotForTime(LocalTime.of(23, 30), t))
    }

    @Test
    fun ownFoodsNeedThreeUsesAndCarryTheCataloguesNumbers() {
        val cat = mapOf(
            1L to CatalogueFood(1, "jasmine rice, cooked", 28.0, 2.7, 0.3, 0.4, 130.0),
            2L to CatalogueFood(2, "tofu", 2.8, 17.3, 8.7, 2.3, 144.0),
        )
        val logged = listOf(
            LoggedFood(1, MealSlotKind.DINNER, 200.0), LoggedFood(1, MealSlotKind.DINNER, 250.0), LoggedFood(1, MealSlotKind.LUNCH, 180.0),
            LoggedFood(2, MealSlotKind.DINNER, 150.0), LoggedFood(2, MealSlotKind.DINNER, 150.0),
        )
        val own = ownFoods(logged, cat)
        assertEquals(listOf("food:1"), own.map { it.id })
        val rice = own.single()
        assertEquals(FoodRole.CARB, rice.role)
        assertEquals(200.0, rice.typicalG, 1e-9)
        assertEquals(0.28, rice.carbPerG, 1e-9)
        assertEquals(setOf(MealSlotKind.DINNER, MealSlotKind.LUNCH), rice.slots)
        assertTrue(rice.yours && rice.usual)
        // An own food ranks before the staples for its slot.
        val plates = suggestPlates(PlateTarget(MealSlotKind.DINNER, 85.0, 115.0, 40.0, 25.0, false), own + STAPLE_FOODS)
        assertEquals("food:1", plates.plates.first().carbSource!!.id)
    }

    @Test
    fun tenDaysOfPlatesAreQuickEnoughForThePhone() {
        val days = (0 until 12).map { day(it, if (it % 3 == 0) null else 75.0, 700.0) }
        val plans = planInWeekBlocks(days, 0, WeekEnergy(2900.0, 130.0, true), 70.0)
        val t0 = System.nanoTime()
        val all = (0 until 11).map { i -> suggestDay(codeMeals(plans[i], days[i], days[i + 1], 70.0, zone), STAPLE_FOODS) }
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(all.flatten().all { it.plates.isNotEmpty() || it.note != null })
        assertTrue("took $ms ms", ms < 3000)
        println("ten days of plates: %.0f ms".format(ms))
    }
}
