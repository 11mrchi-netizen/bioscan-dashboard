package com.bioscan.fieldterminal.domain.aging

import com.bioscan.fieldterminal.domain.LiftReading
import com.bioscan.fieldterminal.domain.SRI_REGULAR
import com.bioscan.fieldterminal.domain.SriEvaluation
import com.bioscan.fieldterminal.domain.StrengthLevel
import com.bioscan.fieldterminal.domain.comparison.extendedPercentile
import com.bioscan.fieldterminal.domain.levels.ladderFraction
import com.bioscan.fieldterminal.domain.strengthLevelFor
import com.bioscan.fieldterminal.domain.strengthThresholds
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// Analysis Layer 3 / Phase 8. A general "healthspan" score, product-defined and not clinically validated. It exists ONLY for
// the User profile page (a unit test fails if any other screen imports it): everywhere else this app deliberately shows no
// cross-metric score.
//
// Eight factors, each scored 1-100 or left unassessed. A factor is assessed only when every input passes the trust gate:
// present, recent enough, confidence met, units known and matching, no conflicting duplicates, nothing assumed.
//
// One scale for every factor: favorable from 50, neutral from 25, an opportunity below. A factor with a population table
// (VO2max, handgrip) scores its percentile for the person's age and sex. The others score against
// cited clinical targets or the lifter standards, through anchors that put each cited cut-off exactly on the 50 or 25 line;
// the band a value falls in is still decided by that cut-off's own comparison (a triglyceride of exactly 150 counts as
// flagged, as the guideline says), and the score is held inside it. The summary score is the mean of the assessed factors,
// shown from HEALTHSPAN_MIN_ASSESSED; below that it stays "preliminary". Anchors are named below with their source; the
// points between and beyond the cited cut-offs are product-defined.

enum class HealthspanFactorId(val label: String) {
    CARDIO_FITNESS("Cardio fitness"),
    BIOLOGICAL_AGE("Biological age"),
    STRENGTH("Strength"),
    BODY_COMPOSITION("Body composition"),
    SLEEP("Sleep"),
    ACTIVITY("Activity"),
    METABOLIC("Blood lipids"),
    GLYCAEMIC("Blood glucose"),
}

enum class FactorStatus { UNASSESSED, FAVORABLE, NEUTRAL, OPPORTUNITY }

data class DatedValue(
    val value: Double,
    val date: LocalDate,
    val unit: String? = null,
    // More than one different value recorded for this marker on its latest date.
    val conflicting: Boolean = false,
)

data class HealthspanInputs(
    val asOf: LocalDate = LocalDate.now(),
    val sex: String? = null, // "male" | "female"
    val vo2max: DatedValue? = null, // mL/kg/min
    // Placement against an age- and sex-matched table; null when none fits (never a nearest-table guess).
    val vo2Percentile: Double? = null,
    // That table's published points, so the score can go past its 10th and 90th percentile (extendedPercentile).
    val vo2Quantiles: Map<Int, Double>? = null,
    val phenoAge: BiologicalAgeResult? = null,
    // Best estimated 1RM per lift from the last 6 months, and the latest body weight (kg).
    val lifts: List<LiftReading> = emptyList(),
    val bodyWeightKg: DatedValue? = null,
    // Logged by hand; scored as a percentile only when an age- and sex-matched grip table fits (gripPercentile), with that
    // table's points for the tails as for VO2max.
    val grip: GripReading? = null,
    val gripPercentile: Double? = null,
    val gripQuantiles: Map<Int, Double>? = null,
    val waistCm: DatedValue? = null,
    val heightCm: DatedValue? = null,
    val sleepMeanHours: Double? = null,
    val sleepNights: Int = 0,
    val sri: SriEvaluation? = null,
    val stepsMedian: Double? = null,
    val stepsDays: Int = 0,
    val stepsBand: String? = null, // Tudor-Locke & Bassett category
    val triglyceridesMgDl: DatedValue? = null,
    val hdlMgDl: DatedValue? = null,
    val hba1cPct: DatedValue? = null,
    val fastingGlucoseMgDl: DatedValue? = null,
)

