package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random
import kotlin.math.exp

// Analysis review phase G: the composite scorecard on synthetic histories with a known truth.
class ScorecardTest {
    private val start = LocalDate.of(2026, 1, 1)

    private fun ar1(random: Random, n: Int, phi: Double = 0.5): DoubleArray {
        val out = DoubleArray(n)
        var s = 0.0
        for (i in 0 until n) {
            s = phi * s + random.nextGaussian()
            out[i] = s
        }
        return out
    }

    private fun byDate(values: DoubleArray): Map<LocalDate, Double> = values.withIndex().associate { (i, v) -> start.plusDays(i.toLong()) to v }

    private fun spec(composite: DoubleArray, x1: DoubleArray, x2: DoubleArray, y: Map<LocalDate, Double>, rule: ScoreRule = ScoreRule.LOG) = ScorecardSpec(
        key = "test", label = "Test", targetText = "the outcome",
        composite = ScorePredictor("composite", "Composite", byDate(composite)),
        inputs = listOf(ScorePredictor("x1", "First input", byDate(x1)), ScorePredictor("x2", "Second input", byDate(x2))),
        target = y, rule = rule,
    )

    @Test
    fun aCompositeThatCombinesTwoUsefulInputsEarnsItsPlace() {
        val random = Random(11)
        val x1 = ar1(random, 300)
        val x2 = ar1(random, 300)
        val y = DoubleArray(300) { 0.6 * x1[it] + 0.6 * x2[it] + 0.8 * random.nextGaussian() }
        val result = scorecard(spec(DoubleArray(300) { x1[it] + x2[it] }, x1, x2, byDate(y)))
        assertEquals(ScorecardVerdict.EARNS_ITS_PLACE, result.verdict)
        assertTrue(result.lo!! > 0)
        assertTrue(result.sentence, result.sentence.contains("earns its place"))
        assertTrue(result.days >= ScorecardRules.MIN_SCORED_LOG)
    }

    @Test
    fun aNoisyCopyOfOneInputIsNoBetterThanThatInput() {
        val random = Random(12)
        val x1 = ar1(random, 300)
        val x2 = ar1(random, 300)
        val y = DoubleArray(300) { 0.8 * x1[it] + 0.8 * random.nextGaussian() }
        val result = scorecard(spec(DoubleArray(300) { x1[it] + 1.5 * random.nextGaussian() }, x1, x2, byDate(y)))
        assertEquals(ScorecardVerdict.NO_BETTER, result.verdict)
        assertEquals("x1", result.best!!.key)
        assertTrue(result.demotes())
    }

    // About 2.5% of histories at most could read EARNS ITS PLACE by chance (less, since the best input is picked after
    // looking); none of 40 here should be far above that.
    @Test
    fun withNothingToPredictACompositeRarelyEarnsItsPlace() {
        var earns = 0
        repeat(40) { seed ->
            val random = Random(100L + seed)
            val x1 = ar1(random, 300)
            val x2 = ar1(random, 300)
            val y = DoubleArray(300) { random.nextGaussian() }
            val result = scorecard(spec(DoubleArray(300) { x1[it] + x2[it] }, x1, x2, byDate(y)))
            if (result.verdict == ScorecardVerdict.EARNS_ITS_PLACE) earns++
        }
        assertTrue("$earns of 40", earns <= 3)
    }

    @Test
    fun tooFewDaysIsNotYet() {
        val random = Random(13)
        val x1 = ar1(random, 70)
        val x2 = ar1(random, 70)
        val y = DoubleArray(70) { x1[it] + random.nextGaussian() }
        val result = scorecard(spec(x1, x1, x2, byDate(y)))
        assertEquals(ScorecardVerdict.NOT_YET, result.verdict)
        assertTrue(result.sentence, result.sentence.startsWith("Scored on"))
    }

    @Test
    fun theBrierRuleScoresYesNoOutcomes() {
        val random = Random(14)
        val x1 = ar1(random, 300)
        val x2 = ar1(random, 300)
        val y = DoubleArray(300) { if (random.nextDouble() < 1 / (1 + exp(-1.5 * (x1[it] + x2[it])))) 1.0 else 0.0 }
        val result = scorecard(spec(DoubleArray(300) { x1[it] + x2[it] }, x1, x2, byDate(y), ScoreRule.BRIER))
        assertEquals(ScorecardVerdict.EARNS_ITS_PLACE, result.verdict)
        assertTrue(result.composite!!.meanScore < 0 && result.composite!!.meanScore > -0.25)
    }

    // A day whose own outcome were in its training set would forecast itself: a one-day spike shared by predictor and
    // outcome must score as a miss on that day.
    @Test
    fun aForecastNeverTrainsOnItsOwnOutcome() {
        val random = Random(15)
        val x = DoubleArray(200) { random.nextGaussian() }
        val y = DoubleArray(200) { random.nextGaussian() }
        x[150] = 1000.0
        y[150] = 1000.0
        val scores = rollingScores(byDate(y), byDate(x), ScoreRule.LOG)
        // Trained on its own spike it would forecast it almost exactly (a score near -1.3); without, it misses by far.
        assertTrue("${scores[start.plusDays(150)]}", scores.getValue(start.plusDays(150)) < -20)
        // A next-morning outcome (lag 1) trains on the same past days; a lag of 2 waits one more day.
        val lag2 = rollingScores(byDate(y), byDate(x), ScoreRule.LOG, lagDays = 2)
        assertEquals(start.plusDays(ScorecardRules.MIN_TRAIN + 1L), lag2.keys.first())
        assertEquals(start.plusDays(ScorecardRules.MIN_TRAIN.toLong()), rollingScores(byDate(y), byDate(x), ScoreRule.LOG).keys.first())
    }

    @Test
    fun compareScoresGivesTheMeanDifferenceWithAnInterval() {
        val a = (0 until 100).associate { start.plusDays(it.toLong()) to -1.0 }
        val b = (0 until 100).associate { start.plusDays(it.toLong()) to -1.5 }
        val d = compareScores(a, b)!!
        assertEquals(0.5, d.mean, 1e-12)
        assertEquals(0.5, d.lo, 1e-9)
        assertEquals(100, d.days)
    }
}
