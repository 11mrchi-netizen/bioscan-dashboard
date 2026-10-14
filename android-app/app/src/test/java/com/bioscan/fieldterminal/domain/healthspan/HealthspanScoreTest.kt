package com.bioscan.fieldterminal.domain.healthspan

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.SwcEvaluation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class HealthspanScoreTest {

    private fun input(axis: HealthspanAxis, score: Double) = HealthspanAxisInput(axis, score, Confidence(1, 1))

    @Test
    fun headlineIsTheMeanOfResolvedAxesAndContributionsSumToIt() {
        val result = computeHealthspan(
            listOf(
                input(HealthspanAxis.SLEEP, 80.0),
                input(HealthspanAxis.MOVEMENT, 60.0),
                input(HealthspanAxis.CARDIO, 40.0),
            ),
        )
        assertEquals(60.0, result.score!!, 1e-9)
        assertEquals(EvalState.Stable, result.state)
        assertEquals(Confidence(3, 7), result.confidence)
        assertEquals(result.score!!, result.axes.sumOf { it.contributionPts ?: 0.0 }, 1e-9)
        // Each of 3 resolved axes carries 1/3 of the headline: 80/3 points, headroom 20/3.
        val sleep = result.axes.first { it.axis == HealthspanAxis.SLEEP }
        assertEquals(80.0 / 3, sleep.contributionPts!!, 1e-9)
        assertEquals(20.0 / 3, sleep.headroomPts!!, 1e-9)
    }

    @Test
    fun underThreeAxesIsBuildingWithNoHeadlineAndNoContributions() {
        val result = computeHealthspan(listOf(input(HealthspanAxis.SLEEP, 80.0), input(HealthspanAxis.MOVEMENT, 60.0)))
        assertNull(result.score)
        assertEquals(EvalState.Building, result.state)
        assertTrue(result.axes.all { it.contributionPts == null })
        // The resolved axes still report their own score for the polygon.
        assertEquals(80.0, result.axes.first { it.axis == HealthspanAxis.SLEEP }.score!!, 1e-9)
    }

    @Test
    fun noAxesIsNoData() {
        val result = computeHealthspan(emptyList())
        assertEquals(EvalState.NoData, result.state)
        assertEquals(7, result.axes.size)
        assertTrue(result.axes.all { it.reason != null })
    }

    @Test
    fun unavailableAxisKeepsItsReasonAndDoesNotDragTheScore() {
        val result = computeHealthspan(
            listOf(
                input(HealthspanAxis.SLEEP, 90.0),
                input(HealthspanAxis.MOVEMENT, 90.0),
                input(HealthspanAxis.CARDIO, 90.0),
                HealthspanAxisInput.unavailable(HealthspanAxis.NUTRITION, "Needs 10 logged days"),
            ),
        )
        assertEquals(90.0, result.score!!, 1e-9)
        val nutrition = result.axes.first { it.axis == HealthspanAxis.NUTRITION }
        assertNull(nutrition.score)
        assertEquals("Needs 10 logged days", nutrition.reason)
    }

    @Test
    fun scoresAreClampedToTheZeroToHundredScale() {
        val result = computeHealthspan(
            listOf(input(HealthspanAxis.SLEEP, 140.0), input(HealthspanAxis.MOVEMENT, -5.0), input(HealthspanAxis.CARDIO, 50.0)),
        )
        assertEquals(100.0, result.axes.first { it.axis == HealthspanAxis.SLEEP }.score!!, 1e-9)
        assertEquals(0.0, result.axes.first { it.axis == HealthspanAxis.MOVEMENT }.score!!, 1e-9)
    }

    @Test
    fun cardioScoreIsFiftyAtZeroAccelerationAndRisesWhenYounger() {
        assertEquals(50.0, cardioAxisScore(0.0), 1e-9)
        assertEquals(75.0, cardioAxisScore(-10.0), 1e-9)
        assertEquals(25.0, cardioAxisScore(10.0), 1e-9)
        assertEquals(100.0, cardioAxisScore(-40.0), 1e-9)
        assertEquals(0.0, cardioAxisScore(40.0), 1e-9)
    }

    private fun swc(baseline7d: Double, mean60d: Double, met: Boolean = true) = SwcEvaluation(
        EvalState.Stable, Confidence(if (met) 4 else 1, 4), baseline7d, mean60d, 5.0, 4.0,
    )

    @Test
    fun recoveryRewardsHigherHrvAndLowerRestingHr() {
        // HRV (ln-space) 20% above its 60-day mean, RHR 20% below: both at the favourable span -> 100.
        val hrv = swc(ln(60.0), ln(50.0))
        val rhr = swc(48.0, 60.0)
        val (score, conf) = recoveryAxisScore(hrv, rhr)
        assertEquals(100.0, score!!, 1e-6)
        assertEquals(Confidence(2, 2), conf)
    }

    @Test
    fun recoveryAtBaselineIsFiftyAndUnmetEvaluationsAreIgnored() {
        val (score, conf) = recoveryAxisScore(swc(ln(50.0), ln(50.0)), swc(55.0, 55.0, met = false))
        assertEquals(50.0, score!!, 1e-6)
        assertEquals(Confidence(1, 2), conf)
        assertNull(recoveryAxisScore(null, null).first)
    }

    @Test
    fun bodyCompositionIsFullInsideTheBandAndFallsOffOutsideIt() {
        assertEquals(100.0, bodyCompositionAxisScore(18.0, "male")!!, 1e-9)
        assertEquals(90.0, bodyCompositionAxisScore(26.0, "male")!!, 1e-9)
        assertEquals(90.0, bodyCompositionAxisScore(16.0, "female")!!, 1e-9)
        assertEquals(0.0, bodyCompositionAxisScore(60.0, "male")!!, 1e-9)
    }

    @Test
    fun bodyCompositionNeedsSexRatherThanGuessing() {
        assertNull(bodyCompositionAxisScore(20.0, null))
        assertNull(bodyFatBand(null))
        assertNotNull(bodyFatBand("female"))
    }

    @Test
    fun nutritionIsTheMeanOfWhateverAdherencesExist() {
        assertEquals(70.0, nutritionAxisScore(listOf(80.0, null, 60.0))!!, 1e-9)
        assertNull(nutritionAxisScore(listOf(null, null)))
    }
}
