package com.bioscan.fieldterminal.domain.fuel

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

// Each meal of the day gets a carbohydrate code (LOW / MEDIUM / HIGH), a gram range and the reason, the way Hexis's
// Carb Coding and Fuelin's traffic lights work, but with one hue in three steps plus the word on screen (the colour
// contract keeps red, amber and green for status).
//
//   HIGH   1-4 h before a hard, long or race session (Thomas 2016: 1-4 g/kg in the 1-4 h before), within 2 h after one
//          (recovery, 1-1.2 g/kg/h), the evening before a hard morning, and every meal of a loading day;
//   LOW    on rest days, and before an easy session only when the person has chosen to train low (never on a deficit
//          day or while loading: Impey 2018's case for train-low is easy sessions with glycogen to spare);
//   MEDIUM otherwise.
//
// The day's carbohydrate, less what is planned during sessions, is split across the meals by weight (LOW 0.5, MEDIUM 1,
// HIGH 2) and shown as a range of +-15 %. Protein is 0.3-0.4 g/kg at each main meal (Areta 2013; Moore 2015). When no
// meal falls 1-4 h before a key session, a pre-session snack is added; after one with no meal within 2 h, a recovery snack.

enum class CarbCode(val label: String, val weight: Double) { LOW("LOW", 0.5), MEDIUM("MEDIUM", 1.0), HIGH("HIGH", 2.0) }

enum class MealSlotKind(val label: String, val main: Boolean) {
    BREAKFAST("BREAKFAST", true),
    LUNCH("LUNCH", true),
    SNACK("SNACK", false),
    DINNER("DINNER", true),
    PRE_SESSION("PRE-SESSION", false),
    RECOVERY("RECOVERY", false),
}

data class MealTimes(
    val breakfast: LocalTime = LocalTime.of(7, 30),
    val lunch: LocalTime = LocalTime.of(12, 30),
    val snack: LocalTime? = LocalTime.of(16, 0),
    val dinner: LocalTime = LocalTime.of(19, 30),
)

data class CodedMeal(
    val kind: MealSlotKind,
    val time: ZonedDateTime,
    val code: CarbCode,
    val carbLowG: Double,
    val carbHighG: Double,
    val proteinLowG: Double?,
    val proteinHighG: Double?,
    val reason: String,
) {
    val carbMidG: Double get() = (carbLowG + carbHighG) / 2
}

data class SessionWindow(val item: PlannedItem, val fuel: SessionFuel)

data class CodedDay(val date: LocalDate, val meals: List<CodedMeal>, val sessions: List<SessionWindow>)

private fun hoursBetween(a: ZonedDateTime, b: ZonedDateTime): Double = Duration.between(a, b).toMinutes() / 60.0

/**
 * Codes the meals of [plan]'s day. [day] holds its sessions; [nextDay] is checked for a hard morning; [trainLow] is the
 * person's opt-in; [deficit] is true when the goal is a deficit.
 */
