package com.bioscan.fieldterminal.domain.score

import com.bioscan.fieldterminal.data.model.PlannedOutcomeRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.EveningCheckin
import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.domain.dailySessionLoadMap
import com.bioscan.fieldterminal.domain.stress.StressDay
import com.bioscan.fieldterminal.domain.trainingLoadSeries
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.sqrt

// Scoring system: builds the engine's series from rows the app already loads.
//
// Every series is keyed by the MORNING whose score it feeds:
//   - night and morning readings (sleep, HRV, resting HR, the morning wellbeing answers) keep their own date: the
//     sleep and wearable rows are already keyed by the morning they end on (domain/DailyAggregation.kt's night date);
//   - anything about a day (training through it, food, water, gut, the evening check-in, daytime stress) is about the
//     day BEFORE the morning, so it is shifted forward one day here.
// Nothing in the engine shifts dates again.
//
// Not assembled yet (the inputs exist in ScoreSpecs and read as missing until they are): gut symptoms and the
// in-session gut score (the CLOSE THE DAY check-in will collect symptoms), the night-time HR dip, and meal-timing
// regularity.

/** The day's targets FUEL compares intake with. */
data class FuelTargets(val kcal: Double?, val proteinG: Double?, val carbsG: Double?, val waterMl: Double?)

