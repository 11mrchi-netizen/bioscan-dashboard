package com.bioscan.fieldterminal.domain.risk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

// Risk profile R4 (docs/risk-profile/01-plan.md): the home and water checklist and the layered preventive schedule, read
// from the assets home-checklist.json and preventive.json (supabase/functions/_shared/risk/). Pure.

private val json = Json { ignoreUnknownKeys = true }

// ---------- Home and water checklist ----------

@Serializable
data class ChecklistItem(
    val id: String,
    val question: String,
    val options: List<List<String>>,
    val flag: List<String>,
    val lowers: List<String> = emptyList(),
    val why: String,
    val fix: String,
    val source: String,
) {
    /** (code, words) pairs. */
    val choices: List<Pair<String, String>> get() = options.map { it[0] to it[1] }
    fun words(code: String?): String? = choices.firstOrNull { it.first == code }?.second
}

@Serializable
data class ChecklistDefs(val version: String, @SerialName("recheck_months") val recheckMonths: Long, val items: List<ChecklistItem>)

fun parseChecklist(text: String): ChecklistDefs = json.decodeFromString(ChecklistDefs.serializer(), text)

data class ChecklistAnswer(val item: String, val value: String, val date: LocalDate)

data class ChecklistState(
    val item: ChecklistItem,
    val answer: ChecklistAnswer?,
    val flagged: Boolean,
    /** Answered more than recheck_months ago, or never. */
    val stale: Boolean,
)

fun ChecklistDefs.states(answers: List<ChecklistAnswer>, today: LocalDate): List<ChecklistState> = items.map { item ->
    val latest = answers.filter { it.item == item.id }.maxByOrNull { it.date }
    ChecklistState(
        item = item,
        answer = latest,
        flagged = latest != null && latest.value in item.flag,
        stale = latest == null || latest.date.plusMonths(recheckMonths).isBefore(today),
    )
}

// ---------- Preventive care ----------

@Serializable
data class PreventiveItem(
    val id: String,
    val name: String,
    val kind: String,
    val layer: String,
    val sex: String,
    @SerialName("age_min") val ageMin: Int,
    @SerialName("age_max") val ageMax: Int,
    @SerialName("interval_months") val intervalMonths: Long? = null,
    val `when`: String? = null,
    /** [code, words, months] */
    val methods: List<JsonArray> = emptyList(),
    val auto: String? = null,
    val source: String,
) {
    data class Method(val code: String, val words: String, val months: Long)

    val methodList: List<Method> get() = methods.map { Method(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content, it[2].jsonPrimitive.int.toLong()) }

    /** Months until it is due again after [method] (or the item's own interval); null = once in adult life. */
    fun intervalFor(method: String?): Long? = methodList.firstOrNull { it.code == method }?.months ?: intervalMonths
}

@Serializable
data class PreventiveDefs(val version: String, val items: List<PreventiveItem>)

fun parsePreventive(text: String): PreventiveDefs = json.decodeFromString(PreventiveDefs.serializer(), text)

/** What decides who an item is for, and what data already shows it done. */
data class PreventiveContext(
    val age: Int?,
    val sex: String?,
    val bmi: Double?,
    val smoking: String?,
    /** PREVENT 10-year total CVD, when worked out. */
    val prevent10y: Double?,
    /** auto key (preventive.json "auto") to the latest date the person's data shows it. */
    val autoDates: Map<String, LocalDate>,
)

data class PreventiveRecord(val item: String, val date: LocalDate?, val method: String?, val notApplicable: Boolean, val created: LocalDate)

enum class PreventiveState(val word: String) {
    DUE("due"), UPCOMING("next"), DONE("done"), NOT_APPLICABLE("not applicable"), CHECK("check if it applies"),
}

data class PreventiveStatus(
    val item: PreventiveItem,
    val state: PreventiveState,
    val last: LocalDate?,
    val lastFrom: String?,
    val next: LocalDate?,
    val method: String?,
    /** Why it shows as CHECK: who it is for that the app cannot tell. */
    val check: String?,
)

