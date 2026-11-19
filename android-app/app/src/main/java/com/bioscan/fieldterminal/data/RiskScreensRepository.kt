package com.bioscan.fieldterminal.data

import android.content.Context
import com.bioscan.fieldterminal.domain.aging.LabObservation
import com.bioscan.fieldterminal.domain.aging.latestLab
import com.bioscan.fieldterminal.domain.bp.bpForScreens
import com.bioscan.fieldterminal.domain.ratioWaist
import com.bioscan.fieldterminal.domain.risk.BrainFactor
import com.bioscan.fieldterminal.domain.risk.Dated
import com.bioscan.fieldterminal.domain.risk.FindriscResult
import com.bioscan.fieldterminal.domain.risk.HealthBackground
import com.bioscan.fieldterminal.domain.risk.Le8Result
import com.bioscan.fieldterminal.domain.risk.MGDL_TO_MMOL
import com.bioscan.fieldterminal.domain.risk.PreventDefs
import com.bioscan.fieldterminal.domain.risk.PreventInputs
import com.bioscan.fieldterminal.domain.risk.PreventOutcomeOrWhy
import com.bioscan.fieldterminal.domain.risk.ScreenInputs
import com.bioscan.fieldterminal.domain.risk.Source
import com.bioscan.fieldterminal.domain.risk.brainFactors
import com.bioscan.fieldterminal.domain.risk.ckdEpi2021
import com.bioscan.fieldterminal.domain.risk.diabetes
import com.bioscan.fieldterminal.domain.risk.estimate
import com.bioscan.fieldterminal.domain.risk.findrisc
import com.bioscan.fieldterminal.domain.risk.le8
import com.bioscan.fieldterminal.domain.risk.parsePrevent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.Period

// Risk profile R3: gathers what the screens read (domain/risk/Screens.kt, Prevent.kt) from the tables that already hold it,
// plus health_background, and works the screens out. Measured values win: home BP over clinic, labs as they are. Every
// part is read softly, so a missing table or an empty stream makes that input unknown, never the page fail.

@Serializable
data class HealthBackgroundRow(
    val smoking: String? = null,
    @SerialName("quit_year") val quitYear: Int? = null,
    @SerialName("secondhand_smoke") val secondhandSmoke: Boolean? = null,
    @SerialName("diabetes_diagnosed") val diabetesDiagnosed: Boolean? = null,
    @SerialName("bp_meds") val bpMeds: Boolean? = null,
    @SerialName("lipid_meds") val lipidMeds: Boolean? = null,
    @SerialName("high_glucose_history") val highGlucoseHistory: Boolean? = null,
    @SerialName("family_diabetes") val familyDiabetes: String? = null,
    @SerialName("veg_fruit_daily") val vegFruitDaily: Boolean? = null,
    val hearing: String? = null,
    val vision: String? = null,
    @SerialName("head_injury") val headInjury: Boolean? = null,
    val education: String? = null,
) {
    fun toDomain() = HealthBackground(smoking, quitYear, secondhandSmoke, diabetesDiagnosed, bpMeds, lipidMeds, highGlucoseHistory, familyDiabetes, vegFruitDaily, hearing, vision, headInjury, education)
}

fun HealthBackground.toRow() = HealthBackgroundRow(smoking, quitYear, secondhandSmoke, diabetesDiagnosed, bpMeds, lipidMeds, highGlucoseHistory, familyDiabetes, vegFruitDaily, hearing, vision, headInjury, education)

@Serializable
private data class RsDrawRow(val id: Long, @SerialName("draw_date") val drawDate: String)

@Serializable
private data class RsLabRow(@SerialName("draw_id") val drawId: Long, @SerialName("marker_name") val markerName: String, val value: Double? = null, val unit: String? = null)

@Serializable
private data class RsSessionRow(@SerialName("start_time") val startTime: String, @SerialName("duration_min") val durationMin: Double? = null)

@Serializable
private data class RsMealItem(@SerialName("alcohol_g") val alcoholG: Double? = null)

@Serializable
private data class RsMealRow(@SerialName("logged_at") val loggedAt: String, @SerialName("meal_items") val items: List<RsMealItem> = emptyList())

/** What Me › EXPOSURES shows under HEART, SUGAR AND BRAIN. */
data class RiskScreens(
    val inputs: ScreenInputs,
    val prevent: PreventOutcomeOrWhy?,
    val le8: Le8Result,
    val findrisc: FindriscResult,
    val brain: List<BrainFactor>,
    val backgroundAnswered: Boolean,
    /** Risk profile R4: each lab marker (lower case) to its latest draw date, for the preventive schedule. */
    val labDates: Map<String, LocalDate> = emptyMap(),
    /** The latest blood pressure reading, home or clinic. */
    val bpLast: LocalDate? = null,
) {
    val prevent10y: Double? get() = (prevent as? PreventOutcomeOrWhy.Ok)?.result?.tenYear?.get(com.bioscan.fieldterminal.domain.risk.PreventOutcome.TOTAL_CVD)
}

