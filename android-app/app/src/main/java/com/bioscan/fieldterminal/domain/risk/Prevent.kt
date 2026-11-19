package com.bioscan.fieldterminal.domain.risk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// Risk profile R3 (docs/risk-profile/01-plan.md): the AHA PREVENT equations, base model (Khan et al., Circulation
// 2024;149:430-449), replacing life expectancy. 10-year risk of total CVD, ASCVD, heart failure, CHD and stroke from age 30
// to 79; 30-year risk from 30 to 59. Coefficients from the asset prevent-base.json (supabase/functions/_shared/risk/),
// extracted from the preventr R package; the file's cases are its published reference results. Pure.

@Serializable
data class PreventModel(val terms: List<String>, val labels: List<String>, val coefficients: Map<String, List<Double>>)

@Serializable
data class PreventDefs(val models: Map<String, PreventModel>)

private val json = Json { ignoreUnknownKeys = true }

fun parsePrevent(text: String): PreventDefs = json.decodeFromString(PreventDefs.serializer(), text)

data class PreventInputs(
    val age: Double,
    /** "female" or "male" */
    val sex: String,
    val totalCholMgDl: Double,
    val hdlMgDl: Double,
    val sbp: Double,
    val bpTreated: Boolean,
    val statin: Boolean,
    val diabetes: Boolean,
    val currentSmoker: Boolean,
    val egfr: Double,
    val bmi: Double,
)

enum class PreventOutcome(val key: String, val label: String) {
    TOTAL_CVD("total_cvd", "Heart attack, stroke or heart failure"),
    ASCVD("ascvd", "Heart attack or stroke"),
    HEART_FAILURE("heart_failure", "Heart failure"),
    CHD("chd", "Heart attack (coronary)"),
    STROKE("stroke", "Stroke"),
}

data class PreventResult(
    val tenYear: Map<PreventOutcome, Double>,
    /** Null outside 30-59: the 30-year equations were fitted only there. */
    val thirtyYear: Map<PreventOutcome, Double>?,
)

/** Where an input sits outside the range the equations were fitted on, the result is not shown (PREVENT, Table 1). */
sealed interface PreventOutcomeOrWhy {
    data class Ok(val result: PreventResult) : PreventOutcomeOrWhy
    data class NotApplicable(val reasons: List<String>) : PreventOutcomeOrWhy
}

const val MGDL_TO_MMOL = 0.02586

/** Validity ranges of the base model (Khan et al. 2024): outside them the equations are not used. */
fun preventProblems(i: PreventInputs): List<String> = buildList {
    if (i.age < 30 || i.age > 79) add("PREVENT applies from age 30 to 79")
    if (i.sex != "female" && i.sex != "male") add("sex is needed")
    if (i.totalCholMgDl < 130 || i.totalCholMgDl > 320) add("total cholesterol outside 130-320 mg/dL")
    if (i.hdlMgDl < 20 || i.hdlMgDl > 100) add("HDL outside 20-100 mg/dL")
    if (i.sbp < 90 || i.sbp > 200) add("systolic BP outside 90-200 mmHg")
    if (i.bmi < 18.5 || i.bmi >= 40) add("BMI outside 18.5-39.9")
    if (i.egfr < 15 || i.egfr > 140) add("eGFR outside 15-140")
}

