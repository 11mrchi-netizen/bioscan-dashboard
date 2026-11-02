package com.bioscan.fieldterminal.domain.score

import com.bioscan.fieldterminal.domain.ScorePredictor
import com.bioscan.fieldterminal.domain.ScoreRule
import com.bioscan.fieldterminal.domain.ScorecardResult
import com.bioscan.fieldterminal.domain.ScorecardSpec
import com.bioscan.fieldterminal.domain.hrvResidualByDay
import com.bioscan.fieldterminal.domain.nextMorning
import com.bioscan.fieldterminal.domain.scorecard
import java.time.LocalDate
import kotlin.math.max

// Scoring system: does each score earn its place, and should CHARGE's weights be the person's own
// (docs/scoring-system/README.md, "How the parts connect").
//
// SCORECARDS. Each score names an outcome it should forecast and is scored with the app's existing rolling-origin test
// (domain/Scorecard.kt): every morning it is turned into a forecast fitted only on earlier days, scored once the
// outcome is known, and compared with its best single input on the same days. Unlike the old composites, a score that
// reads NO BETTER is never hidden: the verdict is reported in its overlay, and for CHARGE it drives the weight re-fit.
//
//   CHARGE    the next morning's HRV against its recent level; and, with enough planned days, whether the day's
//             planned sessions were done.
//   SLEEP     that morning's HRV against its recent level (SLEEP itself reads no HRV).
//   RECOVERY  the next morning's HRV against its recent level.
//   LOAD      the HRV two mornings on (the load a body has not absorbed yet shows up there).
//   FUEL, BALANCE  the next morning's HRV against its recent level.
// The next-morning HRV is the outcome with the most data. It favours RECOVERY, whose own HRV carries over a day; the
// comparison with the best single input is what keeps that honest.
//
// PERSONAL WEIGHTS. CHARGE's domain weights are re-fitted on the person's mornings once [MIN_FIT_DAYS] of them have
// both every scored domain it needs and the outcome. A ridge regression of the outcome on the domain scores (missing
// domains read as a typical day) gives raw weights; negative ones are dropped, the rest normalised, then pulled halfway
// back to the defaults and floored at [MIN_WEIGHT]. The fit uses only mornings before the first of the current month,
// so the weights change once a month, not every morning. They are adopted only if, on the last third of those mornings
// (held out of the fit), a straight-line forecast from CHARGE with them beats the same forecast from the default CHARGE
// -- the rule the personal fitness and fatigue spans already follow.

const val MIN_FIT_DAYS = 120
const val MIN_WEIGHT = 0.05
const val WEIGHT_SHRINK = 0.5
private const val FIT_RIDGE = 1.0
const val QUALITY_HISTORY_DAYS = 240

/** What a score is checked against, for its overlay. */
data class ScoreTarget(val text: String, val rule: ScoreRule, val lagDays: Int)

val SCORE_TARGETS: Map<DomainKey?, ScoreTarget> = mapOf(
    null to ScoreTarget("the next morning's HRV against its recent level", ScoreRule.LOG, 1),
    DomainKey.SLEEP to ScoreTarget("that morning's HRV against its recent level", ScoreRule.LOG, 0),
    DomainKey.RECOVERY to ScoreTarget("the next morning's HRV against its recent level", ScoreRule.LOG, 1),
    DomainKey.LOAD to ScoreTarget("the HRV two mornings on against its recent level", ScoreRule.LOG, 2),
    DomainKey.FUEL to ScoreTarget("the next morning's HRV against its recent level", ScoreRule.LOG, 1),
    DomainKey.BALANCE to ScoreTarget("the next morning's HRV against its recent level", ScoreRule.LOG, 1),
)

/** Per morning: CHARGE's raw value, each domain's score and each domain's input sub-scores, as the engine had them. */
data class ScoreHistory(
    val charge: Map<LocalDate, Double>,
    /** What CHARGE combined each morning: the domain scores, LOAD through its fatigue side. */
    val chargeInputs: Map<DomainKey, Map<LocalDate, Double>>,
    val domains: Map<DomainKey, Map<LocalDate, Double>>,
    val inputs: Map<DomainKey, Map<String, Map<LocalDate, Double>>>,
    val inputLabels: Map<String, String>,
)

fun ScoreEngine.history(from: LocalDate, to: LocalDate): ScoreHistory {
    val charge = HashMap<LocalDate, Double>()
    val chargeInputs = DomainKey.entries.associateWith { HashMap<LocalDate, Double>() }
    val domains = DomainKey.entries.associateWith { HashMap<LocalDate, Double>() }
    val inputs = DomainKey.entries.associateWith { HashMap<String, HashMap<LocalDate, Double>>() }
    val labels = HashMap<String, String>()
    var day = from
    while (!day.isAfter(to)) {
        chargeValue(day)?.let { charge[day] = it }
        chargeInputs(day).forEach { (k, v) -> chargeInputs.getValue(k)[day] = v }
        for (key in DomainKey.entries) {
            domainScoreValue(key, day)?.let { domains.getValue(key)[day] = it }
            for (input in domainRaw(key, day).inputs) {
                labels[input.spec.key] = input.spec.label
                inputs.getValue(key).getOrPut(input.spec.key) { HashMap() }[day] = input.score
            }
        }
        day = day.plusDays(1)
    }
    return ScoreHistory(charge, chargeInputs, domains, inputs, labels)
}

