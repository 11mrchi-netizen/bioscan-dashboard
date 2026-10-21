package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MealSuggestionsTest {
    private fun target(kind: MealSlotKind, carb: Double, protein: Double, fat: Double?, before: Boolean = false) =
        PlateTarget(kind, carb * 0.85, carb * 1.15, protein, fat, before)

    @Test
    fun aMainMealHitsItsTargetsWithTwoDifferentCarbSources() {
        val t = target(MealSlotKind.DINNER, 120.0, 40.0, 25.0)
        val s = suggestPlates(t, STAPLE_FOODS)
        assertEquals(2, s.plates.size)
        for (p in s.plates) {
            assertTrue(p.label, p.carbG in t.carbLowG * 0.9..t.carbHighG * 1.1)
            assertTrue(p.label, p.proteinG in 30.0..50.0)
            assertTrue(p.label, p.fatG in 14.0..36.0)
            assertTrue(p.portions.any { it.food.role == FoodRole.VEG })
        }
        assertNotEquals(s.plates[0].carbSource!!.id, s.plates[1].carbSource!!.id)
    }

    @Test
    fun theSameInputsGiveTheSamePlates() {
        val t = target(MealSlotKind.BREAKFAST, 90.0, 30.0, 18.0)
        assertEquals(suggestPlates(t, STAPLE_FOODS).plates.map { it.label }, suggestPlates(t, STAPLE_FOODS).plates.map { it.label })
    }

    @Test
    fun beforeAKeySessionPlatesAreLowInFatAndFibre() {
        val t = target(MealSlotKind.PRE_SESSION, 50.0, 8.0, 6.0, before = true)
        val s = suggestPlates(t, STAPLE_FOODS)
        assertTrue(s.plates.isNotEmpty())
        for (p in s.plates) {
            assertTrue(p.label, p.fatG <= PRE_SESSION_FAT_CAP_G)
            assertTrue(p.label, p.fibreG <= PRE_SESSION_FIBRE_CAP_G)
            assertTrue(p.portions.all { it.food.easyBeforeTraining })
        }
    }

    @Test
    fun portionsStayInBoundsAndOnRealServings() {
        val t = target(MealSlotKind.SNACK, 60.0, 10.0, 8.0)
        for (p in suggestPlates(t, STAPLE_FOODS).plates) for (portion in p.portions) {
            assertTrue(portion.label, portion.grams in portion.food.minG..portion.food.maxG)
            portion.food.serving?.let { sv ->
                val n = portion.grams / (sv.grams * sv.step)
                assertEquals(portion.label, Math.round(n).toDouble(), n, 1e-6)
            }
        }
    }

    @Test
    fun theFoodsAPersonEatsRankFirstAndStaplesFillIn() {
        val foods = staplesWithUses(
            listOf(
                MealSlotKind.DINNER to "Boiled chicken breast, brown rice, and steamed Chinese broccoli",
                MealSlotKind.DINNER to "Grilled chicken breast with brown rice and mixed vegetables",
                MealSlotKind.LUNCH to "Shrimp and brown rice with gai lan",
            ),
        )
        val dinner = suggestPlates(target(MealSlotKind.DINNER, 110.0, 40.0, 20.0), foods)
        val first = dinner.plates.first()
        assertEquals("staple:rice_brown", first.carbSource!!.id)
        assertTrue(first.portions.any { it.food.id == "staple:chicken_breast" })
        assertTrue(first.portions.first { it.food.id == "staple:rice_brown" }.food.usual)
        // Nothing logged: staples alone still give plates.
        assertTrue(suggestPlates(target(MealSlotKind.DINNER, 110.0, 40.0, 20.0), STAPLE_FOODS).plates.isNotEmpty())
    }

    @Test
    fun aTargetNoPlateCanReachIsReportedNotForced() {
        // 600 g of carbohydrate with 2 g of protein from a pre-session snack: nothing within bounds gets there.
        val s = suggestPlates(target(MealSlotKind.PRE_SESSION, 600.0, 2.0, 1.0, before = true), STAPLE_FOODS)
        assertTrue(s.plates.isEmpty())
        assertNotNull(s.note)
    }

    @Test
    fun descriptionsAreMatchedLongestNameFirst() {
        val ids = staplesMentioned("Beef noodle soup and a cup of soy milk, plus brown rice")
        assertTrue("staple:beef_lean" in ids)
        assertTrue("staple:noodles_wheat" in ids)
        assertTrue("staple:soy_milk" in ids)
        assertTrue("staple:rice_brown" in ids)
        assertFalse("staple:milk" in ids)
        assertFalse("staple:rice_white" in ids)
        assertTrue("staple:greek_yoghurt" in staplesMentioned("希臘優格"))
        assertFalse("staple:eggs" in staplesMentioned("eggplant stir-fry"))
    }

    @Test
    fun catalogueFoodsGetARoleFromTheirEnergy() {
        assertEquals(FoodRole.CARB, roleFor(0.28, 0.027, 0.003))
        assertEquals(FoodRole.PROTEIN, roleFor(0.0, 0.31, 0.036))
        assertEquals(FoodRole.FAT, roleFor(0.0, 0.0, 1.0))
        assertEquals(FoodRole.MIXED, roleFor(0.20, 0.09, 0.05))
        assertEquals(FoodRole.VEG, roleFor(0.03, 0.018, 0.003))
    }

    @Test
    fun aWholeCodedDayGetsPlatesAndASummary() {
        val zone = ZoneId.of("Europe/Rome")
        val date = LocalDate.of(2026, 11, 4)
        val run = PlannedItem(PlanSource.PLANNED_SESSION, "r", "tempo run", "run", ZonedDateTime.of(date.atTime(17, 0), zone), 60.0, Intensity.HARD, "test", 700.0)
        val pd = PlannedDay(date, listOf(run))
        val plan = planFuelDay(pd, null, listOf(pd), WeekEnergy(2900.0, 130.0, true), 70.0)
        val coded = codeMeals(plan, pd, null, 70.0, zone)
        val suggestions = suggestDay(coded, STAPLE_FOODS)
        assertEquals(coded.meals.size, suggestions.size)
        assertTrue(suggestions.all { it.plates.isNotEmpty() })
        val summary = daySummary(plan.carbG, STAPLE_FOODS)
        assertTrue(summary, summary.startsWith("≈ ") && summary.contains("banana"))
        assertTrue(Portion(STAPLE_FOODS.first { it.id == "staple:banana" }, 177.0).label.startsWith("1½ bananas"))
    }
}