/** The centred and scaled predictors, by term name (preventr prep_terms()). */
fun preventTerms(i: PreventInputs): Map<String, Double> {
    val age = (i.age - 55) / 10
    val nonHdl = (i.totalCholMgDl - i.hdlMgDl) * MGDL_TO_MMOL - 3.5
    val hdl = (i.hdlMgDl * MGDL_TO_MMOL - 1.3) / 0.3
    val sbpLt = (min(i.sbp, 110.0) - 110) / 20
    val sbpGte = (max(i.sbp, 110.0) - 130) / 20
    val dm = if (i.diabetes) 1.0 else 0.0
    val smoking = if (i.currentSmoker) 1.0 else 0.0
    val bmiLt = (min(i.bmi, 30.0) - 25) / 5
    val bmiGte = (max(i.bmi, 30.0) - 30) / 5
    val egfrLt = (min(i.egfr, 60.0) - 60) / -15
    val egfrGte = (max(i.egfr, 60.0) - 90) / -15
    val bpTx = if (i.bpTreated) 1.0 else 0.0
    val statin = if (i.statin) 1.0 else 0.0
    return mapOf(
        "age" to age, "age_squared" to age * age, "non_hdl_c" to nonHdl, "hdl_c" to hdl,
        "sbp_lt_110" to sbpLt, "sbp_gte_110" to sbpGte, "dm" to dm, "smoking" to smoking,
        "bmi_lt_30" to bmiLt, "bmi_gte_30" to bmiGte, "egfr_lt_60" to egfrLt, "egfr_gte_60" to egfrGte,
        "bp_tx" to bpTx, "statin" to statin, "bp_tx_sbp_gte_110" to bpTx * sbpGte, "statin_non_hdl_c" to statin * nonHdl,
        "age_non_hdl_c" to age * nonHdl, "age_hdl_c" to age * hdl, "age_sbp_gte_110" to age * sbpGte, "age_dm" to age * dm,
        "age_smoking" to age * smoking, "age_bmi_gte_30" to age * bmiGte, "age_egfr_lt_60" to age * egfrLt, "constant" to 1.0,
    )
}

private fun PreventModel.risk(sex: String, outcome: PreventOutcome, x: Map<String, Double>): Double {
    val beta = coefficients.getValue("${sex}_${outcome.key}")
    val logOdds = terms.indices.sumOf { beta[it] * x.getValue(terms[it]) }
    return 1 / (1 + exp(-logOdds))
}

fun PreventDefs.estimate(i: PreventInputs): PreventOutcomeOrWhy {
    val problems = preventProblems(i)
    if (problems.isNotEmpty()) return PreventOutcomeOrWhy.NotApplicable(problems)
    val x = preventTerms(i)
    val ten = models.getValue("base_10yr")
    val thirty = models.getValue("base_30yr")
    return PreventOutcomeOrWhy.Ok(
        PreventResult(
            tenYear = PreventOutcome.entries.associateWith { ten.risk(i.sex, it, x) },
            thirtyYear = if (i.age < 60) PreventOutcome.entries.associateWith { thirty.risk(i.sex, it, x) } else null,
        ),
    )
}

/**
 * The 10-year total CVD category the AHA uses with PREVENT for primary prevention (Khan et al. 2024 and the 2024 AHA
 * scientific statement on using PREVENT): under 5 % low, 5 to under 7.5 % borderline, 7.5 to under 20 % intermediate,
 * 20 % or more high. State words, not advice.
 */
enum class PreventCategory(val label: String, val state: String) {
    LOW("Low", "ok"), BORDERLINE("Borderline", "warn"), INTERMEDIATE("Intermediate", "warn"), HIGH("High", "bad"),
}

fun preventCategory(tenYearTotalCvd: Double): PreventCategory = when {
    tenYearTotalCvd < 0.05 -> PreventCategory.LOW
    tenYearTotalCvd < 0.075 -> PreventCategory.BORDERLINE
    tenYearTotalCvd < 0.20 -> PreventCategory.INTERMEDIATE
    else -> PreventCategory.HIGH
}

/** CKD-EPI 2021 (race-free) eGFR from serum creatinine in mg/dL, as preventr's calculate_egfr(). */
fun ckdEpi2021(creatinineMgDl: Double, age: Double, sex: String): Double {
    val female = sex == "female"
    val k = if (female) 0.7 else 0.9
    val a1 = if (female) -0.241 else -0.302
    val ratio = creatinineMgDl / k
    return 142 * Math.pow(min(ratio, 1.0), a1) * Math.pow(max(ratio, 1.0), -1.2) * Math.pow(0.9938, age) * (if (female) 1.012 else 1.0)
}