/** Window for activity, sleep and alcohol. */
const val SCREEN_WINDOW_DAYS = 28L

class RiskScreensRepository(private val supabase: SupabaseClient) {

    suspend fun loadBackground(): HealthBackground =
        catching {
            supabase.postgrest.from("health_background").select(Columns.list(
                "smoking,quit_year,secondhand_smoke,diabetes_diagnosed,bp_meds,lipid_meds,high_glucose_history,family_diabetes,veg_fruit_daily,hearing,vision,head_injury,education",
            )).decodeList<HealthBackgroundRow>().firstOrNull()
        }.getOrNull()?.toDomain() ?: HealthBackground()

    suspend fun saveBackground(b: HealthBackground) {
        supabase.postgrest.from("health_background").upsert(b.toRow()) { onConflict = "user_id" }
    }

    suspend fun load(context: Context, today: LocalDate = LocalDate.now()): RiskScreens {
        val profile = catching { UserProfileRepository(supabase).loadProfile() }.getOrNull()
        val sex = profile?.sex?.takeIf { it == "male" || it == "female" }
        val age = profile?.dateOfBirth?.let { runCatching { Period.between(LocalDate.parse(it), today).years }.getOrNull() }
        val background = loadBackground()

        val labs = catching { loadLabs() }.getOrElse { emptyList() }
        fun lab(vararg names: String) = latestLab(labs) { m -> names.any { m.equals(it, ignoreCase = true) } }
        // mmol/L cholesterol and glucose are converted; mg/dL is the default unit of these labs.
        fun cholMgDl(vararg names: String) = lab(*names)?.let { v -> Dated(if (v.unit?.contains("mmol", true) == true) v.value / MGDL_TO_MMOL else v.value, Source.LABS, v.date.toString()) }
        val glucose = lab("Fasting blood sugar", "Fasting glucose")?.let { v -> Dated(if (v.unit?.contains("mmol", true) == true) v.value * 18.016 else v.value, Source.LABS, v.date.toString()) }
        val hba1c = latestLab(labs) { it.equals("HbA1c", true) || it.contains("a1c", true) }?.let { Dated(it.value, Source.LABS, it.date.toString()) }
        val egfr = lab("eGFR")?.let { Dated(it.value, Source.LABS, it.date.toString()) }
            ?: lab("Creatinine")?.let { c ->
                if (age == null || sex == null) null
                else Dated(ckdEpi2021(if (c.unit?.contains("mol", true) == true) c.value / 88.4 else c.value, age.toDouble(), sex), Source.DERIVED, c.date.toString())
            }

        val analysis = AnalysisRepository(supabase)
        val weight = catching { analysis.loadBodyMetrics() }.getOrElse { emptyList() }.filter { it.weightKg != null }.maxByOrNull { it.date }
        val checkins = BodyCheckinRepository(supabase)
        val heightCm = profile?.heightCm ?: lab("Body Height")?.value ?: catching { checkins.latestHeightCm() }.getOrNull()
        val bmi = if (weight != null && heightCm != null && heightCm > 0) Dated(weight.weightKg!! / Math.pow(heightCm / 100, 2.0), Source.MEASURED, weight.date) else null
        val tapeWaist = catching { checkins.loadHistory() }.getOrElse { emptyList() }.lastOrNull { ratioWaist(it) != null }
        val labWaist = lab("Waistline")
        val waist = when {
            tapeWaist != null && (labWaist == null || !labWaist.date.isAfter(tapeWaist.date)) -> Dated(ratioWaist(tapeWaist)!!, Source.MEASURED, tapeWaist.date.toString())
            labWaist != null -> Dated(labWaist.value, Source.LABS, labWaist.date.toString())
            else -> null
        }

        val bp = catching { BloodPressureRepository(supabase).loadOverview(today) }.getOrNull()
        val bpScreen = bp?.let { bpForScreens(it.series, it.clinic) }

        val since = today.minusDays(SCREEN_WINDOW_DAYS)
        val sessions = catching {
            supabase.postgrest.from("exercise_sessions").select(Columns.list("start_time,duration_min")) {
                filter { gte("start_time", since.toString()) }
            }.decodeList<RsSessionRow>()
        }.getOrNull()
        val activity = sessions?.let { s -> Dated(s.sumOf { it.durationMin ?: 0.0 } * 7 / SCREEN_WINDOW_DAYS, Source.MEASURED, "last 4 weeks of logged sessions") }

        val sleep = catching { analysis.loadSleepDaily() }.getOrElse { emptyList() }
            .filter { !LocalDate.parse(it.date).isBefore(since) }.mapNotNull { it.hours }
            .takeIf { it.size >= 7 }?.let { Dated(it.average(), Source.MEASURED, "${it.size} nights") }

        val alcohol = catching {
            supabase.postgrest.from("meals").select(Columns.list("logged_at,meal_items(alcohol_g)")) {
                filter { gte("logged_at", since.toString()) }
            }.decodeList<RsMealRow>()
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { meals ->
            Dated(meals.sumOf { m -> m.items.sumOf { it.alcoholG ?: 0.0 } } * 7 / SCREEN_WINDOW_DAYS, Source.MEASURED, "logged drinks, last 4 weeks")
        }

        val answers = catching { InstrumentsRepository(supabase).loadResponses() }.getOrElse { emptyList() }
        fun latestScore(id: String) = answers.filter { it.instrument == id }.maxByOrNull { it.answeredAt }?.let { Dated(it.score, Source.ANSWERED, it.localDate) }
        val pm25 = catching { ExposuresRepository(supabase).load(today) }.getOrNull()?.summary?.pm25Mean?.let { Dated(it, Source.MEASURED, "30-day mean") }

        val inputs = ScreenInputs(
            age = age, sex = sex, background = background, bmi = bmi, waistCm = waist,
            sbp = bpScreen?.let { Dated(it.systolic, if (it.fromHome) Source.MEASURED else Source.LABS, if (it.fromHome) "home mean" else "clinic ${it.date}") },
            dbp = bpScreen?.let { Dated(it.diastolic, if (it.fromHome) Source.MEASURED else Source.LABS, null) },
            totalCholMgDl = cholMgDl("Total cholesterol"), hdlMgDl = cholMgDl("HDL Cholesterol", "HDL"), ldlMgDl = cholMgDl("LDL Cholesterol", "LDL"),
            fastingGlucoseMgDl = glucose, hba1cPct = hba1c, egfr = egfr, activityMinPerWeek = activity, sleepHours = sleep,
            alcoholGramsPerWeek = alcohol, phq9 = latestScore("phq9"), ucla3 = latestScore("ucla3"), pm25 = pm25, currentYear = today.year,
        )
        return RiskScreens(
            inputs = inputs,
            prevent = preventFor(definitions(context), inputs),
            le8 = le8(inputs),
            findrisc = findrisc(inputs),
            brain = brainFactors(inputs),
            backgroundAnswered = background != HealthBackground(),
            labDates = labs.groupBy { it.marker.lowercase() }.mapValues { (_, v) -> v.map { it.date }.reduce { a, b -> if (b.isAfter(a)) b else a } },
            bpLast = listOfNotNull(bp?.readings?.lastOrNull()?.at?.toLocalDate(), bp?.clinic?.date).maxOrNull(),
        )
    }

    private suspend fun loadLabs(): List<LabObservation> {
        val draws = supabase.postgrest.from("lab_draws").select(Columns.list("id,draw_date")).decodeList<RsDrawRow>()
            .mapNotNull { d -> runCatching { LocalDate.parse(d.drawDate) }.getOrNull()?.let { d.id to it } }.toMap()
        return supabase.postgrest.from("lab_results").select(Columns.list("draw_id,marker_name,value,unit")).decodeList<RsLabRow>()
            .mapNotNull { r -> val date = draws[r.drawId] ?: return@mapNotNull null; r.value?.let { LabObservation(r.markerName, it, r.unit, date) } }
    }

    companion object {
        @Volatile private var prevent: PreventDefs? = null

        fun definitions(context: Context): PreventDefs =
            prevent ?: context.applicationContext.assets.open("prevent-base.json").bufferedReader().use { parsePrevent(it.readText()) }.also { prevent = it }
    }
}

/** PREVENT needs every input; when one is missing it says which instead of guessing. */
fun preventFor(defs: PreventDefs, i: ScreenInputs): PreventOutcomeOrWhy? {
    val missing = listOfNotNull(
        "age".takeIf { i.age == null }, "sex".takeIf { i.sex == null }, "total cholesterol".takeIf { i.totalCholMgDl == null },
        "HDL".takeIf { i.hdlMgDl == null }, "blood pressure".takeIf { i.sbp == null }, "eGFR or creatinine".takeIf { i.egfr == null },
        "BMI".takeIf { i.bmi == null }, "BP medicine".takeIf { i.background.bpMeds == null },
        "cholesterol medicine".takeIf { i.background.lipidMeds == null }, "smoking".takeIf { i.background.smoking == null },
        "diabetes".takeIf { i.diabetes == null },
    )
    if (missing.isNotEmpty()) return PreventOutcomeOrWhy.NotApplicable(listOf("needs " + missing.joinToString(", ")))
    return defs.estimate(
        PreventInputs(
            age = i.age!!.toDouble(), sex = i.sex!!, totalCholMgDl = i.totalCholMgDl!!.value, hdlMgDl = i.hdlMgDl!!.value,
            sbp = i.sbp!!.value, bpTreated = i.background.bpMeds!!, statin = i.background.lipidMeds!!, diabetes = i.diabetes!!,
            currentSmoker = i.background.smoking == "current", egfr = i.egfr!!.value, bmi = i.bmi!!.value,
        ),
    )
}
