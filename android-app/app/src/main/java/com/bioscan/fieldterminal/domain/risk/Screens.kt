package com.bioscan.fieldterminal.domain.risk

import kotlin.math.roundToInt

// Risk profile R3 (docs/risk-profile/01-plan.md): Life's Essential 8, FINDRISC and the 2024 Lancet Commission's modifiable
// dementia factors. Every input says where it came from (measured, answered) and a screen that lacks one says what is
// missing; nothing is filled in silently. Pure.

/** The one-time answers no sensor or lab gives (table health_background). Null = not answered. */
data class HealthBackground(
    /** "never", "former" or "current" (any tobacco or nicotine product). */
    val smoking: String? = null,
    val quitYear: Int? = null,
    /** Someone smokes indoors where you live. */
    val secondhandSmoke: Boolean? = null,
    val diabetesDiagnosed: Boolean? = null,
    val bpMeds: Boolean? = null,
    val lipidMeds: Boolean? = null,
    /** Ever told your blood glucose was high (a check-up, illness, pregnancy). */
    val highGlucoseHistory: Boolean? = null,
    /** "none", "second" (grandparent, aunt, uncle, cousin) or "first" (parent, sibling, child). */
    val familyDiabetes: String? = null,
    val vegFruitDaily: Boolean? = null,
    /** "good", "aided" (hearing aid used) or "difficulty" (difficulty without an aid). */
    val hearing: String? = null,
    /** "good", "corrected" or "difficulty" (not corrected). */
    val vision: String? = null,
    val headInjury: Boolean? = null,
    /** "primary", "secondary" or "tertiary": highest completed. */
    val education: String? = null,
)

enum class Source(val word: String) { MEASURED("measured"), LABS("lab"), ANSWERED("answered"), DERIVED("worked out") }

data class Dated<T>(val value: T, val source: Source, val note: String? = null)

/** Everything the screens read, gathered by data/RiskScreensRepository.kt. */
data class ScreenInputs(
    val age: Int?,
    val sex: String?,
    val background: HealthBackground,
    val bmi: Dated<Double>?,
    val waistCm: Dated<Double>?,
    val sbp: Dated<Double>?,
    val dbp: Dated<Double>?,
    val totalCholMgDl: Dated<Double>?,
    val hdlMgDl: Dated<Double>?,
    val ldlMgDl: Dated<Double>?,
    val fastingGlucoseMgDl: Dated<Double>?,
    val hba1cPct: Dated<Double>?,
    val egfr: Dated<Double>?,
    /** Moderate-to-vigorous minutes a week, last 4 weeks: logged sessions count as moderate or harder. */
    val activityMinPerWeek: Dated<Double>?,
    val sleepHours: Dated<Double>?,
    /** Grams of alcohol a week, last 4 weeks of logged drinks. */
    val alcoholGramsPerWeek: Dated<Double>?,
    val phq9: Dated<Double>?,
    val ucla3: Dated<Double>?,
    val pm25: Dated<Double>?,
    val currentYear: Int,
)

val ScreenInputs.diabetes: Boolean?
    get() = when {
        background.diabetesDiagnosed == true -> true
        (hba1cPct?.value ?: 0.0) >= 6.5 || (fastingGlucoseMgDl?.value ?: 0.0) >= 126 -> true
        background.diabetesDiagnosed == false -> false
        hba1cPct != null || fastingGlucoseMgDl != null -> false
        else -> null
    }

// ---------- Life's Essential 8 (Lloyd-Jones et al., Circulation 2022;146:e18-e43, Table 2) ----------

enum class Le8Metric(val label: String) {
    DIET("Diet"), ACTIVITY("Physical activity"), NICOTINE("Nicotine exposure"), SLEEP("Sleep"),
    BMI("Body mass index"), LIPIDS("Blood lipids"), GLUCOSE("Blood glucose"), BP("Blood pressure"),
}

data class Le8Score(val metric: Le8Metric, val points: Int?, val reading: String?, val missing: String?)