data class GripReading(val kg: Double, val hand: String, val date: LocalDate)

// What a factor's number is measured against.
enum class ScoreBasis(val label: String) {
    POPULATION_PERCENTILE("percentile for your age and sex"),
    CLINICAL_TARGET("against clinical targets"),
    PRODUCT_DEFINED("against product-defined targets"),
    LIFTER_STANDARD("on the lifter standards"),
    MIXED("lifter standards and grip percentile, averaged"),
}

data class HealthspanFactor(
    val id: HealthspanFactorId,
    val status: FactorStatus,
    val reading: String?, // what was measured, with its date where it matters
    val missingInputs: List<String>,
    val action: String?, // one thing: how to get it assessed, or the main lever
    val notes: List<String> = emptyList(), // caveats that belong to this factor
    val score: Int? = null, // 1..100, null when unassessed
    val basis: ScoreBasis? = null,
)

data class HealthspanSummary(
    val factors: List<HealthspanFactor>,
    val assessed: Int,
    val favorable: Int,
    val score: Int?, // mean of the assessed factors' scores; null while preliminary
    val preliminary: Boolean,
)

// ---- Provisional parameters (product-defined; see docs/analysis-layer-3/healthspan.md) ----
const val HEALTHSPAN_MIN_ASSESSED = 5
// The shared scale's two lines.
const val SCORE_FAVORABLE = 50
const val SCORE_NEUTRAL = 25

private const val LAB_MAX_AGE_DAYS = 365L
private const val VO2_MAX_AGE_DAYS = 90L
private const val MIN_NIGHTS = 14
private const val MIN_STEP_DAYS = 14
private const val STRENGTH_WINDOW_DAYS = 183L // about 6 months
private const val MIN_LIFTS = 3
private const val BODY_WEIGHT_MAX_AGE_DAYS = 90L

private const val PERCENTILE_FAVORABLE = 50.0 // at or above the age/sex median (Mandsager 2018 groups by percentile)
private const val PERCENTILE_NEUTRAL = 25.0 // below this is the lowest-fitness group in that study
private const val PHENOAGE_FAVORABLE_YEARS = -2.0
private const val PHENOAGE_OPPORTUNITY_YEARS = 2.0
private const val WHTR_FAVORABLE = 0.5 // Ashwell 2012: keep waist under half of height
private const val WHTR_OPPORTUNITY = 0.6 // product-defined step
private const val SLEEP_MIN_HOURS = 7.0 // Watson 2015 consensus: adults at least 7 h
private const val SLEEP_SHORT_HOURS = 6.0 // product-defined: the next step down
private const val SRI_IRREGULAR = 60.0 // product-defined: the next step down from SRI_REGULAR
private const val TRIGLYCERIDES_FLAG = 150.0 // Alberti 2009 harmonised criteria
private const val TRIGLYCERIDES_HIGH = 200.0 // NCEP ATP III "high"
private const val HDL_FLAG_MALE = 40.0
private const val HDL_FLAG_FEMALE = 50.0
private const val HDL_LOW_STEP = 10.0 // product-defined: 10 mg/dL under the flag
private const val HBA1C_NEUTRAL = 5.7 // ADA Standards of Care
private const val HBA1C_OPPORTUNITY = 6.5
private const val GLUCOSE_NEUTRAL = 100.0
private const val GLUCOSE_OPPORTUNITY = 126.0

