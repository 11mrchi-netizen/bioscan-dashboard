package com.bioscan.fieldterminal.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// The regimen model (docs/regimen/README.md, migration 20261115_regimen_model.sql). Everything taken on purpose --
// supplements, medications, peptides, hormones -- with a dose as a number and a unit, the times of day it is taken, a
// schedule rule, an optional on/off cycle and optional planned dose steps (titration or taper). Every screen asks this
// file whether an item is due, what today's dose is and how to say its schedule, so they can never disagree.
//
// Rows saved before the model have only the old columns (time_of_day, every_n_days, dose text); they read through
// [regimenSpec]'s fallback exactly as they behaved before.

enum class RegimenKind(val key: String, val label: String) {
    SUPPLEMENT("supplement", "Supplement"),
    MEDICATION("medication", "Medication"),
    PEPTIDE("peptide", "Peptide"),
    HORMONE("hormone", "Hormone"),
    OTHER("other", "Other");

    companion object {
        fun of(key: String?): RegimenKind = entries.firstOrNull { it.key == key } ?: SUPPLEMENT
    }
}

/** Forms and routes as stored; the labels are what the form shows. */
val REGIMEN_FORMS: List<Pair<String, String>> = listOf(
    "capsule" to "Capsule", "tablet" to "Tablet", "softgel" to "Softgel", "powder" to "Powder", "liquid" to "Liquid",
    "gummy" to "Gummy", "injection" to "Injection", "nasal_spray" to "Nasal spray", "cream" to "Cream", "patch" to "Patch",
    "other" to "Other",
)
val REGIMEN_ROUTES: List<Pair<String, String>> = listOf(
    "oral" to "Oral", "sublingual" to "Under the tongue", "subcutaneous" to "Under the skin", "intramuscular" to "Into muscle",
    "nasal" to "Nasal", "topical" to "On the skin", "other" to "Other",
)
val REGIMEN_UNITS: List<String> = listOf("mg", "mcg", "g", "IU", "ml", "drop", "pill", "capsule", "tablet", "softgel", "scoop", "serving", "unit")

/** The times of day a dose can be planned in, in order. Reminders and the logging bundles use the same three. */
val REGIMEN_SLOTS: List<String> = listOf("morning", "afternoon", "night")

enum class ScheduleType(val key: String) { DAILY("daily"), EVERY_N_DAYS("every_n_days"), WEEKDAYS("weekdays"), AS_NEEDED("as_needed") }

@Serializable
data class CycleJson(
    @SerialName("on_days") val onDays: Int,
    @SerialName("off_days") val offDays: Int,
    val start: String,
)

/** The `schedule` column. [days] are ISO weekdays (Monday = 1). */
@Serializable
data class ScheduleJson(
    val type: String,
    val n: Int? = null,
    val days: List<Int>? = null,
    val cycle: CycleJson? = null,
)

@Serializable
data class DoseStepJson(val weeks: Int? = null, val amount: Double)

/** The `dose_steps` column: steps from [start], each [DoseStepJson.weeks] long; the last one carries on. */
@Serializable
data class DoseStepsJson(val start: String, val steps: List<DoseStepJson>)

data class Cycle(val onDays: Int, val offDays: Int, val start: LocalDate)

data class RegimenSpec(
    val kind: RegimenKind,
    val form: String?,
    val route: String?,
    val amount: Double?,
    val unit: String?,
    /** The stored dose text, used when there is no structured dose. */
    val doseText: String,
    /** Times of day, in [REGIMEN_SLOTS] order; empty for as-needed. */
    val slots: List<String>,
    val type: ScheduleType,
    val everyNDays: Int? = null,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val cycle: Cycle? = null,
    val steps: DoseStepsJson? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
) {
    val asNeeded: Boolean get() = type == ScheduleType.AS_NEEDED || slots.isEmpty()
}

