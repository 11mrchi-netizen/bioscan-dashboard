package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// Analysis review phase G (P11): a composite has to earn its place. Each composite names the outcome it should help
// predict, and is scored on the person's own history with rolling-origin evaluation: every morning it is turned into a
// forecast by a model fitted only on the days before (a straight line over the last 120 days with an outcome), and the
// forecast is scored with a proper scoring rule once the outcome is known -- the log score of a Gaussian forecast for a
// number, the Brier score for a yes/no. Each of its single inputs is scored the same way on the same days. A composite
// that does not beat its best single input adds nothing a reader could not get from that input alone.
//
// The verdict compares the composite with its best single input (the input with the best score on the days both were
// scored, which favours the input) by the mean difference of their daily scores, with a Newey-West interval because
// neighbouring days are not independent: EARNS ITS PLACE when the interval lies above zero, NO BETTER when it lies below
// a negligible margin (about a 2% narrower forecast, or 0.005 of Brier score), UNCLEAR in between, NOT YET while fewer
// days have been scored than the comparison needs.

enum class ScoreRule { LOG, BRIER }

/** One daily predictor: its value as known that morning, by date. */
data class ScorePredictor(val key: String, val label: String, val values: Map<LocalDate, Double>)

data class ScorecardSpec(
    val key: String,
    val label: String,
    /** What it should predict, as a phrase: "tomorrow morning's HRV against its recent level". */
    val targetText: String,
    val composite: ScorePredictor,
    val inputs: List<ScorePredictor>,
    /** The outcome, keyed on the date of the predictor it belongs to (a next-morning outcome sits on the day before). */
    val target: Map<LocalDate, Double>,
    val rule: ScoreRule,
    /** Days after a predictor's date until its outcome is known: 0 the same day, 1 the next morning. */
    val lagDays: Int = 0,
)

enum class ScorecardVerdict { EARNS_ITS_PLACE, NO_BETTER, UNCLEAR, NOT_YET }

data class PredictorScore(val key: String, val label: String, val days: Int, val meanScore: Double)

data class ScorecardResult(
    val key: String,
    val label: String,
    val targetText: String,
    val rule: ScoreRule,
    val verdict: ScorecardVerdict,
    /** Days on which the composite and its best input were both scored, and how many the comparison needs. */
    val days: Int,
    val need: Int,
    val composite: PredictorScore?,
    val best: PredictorScore?,
    val inputs: List<PredictorScore>,
    /** The same model with no predictor at all (the recent average), for reference. */
    val climatology: PredictorScore?,
    /** Composite minus best input, mean daily score (higher is better for both rules), with its 95% interval. */
    val difference: Double?,
    val lo: Double?,
    val hi: Double?,
    val sentence: String,
)

object ScorecardRules {
    const val WINDOW_DAYS = 120
    const val MIN_TRAIN = 30
    const val MIN_SCORED_LOG = 60
    const val MIN_SCORED_BRIER = 40
    const val NEGLIGIBLE_LOG = 0.02
    const val NEGLIGIBLE_BRIER = 0.005
}

private data class Pairing(val date: LocalDate, val x: Double, val y: Double)

/**
 * The predictor's daily scores: for each date with an outcome (and a predictor value), a forecast from the pairs of the
 * last [ScorecardRules.WINDOW_DAYS] days whose outcomes were already known that morning, scored against the outcome.
 * `x == null` scores the recent average alone.
 */
fun rollingScores(target: Map<LocalDate, Double>, x: Map<LocalDate, Double>?, rule: ScoreRule, lagDays: Int = 0): Map<LocalDate, Double> {
    val pairs = target.keys.sorted().mapNotNull { d ->
        val y = target[d] ?: return@mapNotNull null
        val xv = if (x == null) 0.0 else x[d] ?: return@mapNotNull null
        if (!y.isFinite() || !xv.isFinite()) null else Pairing(d, xv, y)
    }
    // An outcome keyed on day s is known on day s + lag; at the morning of t only s <= t - max(1, lag) can train.
    val gap = max(1, lagDays).toLong()
    val out = LinkedHashMap<LocalDate, Double>()
    var lo = 0
    var hi = 0
    for (pair in pairs) {
        val from = pair.date.minusDays(ScorecardRules.WINDOW_DAYS.toLong())
        val until = pair.date.minusDays(gap)
        while (lo < pairs.size && pairs[lo].date.isBefore(from)) lo++
        while (hi < pairs.size && !pairs[hi].date.isAfter(until)) hi++
        if (hi - lo < ScorecardRules.MIN_TRAIN) continue
        val train = pairs.subList(lo, hi)
        val score = when (rule) {
            ScoreRule.LOG -> gaussianLogScore(train, pair.x, pair.y, useX = x != null)
            ScoreRule.BRIER -> brierScore(train, pair.x, pair.y, useX = x != null)
        }
        if (score != null) out[pair.date] = score
    }
    return out
}

