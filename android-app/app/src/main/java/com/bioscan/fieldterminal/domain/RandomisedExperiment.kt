package com.bioscan.fieldterminal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Random
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

// Analysis review phase F. Randomised self-experiments, registered from the phone: the schedule is drawn here, with the
// phone's own random source (SecureRandom in the app, a seeded Random in tests), when the person registers, and stored in
// the protocol; from then on it is fixed. Two designs:
//   - periods in random order (supplements, fueling): pairs of equal periods, the order inside each pair drawn at random,
//     every period starting with a washout whose days do not count;
//   - days in random order (same-day choices from the evidence board): each week 3 or 4 days drawn for the rule, the
//     others without it, the outcome read the next morning.
// The analysis (supabase/functions/_shared/analysis-core/randomized.ts) mirrors the drawing for its own tests and judges
// the result against the no-effect zone set here, in four words.

const val PROTOCOL_VERSION_RANDOMISED = 2
val PAIR_CHOICES = listOf(3, 4, 6)
val PERIOD_DAY_CHOICES = listOf(7, 10, 14)
val WASHOUT_CHOICES = listOf(2, 3, 4)
val DAILY_WEEK_CHOICES = listOf(6, 8, 12)

// ---- the no-effect zone: the smallest change that would matter, in the outcome's display unit ----

data class ZoneSpec(val zone: Double, val unit: String, val choices: List<Double>)

fun zoneSpec(metric: String): ZoneSpec = when (metric) {
    "hrv" -> ZoneSpec(2.0, "%", listOf(1.0, 2.0, 3.0, 5.0))
    "rhr" -> ZoneSpec(1.0, "bpm", listOf(0.5, 1.0, 2.0))
    "sleep_hours", "deep_rem_min" -> ZoneSpec(10.0, "min", listOf(5.0, 10.0, 15.0, 20.0))
    else -> ZoneSpec(0.5, "points", listOf(0.25, 0.5, 1.0))
}

fun zoneText(zone: Double, unit: String): String {
    val v = if (Math.abs(zone - Math.rint(zone)) < 0.005) "%.0f".format(zone) else "%.2f".format(zone).trimEnd('0').trimEnd('.', ',')
    return if (unit == "%") "±$v%" else "±$v $unit"
}

// ---- drawing ----

/** Pairs of periods from [first], each pair's order (with-then-without or the reverse) drawn at random. A = without, B = with. */
fun drawPeriodSchedule(random: Random, first: LocalDate, periodDays: Int, pairs: Int): List<ExperimentPhase> {
    val out = mutableListOf<ExperimentPhase>()
    var day = first
    for (p in 0 until pairs) {
        val order = if (random.nextDouble() < 0.5) listOf("A", "B") else listOf("B", "A")
        for (label in order) {
            out += ExperimentPhase(label, day.toString(), day.plusDays((periodDays - 1).toLong()).toString(), pair = p)
            day = day.plusDays(periodDays.toLong())
        }
    }
    return out
}

/** Within each week from [first], 3 then 4 (and so on) days drawn at random for the rule (B). */
fun drawDailySchedule(random: Random, first: LocalDate, weeks: Int, perWeek: Pair<Int, Int> = 3 to 4): List<DailyAssignment> {
    val out = mutableListOf<DailyAssignment>()
    for (w in 0 until weeks) {
        val k = if (w % 2 == 0) perWeek.first else perWeek.second
        val days = (0 until 7).toMutableList()
        for (i in days.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            days[i] = days[j].also { days[j] = days[i] }
        }
        val withDays = days.take(k).toSet()
        for (i in 0 until 7) out += DailyAssignment(first.plusDays((w * 7 + i).toLong()).toString(), if (i in withDays) "B" else "A")
    }
    return out
}