data class ScoreSources(
    val wearable: List<WearableAnalysisRow> = emptyList(),
    val sleep: List<SleepAnalysisRow> = emptyList(),
    val wellbeing: List<WellbeingAnalysisRow> = emptyList(),
    /** Per-session training loads (buildTrainingSessionLoads). */
    val sessionLoads: List<Pair<LocalDate, Double>> = emptyList(),
    val loadSpans: LoadSpans = LoadSpans.STANDARD,
    val planned: List<PlannedOutcomeRow> = emptyList(),
    val nutrition: List<DailyNutrition> = emptyList(),
    val hydrationMl: Map<LocalDate, Double> = emptyMap(),
    /** The targets in force on a day (the fuel plan's, else the saved nutrition goal). */
    val targets: (LocalDate) -> FuelTargets? = { null },
    val checkins: List<EveningCheckin> = emptyList(),
    val stool: List<StoolAnalysisRow> = emptyList(),
    val stressDays: List<StressDay> = emptyList(),
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** A training day for "days without rest": a daily load at or above this (a 30-40 minute easy session). */
const val TRAINING_DAY_LOAD = 40.0
const val MIN_SLEEP_NEED_H = 7.0

private class SeriesBuilder {
    val out = HashMap<String, HashMap<LocalDate, Double>>()
    fun put(key: String, day: LocalDate, value: Double?) {
        if (value != null && value.isFinite()) out.getOrPut(key) { HashMap() }[day] = value
    }
    fun build(): Map<String, Series> = out
}

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

private fun sd(values: List<Double>): Double? {
    if (values.size < 2) return null
    val m = values.average()
    return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
}

private fun parseInstant(text: String?): Instant? = text?.let { t ->
    runCatching { Instant.parse(t) }.getOrNull() ?: runCatching { OffsetDateTime.parse(t).toInstant() }.getOrNull()
}

/** Minutes after noon of the day before [morning]: bedtimes either side of midnight stay one continuous number. */
private fun minutesFromNoonBefore(instant: Instant, morning: LocalDate, zone: ZoneId): Double =
    Duration.between(morning.minusDays(1).atTime(12, 0).atZone(zone).toInstant(), instant).toMinutes().toDouble()

fun assembleScoreSeries(src: ScoreSources, today: LocalDate = LocalDate.now(src.zone)): Map<String, Series> {
    val b = SeriesBuilder()
    val zone = src.zone

    // ---- SLEEP ----
    val sleepByDay = src.sleep.mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row } }.toMap()
    val hours = sleepByDay.mapNotNull { (d, r) -> r.hours?.let { d to it } }.toMap()
    val bed = HashMap<LocalDate, Instant>()
    val wake = HashMap<LocalDate, Instant>()
    for ((d, r) in sleepByDay) {
        b.put(ScoreKeys.SLEEP_HOURS, d, r.hours)
        b.put(ScoreKeys.SLEEP_BREATHING, d, r.respiratoryRate)
        if (r.deepMin != null && r.remMin != null) b.put(ScoreKeys.SLEEP_DEEP_REM, d, r.deepMin + r.remMin)
        val bt = parseInstant(r.bedtime)
        val wt = parseInstant(r.wakeTime)
        if (bt != null && wt != null && wt.isAfter(bt)) {
            bed[d] = bt
            wake[d] = wt
            b.put(ScoreKeys.SLEEP_MIDPOINT, d, minutesFromNoonBefore(bt.plus(Duration.between(bt, wt).dividedBy(2)), d, zone))
        }
    }
    for (d in sleepByDay.keys) {
        val need = maxOf(MIN_SLEEP_NEED_H, median((1..60).mapNotNull { hours[d.minusDays(it.toLong())] }.takeIf { it.size >= 14 }.orEmpty()) ?: MIN_SLEEP_NEED_H)
        val week = (0..6).mapNotNull { hours[d.minusDays(it.toLong())] }
        if (week.size >= 4) b.put(ScoreKeys.SLEEP_DEBT, d, week.sumOf { maxOf(0.0, need - it) })
        val beds = (0..6).mapNotNull { i -> d.minusDays(i.toLong()).let { n -> bed[n]?.let { minutesFromNoonBefore(it, n, zone) } } }
        val wakes = (0..6).mapNotNull { i -> d.minusDays(i.toLong()).let { n -> wake[n]?.let { minutesFromNoonBefore(it, n, zone) } } }
        val bedSd = sd(beds.takeIf { it.size >= 4 }.orEmpty())
        val wakeSd = sd(wakes.takeIf { it.size >= 4 }.orEmpty())
        if (bedSd != null && wakeSd != null) b.put(ScoreKeys.SLEEP_REGULARITY, d, (bedSd + wakeSd) / 2)
    }

    // ---- RECOVERY ----
    val hrvByDay = HashMap<LocalDate, Double>()
    for (row in src.wearable) {
        val d = runCatching { LocalDate.parse(row.date) }.getOrNull() ?: continue
        b.put(ScoreKeys.HRV, d, row.hrv)
        b.put(ScoreKeys.RHR, d, row.rhr)
        row.hrv?.takeIf { it > 0 }?.let { hrvByDay[d] = it }
    }
    for (row in src.wellbeing) {
        val d = runCatching { LocalDate.parse(row.date) }.getOrNull() ?: continue
        b.put(ScoreKeys.SORENESS, d, row.soreness?.toDouble())
        b.put(ScoreKeys.ENERGY, d, row.energy?.toDouble())
        b.put(ScoreKeys.MORNING_ERECTION, d, row.morningErectionQuality?.toDouble())
        b.put(ScoreKeys.AROUSAL, d, row.arousalLevel?.toDouble())
        // The morning answers are about how the person feels that morning; BALANCE reads them on the same morning.
        b.put(ScoreKeys.MOOD, d, row.mood?.toDouble())
        b.put(ScoreKeys.FELT_STRESS, d, row.stress?.toDouble())
    }
    val samples = src.stressDays.flatMap { it.samples }.sortedBy { it.timestamp }
    fun meanBetween(from: Instant, to: Instant): Double? =
        samples.filter { !it.timestamp.isBefore(from) && it.timestamp.isBefore(to) }.map { it.value.toDouble() }.takeIf { it.size >= 3 }?.average()
    for ((d, bt) in bed) {
        b.put(ScoreKeys.NIGHT_STRESS, d, meanBetween(bt, wake.getValue(d)))
        // Wind-down: the two hours before bed. Bedtime belongs to the morning's night, and so does this.
        b.put(ScoreKeys.WIND_DOWN, d, meanBetween(bt.minus(Duration.ofHours(2)), bt))
    }
    // HRV stability: the week's coefficient of variation of ln RMSSD, in percent (Plews et al.).
    for (d in hrvByDay.keys) {
        val logs = (0..6).mapNotNull { hrvByDay[d.minusDays(it.toLong())]?.let(::ln) }
        if (logs.size >= 5) sd(logs)?.let { s -> b.put(ScoreKeys.HRV_CV, d, 100 * s / logs.average()) }
    }

    // ---- LOAD (training through the day before the morning) ----
    if (src.sessionLoads.isNotEmpty()) {
        // TSB(d) = CTL(d-1) - ATL(d-1): already "through yesterday" for morning d.
        for (p in trainingLoadSeries(src.sessionLoads, today, src.loadSpans)) b.put(ScoreKeys.TSB, p.date, p.tsb)
        val daily = dailySessionLoadMap(src.sessionLoads)
        val first = src.sessionLoads.minOf { it.first }
        var morning = first.plusDays(1)
        while (!morning.isAfter(today)) {
            var streak = 0
            var d = morning.minusDays(1)
            while (!d.isBefore(first) && (daily[d] ?: 0.0) >= TRAINING_DAY_LOAD) { streak++; d = d.minusDays(1) }
            b.put(ScoreKeys.DAYS_WITHOUT_REST, morning, streak.toDouble())
            val week = (1..7).map { daily[morning.minusDays(it.toLong())] ?: 0.0 }
            val s = sd(week)
            if (week.average() > 0 && s != null && s >= 1.0) b.put(ScoreKeys.MONOTONY, morning, week.average() / s)
            morning = morning.plusDays(1)
        }
    }
    if (src.planned.isNotEmpty()) {
        val byDay = src.planned.mapNotNull { row -> runCatching { LocalDate.parse(row.scheduledDate.take(10)) }.getOrNull()?.let { it to row } }
            .groupBy({ it.first }, { it.second })
        val firstPlanned = byDay.keys.minOrNull()
        if (firstPlanned != null) {
            var morning = firstPlanned.plusDays(1)
            while (!morning.isAfter(today)) {
                val rows = (1..7).flatMap { byDay[morning.minusDays(it.toLong())].orEmpty() }
                if (rows.size >= 2) {
                    val done = rows.count { it.exerciseSessionId != null || it.status == "confirmed" || it.status == "completed" }
                    b.put(ScoreKeys.PLAN_DONE, morning, done.toDouble() / rows.size)
                }
                morning = morning.plusDays(1)
            }
        }
    }

    // ---- FUEL (the day before) ----
    val kcalByDay = src.nutrition.mapNotNull { n -> runCatching { LocalDate.parse(n.date) }.getOrNull()?.let { it to n } }.toMap()
    for ((d, n) in kcalByDay) {
        val morning = d.plusDays(1)
        val t = src.targets(d)
        val usual = median((1..28).mapNotNull { kcalByDay[d.minusDays(it.toLong())]?.calories?.takeIf { c -> c > 0 } }.takeIf { it.size >= 7 }.orEmpty())
        val reference = usual ?: t?.kcal
        if (reference != null && reference > 0) b.put(ScoreKeys.FUEL_LOGGED_SHARE, morning, n.calories / reference)
        t?.kcal?.takeIf { it > 0 }?.let { b.put(ScoreKeys.ENERGY_RATIO, morning, n.calories / it) }
        t?.proteinG?.takeIf { it > 0 }?.let { b.put(ScoreKeys.PROTEIN_RATIO, morning, n.proteinG / it) }
        t?.carbsG?.takeIf { it > 0 }?.let { b.put(ScoreKeys.CARB_RATIO, morning, n.carbsG / it) }
    }
    for ((d, ml) in src.hydrationMl) {
        val target = src.targets(d)?.waterMl?.takeIf { it > 0 } ?: continue
        b.put(ScoreKeys.WATER_RATIO, d.plusDays(1), ml / target)
    }
    for (c in src.checkins) {
        val morning = c.date.plusDays(1)
        b.put(ScoreKeys.DRINKS, morning, c.drinks?.toDouble())
        b.put(ScoreKeys.LATE_CAFFEINE, morning, c.caffeineAfter14?.let { if (it) 1.0 else 0.0 })
        b.put(ScoreKeys.LATE_SCREEN, morning, c.lateScreen?.let { if (it) 1.0 else 0.0 })
        // The evening's own stress answer is about the day just lived; it replaces the morning answer when both exist.
        b.put(ScoreKeys.FELT_STRESS, morning, c.stress?.toDouble())
    }

    // ---- GUT (the day before) ----
    // Each entry with its local day and instant.
    val stoolByDay: Map<LocalDate, List<Pair<StoolAnalysisRow, Instant>>> = src.stool
        .mapNotNull { row -> parseInstant(row.occurredAt)?.let { at -> row to at } }
        .groupBy { (_, at) -> at.atZone(zone).toLocalDate() }
    val firstTime = stoolByDay.mapValues { (_, entries) -> entries.minOf { it.second }.atZone(zone).toLocalTime().toSecondOfDay() / 60.0 }
    for ((d, entries) in stoolByDay) {
        val morning = d.plusDays(1)
        b.put(ScoreKeys.GUT_FREQUENCY, morning, entries.size.toDouble())
        b.put(ScoreKeys.GUT_TIMING, morning, firstTime[d])
        val times = (0..13).mapNotNull { firstTime[d.minusDays(it.toLong())] }
        if (times.size >= 6) b.put(ScoreKeys.GUT_TIMING_SPREAD, morning, sd(times))
        val forms = (0..2).flatMap { i -> stoolByDay[d.minusDays(i.toLong())].orEmpty().mapNotNull { it.first.bristolType?.toDouble() } }
        if (forms.isNotEmpty()) b.put(ScoreKeys.GUT_FORM, morning, forms.average())
    }

    // ---- BALANCE: daytime stress (the day before) ----
    for (day in src.stressDays) {
        val s = day.summary
        val stressed = listOfNotNull(s.mediumProportion, s.highProportion).takeIf { it.isNotEmpty() }?.sum()?.toDouble()
        b.put(ScoreKeys.STRESSED_SHARE, day.date.plusDays(1), stressed)
        b.put(ScoreKeys.RELAXED_SHARE, day.date.plusDays(1), s.relaxProportion?.toDouble())
    }

    return b.build()
}

/** Days a morning's caps apply on, from open-ended episodes (start, end or null if still open). */
fun capsFrom(episodes: List<Triple<String, LocalDate, LocalDate?>>): (LocalDate) -> ChargeCap? = { day ->
    episodes.firstOrNull { (_, start, end) -> !day.isBefore(start) && (end == null || !day.isAfter(end)) }
        ?.let { (reason, start, _) -> ChargeCap("$reason · day ${ChronoUnit.DAYS.between(start, day) + 1}") }
}