// Anchors, value -> score, values ascending. The 50 and 25 points are the cut-offs above; the rest is product-defined unless
// a source is named.
private val PHENOAGE_ANCHORS = listOf(-10.0 to 100.0, PHENOAGE_FAVORABLE_YEARS to 50.0, PHENOAGE_OPPORTUNITY_YEARS to 25.0, 10.0 to 1.0)
// Ashwell & Gibson 2016 boundary chart: 0.4, 0.5, 0.6. Under 0.4 is "take care" too, so the score falls back towards the
// line there (never below it: those ratios are favorable by the 0.5 cut-off).
private val WHTR_ANCHORS = listOf(0.30 to 50.0, 0.40 to 100.0, WHTR_FAVORABLE to 50.0, WHTR_OPPORTUNITY to 25.0, 0.70 to 1.0)
// Long sleep is not penalised (the association is not shown to be causal). 8 h is the middle of the 7-9 h range.
private val SLEEP_HOURS_ANCHORS = listOf(4.5 to 1.0, SLEEP_SHORT_HOURS to 25.0, SLEEP_MIN_HOURS to 50.0, 8.0 to 100.0)
private val SRI_ANCHORS = listOf(40.0 to 1.0, SRI_IRREGULAR to 25.0, SRI_REGULAR to 50.0, 90.0 to 100.0)
// Tudor-Locke & Bassett 2004 bands: 7,500 somewhat active, 10,000 active, 12,500 highly active.
private val STEPS_ANCHORS = listOf(2_500.0 to 1.0, 7_500.0 to 25.0, 10_000.0 to 50.0, 12_500.0 to 100.0)
// AHA 2011 scientific statement: under 100 mg/dL is optimal; ATP III: 500 is "very high".
private val TRIGLYCERIDES_ANCHORS = listOf(100.0 to 100.0, TRIGLYCERIDES_FLAG to 50.0, TRIGLYCERIDES_HIGH to 25.0, 500.0 to 1.0)
// ATP III: 60 mg/dL or more is protective.
private fun hdlAnchors(limit: Double) = listOf(limit - 2 * HDL_LOW_STEP to 1.0, limit - HDL_LOW_STEP to 25.0, limit to 50.0, 60.0 to 100.0)
// Selvin 2010 (NEJM): risk lowest from 5.0 to 5.4 %.
private val HBA1C_ANCHORS = listOf(5.4 to 100.0, HBA1C_NEUTRAL to 50.0, HBA1C_OPPORTUNITY to 25.0, 9.0 to 1.0)
// 70-90 mg/dL is the normal fasting range; under 70 is hypoglycaemia, so the score falls back to the line there.
private val GLUCOSE_ANCHORS = listOf(60.0 to 50.0, 70.0 to 100.0, 90.0 to 100.0, GLUCOSE_NEUTRAL to 50.0, GLUCOSE_OPPORTUNITY to 25.0, 200.0 to 1.0)

fun statusFor(score: Int): FactorStatus = when {
    score >= SCORE_FAVORABLE -> FactorStatus.FAVORABLE
    score >= SCORE_NEUTRAL -> FactorStatus.NEUTRAL
    else -> FactorStatus.OPPORTUNITY
}

// Piecewise linear through the anchors, flat past the first and last.
internal fun anchored(value: Double, anchors: List<Pair<Double, Double>>): Double {
    if (value <= anchors.first().first) return anchors.first().second
    if (value >= anchors.last().first) return anchors.last().second
    for (k in 0 until anchors.size - 1) {
        val (v0, s0) = anchors[k]
        val (v1, s1) = anchors[k + 1]
        if (value <= v1) return s0 + (value - v0) / (v1 - v0) * (s1 - s0)
    }
    return anchors.last().second
}

// Two parts that are each a risk criterion (lipids, sleep): the weaker part, and an opportunity when both are below the
// favorable line, as the flag count had it. One good part never makes up for the other.
internal fun weakerPart(a: Int, b: Int): Int =
    if (a < SCORE_FAVORABLE && b < SCORE_FAVORABLE) minOf(a, b, SCORE_NEUTRAL - 1) else minOf(a, b)