fun randomisedPairsProtocol(
    random: Random,
    first: LocalDate,
    periodDays: Int,
    pairs: Int,
    washoutDays: Int,
    zone: Double,
    blinded: Boolean,
    drawnAt: Instant = Instant.now(),
): ExperimentProtocol = ExperimentProtocol(
    design = "randomized_pairs",
    phases = drawPeriodSchedule(random, first, periodDays, pairs),
    washoutDays = washoutDays,
    version = PROTOCOL_VERSION_RANDOMISED,
    zone = zone,
    blinded = blinded,
    pairs = pairs,
    periodDays = periodDays,
    randomization = ExperimentRandomization("order within each pair drawn on the phone (SecureRandom)", drawnAt.toString()),
)

// Alcohol days stay in a daily design: what someone drinks can follow from the day's rule, and leaving days out because of
// something that happened after the draw would undo the randomisation. Illness days still drop out.
fun dailyProtocol(
    random: Random,
    first: LocalDate,
    weeks: Int,
    zone: Double,
    lag: Int,
    behaviour: ExperimentBehaviour,
    drawnAt: Instant = Instant.now(),
): ExperimentProtocol = ExperimentProtocol(
    design = "micro_randomized",
    phases = emptyList(),
    washoutDays = 0,
    excludeAlcoholDays = false,
    version = PROTOCOL_VERSION_RANDOMISED,
    zone = zone,
    assignments = drawDailySchedule(random, first, weeks),
    lag = lag,
    behaviour = behaviour,
    randomization = ExperimentRandomization("3 or 4 days a week drawn on the phone (SecureRandom)", drawnAt.toString()),
)

// ---- what the design can detect (80% power, 5% two-sided), from the preview's day-to-day noise ----

// Student t quantiles for the few degrees of freedom a pairs design has (pairs - 1).
private val T975 = doubleArrayOf(12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228)
private val T80 = doubleArrayOf(1.376, 1.061, 0.978, 0.941, 0.920, 0.906, 0.896, 0.889, 0.883, 0.879)
private fun t975(df: Int) = if (df in 1..10) T975[df - 1] else 1.96 + 2.7 / df
private fun t80(df: Int) = if (df in 1..10) T80[df - 1] else 0.842 + 0.37 / df

// The preview's sigma is in model units (log for HRV, hours for sleep duration).
private fun toDisplay(metric: String, v: Double): Double = when (metric) {
    "hrv" -> (exp(v) - 1) * 100
    "sleep_hours" -> v * 60
    else -> v
}

/** Smallest effect a pairs design detects 80% of the time: each pair's difference has two period means of the days after
 *  the washout (autocorrelation thins them), and the verdict's interval is a t interval over the pairs. */
fun pairsMde(p: MdePreviewRow, periodDays: Int, washoutDays: Int, pairs: Int): Double {
    val m = max(1, periodDays - washoutDays)
    val effective = max(1.0, m * (1 - p.rho) / (1 + p.rho))
    val se = p.sigma * sqrt(2.0 / effective / pairs)
    return toDisplay(p.metric, (t975(pairs - 1) + t80(pairs - 1)) * se)
}

/** Smallest effect a daily design detects 80% of the time: with- and without-days compared, about one day in ten missing. */
fun dailyMde(p: MdePreviewRow, weeks: Int): Double {
    val perArm = 7 * weeks * 0.9 / 2
    return toDisplay(p.metric, 2.8 * p.sigma * sqrt(2.0 / perArm))
}

/** "Can detect about ±6.1% (80% of the time); 6 pairs would get to ±4.4%." and what that means against the zone. */
fun powerLine(mde: Double, zone: Double, unit: String, longer: Pair<String, Double>?): String {
    val now = zoneText(mde, unit).replace("±", "about ±")
    val more = longer?.let { (label, v) -> "; $label would get to ${zoneText(v, unit)}" } ?: ""
    val reading = when {
        mde <= zone -> " Small enough to tell an effect from none at your zone of ${zoneText(zone, unit)}."
        mde <= 2 * zone -> " Effects close to your zone of ${zoneText(zone, unit)} may still read UNCLEAR."
        else -> " Only effects well beyond your zone of ${zoneText(zone, unit)} would read CLEAR; smaller ones would read UNCLEAR."
    }
    return "Can detect $now (80% of the time)$more.$reading"
}

