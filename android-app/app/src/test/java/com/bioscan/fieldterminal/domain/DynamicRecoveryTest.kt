package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicRecoveryTest {

    private val met = Confidence(14, 14)
    private val unmet = Confidence(0, 14)
    private val goodSleep = SleepIndexResult(80.0, "AS EXPECTED", EvalState.Stable, met, emptyMap())
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
            sleepIndex = SleepIndexResult(20.0, "UNUSUALLY LOW", EvalState.Stable, met, emptyMap()),
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
            sleepIndex = goodSleep, // as expected: neutral
            hrvEval = swc(EvalState.ShiftDown), // bad
            rhrEval = swc(EvalState.Building, unmet),
            trainingLoadEval = trainingLoad(EvalState.Building, unmet),
            energyEval = subjective(1), // good
            stressEval = subjective(null),
            sorenessEval = subjective(null),
        )

        assertEquals(EvalState.Unstable, result.state)
    }

    // Analysis review phase G: the Sleep Index's own cut-offs. A week of sleep as expected is neutral (it used to count as
    // support at 70 and above), and one below expected holds recovery back (it used to count only under 50).
    @Test
    fun sleepUsesTheSleepIndexCutOffs() {
        fun sleepDirection(score: Double) = computeDynamicRecovery(
            sleepIndex = SleepIndexResult(score, sleepIndexBand(score), EvalState.Stable, met, emptyMap()),
            hrvEval = swc(EvalState.Stable),
            rhrEval = swc(EvalState.Stable),
            trainingLoadEval = trainingLoad(EvalState.Building, unmet),
            energyEval = subjective(null, unmet),
            stressEval = subjective(null, unmet),
            sorenessEval = subjective(null, unmet),
        ).contributors.first { it.dimension == "sleep" }.direction
        assertEquals(0, sleepDirection(92.0))
        assertEquals(-1, sleepDirection(72.0))
        assertEquals(-1, sleepDirection(45.0))
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

    @Test
    fun morningWoodAndArousalAreOmittedWhenNotProvided() {
        val result = computeDynamicRecovery(
            sleepIndex = goodSleep,
            hrvEval = swc(EvalState.ShiftUp),
            rhrEval = swc(EvalState.ShiftDown),
            trainingLoadEval = trainingLoad(EvalState.ShiftUp),
            energyEval = subjective(1),
            stressEval = subjective(-1),
            sorenessEval = subjective(-1),
        )

        assertTrue(result.contributors.none { it.dimension == "morning_wood" || it.dimension == "arousal" })
    }

    @Test
    fun morningWoodAndArousalContributeTheSamePolarityAsEnergy() {
        val result = computeDynamicRecovery(
            sleepIndex = goodSleep,
            hrvEval = swc(EvalState.ShiftUp),
            rhrEval = swc(EvalState.ShiftDown),
            trainingLoadEval = trainingLoad(EvalState.ShiftUp),
            energyEval = subjective(1),
            stressEval = subjective(-1),
            sorenessEval = subjective(-1),
            morningWoodEval = subjective(1),
            arousalEval = subjective(-1),
        )

        assertEquals(1, result.contributors.first { it.dimension == "morning_wood" }.direction)
        assertEquals(-1, result.contributors.first { it.dimension == "arousal" }.direction)
    }

    // v2 (fix 4): the morning that read MIXED SIGNALS all through training. Load points down as it always does in a block;
    // HRV, resting HR and the answers point up, sleep as expected. Weighted, that is a clear lean up.
    @Test
    fun aGoodMorningInATrainingBlockIsAboveExpected() {
        val result = computeDynamicRecovery(
            sleepIndex = goodSleep,
            hrvEval = swc(EvalState.ShiftUp),
            rhrEval = swc(EvalState.ShiftDown),
            trainingLoadEval = trainingLoad(EvalState.ShiftDown), // loaded
            energyEval = subjective(1),
            stressEval = subjective(-1),
            sorenessEval = subjective(null),
        )
        assertEquals(EvalState.ShiftUp, result.state)
        // Five votes: sleep 0, HRV +1, RHR +1, load -1, the answers together +1.
        assertEquals(5.0, result.balance!!.votes, 1e-9)
        assertEquals(0.4, result.balance!!.net, 1e-9)
    }

    @Test
    fun theAnswersAreOneVoteTogether() {
        // Three answers up cannot outweigh HRV and resting HR both down.
        val result = computeDynamicRecovery(
            sleepIndex = goodSleep,
            hrvEval = swc(EvalState.ShiftDown),
            rhrEval = swc(EvalState.ShiftUp),
            trainingLoadEval = trainingLoad(EvalState.Stable),
            energyEval = subjective(1),
            stressEval = subjective(-1),
            sorenessEval = subjective(-1),
        )
        assertEquals(-0.2, result.balance!!.net, 1e-9)
        assertEquals(EvalState.Stable, result.state)
    }

    @Test
    fun oneUpAndOneDownAmongNeutralsIsAsExpectedNotMixed() {
        val result = computeDynamicRecovery(
            sleepIndex = goodSleep,
            hrvEval = swc(EvalState.ShiftUp),
            rhrEval = swc(EvalState.Stable),
            trainingLoadEval = trainingLoad(EvalState.ShiftDown),
            energyEval = subjective(null, unmet),
            stressEval = subjective(null, unmet),
            sorenessEval = subjective(null, unmet),
        )
        // A quarter each way: neither side reaches a third, and they cancel.
        assertEquals(EvalState.Stable, result.state)
    }

    @Test
    fun aRealSplitIsStillMixed() {
        val result = computeDynamicRecovery(
            sleepIndex = SleepIndexResult(45.0, sleepIndexBand(45.0), EvalState.Stable, met, emptyMap()), // holds back
            hrvEval = swc(EvalState.ShiftUp),
            rhrEval = swc(EvalState.ShiftDown),
            trainingLoadEval = trainingLoad(EvalState.Unstable), // heavily loaded
            energyEval = subjective(null, unmet),
            stressEval = subjective(null, unmet),
            sorenessEval = subjective(null, unmet),
        )
        assertEquals(EvalState.Unstable, result.state)
    }

    @Test
    fun theLeanThresholdIsAQuarter() {
        assertEquals(EvalState.ShiftUp, recoveryStateOf(RecoveryBalance(up = 0.25, down = 0.0, votes = 4.0)))
        assertEquals(EvalState.Stable, recoveryStateOf(RecoveryBalance(up = 0.2, down = 0.0, votes = 5.0)))
        assertEquals(EvalState.ShiftDown, recoveryStateOf(RecoveryBalance(up = 0.0, down = 0.25, votes = 4.0)))
        assertEquals(EvalState.Building, recoveryStateOf(null))
    }
}
