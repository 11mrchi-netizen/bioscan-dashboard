package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionTargetsTest {

    private fun ok(r: MacroPlanResult) = r as MacroPlanResult.Ok
    private fun invalid(r: MacroPlanResult) = (r as MacroPlanResult.Invalid).message

    @Test
    fun carbohydrateIsTheRemainderSoTheMacrosAddUp() {
        // 2,500 kcal, 160 g protein (640 kcal), 80 g fat (720 kcal): carbs = 1,140 kcal = 285 g.
        val plan = ok(planMacros(2500.0, 160.0, ProteinBasis.GRAMS, 80.0, FatBasis.GRAMS, weightKg = 75.0)).plan!!
        assertEquals(285.0, plan.carbsG, 1e-9)
        assertEquals(2500.0, plan.proteinKcal + plan.carbsKcal + plan.fatKcal, 1e-9)
        assertEquals(Triple(26, 45, 29), plan.sharesPercent())
    }

    @Test
    fun perKgProteinAndPercentFat() {
        val plan = ok(planMacros(2400.0, 2.0, ProteinBasis.PER_KG, 25.0, FatBasis.PERCENT, weightKg = 70.0)).plan!!
        assertEquals(140.0, plan.proteinG, 1e-9)
        assertEquals(2400 * 0.25 / 9, plan.fatG, 1e-9)
        val (p, c, f) = plan.sharesPercent()
        assertEquals(100, p + c + f)
        assertEquals(25, f)
    }

    @Test
    fun anIncoherentGoalCannotBeBuilt() {
        // 1,200 kcal cannot hold 200 g of protein and 80 g of fat (1,520 kcal).
        assertTrue(invalid(planMacros(1200.0, 200.0, ProteinBasis.GRAMS, 80.0, FatBasis.GRAMS, null)).contains("1520 kcal"))
        assertTrue(invalid(planMacros(null, 150.0, ProteinBasis.GRAMS, 70.0, FatBasis.GRAMS, null)).startsWith("Set the daily calories"))
        assertTrue(invalid(planMacros(2000.0, 150.0, ProteinBasis.GRAMS, null, FatBasis.GRAMS, null)).startsWith("Set both protein and fat"))
        assertTrue(invalid(planMacros(2000.0, 2.0, ProteinBasis.PER_KG, 70.0, FatBasis.GRAMS, null)).contains("body weight"))
        assertTrue(invalid(planMacros(2000.0, 150.0, ProteinBasis.GRAMS, 100.0, FatBasis.PERCENT, null)).contains("100 %"))
        assertEquals(MacroPlanResult.Empty, planMacros(null, null, ProteinBasis.GRAMS, null, FatBasis.GRAMS, null))
    }

    @Test
    fun caloriesAloneAreAGoal() {
        val r = ok(planMacros(2200.0, null, ProteinBasis.GRAMS, null, FatBasis.GRAMS, null))
        assertNull(r.plan)
        assertEquals(2200.0, r.kcal, 0.0)
    }

    @Test
    fun notesQuoteTheReferenceRangesWithoutBlocking() {
        // 70 kg: 50 g protein = 0.7 g/kg (below the RDA), fat 15 %, carbs plenty.
        val low = ok(planMacros(2500.0, 50.0, ProteinBasis.GRAMS, 15.0, FatBasis.PERCENT, 70.0, restingKcal = 1650.0))
        assertTrue(low.notes.any { it.contains("RDA") })
        assertTrue(low.notes.any { it.contains("20–35 %") })
        // Calories under resting energy.
        assertTrue(ok(planMacros(1400.0, 120.0, ProteinBasis.GRAMS, 40.0, FatBasis.GRAMS, 70.0, restingKcal = 1650.0)).notes.any { it.contains("resting energy") })
        // A sensible goal: no notes.
        assertTrue(ok(planMacros(2800.0, 1.8, ProteinBasis.PER_KG, 28.0, FatBasis.PERCENT, 72.0, restingKcal = 1680.0)).notes.isEmpty())
    }

    @Test
    fun mifflinStJeor() {
        // Male, 35 y, 176 cm, 72.8 kg: 728 + 1100 - 175 + 5 = 1658.
        assertEquals(1658.0, restingEnergyKcal("male", 35, 176.0, 72.8)!!, 1e-9)
        assertEquals(1658.0 - 166, restingEnergyKcal("female", 35, 176.0, 72.8)!!, 1e-9)
        assertNull(restingEnergyKcal(null, 35, 176.0, 72.8))
        assertNull(restingEnergyKcal("male", null, 176.0, 72.8))
    }

    @Test
    fun activityFromTrainingOrChosen() {
        assertEquals(ActivityLevel.SEDENTARY, ActivityLevel.fromWeeklyTraining(10.0))
        assertEquals(ActivityLevel.LIGHT, ActivityLevel.fromWeeklyTraining(120.0))
        assertEquals(ActivityLevel.MODERATE, ActivityLevel.fromWeeklyTraining(150.0))
        assertEquals(ActivityLevel.VERY, ActivityLevel.fromWeeklyTraining(420.0))
        assertEquals(ActivityLevel.EXTRA, ActivityLevel.fromWeeklyTraining(500.0))
        val auto = maintenanceEstimate(1658.0, null, 420.0)!!
        assertEquals(1658.0 * 1.725, auto.kcal, 1e-9)
        assertTrue(auto.fromTraining)
        val chosen = maintenanceEstimate(1658.0, ActivityLevel.MODERATE, 420.0)!!
        assertEquals(ActivityLevel.MODERATE, chosen.level)
        assertNull(maintenanceEstimate(null, ActivityLevel.MODERATE, 420.0))
        assertNull(maintenanceEstimate(1658.0, null, null))
    }

    @Test
    fun deficitMaintenanceOrSurplus() {
        assertEquals(EnergyBalanceKind.MAINTENANCE, energyBalance(2950.0, 2860.0).kind) // +3 %
        assertEquals(EnergyBalanceKind.MAINTENANCE, energyBalance(2717.0, 2860.0).kind) // exactly -5 %
        val cut = energyBalance(2360.0, 2860.0)
        assertEquals(EnergyBalanceKind.DEFICIT, cut.kind)
        assertEquals(-500.0 * 7 / 7700, cut.kgPerWeek, 1e-9)
        assertEquals(EnergyBalanceKind.SURPLUS, energyBalance(3200.0, 2860.0).kind)
    }
}
