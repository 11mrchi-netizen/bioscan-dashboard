package com.bioscan.fieldterminal.domain.aging

import com.bioscan.fieldterminal.domain.LiftReading
import com.bioscan.fieldterminal.domain.SriEvaluation
import com.bioscan.fieldterminal.domain.StrengthLevel
import com.bioscan.fieldterminal.domain.strengthLevelFor
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// Analysis Layer 3 / Phase 8. A general "healthspan" grade, product-defined and not clinically validated. It exists ONLY for
// the User profile page (a unit test fails if any other screen imports it): everywhere else this app deliberately shows no
// cross-metric score.
//
// Eight factors, each unassessed / favorable / neutral / opportunity. A factor is assessed only when every input passes the
// trust gate: present, recent enough, confidence met, units known and matching, no conflicting duplicates, nothing assumed.
// The grade is the share of ASSESSED factors that are favorable; below HEALTHSPAN_MIN_ASSESSED assessed it stays "preliminary"
// and carries no letter. Cut-offs are named below with their source; the letter bands and the floor are provisional.

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
    val phenoAge: BiologicalAgeResult? = null,
    // Best estimated 1RM per lift from the last 6 months, and the latest body weight (kg).
    val lifts: List<LiftReading> = emptyList(),
    val bodyWeightKg: DatedValue? = null,
    // Logged by hand; shown, not scored (no cited norm table yet).
    val grip: GripReading? = null,
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

data class HealthspanFactor(
    val id: HealthspanFactorId,
    val status: FactorStatus,
    val reading: String?, // what was measured, with its date where it matters
    val missingInputs: List<String>,
    val action: String?, // one thing: how to get it assessed, or the main lever
    val notes: List<String> = emptyList(), // caveats that belong to this factor
)

data class HealthspanSummary(
    val factors: List<HealthspanFactor>,
    val assessed: Int,
    val favorable: Int,
    val grade: String?, // null while preliminary
    val preliminary: Boolean,
)

// ---- Provisional parameters (product-defined; see docs/analysis-layer-3/healthspan.md) ----
const val HEALTHSPAN_MIN_ASSESSED = 5
private const val GRADE_A = 0.80
private const val GRADE_B = 0.60
private const val GRADE_C = 0.40

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
private const val SRI_REGULAR = 80.0 // product-defined
private const val TRIGLYCERIDES_FLAG = 150.0 // Alberti 2009 harmonised criteria
private const val HDL_FLAG_MALE = 40.0
private const val HDL_FLAG_FEMALE = 50.0
private const val HBA1C_NEUTRAL = 5.7 // ADA Standards of Care
private const val HBA1C_OPPORTUNITY = 6.5
private const val GLUCOSE_NEUTRAL = 100.0
private const val GLUCOSE_OPPORTUNITY = 126.0

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

private fun factor(id: HealthspanFactorId, status: FactorStatus, reading: String?, missing: List<String>, lever: String, howTo: String) =
    HealthspanFactor(
        id = id,
        status = status,
        reading = reading,
        missingInputs = missing,
        action = when (status) {
            FactorStatus.UNASSESSED -> howTo
            FactorStatus.OPPORTUNITY -> lever
            else -> null
        },
    )

private fun cardio(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.CARDIO_FITNESS
    val howTo = "Set sex and date of birth in Setup > Profile and keep a watch VO2max estimate coming in."
    val missing = mutableListOf<String>()
    val v = i.vo2max
    if (v == null) missing += "a VO2max reading"
    else if (ChronoUnit.DAYS.between(v.date, i.asOf) > VO2_MAX_AGE_DAYS) missing += "a VO2max reading from the last $VO2_MAX_AGE_DAYS days (latest ${v.date})"
    if (i.vo2Percentile == null) missing += "an age- and sex-matched VO2max table that fits (needs sex and date of birth)"
    if (missing.isNotEmpty() || v == null || i.vo2Percentile == null) return factor(id, FactorStatus.UNASSESSED, null, missing, "", howTo)
    return factor(
        id, percentileStatus(i.vo2Percentile),
        "VO2max %.1f mL/kg/min, %s percentile for your age and sex".format(v.value, ordinal(i.vo2Percentile)),
        emptyList(), "More easy aerobic time each week moves this most.", howTo,
    )
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
    if (missing.isNotEmpty() || p == null || accel == null) return factor(id, FactorStatus.UNASSESSED, null, missing, "", howTo)
    val status = when {
        accel <= PHENOAGE_FAVORABLE_YEARS -> FactorStatus.FAVORABLE
        accel < PHENOAGE_OPPORTUNITY_YEARS -> FactorStatus.NEUTRAL
        else -> FactorStatus.OPPORTUNITY
    }
    val dir = if (accel < 0) "younger" else "older"
    return factor(
        id, status, "PhenoAge %.1f y, %.1f y %s than your calendar age (panel of %s)".format(p.biologicalAge, abs(accel), dir, p.observedAt),
        emptyList(), "Open the Aging page to see which markers drive the gap.", howTo,
    )
}