/** Builds the spec from a row's columns; rows without the structured columns read from the old ones. */
fun regimenSpec(
    timeOfDay: String,
    everyNDays: Int?,
    dose: String,
    kind: String? = null,
    form: String? = null,
    route: String? = null,
    doseAmount: Double? = null,
    doseUnit: String? = null,
    slots: List<String> = emptyList(),
    schedule: ScheduleJson? = null,
    doseSteps: DoseStepsJson? = null,
    startDate: String? = null,
    endDate: String? = null,
): RegimenSpec {
    val legacyAsNeeded = timeOfDay.equals("as-needed", ignoreCase = true)
    val type = schedule?.type?.let { t -> ScheduleType.entries.firstOrNull { it.key == t } }
        ?: when {
            legacyAsNeeded -> ScheduleType.AS_NEEDED
            everyNDays != null && everyNDays > 1 -> ScheduleType.EVERY_N_DAYS
            else -> ScheduleType.DAILY
        }
    val orderedSlots = slots.ifEmpty { if (legacyAsNeeded || schedule != null) emptyList() else listOf(timeOfDay.lowercase()) }
        .filter { it in REGIMEN_SLOTS }.distinct().sortedBy { REGIMEN_SLOTS.indexOf(it) }
    val parsedDose = if (doseAmount == null) parseDoseText(dose) else null
    return RegimenSpec(
        kind = RegimenKind.of(kind),
        form = form,
        route = route,
        amount = doseAmount ?: parsedDose?.first,
        unit = doseUnit ?: parsedDose?.second,
        doseText = dose,
        slots = if (type == ScheduleType.AS_NEEDED) emptyList() else orderedSlots,
        type = type,
        everyNDays = (schedule?.n ?: everyNDays)?.takeIf { type == ScheduleType.EVERY_N_DAYS && it > 1 },
        weekdays = schedule?.days.orEmpty().mapNotNull { d -> runCatching { DayOfWeek.of(d) }.getOrNull() }.toSet(),
        cycle = schedule?.cycle?.let { c ->
            runCatching { LocalDate.parse(c.start) }.getOrNull()?.takeIf { c.onDays > 0 && c.offDays > 0 }?.let { Cycle(c.onDays, c.offDays, it) }
        },
        steps = doseSteps?.takeIf { it.steps.isNotEmpty() && runCatching { LocalDate.parse(it.start) }.isSuccess },
        startDate = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        endDate = endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    )
}

/** "36 mg, 3x/week" -> (36.0, "mg"); "3 Gr" -> (3.0, "g"). Null when there is no number and known unit at the start. */
fun parseDoseText(text: String): Pair<Double, String>? {
    val m = Regex("""^\s*([0-9]+(?:[.,][0-9]+)?)\s*([A-Za-z]+)""").find(text) ?: return null
    val amount = m.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: return null
    val unit = when (m.groupValues[2].lowercase()) {
        "mg" -> "mg"; "mcg", "ug" -> "mcg"; "g", "gr" -> "g"; "iu" -> "IU"; "ml" -> "ml"
        "drop", "drops" -> "drop"; "pill", "pills" -> "pill"; "capsule", "capsules", "caps" -> "capsule"
        "tablet", "tablets" -> "tablet"; "softgel", "softgels" -> "softgel"; "scoop", "scoops" -> "scoop"
        "serving", "servings" -> "serving"; "unit", "units" -> "unit"
        else -> return null
    }
    return amount to unit
}

/** 2.5 -> "2.5", 3000.0 -> "3000" (the same text Postgres' trim_scale gives, so the backfill and the app agree). */
fun formatAmount(amount: Double): String = BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()

// ---- Cycles and dose steps -------------------------------------------------------------------------------------------

data class CycleState(val on: Boolean, val dayOfPhase: Int, val phaseDays: Int, val nextChange: LocalDate)

/** Where [date] falls in the cycle; null without a cycle or before it starts. */
fun RegimenSpec.cycleOn(date: LocalDate): CycleState? {
    val c = cycle ?: return null
    if (date.isBefore(c.start)) return null
    val period = c.onDays + c.offDays
    val day = (ChronoUnit.DAYS.between(c.start, date) % period).toInt()
    return if (day < c.onDays) CycleState(true, day + 1, c.onDays, date.plusDays((c.onDays - day).toLong()))
    else CycleState(false, day - c.onDays + 1, c.offDays, date.plusDays((period - day).toLong()))
}