/** Null = not for this person (age or sex); otherwise null-or-text: the condition the person must confirm. */
private fun PreventiveItem.applies(c: PreventiveContext): Pair<Boolean, String?> {
    if (sex != "any" && c.sex != null && c.sex != sex) return false to null
    if (c.age != null && (c.age < ageMin || c.age > ageMax)) return false to null
    return when (`when`) {
        null -> true to null
        "overweight" -> when { c.bmi == null -> true to "if your BMI is 25 or more"; c.bmi >= 25 -> true to null; else -> false to null }
        "ever_smoked" -> when (c.smoking) { "former", "current" -> true to null; "never" -> false to null; else -> true to "if you have ever smoked" }
        "lung_ldct" -> when (c.smoking) { "former", "current" -> true to "if you have 20 pack-years and smoke now or quit within 15 years"; "never" -> false to null; else -> true to "if you have smoked 20 pack-years" }
        "prevent_borderline" -> when { c.prevent10y == null -> false to null; c.prevent10y >= 0.05 && c.prevent10y < 0.20 -> true to null; else -> false to null }
        else -> true to null
    }
}

fun PreventiveDefs.statuses(records: List<PreventiveRecord>, c: PreventiveContext, today: LocalDate): List<PreventiveStatus> = items.mapNotNull { item ->
    val (applies, check) = item.applies(c)
    if (!applies) return@mapNotNull null
    val mine = records.filter { it.item == item.id }.sortedBy { it.created }
    val latestNa = mine.lastOrNull()?.takeIf { it.notApplicable }
    if (latestNa != null) return@mapNotNull PreventiveStatus(item, PreventiveState.NOT_APPLICABLE, null, null, null, null, null)
    val recorded = mine.filter { !it.notApplicable && it.date != null }.maxByOrNull { it.date!! }
    val auto = item.auto?.let { c.autoDates[it] }
    val (last, from) = when {
        recorded != null && (auto == null || !auto.isAfter(recorded.date!!)) -> recorded.date to "recorded"
        auto != null -> auto to "from your data"
        else -> null to null
    }
    val method = recorded?.method
    val months = item.intervalFor(method)
    val next = if (last != null && months != null) last.plusMonths(months) else null
    val state = when {
        last == null -> if (check != null) PreventiveState.CHECK else PreventiveState.DUE
        months == null -> PreventiveState.DONE
        !today.isBefore(next!!) -> PreventiveState.DUE
        else -> PreventiveState.UPCOMING
    }
    PreventiveStatus(item, state, last, from, next, method, check)
}

/** The layers in the order shown, with their words. */
val PREVENTIVE_LAYERS = listOf(
    "core" to "GUIDELINE (USPSTF A/B, CDC VACCINES)",
    "cardio" to "CARDIOVASCULAR PREVENTION (ESC/EAS)",
    "regional" to "WHERE YOU ARE FROM",
    "optional" to "OPTIONAL: DISCUSS WITH A DOCTOR",
)

// ---------- Substances, sun and heat ----------

/** UK Chief Medical Officers' low-risk guideline: no more than 14 units (112 g) a week. */
const val ALCOHOL_LOW_RISK_G_PER_WEEK = 112.0
/** EFSA 2015: up to 400 mg of caffeine a day is not a safety concern for healthy adults. */
const val CAFFEINE_SAFE_MG_PER_DAY = 400.0
/** A day counts as hot from this maximum temperature. */
const val HOT_DAY_C = 32.0

data class SubstanceSummary(
    val alcoholGramsPerWeek: Double?,
    val caffeineMgPerDay: Double?,
    /** Days in the window with caffeine logged at 14:00 or later. */
    val lateCaffeineDays: Int?,
    val days: Int,
)

data class HeatSummary(val hotDays: Int, val sessionsOnHotDays: Int, val sessionsHighUv: Int, val days: Int)
