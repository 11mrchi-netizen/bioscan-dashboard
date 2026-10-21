package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random
import kotlin.math.exp
import kotlin.math.sqrt

// Analysis review B7 gate (docs/analysis-review/README.md, phase A): on data where nothing changes, the baseline state may
// leave Stable on at most 10% of evaluations, with or without day-to-day carry-over; a real drop still shows; and the
// band a card draws is the band that decided its pill (A2).
class SwcCalibrationTest {
    private val asOf = LocalDate.of(2026, 10, 12)

    private fun ar1(rng: Random, n: Int, rho: Double, mean: Double, sd: Double): List<Double> {
        val out = ArrayList<Double>(n)
        var x = rng.nextGaussian() * sd
        val innovation = sd * sqrt(1 - rho * rho)
        repeat(n) {
            out += mean + x
            x = rho * x + rng.nextGaussian() * innovation
        }
        return out
    }

    private fun dated(values: List<Double>) = values.mapIndexed { i, v -> asOf.minusDays((values.size - 1 - i).toLong()) to v }

    private fun shiftRate(rho: Double, sims: Int = 400): Double {
        val rng = Random(20261015L + (rho * 100).toLong())
        var shifts = 0
        repeat(sims) {
            val state = evaluateSwcStream(dated(ar1(rng, 90, rho, 4.0, 0.12)), asOf).state
            if (state == EvalState.ShiftUp || state == EvalState.ShiftDown) shifts++
        }
        return shifts.toDouble() / sims
    }

    @Test
    fun stationaryDataRarelyLeavesStable() {
        for (rho in listOf(0.0, 0.3, 0.5)) {
            val rate = shiftRate(rho)
            assertTrue("shift rate $rate at rho $rho", rate <= 0.10)
        }
    }

    @Test
    fun aLargeDropInTheLastWeekIsStillFlagged() {
        val rng = Random(7)
        var detected = 0
        val sims = 200
        repeat(sims) {
            val values = ar1(rng, 90, 0.0, 4.0, 0.12).mapIndexed { i, v -> if (i >= 83) v - 0.24 else v } // a 2-SD drop
            if (evaluateSwcStream(dated(values), asOf).state == EvalState.ShiftDown) detected++
        }
        assertTrue("detected ${detected.toDouble() / sims}", detected.toDouble() / sims >= 0.8)
    }

    @Test
    fun theDrawnHrvBandIsTheDecidingBandInMilliseconds() {
        // ln(HRV) around ln(50) with a 12% day-to-day spread: the deciding band is roughly +/-3 ms or wider.
        val rng = Random(3)
        val ln = ar1(rng, 90, 0.0, kotlin.math.ln(50.0), 0.12)
        val eval = evaluateHrv(dated(ln.map { exp(it) }), asOf)
        val ms = eval.toExpSpace()
        val oldDrawnHalfWidth = ms.mean60d!! * (eval.swcPct!! / 100.0) // what the card used to draw
        val newHalfWidth = (ms.bandHigh!! - ms.bandLow!!) / 2
        assertTrue("band $newHalfWidth ms vs old $oldDrawnHalfWidth ms", newHalfWidth > 2.5 * oldDrawnHalfWidth)
        assertEquals(exp(eval.bandLow!!), ms.bandLow!!, 1e-9)
        assertEquals(exp(eval.bandHigh!!), ms.bandHigh!!, 1e-9)
        // the state and the drawn band agree
        val weekly = ms.baseline7d!!
        val inside = weekly in ms.bandLow!!..ms.bandHigh!!
        assertEquals(inside, eval.state == EvalState.Stable || eval.state == EvalState.Unstable)
    }

    @Test
    fun carryOverIsNotFooledByALevelShift() {
        // A clean step inside the window is a regime change, not autocorrelation.
        val step = (0 until 60).map { if (it < 30) 50.0 + (it % 3) else 80.0 + (it % 3) }
        assertTrue(carryOver(step) < 0.5)
    }
}