data class StepState(val index: Int, val amount: Double, val endsOn: LocalDate?, val next: Double?)

/** The planned dose step on [date]; null without steps or before they start. */
fun RegimenSpec.stepOn(date: LocalDate): StepState? {
    val s = steps ?: return null
    var from = LocalDate.parse(s.start)
    if (date.isBefore(from)) return null
    s.steps.forEachIndexed { i, step ->
        val until = step.weeks?.let { from.plusWeeks(it.toLong()) }
        if (until == null || date.isBefore(until) || i == s.steps.lastIndex) {
            return StepState(i, step.amount, until?.takeIf { i < s.steps.lastIndex }, s.steps.getOrNull(i + 1)?.amount)
        }
        from = until
    }
    return null
}

/** One dose's amount on [date]: the planned step when there is one, otherwise the item's dose. */
fun RegimenSpec.amountOn(date: LocalDate): Double? = stepOn(date)?.amount ?: amount

/** The dose as words on [date] ("2.5 mg"); the stored text when there is no structured dose. */
fun RegimenSpec.doseTextOn(date: LocalDate): String {
    val a = amountOn(date) ?: return doseText
    val u = unit ?: return doseText
    return "${formatAmount(a)} $u"
}

// ---- Due and adherence -----------------------------------------------------------------------------------------------

/** Inside the item's own dates (end inclusive). */
fun RegimenSpec.coversDate(date: LocalDate): Boolean =
    (startDate == null || !date.isBefore(startDate)) && (endDate == null || !date.isAfter(endDate))

/**
 * Whether [date] is a planned dosing day by the calendar alone: as-needed never, a cycle's off days never, weekdays only
 * on its days. Every-N-days counts from the last take, so it is not decided here ([isDueOn] does).
 */
fun RegimenSpec.calendarDay(date: LocalDate): Boolean {
    if (asNeeded || !coversDate(date)) return false
    if (cycleOn(date)?.on == false) return false
    return when (type) {
        ScheduleType.WEEKDAYS -> date.dayOfWeek in weekdays
        else -> true
    }
}

/** Due on [date] given the last day it was taken (before [date]); every-N-days counts from that take. */
fun RegimenSpec.isDueOn(date: LocalDate, lastTaken: LocalDate?): Boolean {
    if (!calendarDay(date)) return false
    if (type != ScheduleType.EVERY_N_DAYS) return true
    val n = everyNDays ?: return true
    // Taken on [date] itself: it was due today (and is done); the slot logic decides what is left.
    if (lastTaken == date) return true
    val last = lastTaken?.takeIf { it.isBefore(date) } ?: return true
    return ChronoUnit.DAYS.between(last, date) >= n
}

/** The next day it is due after [date], looking a year ahead; null for as-needed or when it has ended. */
fun RegimenSpec.nextDueAfter(date: LocalDate, lastTaken: LocalDate?): LocalDate? {
    var d = date.plusDays(1)
    val last = lastTaken ?: date
    repeat(366) {
        if (isDueOn(d, last)) return d
        d = d.plusDays(1)
    }
    return null
}

/** Which of today's slots are done after [takesToday] logged doses: the first ones, in order. */
fun RegimenSpec.slotsDone(takesToday: Int): List<String> = slots.take(takesToday.coerceAtLeast(0))

/** Planned doses on average per day, across the cycle: what a bottle is used at. As-needed: 0. */
fun RegimenSpec.dosesPerDay(): Double {
    if (asNeeded) return 0.0
    val perDay = slots.size.coerceAtLeast(1).toDouble()
    val freq = when (type) {
        ScheduleType.EVERY_N_DAYS -> 1.0 / (everyNDays ?: 1)
        ScheduleType.WEEKDAYS -> weekdays.size / 7.0
        else -> 1.0
    }
    val onShare = cycle?.let { it.onDays.toDouble() / (it.onDays + it.offDays) } ?: 1.0
    return perDay * freq * onShare
}

/**
 * Planned dosing days in [from]..[to] (clipped to the item's dates). Every-N-days plans one in N days, the first on the
 * window's first day; the calendar rules count their days exactly.
 */