data class Le8Result(val metrics: List<Le8Score>) {
    val assessed: List<Le8Score> get() = metrics.filter { it.points != null }
    /** The mean of the assessed metrics; the full score needs all eight. */
    val score: Int? get() = assessed.takeIf { it.isNotEmpty() }?.map { it.points!! }?.average()?.roundToInt()
    val complete: Boolean get() = assessed.size == 8
    val band: String? get() = score?.let { if (it >= 80) "High" else if (it >= 50) "Moderate" else "Low" }
    val state: String? get() = score?.let { if (it >= 80) "ok" else if (it >= 50) "warn" else "bad" }
}

fun le8Activity(min: Double): Int = when {
    min >= 150 -> 100; min >= 120 -> 90; min >= 90 -> 80; min >= 60 -> 60; min >= 30 -> 40; min >= 1 -> 20; else -> 0
}

fun le8Sleep(h: Double): Int = when {
    h >= 7 && h < 9 -> 100; h >= 9 && h < 10 -> 90; h >= 6 && h < 7 -> 70; (h >= 5 && h < 6) || h >= 10 -> 40; h >= 4 -> 20; else -> 0
}

fun le8Bmi(b: Double): Int = when { b < 25 -> 100; b < 30 -> 70; b < 35 -> 30; b < 40 -> 15; else -> 0 }

fun le8NonHdl(mgDl: Double, treated: Boolean): Int {
    val p = when { mgDl < 130 -> 100; mgDl < 160 -> 60; mgDl < 190 -> 40; mgDl < 220 -> 20; else -> 0 }
    return (p - if (treated) 20 else 0).coerceAtLeast(0)
}

fun le8Glucose(diabetes: Boolean, fastingMgDl: Double?, hba1c: Double?): Int? = if (!diabetes) {
    when {
        hba1c != null -> if (hba1c < 5.7) 100 else 60
        fastingMgDl != null -> if (fastingMgDl < 100) 100 else 60
        else -> null
    }
} else {
    hba1c?.let { a -> when { a < 7 -> 40; a < 8 -> 30; a < 9 -> 20; a < 10 -> 10; else -> 0 } }
}

fun le8Bp(sbp: Double, dbp: Double, treated: Boolean): Int {
    val s = sbp.roundToInt()
    val d = dbp.roundToInt()
    val p = when {
        s >= 160 || d >= 100 -> 0
        s >= 140 || d >= 90 -> 25
        s >= 130 || d >= 80 -> 50
        s >= 120 -> 75
        else -> 100
    }
    return (p - if (treated) 20 else 0).coerceAtLeast(0)
}

fun le8Nicotine(smoking: String, quitYear: Int?, secondhand: Boolean?, currentYear: Int): Int? {
    val p = when (smoking) {
        "never" -> 100
        "current" -> 0
        "former" -> {
            val years = quitYear?.let { currentYear - it } ?: return null
            when { years >= 5 -> 75; years >= 1 -> 50; else -> 25 }
        }
        else -> return null
    }
    return (p - if (secondhand == true) 20 else 0).coerceAtLeast(0)
}

private fun f0(x: Double) = x.roundToInt().toString()
private fun f1(x: Double) = String.format(java.util.Locale.ENGLISH, "%.1f", x)

