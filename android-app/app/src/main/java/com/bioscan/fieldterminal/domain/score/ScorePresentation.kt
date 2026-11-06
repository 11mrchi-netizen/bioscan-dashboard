package com.bioscan.fieldterminal.domain.score

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Scoring system: the words and number formats the score screens use (docs/scoring-system/02-page-mockups.md).
// Pure, so the screens only draw what these return.

private fun hm(hours: Double): String {
    val total = (hours * 60).roundToInt()
    return "${total / 60}h${(total % 60).toString().padStart(2, '0')}"
}

private fun minutes(min: Double): String = if (abs(min) >= 90) hm(min / 60.0) else "${min.roundToInt()} min"

private fun clockFromNoon(min: Double): String {
    val m = ((min.roundToInt() + 12 * 60) % (24 * 60) + 24 * 60) % (24 * 60)
    return "%02d:%02d".format(m / 60, m % 60)
}

private fun clock(minOfDay: Double): String {
    val m = ((minOfDay.roundToInt() % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}

private fun pct(ratio: Double): String = "${(ratio * 100).roundToInt()} %"
private fun one(v: Double): String = "%.1f".format(v)

/**
 * An input's value rounded the way it was judged: a value short of its target never shows as the target itself
 * (99.6 % of protein reads 99 %, not 100 % with a "short" mark), and one over a limit never shows as the limit.
 */
fun formatJudgedValue(input: InputScore): String {
    val v = input.value
    val judged = when {
        input.spec.key !in RATIO_KEYS -> v
        input.direction == InputDirection.SHORT -> kotlin.math.floor(v * 100) / 100
        input.direction == InputDirection.OVER -> kotlin.math.ceil(v * 100) / 100
        else -> v
    }
    return formatInputValue(input.spec.key, judged)
}

private val RATIO_KEYS = setOf(
    ScoreKeys.FUEL_LOGGED_SHARE, ScoreKeys.ENERGY_RATIO, ScoreKeys.PROTEIN_RATIO, ScoreKeys.CARB_RATIO, ScoreKeys.WATER_RATIO, ScoreKeys.PLAN_DONE,
)

/** One input's value as the score screens show it ("8h05", "58 ms", "96 %", "03:10"). */
fun formatInputValue(key: String, value: Double): String = when (key) {
    ScoreKeys.SLEEP_HOURS -> hm(value)
    ScoreKeys.SLEEP_DEBT -> if (value < 0.05) "none" else hm(value)
    ScoreKeys.SLEEP_REGULARITY -> "± ${value.roundToInt()} min"
    ScoreKeys.SLEEP_MIDPOINT -> clockFromNoon(value)
    ScoreKeys.SLEEP_DEEP_REM -> minutes(value)
    ScoreKeys.SLEEP_BREATHING -> "${one(value)}/min"
    ScoreKeys.HRV -> "${value.roundToInt()} ms"
    ScoreKeys.RHR -> "${value.roundToInt()} bpm"
    ScoreKeys.NIGHT_STRESS, ScoreKeys.WIND_DOWN -> "${value.roundToInt()}"
    ScoreKeys.SORENESS, ScoreKeys.ENERGY, ScoreKeys.MORNING_ERECTION, ScoreKeys.AROUSAL,
    ScoreKeys.FELT_STRESS, ScoreKeys.MOOD -> "${value.roundToInt()}/5"
    ScoreKeys.TSB -> "${value.roundToInt()}"
    ScoreKeys.DAYS_WITHOUT_REST -> "${value.roundToInt()} d"
    ScoreKeys.MONOTONY -> one(value)
    ScoreKeys.PLAN_DONE -> pct(value)
    ScoreKeys.FUEL_LOGGED_SHARE, ScoreKeys.ENERGY_RATIO, ScoreKeys.PROTEIN_RATIO, ScoreKeys.CARB_RATIO, ScoreKeys.WATER_RATIO -> pct(value)
    ScoreKeys.DRINKS -> "${value.roundToInt()}"
    ScoreKeys.LATE_CAFFEINE -> if (value >= 0.5) "yes" else "no"
    ScoreKeys.LATE_SCREEN -> "${value.roundToInt()} min"
    ScoreKeys.GUT_SYMPTOMS -> if (value < 0.05) "none" else one(value)
    ScoreKeys.GUT_TIMING -> clock(value)
    ScoreKeys.GUT_TIMING_SPREAD, ScoreKeys.MEAL_TIMING_SPREAD -> "± ${value.roundToInt()} min"
    ScoreKeys.GUT_FREQUENCY -> "${value.roundToInt()}"
    ScoreKeys.GUT_FORM -> "type ${one(value)}"
    ScoreKeys.GUT_SESSION -> "${value.roundToInt()}/10"
    ScoreKeys.STRESSED_SHARE, ScoreKeys.RELAXED_SHARE, ScoreKeys.HR_DIP -> "${value.roundToInt()} %"
    ScoreKeys.HRV_CV -> "${one(value)} %"
    else -> one(value)
}

/** The arrow on a tile: ▲ / ● / ▼ against the person's normal, ✓ / ↓ / ↑ against a target. */
fun directionGlyph(direction: InputDirection): String = when (direction) {
    InputDirection.ABOVE -> "▲"
    InputDirection.NORMAL -> "●"
    InputDirection.BELOW -> "▼"
    InputDirection.ON_TARGET -> "✓"
    InputDirection.SHORT -> "↓"
    InputDirection.OVER -> "↑"
}

/** The words on a metric overlay's header. */
fun directionWords(direction: InputDirection): String = when (direction) {
    InputDirection.ABOVE -> "ABOVE NORMAL"
    InputDirection.NORMAL -> "NORMAL"
    InputDirection.BELOW -> "BELOW NORMAL"
    InputDirection.ON_TARGET -> "ON TARGET"
    InputDirection.SHORT -> "SHORT"
    InputDirection.OVER -> "OVER"
}

/** "your normal 44–52 ms" or "target 100 %": what the value was compared with. */
fun referenceLine(input: InputScore): String? {
    val ref = input.reference ?: return null
    val key = input.spec.key
    return when (val kind = input.spec.kind) {
        is InputKind.Baseline, is InputKind.Stability -> {
            val spread = input.spread
            val log = (kind as? InputKind.Baseline)?.log ?: (kind as InputKind.Stability).log
            if (spread == null) "your normal ${formatInputValue(key, ref)}"
            else {
                val lo = if (log) ref * kotlin.math.exp(-spread) else ref - spread
                val hi = if (log) ref * kotlin.math.exp(spread) else ref + spread
                "your normal ${formatInputValue(key, lo)} – ${formatInputValue(key, hi)}"
            }
        }
        is InputKind.AtLeast -> "target at least ${formatInputValue(key, kind.target)}"
        is InputKind.AtMost -> "limit ${formatInputValue(key, kind.limit)}"
        is InputKind.Band -> "aim ${formatInputValue(key, kind.lo)} – ${formatInputValue(key, kind.hi)}"
    }
}

/**
 * Each input's share of its domain's distance from a typical day, in points: its group's share of the domain times its
 * own share of the group, times (its score − 80). They add up to the domain's raw distance from 80 before the critical
 * cap and ranking.
 */
fun inputPoints(raw: DomainRaw): Map<String, Double> {
    val groups = raw.groups.filter { it.score != null && it.coverage > 0 }
    val groupWeight = groups.sumOf { it.group.weight * it.coverage }
    if (groupWeight <= 0) return emptyMap()
    return buildMap {
        for (g in groups) {
            val share = g.group.weight * g.coverage / groupWeight
            val w = g.inputs.sumOf { it.spec.weight }
            for (input in g.inputs) put(input.spec.key, share * input.spec.weight / w * (input.score - ScoreScale.TYPICAL))
        }
    }
}

fun weekday(day: LocalDate): String = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

/** A label inside a sentence: lower-cased, except acronyms ("HRV", "TSB") keep their case. */
fun inSentence(label: String): String =
    label.split(" ").joinToString(" ") { w ->
        val letters = w.filter { it.isLetter() }
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) w else w.lowercase()
    }

/**
 * The band word under a score. LOAD and FUEL are judged against targets and bands, so their 100 means "right", not
 * "primed": they read ON TARGET there.
 */
fun bandWord(key: DomainKey?, score: Double): String =
    if (key != null && !key.upside && score >= ScoreScale.TOP_OF_NORMAL - 1e-9) "ON TARGET" else ScoreScale.band(score).label

/** One sentence under a domain's ring: its biggest lift and its biggest drag. */
fun domainSentence(score: DomainScore): String {
    if (score.partial) return when (score.key) {
        DomainKey.FUEL -> "Not enough logged to score: the food log or gut entries for the day are missing."
        DomainKey.BALANCE -> "Not enough to score: no stress readings or answers for the day."
        else -> "Not enough data to score this morning."
    }
    val points = inputPoints(score.raw)
    val byKey = score.raw.inputs.associateBy { it.spec.key }
    val up = points.filter { it.value >= 2 }.maxByOrNull { it.value }?.key?.let { byKey[it]?.spec?.label }
    val down = points.filter { it.value <= -2 }.minByOrNull { it.value }?.key?.let { byKey[it]?.spec?.label }
    return when {
        up != null && down != null -> "Lifted by ${inSentence(up)}; held back by ${inSentence(down)}."
        up != null -> "Lifted mostly by ${inSentence(up)}."
        down != null -> "Held back mostly by ${inSentence(down)}."
        else -> "Everything close to your usual."
    }
}

/** The CHARGE overlay's sentence: "Better than 93 of your last 100 mornings." */
fun chargeRankLine(charge: ChargeScore): String? {
    val p = charge.percentile ?: return null
    if (charge.record) return "Your best morning of the last ${charge.rankedAgainst ?: 100}."
    return "Better than ${(p * 100).roundToInt().coerceIn(0, 99)} of your last 100 mornings."
}

/** The one-line driver summary under the CHARGE overlay's header. */
fun driverSummary(charge: ChargeScore): String? {
    val up = charge.drivers.filter { it.points >= 2 }.sortedByDescending { it.points }.take(2)
    val down = charge.drivers.filter { it.points <= -2 }.minByOrNull { it.points }
    val ups = up.joinToString(" and ") { "${it.key.label} (+${it.points.roundToInt()})" }
    return when {
        up.isNotEmpty() && down != null -> "Mostly $ups. ${down.key.label} holds it back (${down.points.roundToInt()})."
        up.isNotEmpty() -> "Mostly $ups."
        down != null -> "${down.key.label} pulls it down (${down.points.roundToInt()})."
        else -> null
    }
}

/** What each domain and CHARGE is, for the overlays' last section. */
fun whatIs(key: DomainKey?): String = when (key) {
    null -> "How you should feel this morning, given last night and yesterday. 100 is the top of your normal; above it " +
        "you are better than 9 in 10 of your own mornings. It is published when you wake and is final once you close " +
        "yesterday (or at 12:00); it never changes after that."
    DomainKey.SLEEP -> "Whether you got the sleep you need: last night's length against your usual, the week's sleep " +
        "debt, deep and REM sleep, how regular and well-timed your nights are, and steady breathing."
    DomainKey.RECOVERY -> "Whether your body has absorbed what you've done: overnight HRV and resting heart rate against " +
        "your own normal, night-time stress, soreness and how you feel in the morning."
    DomainKey.LOAD -> "Whether yesterday's training dose was right: fatigue (TSB) inside its productive band, days in a " +
        "row without rest, how repetitive the week was, and whether the plan was done. 100 means the dose was right; " +
        "the strip says if you are on the high or low side."
    DomainKey.FUEL -> "Whether you were fuelled and your gut coped: energy, protein, carbs and water against yesterday's " +
        "targets, alcohol and late caffeine, then bowel timing, frequency and form against your usual. Needs a " +
        "reasonably complete food log to score intake."
    DomainKey.BALANCE -> "How much stress you carried, in mind and nervous system: time stressed and relaxed during the " +
        "day, how you wound down before bed, how steady your HRV has been, how you felt, and late phone use."
}

/** What one input is, for a metric overlay. */
fun whatIsInput(key: String): String = when (key) {
    ScoreKeys.SLEEP_HOURS -> "Time asleep last night, compared only with your own recent nights."
    ScoreKeys.SLEEP_DEBT -> "What you still owe going into tonight: each night carries 55 % of the shortfall into the next night's target, so older short nights fade and a full night clears it. Extra sleep builds no credit. Your need is your usual sleep, never under 7 h."
    ScoreKeys.SLEEP_REGULARITY -> "How much your bed and wake times moved over the last 7 nights. Smaller is steadier."
    ScoreKeys.SLEEP_MIDPOINT -> "The middle of last night's sleep. Far from your usual midpoint means shifted timing."
    ScoreKeys.SLEEP_DEEP_REM -> "Deep plus REM sleep last night, against your usual."
    ScoreKeys.SLEEP_BREATHING -> "Breathing rate during sleep. A change from your usual, either way, can mean strain or illness."
    ScoreKeys.HRV -> "Variation between heartbeats overnight. Higher than your normal usually means you've recovered. Compared only with you."
    ScoreKeys.RHR -> "Lowest steady heart rate overnight. Lower than your normal usually means you've recovered."
    ScoreKeys.NIGHT_STRESS -> "The watch's stress reading while asleep."
    ScoreKeys.SORENESS -> "Your morning soreness rating."
    ScoreKeys.ENERGY -> "Your morning energy rating."
    ScoreKeys.MORNING_ERECTION -> "Your morning erection rating, a marker of recovery and hormonal state."
    ScoreKeys.AROUSAL -> "Your arousal rating."
    ScoreKeys.TSB -> "Training stress balance: fitness minus fatigue through yesterday. −25 to +5 is the productive band."
    ScoreKeys.DAYS_WITHOUT_REST -> "Days in a row with a training session, up to yesterday."
    ScoreKeys.MONOTONY -> "The week's average daily load divided by its spread. Above 2 means every day looked the same."
    ScoreKeys.PLAN_DONE -> "Share of the last 7 days' planned sessions that were done."
    ScoreKeys.ENERGY_RATIO -> "Yesterday's calories against your target."
    ScoreKeys.PROTEIN_RATIO -> "Yesterday's protein against your target."
    ScoreKeys.CARB_RATIO -> "Yesterday's carbs against your target."
    ScoreKeys.WATER_RATIO -> "Yesterday's water against your target."
    ScoreKeys.DRINKS -> "Alcoholic drinks yesterday, from the evening check-in."
    ScoreKeys.LATE_CAFFEINE -> "Caffeine after 14:00 yesterday, from the evening check-in."
    ScoreKeys.GUT_SYMPTOMS -> "Gut discomfort and symptoms yesterday."
    ScoreKeys.GUT_TIMING -> "Time of yesterday's first bowel movement, against your usual window."
    ScoreKeys.GUT_TIMING_SPREAD -> "How much your first bowel movement's time moved over 14 days. Smaller is more regular."
    ScoreKeys.GUT_FREQUENCY -> "Bowel movements yesterday, against your usual."
    ScoreKeys.GUT_FORM -> "Average Bristol type over the last 3 days, against your own usual type."
    ScoreKeys.GUT_SESSION -> "How your gut coped during yesterday's fuelled session."
    ScoreKeys.STRESSED_SHARE -> "Share of yesterday the watch read as medium or high stress, outside exercise."
    ScoreKeys.RELAXED_SHARE -> "Share of yesterday the watch read as relaxed."
    ScoreKeys.WIND_DOWN -> "The watch's stress in the two hours before bed."
    ScoreKeys.HR_DIP -> "How far your heart rate dropped from day to sleep. A small dip means stress carried into the night."
    ScoreKeys.HRV_CV -> "How much HRV varied over the last 7 nights. A jump means a disturbed nervous system even if the average looks fine."
    ScoreKeys.FELT_STRESS -> "How stressed you felt (the evening check-in, else the morning answer)."
    ScoreKeys.MOOD -> "Your mood rating."
    ScoreKeys.LATE_SCREEN -> "Minutes on the phone in the hour before you fell asleep, measured by the phone (Usage access) against that night's sleep onset. Up to 10 minutes counts as none; each 20 minutes more lowers Rhythm. Without a measurement, a \"yes\" in the evening check-in counts as 30 minutes."
    ScoreKeys.MEAL_TIMING_SPREAD -> "How much your meal times moved."
    else -> ""
}

/**
 * The scorecard sentence for the scoring system's scores. The app's older composites were hidden when they read NO
 * BETTER ("so it is shown as its inputs"); these never are, so the sentence says what the verdict does instead.
 */
fun qualitySentence(result: com.bioscan.fieldterminal.domain.ScorecardResult, isCharge: Boolean): String {
    val best = result.best?.label?.let(::inSentence) ?: "any single input"
    return when (result.verdict) {
        com.bioscan.fieldterminal.domain.ScorecardVerdict.EARNS_ITS_PLACE ->
            "Forecasts ${result.targetText} better than $best alone (${result.days} days)."
        com.bioscan.fieldterminal.domain.ScorecardVerdict.NO_BETTER ->
            "Forecasts ${result.targetText} no better than $best alone (${result.days} days). Still shown; " +
                (if (isCharge) "its weights are re-fitted monthly." else "read it alongside $best.")
        com.bioscan.fieldterminal.domain.ScorecardVerdict.UNCLEAR ->
            "Not yet clearly better than $best alone at forecasting ${result.targetText} (${result.days} days scored)."
        com.bioscan.fieldterminal.domain.ScorecardVerdict.NOT_YET ->
            "Scored on ${result.days} of the ${result.need} days needed to compare it with its inputs."
    }
}
