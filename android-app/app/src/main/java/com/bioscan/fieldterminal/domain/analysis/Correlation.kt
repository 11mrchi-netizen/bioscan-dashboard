package com.bioscan.fieldterminal.domain.analysis

import kotlin.math.sqrt

// Pairwise Pearson correlation between two already-aligned series (e.g. two
// metrics joined on the same date). Self-quantification tool only -- no
// claim of causation or clinical validity is made anywhere this is rendered.
data class CorrelationResult(val r: Double, val n: Int, val strength: String)

private const val MIN_PAIRS = 7

fun pearsonCorrelation(pairs: List<Pair<Double, Double>>): CorrelationResult? {
    if (pairs.size < MIN_PAIRS) return null
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
    return CorrelationResult(r = r, n = n, strength = correlationStrength(r))
}

// Standard |r| bands (Cohen's conventions) -- the same ones the
// sexual-health-analysis skill's own correlation cards use.
private fun correlationStrength(r: Double): String {
    val abs = kotlin.math.abs(r)
    return when {
        abs < 0.1 -> "negligible"
        abs < 0.3 -> "weak"
        abs < 0.5 -> "moderate"
        else -> "strong"
    }
}