// A whole-number score held inside the band its cut-offs give, so number and status never disagree.
internal fun scoreIn(band: FactorStatus, raw: Double): Int {
    val s = kotlin.math.floor(raw).toInt()
    return when (band) {
        FactorStatus.FAVORABLE -> s.coerceIn(SCORE_FAVORABLE, 100)
        FactorStatus.NEUTRAL -> s.coerceIn(SCORE_NEUTRAL, SCORE_FAVORABLE - 1)
        FactorStatus.OPPORTUNITY -> s.coerceIn(1, SCORE_NEUTRAL - 1)
        FactorStatus.UNASSESSED -> s.coerceIn(1, 100)
    }
}

// The grip reading to show: among the readings on the most recent date, the stronger hand's value.
fun latestGrip(readings: List<GripReading>): GripReading? {
    val latest = readings.map { it.date }.reduceOrNull { a, b -> if (b.isAfter(a)) b else a } ?: return null
    return readings.filter { it.date == latest }.maxByOrNull { it.kg }
}

// Last value of a marker across draws. Flags a conflict when the latest date holds more than one different value.
data class LabObservation(val marker: String, val value: Double, val unit: String?, val date: LocalDate)

fun latestLab(observations: List<LabObservation>, matches: (String) -> Boolean): DatedValue? {
    val mine = observations.filter { matches(it.marker) }
    // LocalDate is Comparable<ChronoLocalDate>, so maxOf/maxOfOrNull would widen the type; compare explicitly.
    val latest = mine.map { it.date }.reduceOrNull { a, b -> if (b.isAfter(a)) b else a } ?: return null
    val onLatest = mine.filter { it.date == latest }
    val first = onLatest.first()
    val conflicting = onLatest.any { abs(it.value - first.value) > 1e-9 || !it.unit.equals(first.unit, ignoreCase = true) }
    return DatedValue(first.value, latest, first.unit, conflicting)
}

private class Gate(val asOf: LocalDate) {
    val missing = mutableListOf<String>()

    // The value when it passes every check; otherwise records why not and returns null.
    fun lab(v: DatedValue?, name: String, unit: String, maxAgeDays: Long = LAB_MAX_AGE_DAYS): Double? {
        if (v == null) { missing += name; return null }
        if (v.conflicting) { missing += "$name (conflicting values on ${v.date})"; return null }
        if (v.unit == null || !v.unit.equals(unit, ignoreCase = true)) { missing += "$name in $unit (recorded as ${v.unit ?: "no unit"})"; return null }
        if (ChronoUnit.DAYS.between(v.date, asOf) > maxAgeDays) {
            missing += "$name from " + (if (maxAgeDays == LAB_MAX_AGE_DAYS) "the last year" else "the last $maxAgeDays days") + " (latest ${v.date})"
            return null
        }
        return v.value
    }
}

private fun percentileStatus(p: Double): FactorStatus = when {
    p >= PERCENTILE_FAVORABLE -> FactorStatus.FAVORABLE
    p >= PERCENTILE_NEUTRAL -> FactorStatus.NEUTRAL
    else -> FactorStatus.OPPORTUNITY
}

private fun ordinal(p: Double): String {
    val n = p.toInt()
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$n$suffix"
}

private fun unassessed(id: HealthspanFactorId, missing: List<String>, howTo: String) =
    HealthspanFactor(id = id, status = FactorStatus.UNASSESSED, reading = null, missingInputs = missing, action = howTo)

private fun factor(id: HealthspanFactorId, score: Int, basis: ScoreBasis, reading: String, lever: String): HealthspanFactor {
    val status = statusFor(score)
    return HealthspanFactor(
        id = id,
        status = status,
        reading = reading,
        missingInputs = emptyList(),
        action = if (status == FactorStatus.OPPORTUNITY) lever else null,
        score = score,
        basis = basis,
    )
}

private class Placed(val score: Int, val text: String)