private fun strength(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.STRENGTH
    val howTo = "Log barbell lifts (squat, deadlift, presses, row) and a body weight."
    val notes = mutableListOf("Lifter standards (bodyweight multiples), not population percentiles; not adjusted for sex or age.")
    i.grip?.let {
        notes += "Latest grip: %.1f kg (%s hand, %s). Logged only: there is no cited norm table yet, so it does not change the grade.".format(it.kg, it.hand, it.date)
    }
    val g = Gate(i.asOf)
    val bw = g.lab(i.bodyWeightKg, "body weight", "kg", BODY_WEIGHT_MAX_AGE_DAYS)
    val levels = i.lifts
        .filter { ChronoUnit.DAYS.between(it.date, i.asOf) in 0..STRENGTH_WINDOW_DAYS }
        .mapNotNull { lift -> strengthLevelFor(lift.slug, lift.e1rmKg, bw)?.let { lift.slug to it } }
    if (levels.size < MIN_LIFTS) {
        g.missing += "$MIN_LIFTS lifts from the last 6 months that have a standard (have ${levels.size})"
        return factor(id, FactorStatus.UNASSESSED, null, g.missing, "", howTo).copy(notes = notes)
    }
    // Lower median: with an even count the weaker middle lift decides.
    val sorted = levels.sortedBy { it.second.ordinal }
    val median = sorted[(sorted.size - 1) / 2].second
    val status = when {
        median >= StrengthLevel.INTERMEDIATE -> FactorStatus.FAVORABLE
        median == StrengthLevel.NOVICE -> FactorStatus.NEUTRAL
        else -> FactorStatus.OPPORTUNITY
    }
    val detail = sorted.joinToString(", ") { (slug, level) -> "${slug.replace('_', ' ')} ${level.label.lowercase()}" }
    return factor(id, status, "Median ${median.label.lowercase()} across ${levels.size} lifts ($detail)", emptyList(), "Progressive loading on the weakest main lift moves this.", howTo).copy(notes = notes)
}

private fun bodyComposition(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.BODY_COMPOSITION
    val howTo = "Add waist and height to a lab entry."
    val g = Gate(i.asOf)
    val waist = g.lab(i.waistCm, "waist circumference", "cm")
    val height = g.lab(i.heightCm, "height", "cm")
    val ratio = if (waist != null && height != null && height > 0) waist / height else null
    if (ratio != null && ratio !in 0.3..1.0) g.missing += "a plausible waist and height"
    if (ratio == null || g.missing.isNotEmpty()) return factor(id, FactorStatus.UNASSESSED, null, g.missing, "", howTo)
    val status = when {
        ratio < WHTR_FAVORABLE -> FactorStatus.FAVORABLE
        ratio < WHTR_OPPORTUNITY -> FactorStatus.NEUTRAL
        else -> FactorStatus.OPPORTUNITY
    }
    return factor(id, status, "Waist-to-height %.2f (waist %.0f cm, height %.0f cm)".format(ratio, waist, height), emptyList(), "Waist is the lever; re-measure monthly.", howTo)
}

private fun sleep(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.SLEEP
    val howTo = "Wear the watch to bed for two weeks."
    val missing = mutableListOf<String>()
    if (i.sleepMeanHours == null || i.sleepNights < MIN_NIGHTS) missing += "$MIN_NIGHTS nights of sleep duration (have ${i.sleepNights})"
    val sri = i.sri
    if (sri?.value == null || !sri.confidence.met) missing += "$MIN_NIGHTS nights of bed and wake times for regularity (have ${sri?.confidence?.have ?: 0})"
    val hours = i.sleepMeanHours
    val regularity = sri?.value
    if (missing.isNotEmpty() || hours == null || regularity == null) return factor(id, FactorStatus.UNASSESSED, null, missing, "", howTo)
    val durationOk = hours >= SLEEP_MIN_HOURS
    val regularOk = regularity >= SRI_REGULAR
    val status = when {
        durationOk && regularOk -> FactorStatus.FAVORABLE
        durationOk || regularOk -> FactorStatus.NEUTRAL
        else -> FactorStatus.OPPORTUNITY
    }
    val lever = if (!regularOk) "Hold a fixed wake time first; regularity is the cheaper lever." else "Protect time in bed to reach 7 hours."
    return factor(id, status, "%.1f h average over %d nights, regularity (SRI) %.0f".format(hours, i.sleepNights, regularity), emptyList(), lever, howTo)
}