/** The outcome shifted onto the morning whose score should foresee it ([lag] mornings later). */
private fun shifted(outcome: Map<LocalDate, Double>, lag: Int): Map<LocalDate, Double> {
    var out = outcome
    repeat(lag) { out = nextMorning(out) }
    return out
}

data class ScoreQuality(
    val charge: ScorecardResult?,
    /** CHARGE against the day's planned sessions done (null without planned days). */
    val chargePlanDone: ScorecardResult?,
    val domains: Map<DomainKey, ScorecardResult>,
    val weights: ChargeWeights,
)

/** The scorecards of CHARGE and the five domains, and CHARGE's weights, from [history] and the outcomes. */
fun scoreQuality(
    history: ScoreHistory,
    series: Map<String, Series>,
    plannedDone: Map<LocalDate, Double>,
    weights: ChargeWeights,
): ScoreQuality {
    val hrv = series[ScoreKeys.HRV].orEmpty().toList().sortedBy { it.first }
    val residual = hrvResidualByDay(hrv)
    fun spec(key: String, label: String, composite: Map<LocalDate, Double>, inputs: List<ScorePredictor>, target: ScoreTarget, outcome: Map<LocalDate, Double>) =
        ScorecardSpec(
            key = key, label = label, targetText = target.text,
            composite = ScorePredictor(key, label, composite), inputs = inputs,
            target = shifted(outcome, target.lagDays), rule = target.rule, lagDays = target.lagDays,
        )
    val chargeTarget = SCORE_TARGETS.getValue(null)
    val domainPredictors = DomainKey.entries.mapNotNull { k -> history.domains[k]?.takeIf { it.isNotEmpty() }?.let { ScorePredictor(k.name, k.label, it) } }
    val charge = if (history.charge.isEmpty()) null else scorecard(spec("charge", "CHARGE", history.charge, domainPredictors, chargeTarget, residual))
    val planDone = if (plannedDone.isEmpty() || history.charge.isEmpty()) null else scorecard(
        ScorecardSpec(
            key = "charge_plan", label = "CHARGE", targetText = "whether the day's planned sessions were done",
            composite = ScorePredictor("charge", "CHARGE", history.charge), inputs = domainPredictors,
            target = plannedDone, rule = ScoreRule.BRIER,
        ),
    )
    val domains = DomainKey.entries.mapNotNull { key ->
        val values = history.domains[key]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val inputs = history.inputs[key].orEmpty().map { (k, v) -> ScorePredictor(k, history.inputLabels[k] ?: k, v) }
        key to scorecard(spec(key.name, key.label, values, inputs, SCORE_TARGETS.getValue(key), residual))
    }.toMap()
    return ScoreQuality(charge, planDone, domains, weights)
}

// ---- personal weights ----

data class ChargeWeights(
    val weights: Map<DomainKey, Double>,
    val personal: Boolean,
    /** Mornings the fit had (with the outcome), and how many it needs. */
    val days: Int,
    val need: Int = MIN_FIT_DAYS,
    /** The last morning the fit could use (the day before the first of the month). */
    val fittedThrough: LocalDate?,
    val sentence: String,
)

fun defaultWeights(days: Int = 0, fittedThrough: LocalDate? = null, sentence: String? = null) = ChargeWeights(
    CHARGE_WEIGHTS, personal = false, days = days, fittedThrough = fittedThrough,
    sentence = sentence ?: "Default weights: personal ones are fitted after $MIN_FIT_DAYS scored mornings (you have $days).",
)

/** Ridge regression of y on the columns of x (each already centred), returning the coefficients. */
private fun ridge(x: List<DoubleArray>, y: List<Double>, lambda: Double): DoubleArray {
    val k = x.first().size
    val xtx = Array(k) { DoubleArray(k) }
    val xty = DoubleArray(k)
    for (r in x.indices) {
        for (i in 0 until k) {
            xty[i] += x[r][i] * y[r]
            for (j in 0 until k) xtx[i][j] += x[r][i] * x[r][j]
        }
    }
    for (i in 0 until k) xtx[i][i] += lambda * x.size
    // Gauss-Jordan on the small k x k system.
    val a = Array(k) { i -> xtx[i].copyOf() + doubleArrayOf(xty[i]) }
    for (c in 0 until k) {
        val p = (c until k).maxByOrNull { kotlin.math.abs(a[it][c]) } ?: c
        val tmp = a[c]; a[c] = a[p]; a[p] = tmp
        val pivot = a[c][c].takeIf { kotlin.math.abs(it) > 1e-12 } ?: continue
        for (j in c..k) a[c][j] /= pivot
        for (r in 0 until k) if (r != c) {
            val f = a[r][c]
            for (j in c..k) a[r][j] -= f * a[c][j]
        }
    }
    return DoubleArray(k) { a[it][k] }
}