fun RegimenSpec.plannedDays(from: LocalDate, to: LocalDate): Int {
    if (asNeeded) return 0
    val start = maxOf(from, startDate ?: from)
    val end = minOf(to, endDate ?: to)
    if (end.isBefore(start)) return 0
    val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.filter { calendarDay(it) }.count()
    return if (type == ScheduleType.EVERY_N_DAYS) Math.ceil(days.toDouble() / (everyNDays ?: 1)).toInt() else days
}

// ---- Words -----------------------------------------------------------------------------------------------------------

private val DAY_SHORT = mapOf(
    DayOfWeek.MONDAY to "Mon", DayOfWeek.TUESDAY to "Tue", DayOfWeek.WEDNESDAY to "Wed", DayOfWeek.THURSDAY to "Thu",
    DayOfWeek.FRIDAY to "Fri", DayOfWeek.SATURDAY to "Sat", DayOfWeek.SUNDAY to "Sun",
)

/** "Daily · morning", "Every 3 days · morning", "Mon, Wed, Fri · morning + night", "As needed". */
fun RegimenSpec.scheduleWords(): String {
    if (asNeeded) return "As needed"
    val how = when (type) {
        ScheduleType.EVERY_N_DAYS -> "Every ${everyNDays ?: 1} days"
        ScheduleType.WEEKDAYS -> weekdays.sorted().joinToString(", ") { DAY_SHORT.getValue(it) }.ifEmpty { "No days set" }
        else -> if (slots.size == 2) "Twice a day" else if (slots.size == 3) "Three times a day" else "Daily"
    }
    return "$how · ${slots.joinToString(" + ")}"
}

/** "8 weeks on, 4 off" style words for a cycle. */
fun Cycle.words(): String {
    fun span(d: Int) = if (d % 7 == 0) "${d / 7} week${if (d / 7 == 1) "" else "s"}" else "$d day${if (d == 1) "" else "s"}"
    return "${span(onDays)} on, ${span(offDays)} off"
}

/** "2.5 mg for 4 weeks, then 5 mg". */
fun RegimenSpec.stepsWords(): String? {
    val s = steps ?: return null
    val u = unit.orEmpty()
    return s.steps.joinToString(", then ") { st -> "${formatAmount(st.amount)} $u".trim() + (st.weeks?.let { w -> " for $w week${if (w == 1) "" else "s"}" } ?: "") }
}

/** Today's state in words, for the list: "Off cycle until 12 Dec", "Week 2 of 4 at 2.5 mg, then 5 mg", or null. */
fun RegimenSpec.stateWords(today: LocalDate): String? {
    cycleOn(today)?.let { c ->
        return if (c.on) "On cycle · day ${c.dayOfPhase} of ${c.phaseDays}" else "Off cycle until ${shortDay(c.nextChange)}"
    }
    stepOn(today)?.let { st ->
        val next = st.next ?: return null
        val until = st.endsOn ?: return null
        return "${formatAmount(st.amount)} ${unit.orEmpty()} until ${shortDay(until.minusDays(1))}, then ${formatAmount(next)} ${unit.orEmpty()}".replace("  ", " ")
    }
    return null
}

private fun shortDay(d: LocalDate): String = "${d.dayOfMonth} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"

/** The old columns written alongside the structured ones, for the clients that read only those. */
data class LegacyColumns(val dose: String, val timeOfDay: String, val everyNDays: Int?)

fun RegimenSpec.legacyColumns(today: LocalDate): LegacyColumns = LegacyColumns(
    dose = doseTextOn(today),
    timeOfDay = if (asNeeded) "as-needed" else slots.first(),
    everyNDays = everyNDays?.takeIf { type == ScheduleType.EVERY_N_DAYS && it >= 2 },
)

/** A roster row's spec. */
fun com.bioscan.fieldterminal.data.model.SupplementRow.spec(): RegimenSpec = regimenSpec(
    timeOfDay = timeOfDay, everyNDays = everyNDays, dose = dose, kind = kind, form = form, route = route,
    doseAmount = doseAmount, doseUnit = doseUnit, slots = slots, schedule = schedule, doseSteps = doseSteps,
    startDate = startDate, endDate = endDate,
)