private fun activity(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.ACTIVITY
    val howTo = "Wear the watch through the day for two weeks."
    val missing = mutableListOf<String>()
    if (i.stepsMedian == null || i.stepsDays < MIN_STEP_DAYS) missing += "$MIN_STEP_DAYS days of steps (have ${i.stepsDays})"
    if (i.stepsBand == null) missing += "a steps reference"
    val band = i.stepsBand
    val median = i.stepsMedian
    if (missing.isNotEmpty() || band == null || median == null) return factor(id, FactorStatus.UNASSESSED, null, missing, "", howTo)
    val status = when (band) {
        "active", "highly_active" -> FactorStatus.FAVORABLE
        "somewhat_active" -> FactorStatus.NEUTRAL
        else -> FactorStatus.OPPORTUNITY
    }
    return factor(id, status, "%,.0f steps/day median over %d days (%s)".format(median, i.stepsDays, band.replace('_', ' ')), emptyList(), "Add a daily walk to lift the median.", howTo)
}

private fun lipids(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.METABOLIC
    val howTo = "Add triglycerides and HDL cholesterol to a lab entry."
    val g = Gate(i.asOf)
    val tg = g.lab(i.triglyceridesMgDl, "triglycerides", "mg/dL")
    val hdl = g.lab(i.hdlMgDl, "HDL cholesterol", "mg/dL")
    if (i.sex != "male" && i.sex != "female") g.missing += "sex (Setup > Profile), because the HDL limit differs by sex"
    if (tg == null || hdl == null || g.missing.isNotEmpty()) return factor(id, FactorStatus.UNASSESSED, null, g.missing, "", howTo)
    val hdlLimit = if (i.sex == "male") HDL_FLAG_MALE else HDL_FLAG_FEMALE
    val flags = (if (tg >= TRIGLYCERIDES_FLAG) 1 else 0) + (if (hdl < hdlLimit) 1 else 0)
    val status = when (flags) { 0 -> FactorStatus.FAVORABLE; 1 -> FactorStatus.NEUTRAL; else -> FactorStatus.OPPORTUNITY }
    val tgDate = i.triglyceridesMgDl!!.date
    val hdlDate = i.hdlMgDl!!.date
    val date = if (hdlDate.isAfter(tgDate)) hdlDate else tgDate
    return factor(id, status, "Triglycerides %.0f mg/dL, HDL %.0f mg/dL (panel of %s)".format(tg, hdl, date), emptyList(), "Discuss the lipid result with your clinician and re-test.", howTo)
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
        return factor(id, s, "HbA1c %.1f %% (panel of %s)".format(a1c, i.hba1cPct!!.date), emptyList(), lever, howTo)
    }
    if (glu != null) {
        val s = when { glu < GLUCOSE_NEUTRAL -> FactorStatus.FAVORABLE; glu < GLUCOSE_OPPORTUNITY -> FactorStatus.NEUTRAL; else -> FactorStatus.OPPORTUNITY }
        return factor(id, s, "Fasting glucose %.0f mg/dL (panel of %s)".format(glu, i.fastingGlucoseMgDl!!.date), emptyList(), lever, howTo)
    }
    return factor(id, FactorStatus.UNASSESSED, null, (a1cGate.missing + gGate.missing).distinct(), "", howTo)
}

fun healthspanSummary(inputs: HealthspanInputs): HealthspanSummary {
    val factors = listOf(
        cardio(inputs), biologicalAge(inputs), strength(inputs), bodyComposition(inputs),
        sleep(inputs), activity(inputs), lipids(inputs), glucose(inputs),
    )
    val assessed = factors.count { it.status != FactorStatus.UNASSESSED }
    val favorable = factors.count { it.status == FactorStatus.FAVORABLE }
    val preliminary = assessed < HEALTHSPAN_MIN_ASSESSED
    val grade = if (preliminary) null else {
        val share = favorable.toDouble() / assessed
        when {
            share >= GRADE_A -> "A"
            share >= GRADE_B -> "B"
            share >= GRADE_C -> "C"
            else -> "D"
        }
    }
    return HealthspanSummary(factors, assessed, favorable, grade, preliminary)
}