/** Mean squared error of a straight-line forecast of y from z, fitted on [train] and scored on [test]. */
private fun lineMse(train: List<Pair<Double, Double>>, test: List<Pair<Double, Double>>): Double {
    val mz = train.map { it.first }.average()
    val my = train.map { it.second }.average()
    val sxx = train.sumOf { (z, _) -> (z - mz) * (z - mz) }
    val b = if (sxx < 1e-12) 0.0 else train.sumOf { (z, y) -> (z - mz) * (y - my) } / sxx
    return test.map { (z, y) -> val e = y - (my + b * (z - mz)); e * e }.average()
}

private fun composite(scores: Map<DomainKey, Double>, w: Map<DomainKey, Double>): Double {
    val present = scores.keys
    val total = present.sumOf { w.getValue(it) }
    return present.sumOf { scores.getValue(it) * w.getValue(it) } / total
}

/**
 * CHARGE's weights for [today]: fitted on the mornings in [history] before the first of today's month, from each
 * morning's domain scores ([fatigue] stands in for LOAD, as in CHARGE) and the next morning's HRV residual.
 */
fun fitChargeWeights(
    domains: Map<DomainKey, Map<LocalDate, Double>>,
    hrv: Series,
    today: LocalDate,
    defaults: Map<DomainKey, Double> = CHARGE_WEIGHTS,
): ChargeWeights {
    val cutoff = today.withDayOfMonth(1).minusDays(1)
    val outcome = nextMorning(hrvResidualByDay(hrv.toList().sortedBy { it.first }))
    val keys = DomainKey.entries
    val days = outcome.keys.filter { d ->
        !d.isAfter(cutoff.minusDays(1)) && (domains[DomainKey.SLEEP]?.containsKey(d) == true || domains[DomainKey.RECOVERY]?.containsKey(d) == true)
    }.sorted()
    if (days.size < MIN_FIT_DAYS) return defaultWeights(days.size)
    val rows = days.map { d -> d to keys.associateWith { k -> domains[k]?.get(d) ?: ScoreScale.TYPICAL } }
    val split = (rows.size * 2) / 3
    val train = rows.take(split)
    val test = rows.drop(split)

    fun fit(on: List<Pair<LocalDate, Map<DomainKey, Double>>>): Map<DomainKey, Double>? {
        // Scale to SD units of the scale (about 20 points) so the ridge treats domains alike.
        val x = on.map { (_, s) -> DoubleArray(keys.size) { i -> (s.getValue(keys[i]) - ScoreScale.TYPICAL) / 20.0 } }
        val yRaw = on.map { (d, _) -> outcome.getValue(d) }
        val my = yRaw.average()
        val beta = ridge(x, yRaw.map { it - my }, FIT_RIDGE)
        val positive = keys.indices.associate { keys[it] to max(0.0, beta[it]) }
        val sum = positive.values.sum()
        if (sum <= 1e-12) return null
        val shrunk = keys.associateWith { k -> WEIGHT_SHRINK * positive.getValue(k) / sum + (1 - WEIGHT_SHRINK) * defaults.getValue(k) }
        val floored = shrunk.mapValues { max(it.value, MIN_WEIGHT) }
        val total = floored.values.sum()
        return floored.mapValues { it.value / total }
    }

    val candidate = fit(train)
        ?: return defaultWeights(days.size, cutoff, "Default weights: on your ${days.size} mornings no domain forecast the next morning better than chance.")
    fun pairs(on: List<Pair<LocalDate, Map<DomainKey, Double>>>, w: Map<DomainKey, Double>) = on.map { (d, s) -> composite(s, w) to outcome.getValue(d) }
    val personalMse = lineMse(pairs(train, candidate), pairs(test, candidate))
    val defaultMse = lineMse(pairs(train, defaults), pairs(test, defaults))
    if (personalMse >= defaultMse) {
        return defaultWeights(days.size, cutoff, "Default weights: personal ones fitted on ${days.size} mornings did not forecast held-out mornings better.")
    }
    // Adopted: refit on every morning up to the cutoff, so the weights use all the data the check allowed.
    val final = fit(rows) ?: candidate
    return ChargeWeights(final, personal = true, days = days.size, fittedThrough = cutoff,
        sentence = "Personal weights, fitted on ${days.size} mornings to ${cutoff}; they forecast held-out mornings better than the defaults.")
}