// A population-table placement on the scale. Inside the table the clamped and the extended percentile are the same; past
// its 10th or 90th the extended one keeps going (an estimate, and the text says so). The band comes from the clamped one.
private fun placed(value: Double, percentile: Double, quantiles: Map<Int, Double>?): Placed {
    val extended = quantiles?.let { extendedPercentile(value, it) } ?: percentile
    val lowest = quantiles?.keys?.minOrNull()
    val highest = quantiles?.keys?.maxOrNull()
    val beyond = (lowest != null && percentile <= lowest) || (highest != null && percentile >= highest)
    val score = scoreIn(percentileStatus(percentile), extended)
    return Placed(score, "${ordinal(score.toDouble())} percentile for your age and sex" + if (beyond) " (past the table's published range: estimated)" else "")
}

private fun cardio(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.CARDIO_FITNESS
    val howTo = "Set sex and date of birth in Setup > Profile and keep a watch VO2max estimate coming in."
    val missing = mutableListOf<String>()
    val v = i.vo2max
    if (v == null) missing += "a VO2max reading"
    else if (ChronoUnit.DAYS.between(v.date, i.asOf) > VO2_MAX_AGE_DAYS) missing += "a VO2max reading from the last $VO2_MAX_AGE_DAYS days (latest ${v.date})"
    if (i.vo2Percentile == null) missing += "an age- and sex-matched VO2max table that fits (needs sex and date of birth)"
    if (missing.isNotEmpty() || v == null || i.vo2Percentile == null) return unassessed(id, missing, howTo)
    val placed = placed(v.value, i.vo2Percentile, i.vo2Quantiles)
    val reading = "VO2max %.1f mL/kg/min, %s".format(v.value, placed.text)
    val score = placed.score
    return factor(id, score, ScoreBasis.POPULATION_PERCENTILE, reading, "More easy aerobic time each week moves this most.")
}

private fun biologicalAge(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.BIOLOGICAL_AGE
    val howTo = "Add a lab panel with the nine PhenoAge markers (Labs tile)."
    val p = i.phenoAge
    val missing = mutableListOf<String>()
    when {
        p == null -> missing += "a lab panel with the nine PhenoAge markers"
        !p.isAvailable -> missing += (p.unavailableReason ?: "a complete PhenoAge result")
        !p.confidence.met -> missing += "a PhenoAge result with its inputs complete"
        ChronoUnit.DAYS.between(p.observedAt, i.asOf) > LAB_MAX_AGE_DAYS -> missing += "a PhenoAge panel from the last year (latest ${p.observedAt})"
    }
    val accel = p?.ageAcceleration
    if (missing.isNotEmpty() || p == null || accel == null) return unassessed(id, missing, howTo)
    val band = when {
        accel <= PHENOAGE_FAVORABLE_YEARS -> FactorStatus.FAVORABLE
        accel < PHENOAGE_OPPORTUNITY_YEARS -> FactorStatus.NEUTRAL
        else -> FactorStatus.OPPORTUNITY
    }
    val dir = if (accel < 0) "younger" else "older"
    return factor(
        id, scoreIn(band, anchored(accel, PHENOAGE_ANCHORS)), ScoreBasis.PRODUCT_DEFINED,
        "PhenoAge %.1f y, %.1f y %s than your calendar age (panel of %s)".format(p.biologicalAge, abs(accel), dir, p.observedAt),
        "Open the Aging page to see which markers drive the gap.",
    )
}

private fun levelBand(level: StrengthLevel): FactorStatus = when {
    level >= StrengthLevel.INTERMEDIATE -> FactorStatus.FAVORABLE
    level == StrengthLevel.NOVICE -> FactorStatus.NEUTRAL
    else -> FactorStatus.OPPORTUNITY
}