private fun roundedMde(v: Double, unit: String) = if (unit == "%" || unit == "min") Math.round(v * 10) / 10.0 else Math.round(v * 100) / 100.0

fun pairsPowerLine(p: MdePreviewRow, periodDays: Int, washoutDays: Int, pairs: Int, zone: Double): String {
    val unit = zoneSpec(p.metric).unit
    val more = PAIR_CHOICES.firstOrNull { it > pairs }?.let { "$it pairs" to roundedMde(pairsMde(p, periodDays, washoutDays, it), unit) }
    return powerLine(roundedMde(pairsMde(p, periodDays, washoutDays, pairs), unit), zone, unit, more)
}

fun dailyPowerLine(p: MdePreviewRow, weeks: Int, zone: Double): String {
    val unit = zoneSpec(p.metric).unit
    val more = DAILY_WEEK_CHOICES.firstOrNull { it > weeks }?.let { "$it weeks" to roundedMde(dailyMde(p, it), unit) }
    return powerLine(roundedMde(dailyMde(p, weeks), unit), zone, unit, more)
}

// ---- reading it back ----

data class TodayAssignment(val headline: String, val detail: String?)

/** What today asks for, or null when the experiment is over (or has no schedule). A blinded schedule is never shown. */
fun todayAssignment(protocol: ExperimentProtocol, today: LocalDate): TodayAssignment? {
    if (protocol.version != PROTOCOL_VERSION_RANDOMISED) return null
    if (protocol.design == "micro_randomized") {
        val days = protocol.assignments.orEmpty()
        val first = days.firstOrNull()?.date?.let(LocalDate::parse) ?: return null
        if (today.isBefore(first)) return TodayAssignment("Starts $first", "The schedule is drawn; day 1 is $first.")
        val a = days.firstOrNull { it.date == today.toString() } ?: return null
        val rule = protocol.behaviour?.label ?: "the rule"
        val n = days.indexOf(a) + 1
        return if (a.arm == "B") TodayAssignment("Today: WITH · $rule", "Day $n of ${days.size}.")
        else TodayAssignment("Today: WITHOUT · no rule, as you usually would", "Day $n of ${days.size}.")
    }
    val phases = protocol.phases
    val first = phases.firstOrNull()?.start?.let(LocalDate::parse) ?: return null
    if (today.isBefore(first)) return TodayAssignment("Starts $first", "The order of each pair is drawn; period 1 begins $first.")
    val k = phases.indexOfFirst { !today.isBefore(LocalDate.parse(it.start)) && !today.isAfter(LocalDate.parse(it.end)) }
    if (k < 0) return null
    val phase = phases[k]
    val dayIn = ChronoUnit.DAYS.between(LocalDate.parse(phase.start), today).toInt() + 1
    val washout = if (dayIn <= protocol.washoutDays) " Day $dayIn is a washout day: follow the period; it does not count." else ""
    val period = "Period ${k + 1} of ${phases.size}, day $dayIn."
    if (protocol.blinded == true) return TodayAssignment("Today: take today's organiser dose", "$period$washout The schedule stays with the person who fills the organiser.")
    return if (phase.label == "B") TodayAssignment("Today: WITH · take it", "$period$washout")
    else TodayAssignment("Today: WITHOUT · don't take it", "$period$washout")
}

/** The days a randomised schedule runs, first to last (the outcome of the last daily day is read the morning after). */
fun scheduleSpan(protocol: ExperimentProtocol): Pair<String, String>? = when (protocol.design) {
    "micro_randomized" -> protocol.assignments?.takeIf { it.isNotEmpty() }?.let { it.first().date to it.last().date }
    else -> protocol.phases.takeIf { it.isNotEmpty() }?.let { it.first().start to it.last().end }
}

private val FOUR_WORD_LABELS = mapOf(
    "clear_benefit" to "CLEAR BENEFIT",
    "clear_harm" to "CLEAR HARM",
    "negligible" to "NO MEANINGFUL EFFECT",
    "unclear" to "UNCLEAR",
)