fun le8(i: ScreenInputs): Le8Result {
    val b = i.background
    val dm = i.diabetes
    val nonHdl = if (i.totalCholMgDl != null && i.hdlMgDl != null) i.totalCholMgDl.value - i.hdlMgDl.value else null
    return Le8Result(listOf(
        Le8Score(Le8Metric.DIET, null, null, "a diet questionnaire (risk profile R4)"),
        i.activityMinPerWeek?.let { Le8Score(Le8Metric.ACTIVITY, le8Activity(it.value), "${f0(it.value)} min a week", null) }
            ?: Le8Score(Le8Metric.ACTIVITY, null, null, "logged sessions"),
        b.smoking?.let { s -> le8Nicotine(s, b.quitYear, b.secondhandSmoke, i.currentYear)?.let { Le8Score(Le8Metric.NICOTINE, it, s, null) } }
            ?: Le8Score(Le8Metric.NICOTINE, null, null, "smoking (health background)"),
        i.sleepHours?.let { Le8Score(Le8Metric.SLEEP, le8Sleep(it.value), "${f1(it.value)} h a night", null) }
            ?: Le8Score(Le8Metric.SLEEP, null, null, "sleep from the watch"),
        i.bmi?.let { Le8Score(Le8Metric.BMI, le8Bmi(it.value), "BMI ${f1(it.value)}", null) }
            ?: Le8Score(Le8Metric.BMI, null, null, "weight and height"),
        if (nonHdl != null && b.lipidMeds != null) Le8Score(Le8Metric.LIPIDS, le8NonHdl(nonHdl, b.lipidMeds), "non-HDL ${f0(nonHdl)} mg/dL", null)
        else Le8Score(Le8Metric.LIPIDS, null, null, listOfNotNull("total and HDL cholesterol".takeIf { nonHdl == null }, "cholesterol medicine (health background)".takeIf { b.lipidMeds == null }).joinToString(", ")),
        (if (dm != null) le8Glucose(dm, i.fastingGlucoseMgDl?.value, i.hba1cPct?.value) else null)
            ?.let { Le8Score(Le8Metric.GLUCOSE, it, i.hba1cPct?.let { a -> "HbA1c ${f1(a.value)} %" } ?: i.fastingGlucoseMgDl?.let { g -> "fasting ${f0(g.value)} mg/dL" }, null) }
            ?: Le8Score(Le8Metric.GLUCOSE, null, null, if (dm == true) "an HbA1c test (with diabetes, LE8 scores HbA1c)" else "fasting glucose or HbA1c"),
        if (i.sbp != null && i.dbp != null && b.bpMeds != null) Le8Score(Le8Metric.BP, le8Bp(i.sbp.value, i.dbp.value, b.bpMeds), "${f0(i.sbp.value)}/${f0(i.dbp.value)} mmHg", null)
        else Le8Score(Le8Metric.BP, null, null, listOfNotNull("blood pressure".takeIf { i.sbp == null }, "BP medicine (health background)".takeIf { b.bpMeds == null }).joinToString(", ")),
    ))
}

// ---------- FINDRISC (Lindstrom & Tuomilehto, Diabetes Care 2003;26:725-731) ----------

data class FindriscItem(val label: String, val points: Int?, val max: Int, val reading: String?)

data class FindriscResult(val items: List<FindriscItem>) {
    val missing: List<FindriscItem> get() = items.filter { it.points == null }
    val score: Int get() = items.sumOf { it.points ?: 0 }
    val complete: Boolean get() = missing.isEmpty()
    /** With items missing, the most the total could still be. */
    val maxPossible: Int get() = score + missing.sumOf { it.max }
    val band: String get() = findriscBand(score).first
    val tenYearRisk: String get() = findriscBand(score).second
    val state: String get() = when { score < 7 -> "ok"; score < 15 -> "warn"; else -> "bad" }
}

fun findriscBand(score: Int): Pair<String, String> = when {
    score < 7 -> "Low" to "about 1 in 100"
    score <= 11 -> "Slightly elevated" to "about 1 in 25"
    score <= 14 -> "Moderate" to "about 1 in 6"
    score <= 20 -> "High" to "about 1 in 3"
    else -> "Very high" to "about 1 in 2"
}

fun findrisc(i: ScreenInputs): FindriscResult {
    val b = i.background
    val female = i.sex == "female"
    val waist = i.waistCm?.value
    return FindriscResult(listOf(
        FindriscItem("Age", i.age?.let { a -> when { a < 45 -> 0; a < 55 -> 2; a < 65 -> 3; else -> 4 } }, 4, i.age?.let { "$it" }),
        FindriscItem("BMI", i.bmi?.value?.let { m -> when { m < 25 -> 0; m <= 30 -> 1; else -> 3 } }, 3, i.bmi?.let { f1(it.value) }),
        FindriscItem("Waist", if (waist != null && i.sex != null) {
            if (female) when { waist < 80 -> 0; waist <= 88 -> 3; else -> 4 } else when { waist < 94 -> 0; waist <= 102 -> 3; else -> 4 }
        } else null, 4, waist?.let { "${f0(it)} cm" }),
        // 30 minutes or more of activity daily, at work or in leisure: taken from logged sessions (210 min a week).
        FindriscItem("Active 30 min a day", i.activityMinPerWeek?.value?.let { if (it >= 210) 0 else 2 }, 2, i.activityMinPerWeek?.let { "${f0(it.value)} min a week" }),
        FindriscItem("Vegetables or fruit every day", b.vegFruitDaily?.let { if (it) 0 else 1 }, 1, b.vegFruitDaily?.let { if (it) "yes" else "no" }),
        FindriscItem("Blood pressure medicine", b.bpMeds?.let { if (it) 2 else 0 }, 2, b.bpMeds?.let { if (it) "yes" else "no" }),
        FindriscItem("Ever high blood glucose", b.highGlucoseHistory?.let { if (it) 5 else 0 }, 5, b.highGlucoseHistory?.let { if (it) "yes" else "no" }),
        FindriscItem("Family with diabetes", b.familyDiabetes?.let { when (it) { "first" -> 5; "second" -> 3; else -> 0 } }, 5, b.familyDiabetes),
    ))
}

