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
// HIGH 2) and shown as a range of +-15 %. When no meal falls 1-4 h before a key session, a pre-session snack is added;
// after one with no meal within 2 h, a recovery snack.
//
// Protein and fat are split too, so the meals add up to the day (fuel planning phase 10). Protein: weight 1 for main
// meals and recovery, 0.5 for snacks, with at least 0.3 g/kg at each main meal when the day allows it (Areta 2013;
// Moore 2015). Fat: weight 1 for main meals, 0.5 for snacks and for a HIGH meal in the 4 h before a key session, with a
// pre-session snack capped at 10 g and recovery at 15 g (fat slows gastric emptying; ACSM 2016 advises low fat and fibre
// before training); what a cap leaves goes to the other meals.

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
    /** Protein aimed at, g; the meals' protein adds up to the day's. */
    val proteinG: Double,
    /** Fat aimed at, g; null when the day has no fat target (no energy known). */
    val fatG: Double?,
    val reason: String,
    /** True for a meal in the 4 h before a key session: low in fat and fibre. */
    val beforeKeySession: Boolean = false,
) {
    val carbMidG: Double get() = (carbLowG + carbHighG) / 2
    val proteinLowG: Double get() = proteinG * 0.85
    val proteinHighG: Double get() = proteinG * 1.15
    /** Energy of the targets, kcal (4 / 4 / 9 per gram); null without a fat target. */
    val kcal: Double? get() = fatG?.let { 4 * carbMidG + 4 * proteinG + 9 * it }
}

const val PRE_SESSION_FAT_CAP_G = 10.0
const val RECOVERY_FAT_CAP_G = 15.0
const val MAIN_MEAL_PROTEIN_MIN_G_PER_KG = 0.3

/**
 * Splits [total] across [weights], none above its cap ([caps], null = none): shares by weight; a share over its cap is
 * held at the cap and the rest is split again among the others. When every meal is capped, the caps are what is given.
 */
fun splitWithCaps(total: Double, weights: List<Double>, caps: List<Double?>): List<Double> {
    val out = MutableList(weights.size) { 0.0 }
    val open = weights.indices.toMutableSet()
    var left = total
    while (open.isNotEmpty()) {
        val w = open.sumOf { weights[it] }
        if (w <= 0) break
        val over = open.filter { i -> caps[i]?.let { left * weights[i] / w > it } == true }
        if (over.isEmpty()) {
            open.forEach { out[it] = left * weights[it] / w }
            break
        }
        over.forEach { i -> out[i] = caps[i]!!; left -= caps[i]!!; open -= i }
    }
    return out
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
        val keyAhead = before != null || sessions.any { it.intensity.isKey && hoursBetween(t, it.start!!) in 0.0..4.0 }
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
        Coded(kind, t, code, reason, keyAhead)
    }

    // In-session carbohydrate comes off the day's total first; the rest is split by weight.
    val inSession = windows.sumOf { it.fuel.midG }
    val forMeals = (plan.carbG - inSession).coerceAtLeast(plan.carbG * 0.5)
    val totalWeight = coded.sumOf { it.code.weight }.coerceAtLeast(0.5)

    val protein = splitProtein(plan.proteinG, coded.map { it.kind }, weightKg)
    val fat = plan.fatG?.let { f ->
        splitWithCaps(
            f.coerceAtLeast(0.0),
            coded.map { c -> if (!c.kind.main || (c.code == CarbCode.HIGH && c.beforeKey)) 0.5 else 1.0 },
            coded.map { c ->
                when (c.kind) {
                    MealSlotKind.PRE_SESSION -> PRE_SESSION_FAT_CAP_G
                    MealSlotKind.RECOVERY -> RECOVERY_FAT_CAP_G
                    else -> if (c.beforeKey && !c.kind.main) PRE_SESSION_FAT_CAP_G else null
                }
            },
        )
    }
    val meals = coded.mapIndexed { i, c ->
        val share = forMeals * c.code.weight / totalWeight
        CodedMeal(
            kind = c.kind, time = c.time, code = c.code,
            carbLowG = share * 0.85, carbHighG = share * 1.15,
            proteinG = protein[i], fatG = fat?.get(i),
            reason = c.reason, beforeKeySession = c.beforeKey,
        )
    }
    return CodedDay(date, meals, windows)
}

private data class Coded(val kind: MealSlotKind, val time: ZonedDateTime, val code: CarbCode, val reason: String, val beforeKey: Boolean)

/**
 * The day's protein across the meals: weight 1 for main meals and recovery, 0.5 for snacks; each main meal gets at least
 * [MAIN_MEAL_PROTEIN_MIN_G_PER_KG] g/kg when the day's protein covers that for all of them, the snacks sharing the rest.
 */
fun splitProtein(dayProteinG: Double, kinds: List<MealSlotKind>, weightKg: Double): List<Double> {
    fun weight(k: MealSlotKind) = if (k.main || k == MealSlotKind.RECOVERY) 1.0 else 0.5
    val total = kinds.sumOf { weight(it) }
    if (total <= 0) return kinds.map { 0.0 }
    val shares = kinds.map { dayProteinG * weight(it) / total }
    val floor = MAIN_MEAL_PROTEIN_MIN_G_PER_KG * weightKg
    val mains = kinds.indices.filter { kinds[it].main }
    if (mains.none { shares[it] < floor } || dayProteinG < floor * mains.size) return shares
    // Mains at the floor (or above it by weight); the others share what is left by weight.
    val others = kinds.indices.filter { !kinds[it].main }
    val left = (dayProteinG - floor * mains.size).coerceAtLeast(0.0)
    val otherW = others.sumOf { weight(kinds[it]) }
    return kinds.indices.map { i ->
        when {
            kinds[i].main && otherW > 0 -> floor
            kinds[i].main -> dayProteinG / mains.size
            else -> left * weight(kinds[i]) / otherW
        }
    }
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