// A straight line fitted by least squares; the forecast's variance carries the residual spread and the uncertainty of
// the line at this x. A predictor that did not vary over the window falls back to the average (it can't say more).
private fun gaussianLogScore(train: List<Pairing>, xt: Double, yt: Double, useX: Boolean): Double? {
    val n = train.size
    val ym = train.sumOf { it.y } / n
    val xm = train.sumOf { it.x } / n
    val sxx = train.sumOf { (it.x - xm).pow(2) }
    val (mu, variance) = if (useX && sxx > 1e-12) {
        val b = train.sumOf { (it.x - xm) * (it.y - ym) } / sxx
        val a = ym - b * xm
        val s2 = train.sumOf { (it.y - a - b * it.x).pow(2) } / (n - 2)
        (a + b * xt) to s2 * (1 + 1.0 / n + (xt - xm).pow(2) / sxx)
    } else {
        val s2 = train.sumOf { (it.y - ym).pow(2) } / (n - 1)
        ym to s2 * (1 + 1.0 / n)
    }
    if (!variance.isFinite() || variance <= 1e-12) return null
    return -0.5 * ln(2 * PI * variance) - (yt - mu).pow(2) / (2 * variance)
}

// A logistic regression on the standardised predictor, with a light ridge penalty on the slope so a run of identical
// outcomes cannot send it to infinity. Without a predictor: the recent share, shrunk half an outcome toward one half.
private fun brierScore(train: List<Pairing>, xt: Double, yt: Double, useX: Boolean): Double? {
    val n = train.size
    val share = (train.sumOf { it.y } + 0.5) / (n + 1)
    val xm = train.sumOf { it.x } / n
    val sd = sqrt(train.sumOf { (it.x - xm).pow(2) } / max(1, n - 1))
    if (!useX || sd <= 1e-12) return -(share - yt).pow(2)
    val z = train.map { (it.x - xm) / sd }
    var a = ln(share / (1 - share))
    var b = 0.0
    repeat(30) {
        var g0 = 0.0
        var g1 = -RIDGE * b
        var h00 = 0.0
        var h01 = 0.0
        var h11 = RIDGE
        train.forEachIndexed { i, p ->
            val prob = sigmoid(a + b * z[i])
            val w = prob * (1 - prob)
            g0 += p.y - prob
            g1 += (p.y - prob) * z[i]
            h00 += w
            h01 += w * z[i]
            h11 += w * z[i] * z[i]
        }
        val det = h00 * h11 - h01 * h01
        if (abs(det) < 1e-12) return@repeat
        val da = (h11 * g0 - h01 * g1) / det
        val db = (h00 * g1 - h01 * g0) / det
        a += da
        b += db
        if (abs(da) + abs(db) < 1e-8) return -(sigmoid(a + b * (xt - xm) / sd) - yt).pow(2)
    }
    return -(sigmoid(a + b * (xt - xm) / sd) - yt).pow(2)
}

private const val RIDGE = 1.0
private fun sigmoid(v: Double) = 1.0 / (1.0 + exp(-v))

data class ScoreDifference(val days: Int, val mean: Double, val lo: Double, val hi: Double)

/** Mean of a - b over the days both were scored, with a Newey-West (Bartlett) 95% interval. */
fun compareScores(a: Map<LocalDate, Double>, b: Map<LocalDate, Double>): ScoreDifference? {
    val days = a.keys.filter { it in b }.sorted()
    if (days.size < 3) return null
    val d = days.map { a.getValue(it) - b.getValue(it) }
    val n = d.size
    val mean = d.average()
    val e = d.map { it - mean }
    val lag = floor(4 * (n / 100.0).pow(2.0 / 9.0)).toInt()
    var variance = e.sumOf { it * it } / n
    for (l in 1..lag) {
        var gamma = 0.0
        for (t in l until n) gamma += e[t] * e[t - l]
        variance += 2 * (1 - l / (lag + 1.0)) * gamma / n
    }
    val se = sqrt(max(variance, 0.0) / n)
    return ScoreDifference(n, mean, mean - 1.96 * se, mean + 1.96 * se)
}