/** The four words of a randomised experiment's verdict, or null for any other state. */
fun fourWordLabel(verdict: String): String? = FOUR_WORD_LABELS[verdict]

fun designLabel(design: String): String = when (design) {
    "randomized_pairs" -> "PERIODS IN RANDOM ORDER"
    "micro_randomized" -> "DAYS IN RANDOM ORDER"
    "ABAB" -> "A, B, A, B"
    else -> "A, B"
}

// ---- same-day choices the evidence board can turn into a daily test ----

data class DailyChoice(
    val questionId: String,
    val title: String,
    val behaviour: ExperimentBehaviour,
    val outcome: String,
    val lag: Int,
)

private fun rule(series: String, label: String) = ExperimentBehaviour(series = series, withValue = 0.0, label = label)
// A rule that asks for something to happen (a daylight day, an active commute) rather than not to.
private fun positive(series: String, label: String) = ExperimentBehaviour(series = series, withValue = 1.0, label = label)

// Each with-day asks for the rule; a without-day asks for nothing (never "drink" or "have caffeine": the test measures
// what the rule changes against an ordinary day).
val DAILY_CHOICES: List<DailyChoice> = listOf(
    DailyChoice("caffeine_pm_sleep", "Caffeine cut-off and sleep", rule("caffeine_pm_any", "no caffeine after 14:00"), "sleep_hours", 1),
    DailyChoice("late_meal_sleep", "No late meal and sleep", rule("late_meal", "no meal after 21:00"), "sleep_hours", 1),
    DailyChoice("late_meal_hrv", "No late meal and next-morning HRV", rule("late_meal", "no meal after 21:00"), "hrv", 1),
    DailyChoice("alcohol_sleep", "No alcohol and sleep", rule("alcohol_any", "no alcohol"), "sleep_hours", 1),
    DailyChoice("alcohol_hrv", "No alcohol and next-morning HRV", rule("alcohol_any", "no alcohol"), "hrv", 1),
    DailyChoice("alcohol_rhr", "No alcohol and next-morning resting heart rate", rule("alcohol_any", "no alcohol"), "rhr", 1),
    DailyChoice("evening_training_sleep", "No evening training and sleep", rule("evening_training", "no training after 19:00"), "sleep_hours", 1),
    // Routine (R6): adherence is read from what the phone measured, so a with-day needs no log.
    DailyChoice("screen_bed_sleep", "No phone before bed and sleep", rule("screen_bed_any", "no phone in the hour before sleep"), "sleep_hours", 1),
    DailyChoice("screen_bed_hrv", "No phone before bed and next-morning HRV", rule("screen_bed_any", "no phone in the hour before sleep"), "hrv", 1),
    DailyChoice("night_phone_energy", "Phone out of the bedroom and next-day energy", rule("night_phone", "phone left outside the bedroom overnight"), "energy", 1),
    DailyChoice("screen_pm_social_sleep", "No social apps late and sleep", rule("screen_pm_social_any", "no social or video apps after 21:00"), "sleep_hours", 1),
    DailyChoice("outdoor_daylight_sleep", "Daylight outdoors and sleep", positive("daylight_day", "30 minutes outdoors in daylight"), "sleep_hours", 1),
    DailyChoice("active_commute_hrv", "Active commute and next-morning HRV", positive("active_commute", "walk or cycle part of the commute"), "hrv", 1),
)

fun dailyChoiceFor(questionId: String): DailyChoice? = DAILY_CHOICES.firstOrNull { it.questionId == questionId }

/** What would stop a randomised design from being registered. Empty = valid. */
fun randomisedProblems(primary: String?, zone: Double?, periodDays: Int? = null, washoutDays: Int? = null): List<String> = buildList {
    if (primary == null || EXPERIMENT_METRICS.none { it.first == primary }) add("Choose the one outcome this experiment is about.")
    if (zone == null || zone <= 0) add("Choose the smallest change that would matter.")
    if (periodDays != null && washoutDays != null && washoutDays * 2 >= periodDays) add("The washout must be shorter than half a period.")
}
