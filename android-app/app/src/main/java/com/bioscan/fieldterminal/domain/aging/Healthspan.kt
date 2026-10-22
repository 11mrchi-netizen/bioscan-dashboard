package com.bioscan.fieldterminal.domain.aging

import com.bioscan.fieldterminal.domain.LiftReading
import com.bioscan.fieldterminal.domain.SriEvaluation
import com.bioscan.fieldterminal.domain.StrengthLevel
import com.bioscan.fieldterminal.domain.standardKey
import com.bioscan.fieldterminal.domain.strengthLevelFor
import com.bioscan.fieldterminal.domain.strengthThresholds
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class HealthspanFactorId(val label: String) {
    CARDIO_FITNESS("Cardio fitness"),
    BIOLOGICAL_AGE("Biological age"),
    STRENGTH("Strength"),
    BODY_COMPOSITION("Body composition"),
    SLEEP("Sleep"),
    ACTIVITY("Activity"),
    METABOLIC("Lipids"),
    GLYCAEMIC("Glucose"),
}

enum class FactorStatus { UNASSESSED, FAVORABLE, NEUTRAL, OPPORTUNITY }

data class DatedValue(
    val value: Double,
    val date: LocalDate,
    val unit: String? = null,
    val conflicting: Boolean = false,
)

data class GripReading(val kg: Double, val hand: String, val date: LocalDate)

data class HealthspanInputs(
    val asOf: LocalDate = LocalDate.now(),
    val sex: String? = null,
    val vo2max: DatedValue? = null,
    val vo2Percentile: Double? = null,
    val phenoAge: BiologicalAgeResult? = null,
    val lifts: List<LiftReading> = emptyList(),
    val bodyWeightKg: DatedValue? = null,
    val grip: GripReading? = null,
    val waistCm: DatedValue? = null,
    val heightCm: DatedValue? = null,
    val sleepMeanHours: Double? = null,
    val sleepNights: Int = 0,
    val sri: SriEvaluation? = null,
    val stepsMedian: Double? = null,
    val stepsDays: Int = 0,
    val stepsBand: String? = null,
    val triglyceridesMgDl: DatedValue? = null,
    val hdlMgDl: DatedValue? = null,
    val hba1cPct: DatedValue? = null,
    val fastingGlucoseMgDl: DatedValue? = null,
)

data class HealthspanFactor(
    val id: HealthspanFactorId,
    val status: FactorStatus,
    val score: Double?, // 0.0 to 100.0 scale, null when UNASSESSED
    val reading: String?,
    val missingInputs: List<String>,
    val action: String?,
    val notes: List<String> = emptyList(),
)

data class HealthspanSummary(
    val factors: List<HealthspanFactor>,
    val assessed: Int,
    val favorable: Int,
    val score: Int?, // Composite average score 0..100 (null if preliminary)
    val grade: String?,
    val preliminary: Boolean,
) {
    fun of(id: HealthspanFactorId): HealthspanFactor = factors.first { it.id == id }
}

const val HEALTHSPAN_MIN_ASSESSED = 5

const val LAB_MAX_AGE_DAYS = 365L
const val VO2_MAX_AGE_DAYS = 90L
const val BODY_WEIGHT_MAX_AGE_DAYS = 90L
const val STRENGTH_WINDOW_DAYS = 183L
const val MIN_NIGHTS = 14
const val MIN_STEP_DAYS = 14
const val MIN_LIFTS = 3

fun scoreToStatus(score: Double?): FactorStatus {
    if (score == null) return FactorStatus.UNASSESSED
    return when {
        score >= 75.0 -> FactorStatus.FAVORABLE
        score >= 50.0 -> FactorStatus.NEUTRAL
        else -> FactorStatus.OPPORTUNITY
    }
}

private fun factor(
    id: HealthspanFactorId,
    score: Double?,
    reading: String?,
    missingInputs: List<String>,
    action: String?,
    howTo: String,
): HealthspanFactor {
    val status = scoreToStatus(score)
    val actionText = if (status == FactorStatus.UNASSESSED) howTo else action
    val roundedScore = score?.let { Math.round(it * 10.0) / 10.0 }
    return HealthspanFactor(id, status, roundedScore, reading, missingInputs, actionText)
}

private class Gate(val asOf: LocalDate) {
    val missing = mutableListOf<String>()

