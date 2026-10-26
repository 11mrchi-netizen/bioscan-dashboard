package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PeriodizationTest {
    private val d0 = LocalDate.of(2026, 10, 1)

    // Load alternates 0 / 600 / 1200 kcal; carbohydrate follows it (periodised) or stays flat with noise.
    private fun days(follow: Boolean, n: Int = 21) = (0 until n).map { i ->
        val load = listOf(0.0, 600.0, 1200.0)[i % 3]
        val carbs = if (follow) 3.5 + load / 300 else 5.0 + listOf(0.1, -0.1, 0.0)[i % 3]
        DayIntakeLoad(d0.plusDays(i.toLong()), carbs, load)
    }

    @Test
    fun carbohydrateThatFollowsTrainingScoresHigh() {
        val p = carbPeriodizationIndex(days(follow = true))!!
        assertEquals(1.0, p.correlation, 1e-9)
        assertEquals(4.0, p.rangeGPerKg, 1e-9)
        assertTrue(p.index > 1.0)
        assertTrue(periodizationWords(p).contains("rises and falls"))
    }

    @Test
    fun flatCarbohydrateScoresNearZero() {
        val p = carbPeriodizationIndex(days(follow = false))!!
        assertTrue(kotlin.math.abs(p.index) < 0.05)
        assertTrue(p.monotony > 20)
    }

    @Test
    fun fewerThanFourteenDaysGiveNoIndex() {
        assertNull(carbPeriodizationIndex(days(true, 13)))
    }

    @Test
    fun codesAreCountedOnLoggedMealsOnly() {
        val a = codeAdherence(
            listOf(
                CodedMealOutcome(CarbCode.HIGH, MealAdherence.WITHIN),
                CodedMealOutcome(CarbCode.HIGH, MealAdherence.UNDER),
                CodedMealOutcome(CarbCode.HIGH, MealAdherence.NOT_LOGGED),
                CodedMealOutcome(CarbCode.LOW, MealAdherence.OVER),
            ),
        )
        val high = a.first { it.code == CarbCode.HIGH }
        assertEquals(2, high.logged)
        assertEquals(0.5, high.withinShare!!, 1e-9)
        assertNull(a.first { it.code == CarbCode.MEDIUM }.withinShare)
    }

    @Test
    fun aCleanRaceAtTheCeilingSuggestsMoreAndTroubleLess() {
        val clean = FuelReviewOutcome(d0, 58.0, giScore = 1, energyScore = 8)
        assertEquals(70.0, suggestTolerance(clean, 60.0)!!.newGPerHour, 0.0)
        assertNull(suggestTolerance(clean.copy(carbsGPerHour = 40.0), 60.0)) // well under the ceiling: nothing learned
        assertNull(suggestTolerance(clean, 90.0)) // already at the top
        val bad = FuelReviewOutcome(d0, 60.0, giScore = 7, energyScore = 4)
        assertEquals(50.0, suggestTolerance(bad, 60.0)!!.newGPerHour, 0.0)
        assertEquals(30.0, suggestTolerance(bad, 35.0)!!.newGPerHour, 0.0)
        assertNull(suggestTolerance(FuelReviewOutcome(d0, 60.0, 4, 6), 60.0))
    }

    @Test
    fun ratesAndMisses() {
        assertEquals(60.0, reviewedRate(180.0, 180.0), 1e-9)
        assertEquals(0.1, durationMiss(200.0, 220.0), 1e-9)
    }
}
