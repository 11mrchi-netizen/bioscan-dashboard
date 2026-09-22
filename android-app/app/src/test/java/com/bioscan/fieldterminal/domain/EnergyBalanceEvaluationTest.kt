package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnergyBalanceEvaluationTest {

    private fun stableNutrition(avgIntake: Double, sd: Double, have: Int = 12) = NutritionEvaluation(
        state = EvalState.Stable,
        confidence = Confidence(have, 10),
        completeDaysInLast14 = have,
        energyTrend14d = avgIntake,
        energySd14d = sd,
        energyCv28d = null,
        proteinAdherence14d = null,
    )

    private fun stableWeight(rateKgPerWeek: Double, have: Int = 12, state: EvalState = EvalState.Stable) = WeightEvaluation(
        state = state,
        confidence = Confidence(have, 10),
        emaToday = 75.0,
        rateKgPerWeek = rateKgPerWeek,
    )

    @Test
    fun testLosingWeightImpliesTdeeAboveAverageIntake() {
        // 2500 kcal/day average intake, losing 0.3 kg/week -> real deficit,
        // so TDEE must read higher than the logged intake average.
        val eval = evaluateTdee(stableNutrition(2500.0, 300.0), stableWeight(-0.3))
        assertEquals(EvalState.Stable, eval.state)
        assertEquals(2830.0, eval.tdeeKcal!!, 0.5) // 2500 - (-0.3/7*7700)
        assertEquals(2530.0, eval.rangeLowKcal!!, 0.5)
        assertEquals(3130.0, eval.rangeHighKcal!!, 0.5)
    }

    @Test
    fun testGainingWeightImpliesTdeeBelowAverageIntake() {
        val eval = evaluateTdee(stableNutrition(3000.0, 200.0), stableWeight(0.2))
        assertEquals(2780.0, eval.tdeeKcal!!, 0.5) // 3000 - (0.2/7*7700)
    }

    @Test
    fun testShiftUpWeightStateStillComputesTdee() {
        // Real bug found live on-device: this account's real weight trend
        // is genuinely rising (ShiftUp, confidence 119/10, gate long met),
        // but an earlier version of this gate checked `state == Stable`
        // specifically and got stuck on Building forever for any account
        // with a real, moving trend -- ShiftUp/ShiftDown/Unstable are gate-
        // met states too, not "not ready yet".
        val weight = stableWeight(rateKgPerWeek = 0.76, have = 119, state = EvalState.ShiftUp)
        val eval = evaluateTdee(stableNutrition(2804.0, 300.0), weight)
        assertEquals(EvalState.Stable, eval.state)
        assertEquals(2804.0 - (0.76 / 7.0 * 7700.0), eval.tdeeKcal!!, 0.5)
    }

    @Test
    fun testBuildingWhenNutritionGateNotMet() {
        val nutrition = NutritionEvaluation(EvalState.Building, Confidence(4, 10), 4, null, null, null, null)
        val eval = evaluateTdee(nutrition, stableWeight(-0.2))
        assertEquals(EvalState.Building, eval.state)
        assertNull(eval.tdeeKcal)
    }

    @Test
    fun testBuildingWhenWeightGateNotMet() {
        // Real current-account shape: 5 weigh-ins in 28 days, well under the
        // 10-in-14 gate WeightEvaluation itself requires.
        val weight = WeightEvaluation(EvalState.Building, Confidence(5, 10), null, null)
        val eval = evaluateTdee(stableNutrition(2500.0, 250.0), weight)
        assertEquals(EvalState.Building, eval.state)
        assertNull(eval.tdeeKcal)
        assertEquals(5, eval.confidence.have) // weakest real input, not the nutrition side's stronger count
    }
}
