package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MealPrepTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val monday = LocalDate.of(2026, 11, 2)
    private val kg = 70.0
    private val foods = STAPLE_FOODS.associateBy { it.id }

    private fun session(d: LocalDate, min: Double, i: Intensity) =
        PlannedItem(PlanSource.CALENDAR, "c$d", i.label, "run", ZonedDateTime.of(d.atTime(7, 0), zone), min, i, "test", min * 11)

    // Mon deload, Tue easy, Wed intervals, Thu easy, Fri rest, Sat long run, Sun easy; then the following week.
    private val week: List<PlannedDay> = (0 until 14).map { k ->
        val d = monday.plusDays(k.toLong())
        when (k % 7) {
            0 -> PlannedDay(d, listOf(session(d, 30.0, Intensity.EASY)))
            1, 3, 6 -> PlannedDay(d, listOf(session(d, 60.0, Intensity.EASY)))
            2 -> PlannedDay(d, listOf(session(d, 75.0, Intensity.HARD)))
            4 -> PlannedDay(d, emptyList())
            else -> PlannedDay(d, listOf(session(d, 160.0, Intensity.LONG)))
        }
    }
    private val plans = planInWeekBlocks(week, 0, WeekEnergy(2900.0, 130.0, true), kg)
    private val coded = week.indices.map { i -> codeMeals(plans[i], week[i], week.getOrNull(i + 1), kg, zone) }
    private fun mealFor(date: LocalDate, slot: PrepSlot): CodedMeal? =
        coded.firstOrNull { it.date == date }?.meals?.firstOrNull { it.kind == slot.kind }

    private val picks = PrepPicks(
        proteins = listOf("staple:chicken_breast", "staple:tofu"),
        vegetables = listOf("staple:broccoli", "staple:greens", "staple:mixed_veg"),
        carbs = listOf("staple:rice_brown", "staple:noodles_wheat"),
    )

    @Test
    fun recipesRotateSoLunchAndDinnerDifferAndEveryPickIsUsed() {
        val r = buildRecipes(picks)
        assertEquals(listOf('A', 'B', 'C', 'D'), r.map { it.letter })
        assertNotEquals(r[0].proteins, r[2].proteins) // Monday: A and C
        assertNotEquals(r[1].proteins, r[3].proteins) // Tuesday: B and D
        assertTrue(picks.vegetables.all { v -> r.any { v in it.vegetables } })
        // Two proteins and two carbohydrates still make four different pairings.
        assertEquals(4, r.map { it.proteins to it.carbs }.toSet().size)
        val five = buildRecipes(picks.copy(proteins = listOf("p1", "p2", "p3", "p4", "p5")))
        assertEquals(listOf("p1", "p5"), five[0].proteins)
    }

    @Test
    fun theWeekAlternatesAndSkippedMealsDropOut() {
        assertEquals(listOf('A', 'B', 'A', 'B', 'A'), (0..4).map { letterFor(monday, monday.plusDays(it.toLong()), PrepSlot.LUNCH) })
        assertEquals(listOf('C', 'D', 'C', 'D', 'C'), (0..4).map { letterFor(monday, monday.plusDays(it.toLong()), PrepSlot.DINNER) })
        val all = planContainers(monday, buildRecipes(picks), emptySet(), foods, ::mealFor)
        assertEquals(10, all.size)
        val friday = slotKey(monday.plusDays(4), PrepSlot.DINNER)
        val some = planContainers(monday, buildRecipes(picks), setOf(friday), foods, ::mealFor)
        assertEquals(9, some.size)
        assertTrue(some.none { it.date == monday.plusDays(4) && it.slot == PrepSlot.DINNER })
        assertEquals(upcomingPrepWeek(monday.minusDays(1)), monday) // from Sunday, tomorrow
    }

    @Test
    fun eachContainerFitsItsOwnDay() {
        val c = planContainers(monday, buildRecipes(picks), emptySet(), foods, ::mealFor)
        for (x in c) {
            assertTrue("${x.date} ${x.slot}: ${x.plate.label}", kotlin.math.abs(x.plate.carbG - x.target.carbMidG) <= 0.25 * x.target.carbMidG + 5)
            assertTrue("${x.date} ${x.slot}: ${x.plate.label}", kotlin.math.abs(x.plate.proteinG - x.target.proteinG) <= 0.25 * x.target.proteinG + 5)
            assertTrue(x.plate.portions.any { it.food.role == FoodRole.VEG })
        }
        // Friday's dinner (before Saturday's long run) carries more carbohydrate than Monday's (a deload day).
        val fri = c.first { it.date == monday.plusDays(4) && it.slot == PrepSlot.DINNER }
        val mon = c.first { it.date == monday && it.slot == PrepSlot.DINNER }
        assertTrue("${fri.plate.carbG} vs ${mon.plate.carbG}", fri.plate.carbG > mon.plate.carbG)
        // Same inputs, same containers.
        assertEquals(c.map { it.plate.label }, planContainers(monday, buildRecipes(picks), emptySet(), foods, ::mealFor).map { it.plate.label })
    }

    @Test
    fun theShoppingListIsTheContainersAndBreakfastInRawWeight() {
        val c = planContainers(monday, buildRecipes(picks), emptySet(), foods, ::mealFor)
        val template = listOf(BreakfastItem("staple:oats", 60.0), BreakfastItem("staple:whey", 30.0), BreakfastItem("staple:banana", 118.0))
        val breakfasts = (0 until 7).mapNotNull { k ->
            coded[k].meals.firstOrNull { it.kind == MealSlotKind.BREAKFAST }?.let { scaleBreakfast(template, it.plateTarget(), foods) }
        }
        assertEquals(7, breakfasts.size)
        val list = shoppingList(c.map { it.plate } + breakfasts)
        val rice = list.first { it.food.id == "staple:rice_brown" }
        val cooked = c.sumOf { x -> x.plate.portions.filter { it.food.id == "staple:rice_brown" }.sumOf { it.grams } }
        assertEquals(cooked, rice.eatenG, 1e-6)
        assertEquals(cooked * 0.40, rice.rawG, 1e-6)
        assertTrue(rice.label, rice.label.endsWith("brown rice, dry"))
        val chicken = list.first { it.food.id == "staple:chicken_breast" }
        assertTrue(chicken.rawG > chicken.eatenG)
        val bananas = list.first { it.food.id == "staple:banana" }
        assertTrue(bananas.label, bananas.label.endsWith("bananas"))
        val oats = list.first { it.food.id == "staple:oats" }
        assertEquals(breakfasts.sumOf { b -> b.portions.filter { it.food.id == "staple:oats" }.sumOf { it.grams } }, oats.eatenG, 1e-6)
        // The cook sheet splits each recipe's foods across its containers.
        val sheet = cookSheet(buildRecipes(picks), c)
        val aRice = sheet.first { it.recipe.letter == 'A' && it.food.id == "staple:rice_brown" }
        assertEquals(3, aRice.split.size) // Monday, Wednesday, Friday lunch
        assertTrue(prepText(monday, buildRecipes(picks), c, list, foods).contains("SHOPPING"))
    }

    @Test
    fun breakfastStaysTheSameFoodsAndScalesWithTheDay() {
        val template = listOf(BreakfastItem("staple:oats", 60.0), BreakfastItem("staple:whey", 30.0), BreakfastItem("staple:banana", 118.0))
        val low = scaleBreakfast(template, PlateTarget(MealSlotKind.BREAKFAST, 40.0, 55.0, 30.0, 10.0, false), foods)!!
        val high = scaleBreakfast(template, PlateTarget(MealSlotKind.BREAKFAST, 100.0, 135.0, 30.0, 10.0, false), foods)!!
        assertEquals(template.map { it.foodId }.toSet(), high.portions.map { it.food.id }.toSet())
        assertTrue(high.carbG > low.carbG)
        for (p in low.portions + high.portions) {
            val base = template.first { it.foodId == p.food.id }.grams
            assertTrue(p.label, p.grams in base * BREAKFAST_MIN_SCALE - 1e-6..base * BREAKFAST_MAX_SCALE + 1e-6)
        }
    }

    @Test
    fun onceCookedTheContainersStayAndBreakfastAndSnackTakeUpTheChange() {
        val c = planContainers(monday, buildRecipes(picks), emptySet(), foods, ::mealFor)
        val stored = c.map { StoredContainer(it.date, it.slot, it.letter, it.plate.portions.associate { p -> p.food.id to p.grams }) }
        // Wednesday's session grows into a long run after cooking: the day needs more.
        val wed = monday.plusDays(2)
        val newDay = PlannedDay(wed, listOf(session(wed, 150.0, Intensity.LONG)))
        val newPlan = planFuelDay(newDay, week[3], week.take(7), WeekEnergy(2900.0, 130.0, true), kg)
        val newCoded = codeMeals(newPlan, newDay, week[3], kg, zone)
        val fixed = stored.filter { it.date == wed }.map { s ->
            containerFromStored(s, foods, newCoded.meals.first { it.kind == s.slot.kind }.plateTarget())
        }
        val template = listOf(BreakfastItem("staple:oats", 60.0), BreakfastItem("staple:whey", 30.0), BreakfastItem("staple:banana", 118.0))
        val day = prepDay(newCoded, template, fixed, emptySet(), foods)
        val before = c.filter { it.date == wed }.sumOf { it.plate.carbG }
        assertEquals(before, listOfNotNull(day.lunch, day.dinner).sumOf { it.plate.carbG }, 1e-6)
        // What the fixed meals miss lands on breakfast and the snack, so the day's total still meets the plan.
        val mains = listOfNotNull(day.breakfast, day.lunch?.plate, day.dinner?.plate).sumOf { it.carbG }
        val total = mains + day.snack.carbG
        val planned = newCoded.meals.sumOf { it.carbMidG }
        assertEquals(planned, total, 0.15 * planned)
        assertTrue(day.snack.carbG > 0)
    }
}