private fun strength(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.STRENGTH
    val howTo = "Log barbell lifts (squat, deadlift, presses, row) and a body weight, or a grip test at the body check-in."
    val notes = mutableListOf("Lifter standards (bodyweight multiples), not population percentiles; not adjusted for sex or age.")
    val g = Gate(i.asOf)
    val bw = g.lab(i.bodyWeightKg, "body weight", "kg", BODY_WEIGHT_MAX_AGE_DAYS)
    // Each lift: its level and its place on the ladder (novice 25, intermediate 50, advanced 75, elite 100; linear between).
    data class Rated(val slug: String, val level: StrengthLevel, val score: Int)
    val rated = i.lifts
        .filter { ChronoUnit.DAYS.between(it.date, i.asOf) in 0..STRENGTH_WINDOW_DAYS }
        .mapNotNull { lift ->
            val level = strengthLevelFor(lift.slug, lift.e1rmKg, bw) ?: return@mapNotNull null
            val thresholds = strengthThresholds(lift.slug, bw) ?: return@mapNotNull null
            Rated(lift.slug, level, scoreIn(levelBand(level), ladderFraction(thresholds.toList(), lift.e1rmKg) * 100))
        }
    val liftsOk = rated.size >= MIN_LIFTS
    if (!liftsOk) g.missing += "$MIN_LIFTS lifts from the last 6 months that have a standard (have ${rated.size})"
    // Lower median: with an even count the weaker middle lift decides.
    val sorted = rated.sortedBy { it.score }
    val median = if (liftsOk) sorted[(sorted.size - 1) / 2] else null
    // A grip reading counts from the same six months as the lifts.
    val grip = i.grip
    val gripRecent = grip != null && ChronoUnit.DAYS.between(grip.date, i.asOf) in 0..STRENGTH_WINDOW_DAYS
    val gripPercentile = i.gripPercentile?.takeIf { gripRecent }
    val gripPlaced = if (grip != null && gripPercentile != null) placed(grip.kg, gripPercentile, i.gripQuantiles) else null
    val gripScore = gripPlaced?.score
    grip?.let {
        notes += if (gripPlaced != null) "Grip %.1f kg (%s hand, %s): %s.".format(it.kg, it.hand, it.date, gripPlaced.text)
        else if (!gripRecent) "Latest grip: %.1f kg (%s hand, %s). Older than 6 months, so it does not change the score.".format(it.kg, it.hand, it.date)
        else "Latest grip: %.1f kg (%s hand, %s). Logged only: no grip table fits your age and sex, so it does not change the score.".format(it.kg, it.hand, it.date)
    }
    if (median == null && gripScore == null) return unassessed(id, g.missing, howTo).copy(notes = notes)
    val parts = listOfNotNull(median?.score, gripScore)
    val score = kotlin.math.round(parts.average()).toInt().coerceIn(1, 100)
    val basis = when {
        median != null && gripScore != null -> ScoreBasis.MIXED
        median != null -> ScoreBasis.LIFTER_STANDARD
        else -> ScoreBasis.POPULATION_PERCENTILE
    }
    val reading = if (median != null) {
        val detail = sorted.joinToString(", ") { r -> "${r.slug.replace('_', ' ')} ${r.level.label.lowercase()} ${r.score}" }
        "Median ${median.level.label.lowercase()} across ${rated.size} lifts ($detail)"
    } else {
        "Grip %.1f kg, %s".format(grip!!.kg, gripPlaced!!.text)
    }
    return factor(id, score, basis, reading, "Progressive loading on the weakest main lift moves this.").copy(notes = notes)
}

private fun bodyComposition(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.BODY_COMPOSITION
    val howTo = "Measure your waist at the monthly body check-in (Fuel › Body), and set your height."
    val g = Gate(i.asOf)
    val waist = g.lab(i.waistCm, "waist circumference", "cm")
    val height = g.lab(i.heightCm, "height", "cm")
    val ratio = if (waist != null && height != null && height > 0) waist / height else null
    if (ratio != null && ratio !in 0.3..1.0) g.missing += "a plausible waist and height"
    if (ratio == null || g.missing.isNotEmpty()) return unassessed(id, g.missing, howTo)
    val band = when {
        ratio < WHTR_FAVORABLE -> FactorStatus.FAVORABLE
        ratio < WHTR_OPPORTUNITY -> FactorStatus.NEUTRAL
        else -> FactorStatus.OPPORTUNITY
    }
    return factor(
        id, scoreIn(band, anchored(ratio, WHTR_ANCHORS)), ScoreBasis.CLINICAL_TARGET,
        "Waist-to-height %.2f (waist %.0f cm, height %.0f cm)".format(ratio, waist, height), "Waist is the lever; re-measure monthly.",
    )
}

