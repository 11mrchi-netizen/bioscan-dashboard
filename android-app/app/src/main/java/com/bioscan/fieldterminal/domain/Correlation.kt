package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

// LAB › EXPLORE (after NOOP's CorrelationEngine): any two daily series side by side, the second shifted by a lag, with
// Pearson's r, its 95% interval (Fisher's z) and the number of days. Descriptive only: no causal reading, no hunt for the
// best lag (the person picks it), and the interval is the honest part: few days give a wide one.

const val MIN_CORRELATION_DAYS = 10

data class Correlation(
    val n: Int,
    val r: Double,
    val lo: Double,
    val hi: Double,
    /** y on x: change in y per one unit of x. */
    val slope: Double,
    val pairs: List<Triple<LocalDate, Double, Double>>,
) {
    /** "moderate" etc. by |r| (Cohen's rough bands: 0.1 small, 0.3 moderate, 0.5 large). */
    val strength: String get() = when {
        abs(r) < 0.1 -> "no"
        abs(r) < 0.3 -> "a weak"
        abs(r) < 0.5 -> "a moderate"
        else -> "a strong"
    }
    /** True when the interval leaves out zero. */
    val clear: Boolean get() = lo > 0 || hi < 0
}

/** x on day d against y on day d + [lagDays]; null with fewer than [MIN_CORRELATION_DAYS] pairs or no spread. */
fun correlate(x: Map<LocalDate, Double>, y: Map<LocalDate, Double>, lagDays: Int, from: LocalDate? = null): Correlation? {
    val pairs = x.entries.filter { from == null || !it.key.isBefore(from) }.sortedBy { it.key }
        .mapNotNull { (d, xv) -> y[d.plusDays(lagDays.toLong())]?.let { Triple(d, xv, it) } }
        .filter { it.second.isFinite() && it.third.isFinite() }
    val n = pairs.size
    if (n < MIN_CORRELATION_DAYS) return null
    val mx = pairs.sumOf { it.second } / n
    val my = pairs.sumOf { it.third } / n
    val sxy = pairs.sumOf { (it.second - mx) * (it.third - my) }
    val sxx = pairs.sumOf { (it.second - mx) * (it.second - mx) }
    val syy = pairs.sumOf { (it.third - my) * (it.third - my) }
    if (sxx <= 0 || syy <= 0) return null
    val r = (sxy / sqrt(sxx * syy)).coerceIn(-0.999999, 0.999999)
    val z = 0.5 * ln((1 + r) / (1 - r))
    val se = 1 / sqrt((n - 3).toDouble())
    return Correlation(n, r, tanh(z - 1.96 * se), tanh(z + 1.96 * se), sxy / sxx, pairs)
}

/** "A moderate negative link: r −0.34 (95% interval −0.52 to −0.13), 64 days." */
fun correlationWords(c: Correlation): String {
    val dir = if (c.r >= 0) "positive" else "negative"
    val head = if (c.strength == "no") "No clear link" else "${c.strength.replaceFirstChar { it.uppercase() }} $dir link"
    fun f(v: Double) = (if (v < 0) "−" else "") + "%.2f".format(abs(v))
    return "$head: r ${f(c.r)} (95% interval ${f(c.lo)} to ${f(c.hi)}), ${c.n} days." +
        if (!c.clear) " The interval includes zero." else ""
}

