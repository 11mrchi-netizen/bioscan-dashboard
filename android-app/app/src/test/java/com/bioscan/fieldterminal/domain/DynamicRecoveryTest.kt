package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicRecoveryTest {

    private val met = Confidence(14, 14)
    private val unmet = Confidence(0, 14)
    private val goodSleep = SleepIndexResult(80.0, "Good", EvalState.Stable, met, emptyMap())
    private val buildingSleep = SleepIndexResult(null, null, EvalState.Building, unmet, emptyMap())

    private fun swc(state: EvalState, confidence: Confidence = met) = SwcEvaluation(state, confidence, 50.0, 50.0, 5.0, 5.0)
    private fun trainingLoad(state: EvalState, confidence: Confidence = met) = TrainingLoadEvaluation(state, confidence, 40.0, 30.0, 10.0, "Freshened")
    private fun subjective(trendDirection: Int?, confidence: Confidence = met) = SubjectiveEvaluation(EvalState.Stable, confidence, 6.0, 1.0, 6.0, trendDirection)

    @Test
    fun allPositiveContributorsShiftUp() {
        val result = computeDynamicRecovery(
            sleepIndex = goodSleep,
            hrvEval = swc(EvalState.ShiftUp), // higher HRV -- good
            rhrEval = swc(EvalState.ShiftDown), // lower RHR -- good
            trainingLoadEval = trainingLoad(EvalState.ShiftUp), // freshened -- good
            energyEval = subjective(1), // energy up -- good
            stressEval = subjective(-1), // subjective stress down -- good (inverted)
            sorenessEval = subjective(-1), // soreness down -- good (inverted)
        )

        assertEquals(EvalState.ShiftUp, result.state)
        assertEquals(7, result.contributors.size)
    }

    @Test
    fun allNegativeContributorsShiftDown() {
        val result = computeDynamicRecovery(
            sleepIndex = SleepIndexResult(20.0, "Needs attention", EvalState.Stable, met, emptyMap()),
            hrvEval = swc(EvalState.ShiftDown), // lower HRV -- bad
            rhrEval = swc(EvalState.ShiftUp), // higher RHR -- bad
            trainingLoadEval = trainingLoad(EvalState.Unstable), // heavily loaded -- bad
            energyEval = subjective(-1),
            stressEval = subjective(1), // subjective stress up -- bad
            sorenessEval = subjective(1), // soreness up -- bad
        )

        assertEquals(EvalState.ShiftDown, result.state)
    }

    @Test
    fun mixedSignalsAreUnstableNotAveragedAway() {
        val result = computeDynamicRecovery(
            sleepIndex = goodSleep, // good
            hrvEval = swc(EvalState.ShiftDown), // bad
            rhrEval = swc(EvalState.Building, unmet),
            trainingLoadEval = trainingLoad(EvalState.Building, unmet),
            energyEval = subjective(null),
            stressEval = subjective(null),
            sorenessEval = subjective(null),
        )

        assertEquals(EvalState.Unstable, result.state)
    }

    @Test
    fun tooFewContributorsIsBuildingNotFabricated() {
        val result = computeDynamicRecovery(
            sleepIndex = buildingSleep,
            hrvEval = swc(EvalState.Building, unmet),
            rhrEval = swc(EvalState.Building, unmet),
            trainingLoadEval = trainingLoad(EvalState.Building, unmet),
            energyEval = subjective(null, unmet),
            stressEval = subjective(null, unmet),
            sorenessEval = subjective(null, unmet),
        )

        assertEquals(EvalState.Building, result.state)
        assertTrue(result.contributors.isEmpty())
    }

    @Test
    fun physiologicalStressIsOmittedWhenNotProvided() {
        val result = computeDynamicRecovery(
            sleepIndex = goodSleep,
            hrvEval = swc(EvalState.ShiftUp),
            rhrEval = swc(EvalState.ShiftDown),
            trainingLoadEval = trainingLoad(EvalState.ShiftUp),
            energyEval = subjective(1),
            stressEval = subjective(-1),
            sorenessEval = subjective(-1),
            physiologicalStress = null,
        )

        assertTrue(result.contributors.none { it.dimension == "physiological_stress" })
    }
}