fun codeMeals(
    plan: DayFuelPlan,
    day: PlannedDay,
    nextDay: PlannedDay?,
    weightKg: Double,
    zone: ZoneId,
    times: MealTimes = MealTimes(),
    trainLow: Boolean = false,
    deficit: Boolean = false,
    toleratedGPerHour: Double = DEFAULT_TOLERATED_G_PER_H,
): CodedDay {
    val date = day.date
    fun at(t: LocalTime) = date.atTime(t).atZone(zone)
    val sessions = day.items.filter { it.start != null }.sortedBy { it.start!!.toEpochSecond() }
    val windows = day.items.map { SessionWindow(it, sessionFuel(it.durationMin, it.intensity, toleratedGPerHour)) }

    val slots = mutableListOf(
        MealSlotKind.BREAKFAST to at(times.breakfast),
        MealSlotKind.LUNCH to at(times.lunch),
        MealSlotKind.DINNER to at(times.dinner),
    )
    times.snack?.let { slots += MealSlotKind.SNACK to at(it) }
    // A meal that would fall during a session moves to just before it (breakfast before a 07:00 race, say).
    for (i in slots.indices) {
        val (kind, t) = slots[i]
        val clash = sessions.firstOrNull { s -> !t.isBefore(s.start!!) && t.isBefore(s.end!!) }
        if (clash != null) slots[i] = kind to clash.start!!.minusMinutes(if (kind.main) 150 else 60)
    }
    for (s in sessions.filter { it.intensity.isKey && it.durationMin >= 60 }) {
        val start = s.start!!
        if (slots.none { (_, t) -> hoursBetween(t, start) in 1.0..4.0 }) slots += MealSlotKind.PRE_SESSION to start.minusMinutes(90)
        val end = s.end!!
        if (slots.none { (_, t) -> hoursBetween(end, t) in 0.0..2.0 }) slots += MealSlotKind.RECOVERY to end.plusMinutes(30)
    }
    slots.sortBy { it.second.toEpochSecond() }

    val hardMorningTomorrow = nextDay?.items?.firstOrNull { it.intensity.isKey && (it.start?.hour ?: 12) < 12 }
    val coded = slots.map { (kind, t) ->
        val before = sessions.firstOrNull { it.intensity.isKey && hoursBetween(t, it.start!!) in 1.0..4.0 }
        val beforeAny = sessions.firstOrNull { hoursBetween(t, it.start!!) in 0.5..4.0 }
        val after = sessions.lastOrNull { it.intensity.isKey && hoursBetween(it.end!!, t) in 0.0..2.0 }
        val (code, reason) = when {
            plan.loading -> CarbCode.HIGH to "Loading day: every meal carries carbohydrate."
            before != null -> CarbCode.HIGH to "${formatGap(t, before.start!!)} before ${before.title}: top up for the work."
            after != null -> CarbCode.HIGH to "After ${after.title}: refill (1-1.2 g/kg in the first hours)."
            kind == MealSlotKind.DINNER && hardMorningTomorrow != null ->
                CarbCode.HIGH to "Tomorrow morning: ${hardMorningTomorrow.title}. Tonight's meal is its fuel."
            trainLow && !deficit && beforeAny != null && beforeAny.intensity == Intensity.EASY ->
                CarbCode.LOW to "Before an easy session: training low, as you chose. Keep the protein."
            day.isRest -> CarbCode.LOW to "Rest day: carbohydrate low, protein as usual."
            else -> CarbCode.MEDIUM to "Training day: a normal share."
        }
        Triple(kind, t, code to reason)
    }

    // In-session carbohydrate comes off the day's total first; the rest is split by weight.
    val inSession = windows.sumOf { it.fuel.midG }
    val forMeals = (plan.carbG - inSession).coerceAtLeast(plan.carbG * 0.5)
    val totalWeight = coded.sumOf { it.third.first.weight }.coerceAtLeast(0.5)
    val meals = coded.map { (kind, t, cr) ->
        val share = forMeals * cr.first.weight / totalWeight
        CodedMeal(
            kind = kind, time = t, code = cr.first,
            carbLowG = share * 0.85, carbHighG = share * 1.15,
            proteinLowG = if (kind.main || kind == MealSlotKind.RECOVERY) 0.3 * weightKg else null,
            proteinHighG = if (kind.main || kind == MealSlotKind.RECOVERY) 0.4 * weightKg else null,
            reason = cr.second,
        )
    }
    return CodedDay(date, meals, windows)
}

private fun formatGap(a: ZonedDateTime, b: ZonedDateTime): String {
    val m = Duration.between(a, b).toMinutes()
    return if (m >= 90) "${(m / 60.0).roundToInt()} h" else "$m min"
}

/** Which coded meal a logged meal at [loggedAt] belongs to: the nearest one within 2 hours, else none. */
fun mealForLog(meals: List<CodedMeal>, loggedAt: ZonedDateTime): CodedMeal? =
    meals.minByOrNull { kotlin.math.abs(Duration.between(it.time, loggedAt).toMinutes()) }
        ?.takeIf { kotlin.math.abs(Duration.between(it.time, loggedAt).toMinutes()) <= 120 }

enum class MealAdherence(val label: String) { WITHIN("WITHIN"), UNDER("UNDER"), OVER("OVER"), NOT_LOGGED("NOT LOGGED") }

fun adherence(meal: CodedMeal, loggedCarbG: Double?): MealAdherence = when {
    loggedCarbG == null -> MealAdherence.NOT_LOGGED
    loggedCarbG < meal.carbLowG -> MealAdherence.UNDER
    loggedCarbG > meal.carbHighG -> MealAdherence.OVER
    else -> MealAdherence.WITHIN
}