// ---------- Modifiable dementia factors (Livingston et al., Lancet 2024;404:572-628) ----------

enum class FactorState(val word: String) { PRESENT("present"), ABSENT("absent"), UNKNOWN("unknown") }

data class BrainFactor(val label: String, val state: FactorState, val reading: String?, val source: Source?, val needs: String?)

private fun factor(label: String, present: Boolean?, reading: String?, source: Source?, needs: String) =
    BrainFactor(label, when (present) { true -> FactorState.PRESENT; false -> FactorState.ABSENT; null -> FactorState.UNKNOWN }, reading, source, if (present == null) needs else null)

/** UK low-risk limit (14 units = 112 g a week); the Commission counts more than 21 units (168 g). */
const val ALCOHOL_LANCET_G_PER_WEEK = 168.0
/** LDL above 3 mmol/L (116 mg/dL) in midlife (Lancet 2024). */
const val LDL_LANCET_MGDL = 116.0

fun brainFactors(i: ScreenInputs): List<BrainFactor> {
    val b = i.background
    return listOf(
        factor("Less education", b.education?.let { it == "primary" }, b.education, Source.ANSWERED, "education (health background)"),
        factor("Hearing loss, untreated", b.hearing?.let { it == "difficulty" }, b.hearing, Source.ANSWERED, "hearing (health background)"),
        factor("High LDL cholesterol", i.ldlMgDl?.let { it.value > LDL_LANCET_MGDL }, i.ldlMgDl?.let { "LDL ${f0(it.value)} mg/dL" }, Source.LABS, "an LDL result"),
        factor("Depression", i.phq9?.let { it.value >= 10 }, i.phq9?.let { "PHQ-9 ${f0(it.value)}" }, Source.ANSWERED, "a PHQ-9"),
        factor("Head injury", b.headInjury, b.headInjury?.let { if (it) "yes" else "no" }, Source.ANSWERED, "head injury (health background)"),
        factor("Physical inactivity", i.activityMinPerWeek?.let { it.value < 150 }, i.activityMinPerWeek?.let { "${f0(it.value)} min a week" }, Source.MEASURED, "logged sessions"),
        factor("Diabetes", i.diabetes, null, Source.DERIVED, "glucose or diabetes (health background)"),
        factor("Smoking", b.smoking?.let { it == "current" }, b.smoking, Source.ANSWERED, "smoking (health background)"),
        factor("High blood pressure", if (i.sbp != null) (i.sbp.value >= 130 || b.bpMeds == true) else b.bpMeds?.takeIf { it },
            i.sbp?.let { "SBP ${f0(it.value)}" }, i.sbp?.source ?: Source.ANSWERED, "blood pressure"),
        factor("Obesity", i.bmi?.let { it.value >= 30 }, i.bmi?.let { "BMI ${f1(it.value)}" }, Source.MEASURED, "weight and height"),
        factor("Excessive alcohol", i.alcoholGramsPerWeek?.let { it.value > ALCOHOL_LANCET_G_PER_WEEK }, i.alcoholGramsPerWeek?.let { "${f0(it.value / 8)} units a week" }, Source.MEASURED, "logged drinks"),
        factor("Social isolation", i.ucla3?.let { it.value >= 6 }, i.ucla3?.let { "UCLA-3 ${f0(it.value)}" }, Source.ANSWERED, "a UCLA-3"),
        factor("Air pollution", i.pm25?.let { it.value > 5 }, i.pm25?.let { "PM2.5 ${f1(it.value)} µg/m³" }, Source.MEASURED, "air exposure days"),
        factor("Vision loss, untreated", b.vision?.let { it == "difficulty" }, b.vision, Source.ANSWERED, "vision (health background)"),
    )
}
