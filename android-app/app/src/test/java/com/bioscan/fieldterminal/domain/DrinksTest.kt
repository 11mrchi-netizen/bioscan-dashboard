package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DrinksTest {
    private val cola = DrinkMatch(
        id = 1, name = "Cola", beverageClass = "soda", kcal = 42.0, sugarG = 10.6, caffeineMg = 10.0, waterMl = 89.0,
        servings = listOf(DrinkServing("1 fl oz", 30.0), DrinkServing("can", 330.0), DrinkServing("bottle", 600.0)),
    )

    @Test
    fun amountsScaleThePer100mlNumbers() {
        val a = drinkAmounts(cola, 330.0)
        assertEquals(138.6, a.kcal!!, 1e-9)
        assertEquals(35.0, a.sugarG!!, 1e-9)
        assertEquals(33.0, a.caffeineMg!!, 1e-9)
        assertEquals(293.7, a.waterMl, 1e-9)
        assertNull(a.alcoholG)
    }

    @Test
    fun aTypedStrengthReplacesTheCatalogAlcoholAndNoDrinkKnowsOnlyTheVolume() {
        val beer = DrinkMatch(id = 2, name = "Beer", beverageClass = "alcohol", alcoholG = 3.9)
        assertEquals(19.5, drinkAmounts(beer, 500.0).alcoholG!!, 1e-9)
        assertEquals(19.7, drinkAmounts(beer, 500.0, abvPercent = 5.0).alcoholG!!, 0.05)
        val none = drinkAmounts(null, 250.0)
        assertNull(none.kcal)
        assertEquals(250.0, none.waterMl, 1e-9)
    }

    @Test
    fun sizesSkipTableUnitsAndAddTheUsualCups() {
        val sizes = drinkSizes(cola, "soda").map { it.ml }
        assertTrue(30.0 !in sizes)
        assertTrue(330.0 in sizes && 600.0 in sizes)
        assertEquals(sizes.sorted(), sizes)
        assertEquals(sizes.distinct(), sizes)
    }

    @Test
    fun usualDrinksNeedTwoTimesAndTakeTheMedianAmount() {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        fun d(daysAgo: Long, id: Long?, name: String, ml: Double) = LoggedDrink(now.minusSeconds(daysAgo * 86_400), id, name, "coffee", ml, null, null)
        val usual = usualDrinks(listOf(d(1, 7, "Latte", 360.0), d(2, 7, "Latte", 480.0), d(3, 7, "Latte", 360.0), d(4, null, "Tea", 240.0), d(90, null, "Tea", 240.0)), now)
        assertEquals(1, usual.size)
        assertEquals(7L, usual[0].foodId)
        assertEquals(360.0, usual[0].ml, 1e-9)
        assertEquals(3, usual[0].times)
    }

    @Test
    fun drinkDaysCountCaffeineFromTwoPmOnByTheLocalDay() {
        val zone = ZoneOffset.ofHours(8)
        fun at(local: String) = java.time.LocalDateTime.parse(local).atZone(zone).toInstant()
        val days = drinkDays(listOf(
            LoggedDrink(at("2026-11-07T13:59"), 1, "Coffee", "coffee", 240.0, 95.0, null),
            LoggedDrink(at("2026-11-07T14:00"), 1, "Coffee", "coffee", 240.0, 95.0, null),
            LoggedDrink(at("2026-11-07T23:30"), 2, "Wine", "alcohol", 150.0, null, 14.2),
        ), zone)
        val d = days.getValue(LocalDate.of(2026, 11, 7))
        assertEquals(95.0, d.caffeineAfter14Mg, 1e-9)
        assertEquals(14.2, d.alcoholG, 1e-9)
    }
}