    fun lab(dv: DatedValue?, name: String, unit: String, maxAgeDays: Long = LAB_MAX_AGE_DAYS): Double? {
        if (dv == null) { missing += name; return null }
        if (dv.unit != null && !dv.unit.equals(unit, ignoreCase = true)) {
            missing += "$name in $unit (got ${dv.unit})"
            return null
        }
        val age = ChronoUnit.DAYS.between(dv.date, asOf)
        if (age < 0 || age > maxAgeDays) {
            missing += "$name from the last ${if (maxAgeDays == LAB_MAX_AGE_DAYS) "year" else "$maxAgeDays days"} (latest ${dv.date})"
            return null
        }
        return dv.value
    }
}

private fun cardio(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.CARDIO_FITNESS
    val howTo = "Record a VO2max estimate (from a outdoor run or walk with watch)."
    val g = Gate(i.asOf)
    val v = g.lab(i.vo2max, "VO2max", "mL/kg/min", VO2_MAX_AGE_DAYS)
    if (i.vo2Percentile == null) g.missing += "an age- and sex-matched VO2max norm table"
    if (v == null || i.vo2Percentile == null || g.missing.isNotEmpty()) {
        return factor(id, null, null, g.missing, "", howTo)
    }
    val score = i.vo2Percentile.coerceIn(1.0, 100.0)
    return factor(
        id, score,
        "VO2max %.1f mL/kg/min, %.0f percentile for your age and sex".format(v, score),
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
    val chrono = p?.chronologicalAgeYears
    if (missing.isNotEmpty() || p == null || accel == null || chrono == null || chrono <= 0) {
        return factor(id, null, null, missing, "", howTo)
    }

    val relAccel = accel / chrono
    val rawScore = when {
        relAccel <= 0 -> 70.0 + (-relAccel / 0.20) * 30.0
        else -> 70.0 - (relAccel / 0.20) * 50.0
    }
    val score = rawScore.coerceIn(1.0, 100.0)
    val dir = if (accel < 0) "younger" else "older"
    return factor(
        id, score,
        "PhenoAge %.1f y, %.1f y %s than your calendar age (panel of %s)".format(p.biologicalAge, abs(accel), dir, p.observedAt),
        emptyList(), "Open the Aging page to see which markers drive the gap.", howTo,
    )
}

private fun strength(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.STRENGTH
    val howTo = "Log barbell lifts (squat, deadlift, presses, row) and a body weight."
    val notes = mutableListOf("Lifter standards (bodyweight multiples), not population percentiles; not adjusted for sex or age.")
    i.grip?.let {
        notes += "Latest grip: %.1f kg (%s hand, %s). Logged only: there is no cited norm table yet, so it does not change the score.".format(it.kg, it.hand, it.date)
    }
    val g = Gate(i.asOf)
    val bw = g.lab(i.bodyWeightKg, "body weight", "kg", BODY_WEIGHT_MAX_AGE_DAYS)
    val recentLifts = i.lifts.filter { ChronoUnit.DAYS.between(it.date, i.asOf) in 0..STRENGTH_WINDOW_DAYS }

    val liftScores = recentLifts.mapNotNull { lift ->
        val key = standardKey(lift.slug)
        val thresholds = strengthThresholds(key, bw) ?: return@mapNotNull null
        val e1rm = lift.e1rmKg
        val score = when {
            e1rm <= 0 -> 1.0
            e1rm < thresholds[0] -> 20.0 + (e1rm / thresholds[0]) * 20.0
            e1rm < thresholds[1] -> 40.0 + ((e1rm - thresholds[0]) / (thresholds[1] - thresholds[0])) * 20.0
            e1rm < thresholds[2] -> 60.0 + ((e1rm - thresholds[1]) / (thresholds[2] - thresholds[1])) * 20.0
            e1rm < thresholds[3] -> 80.0 + ((e1rm - thresholds[2]) / (thresholds[3] - thresholds[2])) * 20.0
            else -> 100.0
        }
        lift.slug to score
    }

    if (liftScores.size < MIN_LIFTS) {
        g.missing += "$MIN_LIFTS lifts from the last 6 months that have a standard (have ${liftScores.size})"
        return factor(id, null, null, g.missing, "", howTo).copy(notes = notes)
    }

    val sortedScores = liftScores.map { it.second }.sorted()
    val medianScore = if (sortedScores.size % 2 == 1) {
        sortedScores[sortedScores.size / 2]
    } else {
        (sortedScores[sortedScores.size / 2 - 1] + sortedScores[sortedScores.size / 2]) / 2.0
    }

    val detail = liftScores.joinToString(", ") { (slug, score) ->
        val level = strengthLevelFor(slug, recentLifts.first { it.slug == slug }.e1rmKg, bw)?.label?.lowercase() ?: "untrained"
        "${slug.replace('_', ' ')} ${level} (%.0f)".format(score)
    }
    return factor(
        id, medianScore,
        "Strength score %.0f based on %d lifts ($detail)".format(medianScore, liftScores.size),
        emptyList(), "Progressive loading on the weakest main lift moves this.", howTo,
    ).copy(notes = notes)
}

private fun bodyComposition(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.BODY_COMPOSITION
    val howTo = "Measure your waist at the monthly body check-in (Fuel › Body), and set your height."
    val g = Gate(i.asOf)
    val waist = g.lab(i.waistCm, "waist circumference", "cm")
    val height = g.lab(i.heightCm, "height", "cm")
    val ratio = if (waist != null && height != null && height > 0) waist / height else null
    if (ratio != null && ratio !in 0.3..1.0) g.missing += "a plausible waist and height"
    if (ratio == null || g.missing.isNotEmpty()) return factor(id, null, null, g.missing, "", howTo)

    val score = when {
        ratio <= 0.45 -> 100.0
        ratio <= 0.50 -> 100.0 - ((ratio - 0.45) / 0.05) * 25.0
        ratio <= 0.60 -> 75.0 - ((ratio - 0.50) / 0.10) * 35.0
        ratio <= 0.65 -> 40.0 - ((ratio - 0.60) / 0.05) * 30.0
        else -> 10.0
    }.coerceIn(1.0, 100.0)

    return factor(
        id, score,
        "Waist-to-height ratio %.2f (waist %.0f cm, height %.0f cm)".format(ratio, waist, height),
        emptyList(), "Waist is the lever; re-measure monthly.", howTo,
    )
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
    if (missing.isNotEmpty() || hours == null || regularity == null) return factor(id, null, null, missing, "", howTo)

    val durationScore = when {
        hours in 7.0..9.0 -> 100.0
        hours < 7.0 -> (20.0 + (hours - 5.0).coerceAtLeast(0.0) / 2.0 * 80.0).coerceIn(1.0, 100.0)
        else -> (100.0 - (hours - 9.0) * 20.0).coerceIn(40.0, 100.0)
    }
    val regularityScore = when {
        regularity >= 80.0 -> 100.0
        regularity >= 50.0 -> 40.0 + ((regularity - 50.0) / 30.0) * 60.0
        else -> (regularity / 50.0 * 40.0).coerceIn(1.0, 40.0)
    }

    val score = (0.5 * durationScore + 0.5 * regularityScore).coerceIn(1.0, 100.0)

    val lever = if (regularity < 80.0) "Hold a fixed wake time first; regularity is the cheaper lever." else "Protect time in bed to reach 7-8 hours."
    return factor(
        id, score,
        "%.1f h avg sleep over %d nights, regularity (SRI) %.0f".format(hours, i.sleepNights, regularity),
        emptyList(), lever, howTo,
    )
}

private fun activity(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.ACTIVITY
    val howTo = "Wear the watch through the day for two weeks."
    val missing = mutableListOf<String>()
    if (i.stepsMedian == null || i.stepsDays < MIN_STEP_DAYS) missing += "$MIN_STEP_DAYS days of steps (have ${i.stepsDays})"
    val median = i.stepsMedian
    if (missing.isNotEmpty() || median == null) return factor(id, null, null, missing, "", howTo)

    val score = when {
        median <= 2500.0 -> (median / 2500.0 * 20.0).coerceAtLeast(1.0)
        median <= 5000.0 -> 20.0 + ((median - 2500.0) / 2500.0) * 25.0
        median <= 7500.0 -> 45.0 + ((median - 5000.0) / 2500.0) * 25.0
        median <= 10000.0 -> 70.0 + ((median - 7500.0) / 2500.0) * 20.0
        median <= 12500.0 -> 90.0 + ((median - 10000.0) / 2500.0) * 10.0
        else -> 100.0
    }.coerceIn(1.0, 100.0)

    val bandStr = i.stepsBand?.replace('_', ' ') ?: "assessed"
    return factor(
        id, score,
        "%,.0f steps/day median over %d days (%s)".format(median, i.stepsDays, bandStr),
        emptyList(), "Add a daily walk to lift the median.", howTo,
    )
}

private fun lipids(i: HealthspanInputs): HealthspanFactor {
    val id = HealthspanFactorId.METABOLIC
    val howTo = "Add triglycerides and HDL cholesterol to a lab entry."
    val g = Gate(i.asOf)
    val tg = g.lab(i.triglyceridesMgDl, "triglycerides", "mg/dL")
    val hdl = g.lab(i.hdlMgDl, "HDL cholesterol", "mg/dL")
    if (i.sex != "male" && i.sex != "female") g.missing += "sex (Setup > Profile), because the HDL target differs by sex"
    if (tg == null || hdl == null || g.missing.isNotEmpty()) return factor(id, null, null, g.missing, "", howTo)

    val tgScore = when {
        tg <= 100.0 -> 100.0
        tg <= 150.0 -> 100.0 - ((tg - 100.0) / 50.0) * 40.0
        tg <= 200.0 -> 60.0 - ((tg - 150.0) / 50.0) * 40.0
        else -> 20.0 - ((tg - 200.0) / 100.0 * 10.0)
    }.coerceIn(1.0, 100.0)

    val hdlTarget = if (i.sex == "male") 40.0 else 50.0
    val hdlOptimal = hdlTarget + 20.0
    val hdlScore = when {
        hdl >= hdlOptimal -> 100.0
        hdl >= hdlTarget -> 50.0 + ((hdl - hdlTarget) / (hdlOptimal - hdlTarget)) * 50.0
        else -> (hdl / hdlTarget * 50.0)
    }.coerceIn(1.0, 100.0)

    val score = (0.5 * tgScore + 0.5 * hdlScore).coerceIn(1.0, 100.0)
    val date = if (i.hdlMgDl!!.date.isAfter(i.triglyceridesMgDl!!.date)) i.hdlMgDl.date else i.triglyceridesMgDl.date
    return factor(
        id, score,
        "Triglycerides %.0f mg/dL, HDL %.0f mg/dL (panel of %s)".format(tg, hdl, date),
        emptyList(), "Discuss the lipid result with your clinician and re-test.", howTo,
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

    if (a1c != null) {
        val score = when {
            a1c <= 5.4 -> 100.0
            a1c <= 5.7 -> 100.0 - ((a1c - 5.4) / 0.3) * 25.0
            a1c <= 6.5 -> 75.0 - ((a1c - 5.7) / 0.8) * 40.0
            else -> 35.0 - ((a1c - 6.5) / 1.5 * 25.0)
        }.coerceIn(1.0, 100.0)
        return factor(id, score, "HbA1c %.1f %% (panel of %s)".format(a1c, i.hba1cPct!!.date), emptyList(), lever, howTo)
    }

    if (glu != null) {
        val score = when {
            glu <= 90.0 -> 100.0
            glu <= 100.0 -> 100.0 - ((glu - 90.0) / 10.0) * 25.0
            glu <= 126.0 -> 75.0 - ((glu - 100.0) / 26.0) * 40.0
            else -> 35.0 - ((glu - 126.0) / 50.0 * 25.0)
        }.coerceIn(1.0, 100.0)
        return factor(id, score, "Fasting glucose %.0f mg/dL (panel of %s)".format(glu, i.fastingGlucoseMgDl!!.date), emptyList(), lever, howTo)
    }

    return factor(id, null, null, (a1cGate.missing + gGate.missing).distinct(), "", howTo)
}

fun healthspanSummary(inputs: HealthspanInputs): HealthspanSummary {
    val factors = listOf(
        cardio(inputs), biologicalAge(inputs), strength(inputs), bodyComposition(inputs),
        sleep(inputs), activity(inputs), lipids(inputs), glucose(inputs),
    )
    val assessedFactors = factors.filter { it.status != FactorStatus.UNASSESSED }
    val assessed = assessedFactors.size
    val favorable = factors.count { it.status == FactorStatus.FAVORABLE }
    val preliminary = assessed < HEALTHSPAN_MIN_ASSESSED

    val averageScore = if (preliminary || assessed == 0) null else {
        Math.round(assessedFactors.mapNotNull { it.score }.average()).toInt()
    }
    val gradeString = averageScore?.toString()

    return HealthspanSummary(factors, assessed, favorable, averageScore, gradeString, preliminary)
}

data class LabObservation(val name: String, val value: Double, val unit: String?, val date: LocalDate)

fun latestGrip(readings: List<GripReading>): GripReading? {
    val latest = readings.map { it.date }.reduceOrNull { a, b -> if (b.isAfter(a)) b else a } ?: return null
    return readings.filter { it.date == latest }.maxByOrNull { it.kg }
}

fun latestLab(observations: List<LabObservation>, predicate: (String) -> Boolean = { true }): DatedValue? {
    val matching = observations.filter { predicate(it.name) }
    val latestDate = matching.map { it.date }.reduceOrNull { a, b -> if (b.isAfter(a)) b else a } ?: return null
    val onDate = matching.filter { it.date == latestDate }
    if (onDate.isEmpty()) return null
    val first = onDate.first()
    val conflicting = onDate.any { it.value != first.value }
    return DatedValue(first.value, latestDate, first.unit, conflicting)
}