fun scorecard(spec: ScorecardSpec): ScorecardResult {
    val need = if (spec.rule == ScoreRule.LOG) ScorecardRules.MIN_SCORED_LOG else ScorecardRules.MIN_SCORED_BRIER
    val negligible = if (spec.rule == ScoreRule.LOG) ScorecardRules.NEGLIGIBLE_LOG else ScorecardRules.NEGLIGIBLE_BRIER
    val composite = rollingScores(spec.target, spec.composite.values, spec.rule, spec.lagDays)
    val climatology = rollingScores(spec.target, null, spec.rule, spec.lagDays)
    val inputScores = spec.inputs.map { it to rollingScores(spec.target, it.values, spec.rule, spec.lagDays) }
    fun summary(p: ScorePredictor, s: Map<LocalDate, Double>) = PredictorScore(p.key, p.label, s.size, if (s.isEmpty()) Double.NaN else s.values.average())

    // The best single input: on the days it shares with the composite, the one with the best mean score.
    val eligible = inputScores.mapNotNull { (p, s) ->
        val shared = s.keys.filter { it in composite }
        if (shared.size < need) null else Triple(p, s, shared.sumOf { s.getValue(it) } / shared.size)
    }
    val bestPick = eligible.maxByOrNull { it.third }
    val diff = bestPick?.let { compareScores(composite, it.second) }
    val days = diff?.days ?: (inputScores.maxOfOrNull { (_, s) -> s.keys.count { it in composite } } ?: 0)
    val verdict = when {
        diff == null || diff.days < need -> ScorecardVerdict.NOT_YET
        diff.lo > 0 -> ScorecardVerdict.EARNS_ITS_PLACE
        diff.hi < negligible -> ScorecardVerdict.NO_BETTER
        else -> ScorecardVerdict.UNCLEAR
    }
    val bestLabel = bestPick?.first?.label?.lowercase() ?: "any single input"
    val sentence = when (verdict) {
        ScorecardVerdict.EARNS_ITS_PLACE -> "Forecast ${spec.targetText} better than $bestLabel alone over $days days: it earns its place."
        ScorecardVerdict.NO_BETTER -> "Forecast ${spec.targetText} no better than $bestLabel alone over $days days, so it is shown as its inputs."
        ScorecardVerdict.UNCLEAR -> "Not yet shown to forecast ${spec.targetText} better than $bestLabel alone ($days days scored)."
        ScorecardVerdict.NOT_YET -> "Scored on $days of the $need days needed to compare it with its inputs."
    }
    return ScorecardResult(
        key = spec.key, label = spec.label, targetText = spec.targetText, rule = spec.rule, verdict = verdict, days = days, need = need,
        composite = summary(spec.composite, composite).takeIf { composite.isNotEmpty() },
        best = bestPick?.let { summary(it.first, it.second) },
        inputs = inputScores.map { (p, s) -> summary(p, s) },
        climatology = summary(ScorePredictor("average", "Recent average", emptyMap()), climatology).takeIf { climatology.isNotEmpty() },
        difference = diff?.mean, lo = diff?.lo, hi = diff?.hi,
        sentence = sentence,
    )
}

/** A composite whose scorecard reads NO BETTER is shown as a plain summary of its inputs, without its own verdict. */
fun ScorecardResult?.demotes(): Boolean = this?.verdict == ScorecardVerdict.NO_BETTER

fun scorecardWord(verdict: ScorecardVerdict): String = when (verdict) {
    ScorecardVerdict.EARNS_ITS_PLACE -> "EARNS ITS PLACE"
    ScorecardVerdict.NO_BETTER -> "NO BETTER THAN ITS INPUTS"
    ScorecardVerdict.UNCLEAR -> "UNCLEAR"
    ScorecardVerdict.NOT_YET -> "NOT YET SCORED"
}