// Sleep and lipids have two parts, combined by weakerPart.
private fun sleep(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.SLEEP
    val howTo = "Wear the watch to bed for two weeks."
    val missing = mutableListOf<String>()
    if (i.sleepMeanHours == null || i.sleepNights < MIN_NIGHTS) missing += "$MIN_NIGHTS nights of sleep duration (have ${i.sleepNights})"
    val sri = i.sri
    if (sri?.value == null || !sri.confidence.met) missing += "$MIN_NIGHTS nights of bed and wake times for regularity (have ${sri?.confidence?.have ?: 0})"
    val hours = i.sleepMeanHours
    val regularity = sri?.value
    if (missing.isNotEmpty() || hours == null || regularity == null) return unassessed(id, missing, howTo)
    val hoursBand = when { hours >= SLEEP_MIN_HOURS -> FactorStatus.FAVORABLE; hours >= SLEEP_SHORT_HOURS -> FactorStatus.NEUTRAL; else -> FactorStatus.OPPORTUNITY }
    val sriBand = when { regularity >= SRI_REGULAR -> FactorStatus.FAVORABLE; regularity >= SRI_IRREGULAR -> FactorStatus.NEUTRAL; else -> FactorStatus.OPPORTUNITY }
    val hoursScore = scoreIn(hoursBand, anchored(hours, SLEEP_HOURS_ANCHORS))
    val sriScore = scoreIn(sriBand, anchored(regularity, SRI_ANCHORS))
    val lever = if (sriScore <= hoursScore) "Hold a fixed wake time first; regularity is the cheaper lever." else "Protect time in bed to reach 7 hours."
    return factor(
        id, weakerPart(hoursScore, sriScore), ScoreBasis.CLINICAL_TARGET,
        "%.1f h average over %d nights (%d), regularity (SRI) %.0f (%d)".format(hours, i.sleepNights, hoursScore, regularity, sriScore), lever,
    )
}

private fun activity(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.ACTIVITY
    val howTo = "Wear the watch through the day for two weeks."
    val missing = mutableListOf<String>()
    if (i.stepsMedian == null || i.stepsDays < MIN_STEP_DAYS) missing += "$MIN_STEP_DAYS days of steps (have ${i.stepsDays})"
    if (i.stepsBand == null) missing += "a steps reference"
    val band = i.stepsBand
    val median = i.stepsMedian
    if (missing.isNotEmpty() || band == null || median == null) return unassessed(id, missing, howTo)
    val status = when (band) {
        "active", "highly_active" -> FactorStatus.FAVORABLE
        "somewhat_active" -> FactorStatus.NEUTRAL
        else -> FactorStatus.OPPORTUNITY
    }
    return factor(
        id, scoreIn(status, anchored(median, STEPS_ANCHORS)), ScoreBasis.CLINICAL_TARGET,
        "%,.0f steps/day median over %d days (%s)".format(median, i.stepsDays, band.replace('_', ' ')), "Add a daily walk to lift the median.",
    )
}

