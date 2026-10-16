package com.bioscan.fieldterminal.domain.analysis

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

// Pairwise Pearson correlation between two already-aligned series (e.g. two
// metrics joined on the same date). Self-quantification tool only -- no
// claim of causation or clinical validity is made anywhere this is rendered.
//
// Analysis review A4: r now comes with its 95% interval (Fisher z), the minimum is 20 pairs instead of 7, and the
// strength word is only given when the interval excludes zero. At 7 pairs an r of 0.5 has an interval from about -0.4
// to 0.9, so "moderate" could not be told apart from no relationship or a strong one.
data class CorrelationResult(val r: Double, val n: Int, val strength: String, val lower: Double, val upper: Double) {
    val clear: Boolean get() = lower > 0.0 || upper < 0.0
}

const val MIN_CORRELATION_PAIRS = 20

fun pearsonCorrelation(pairs: List<Pair<Double, Double>>): CorrelationResult? {
    if (pairs.size < MIN_CORRELATION_PAIRS) return null
    val n = pairs.size
    val xs = pairs.map { it.first }
    val ys = pairs.map { it.second }
    val xMean = xs.average()
    val yMean = ys.average()
    var cov = 0.0
    var xVar = 0.0
    var yVar = 0.0
    for (i in 0 until n) {
        val dx = xs[i] - xMean
        val dy = ys[i] - yMean
        cov += dx * dy
        xVar += dx * dx
        yVar += dy * dy
    }
    if (xVar == 0.0 || yVar == 0.0) return null
    val r = cov / sqrt(xVar * yVar)
    val (lower, upper) = fisherInterval(r, n)
    val clear = lower > 0.0 || upper < 0.0
    return CorrelationResult(r = r, n = n, strength = if (clear) correlationStrength(r) else "unclear", lower = lower, upper = upper)
}

// 95% interval for r via Fisher's z = atanh(r), whose standard error is 1 / sqrt(n - 3). It assumes independent days,
// so for autocorrelated daily data it is, if anything, too narrow -- one more reason the card asks for 20 pairs.
fun fisherInterval(r: Double, n: Int): Pair<Double, Double> {
    if (n <= 3) return -1.0 to 1.0
    val rc = r.coerceIn(-0.999999, 0.999999)
    val z = 0.5 * ln((1 + rc) / (1 - rc))
    val half = 1.959964 / sqrt((n - 3).toDouble())
    return tanh(z - half) to tanh(z + half)
}

private fun tanh(x: Double): Double {
    val e2 = exp(2 * x)
    return (e2 - 1) / (e2 + 1)
}

// Standard |r| bands (Cohen's conventions) -- the same ones the
// sexual-health-analysis skill's own correlation cards use.
private fun correlationStrength(r: Double): String {
    val a = abs(r)
    return when {
        a < 0.1 -> "negligible"
        a < 0.3 -> "weak"
        a < 0.5 -> "moderate"
        else -> "strong"
    }
}