private fun lipids(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.METABOLIC
    val howTo = "Add triglycerides and HDL cholesterol to a lab entry."
    val g = Gate(i.asOf)
    val tg = g.lab(i.triglyceridesMgDl, "triglycerides", "mg/dL")
    val hdl = g.lab(i.hdlMgDl, "HDL cholesterol", "mg/dL")
    if (i.sex != "male" && i.sex != "female") g.missing += "sex (Setup > Profile), because the HDL limit differs by sex"
    if (tg == null || hdl == null || g.missing.isNotEmpty()) return unassessed(id, g.missing, howTo)
    val hdlLimit = if (i.sex == "male") HDL_FLAG_MALE else HDL_FLAG_FEMALE
    val tgBand = when { tg < TRIGLYCERIDES_FLAG -> FactorStatus.FAVORABLE; tg < TRIGLYCERIDES_HIGH -> FactorStatus.NEUTRAL; else -> FactorStatus.OPPORTUNITY }
    val hdlBand = when { hdl >= hdlLimit -> FactorStatus.FAVORABLE; hdl >= hdlLimit - HDL_LOW_STEP -> FactorStatus.NEUTRAL; else -> FactorStatus.OPPORTUNITY }
    val tgScore = scoreIn(tgBand, anchored(tg, TRIGLYCERIDES_ANCHORS))
    val hdlScore = scoreIn(hdlBand, anchored(hdl, hdlAnchors(hdlLimit)))
    val tgDate = i.triglyceridesMgDl!!.date
    val hdlDate = i.hdlMgDl!!.date
    val date = if (hdlDate.isAfter(tgDate)) hdlDate else tgDate
    return factor(
        id, weakerPart(tgScore, hdlScore), ScoreBasis.CLINICAL_TARGET,
        "Triglycerides %.0f mg/dL (%d), HDL %.0f mg/dL (%d) (panel of %s)".format(tg, tgScore, hdl, hdlScore, date),
        "Discuss the lipid result with your clinician and re-test.",
    )
}

private fun glucose(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.GLYCAEMIC
    val howTo = "Add fasting glucose or HbA1c to a lab entry."
    val a1cGate = Gate(i.asOf)
    val a1c = a1cGate.lab(i.hba1cPct, "HbA1c", "%")
    val gGate = Gate(i.asOf)
    val glu = gGate.lab(i.fastingGlucoseMgDl, "fasting glucose", "mg/dL")
    val lever = "Discuss the result with your clinician and repeat the test."
    // HbA1c wins when it passes the gate; otherwise fasting glucose.
    if (a1c != null) {
        val s = when { a1c < HBA1C_NEUTRAL -> FactorStatus.FAVORABLE; a1c < HBA1C_OPPORTUNITY -> FactorStatus.NEUTRAL; else -> FactorStatus.OPPORTUNITY }
        return factor(id, scoreIn(s, anchored(a1c, HBA1C_ANCHORS)), ScoreBasis.CLINICAL_TARGET, "HbA1c %.1f %% (panel of %s)".format(a1c, i.hba1cPct!!.date), lever)
    }
    if (glu != null) {
        val s = when { glu < GLUCOSE_NEUTRAL -> FactorStatus.FAVORABLE; glu < GLUCOSE_OPPORTUNITY -> FactorStatus.NEUTRAL; else -> FactorStatus.OPPORTUNITY }
        return factor(id, scoreIn(s, anchored(glu, GLUCOSE_ANCHORS)), ScoreBasis.CLINICAL_TARGET, "Fasting glucose %.0f mg/dL (panel of %s)".format(glu, i.fastingGlucoseMgDl!!.date), lever)
    }
    return unassessed(id, (a1cGate.missing + gGate.missing).distinct(), howTo)
}

fun healthspanSummary(inputs: HealthspanInputs): HealthspanSummary {
    val factors = listOf(
        cardio(inputs), biologicalAge(inputs), strength(inputs), bodyComposition(inputs),
        sleep(inputs), activity(inputs), lipids(inputs), glucose(inputs),
    )
    val scores = factors.mapNotNull { it.score }
    val assessed = scores.size
    val favorable = factors.count { it.status == FactorStatus.FAVORABLE }
    val preliminary = assessed < HEALTHSPAN_MIN_ASSESSED
    val score = if (preliminary) null else kotlin.math.round(scores.average()).toInt()
    return HealthspanSummary(factors, assessed, favorable, score, preliminary)
}
