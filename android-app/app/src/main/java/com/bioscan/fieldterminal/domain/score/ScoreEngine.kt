@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class, com.bioscan.fieldterminal.domain.InstantSerializer::class, com.bioscan.fieldterminal.domain.LocalTimeSerializer::class)
package com.bioscan.fieldterminal.domain.score

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs

// Scoring system: CHARGE and the five domain scores for one morning (docs/scoring-system/README.md).
//
//   domain score  SLEEP, RECOVERY and BALANCE: the domain's raw combination ranked against its own previous 180
//                 mornings (from 28 of them; before that the raw value is shown and the score reads CALIBRATING).
//                 LOAD and FUEL are judged against targets and bands, where most good days sit exactly on target, so
//                 ranking would turn "on target" into a middling number: their raw value is their score (100 = right).
//   CHARGE        a weighted mean of the domain scores (LOAD through its fatigue side only), ranked the same way
//                 against CHARGE's own history, then capped by an open illness, an acute injury or an acute gut episode.
//
// Every score here describes a morning: the night before and the day before it. Nothing is scored while a day is live.

// 2: the illness ceiling is graded by the check-in (IllnessRead.kt); kept mornings capped at a flat 40 are rebuilt.
// 3: sleep debt is carried night to night (SleepDebt.kt) instead of a 7-night sum.
// 4: Rhythm's phone input is the measured minutes on the phone before sleep.
// 5: the evening check-in's 1-5 stress is put on the morning's 0-10 scale before the two share felt stress.
const val SCORE_MODEL_VERSION = "6"
const val RANK_WINDOW_DAYS = 180
const val MIN_RANK_HISTORY = 28
const val CAP_CEILING = 40.0

val CHARGE_WEIGHTS: Map<DomainKey, Double> = mapOf(
    DomainKey.RECOVERY to 0.35,
    DomainKey.SLEEP to 0.20,
    DomainKey.LOAD to 0.15,
    DomainKey.BALANCE to 0.15,
    DomainKey.FUEL to 0.15,
)

/** What HRV and resting HR say together about the autonomic nervous system (BALANCE's state pill). */
enum class AnsState(val label: String) {
    BALANCED("BALANCED"),
    SYMPATHETIC("SYMPATHETIC"),
    SATURATED("SATURATED"),
    UNUSUAL("UNUSUAL"),
}

/** Which side of the band LOAD is on: a lower LOAD can mean too much or too little. */
enum class LoadDirection(val label: String) { HIGH("HIGH SIDE"), IN_BAND("IN BAND"), LOW("LOW SIDE") }

enum class ChargeStatus { PROVISIONAL, FINAL }

/** Something that holds CHARGE down regardless of the domains: an open illness, an acute injury, an acute gut episode. */
@Serializable
data class ChargeCap(val reason: String, val ceiling: Double = CAP_CEILING)

@Serializable
data class DomainScore(
    val key: DomainKey,
    /** Null when PARTIAL. */
    val score: Double?,
    val raw: DomainRaw,
    /** True while fewer than [MIN_RANK_HISTORY] earlier mornings exist to rank against. */
    val calibrating: Boolean,
    /** The previous morning's score, for the ring's arrow. */
    val previous: Double?,
) {
    val partial: Boolean get() = score == null
    val band: ScoreBand? get() = score?.let(ScoreScale::band)
    val change: Double? get() = if (score != null && previous != null) score - previous else null
}

@Serializable
data class Driver(val key: DomainKey, val points: Double)

@Serializable
data class ChargeScore(
    val day: LocalDate,
    /** Null when neither SLEEP nor RECOVERY could be scored. */
    val score: Double?,
    /** Before caps. */
    val uncapped: Double?,
    val raw: Double?,
    /** Share of earlier mornings this one beats (0-1), for "better than N of your last 100 mornings". */
    val percentile: Double?,
    val drivers: List<Driver>,
    val domainsUsed: Set<DomainKey>,
    val cap: ChargeCap?,
    val calibrating: Boolean,
    val previous: Double?,
    /** How many earlier mornings it was ranked against, and whether it beat every one of them. */
    val rankedAgainst: Int? = null,
    val record: Boolean = false,
) {
    val band: ScoreBand? get() = score?.let(ScoreScale::band)
    val change: Double? get() = if (score != null && previous != null) score - previous else null
}

@Serializable
data class DayScores(
    val day: LocalDate,
    val charge: ChargeScore,
    val domains: Map<DomainKey, DomainScore>,
    val ansState: AnsState?,
    val loadDirection: LoadDirection?,
    val modelVersion: String = SCORE_MODEL_VERSION,
)

/** Direction under the number: up, down or about the same as yesterday. */
enum class ScoreTrend { UP, SAME, DOWN }

fun trendOf(change: Double?, threshold: Double = 3.0): ScoreTrend? = when {
    change == null -> null
    change >= threshold -> ScoreTrend.UP
    change <= -threshold -> ScoreTrend.DOWN
    else -> ScoreTrend.SAME
}

/**
 * PROVISIONAL from wake; FINAL once the day before is closed (the evening check-in) or from 12:00 on the scored morning.
 * A past morning is always FINAL.
 */
fun chargeStatus(day: LocalDate, dayBeforeClosed: Boolean, now: LocalDateTime): ChargeStatus = when {
    dayBeforeClosed -> ChargeStatus.FINAL
    !now.isBefore(day.atTime(LocalTime.NOON)) -> ChargeStatus.FINAL
    else -> ChargeStatus.PROVISIONAL
}

/** ANS state from the morning's HRV and resting HR against their baselines, and the week's HRV variation. */
fun ansStateOf(series: Map<String, Series>, day: LocalDate, threshold: Double = 0.2): AnsState? {
    fun zOf(key: String, log: Boolean, minSd: Double): Double? {
        val v = series[key]?.get(day) ?: return null
        if (log && v <= 0) return null
        val (med, sd) = baselineOf(series[key].orEmpty(), day, 60, 14, log, minSd) ?: return null
        return ((if (log) kotlin.math.ln(v) else v) - med) / sd
    }
    val hrv = zOf(ScoreKeys.HRV, log = true, minSd = 0.03) ?: return null
    val rhr = zOf(ScoreKeys.RHR, log = false, minSd = 1.0) ?: return null
    val cv = zOf(ScoreKeys.HRV_CV, log = false, minSd = 0.5)
    return when {
        (hrv < -threshold && rhr < -threshold) || (cv != null && cv > 1.5) -> AnsState.SATURATED
        hrv < -threshold && rhr > threshold -> AnsState.SYMPATHETIC
        hrv > threshold && rhr > threshold -> AnsState.UNUSUAL
        else -> AnsState.BALANCED
    }
}

fun loadDirectionOf(series: Map<String, Series>, day: LocalDate): LoadDirection? {
    val tsb = series[ScoreKeys.TSB]?.get(day)
    val streak = series[ScoreKeys.DAYS_WITHOUT_REST]?.get(day)
    if (tsb == null && streak == null) return null
    return when {
        tsb != null && tsb < ScoreSpecs.TSB_BAND_LO -> LoadDirection.HIGH
        streak != null && streak > ScoreSpecs.MAX_DAYS_WITHOUT_REST -> LoadDirection.HIGH
        tsb != null && tsb > ScoreSpecs.TSB_BAND_HI -> LoadDirection.LOW
        else -> LoadDirection.IN_BAND
    }
}

/**
 * Scores mornings from assembled series. Results are cached, so scoring a run of mornings (the 30-day strip, the
 * history a rank needs) costs each morning once.
 *
 * [caps] gives a morning's caps (open illness etc.); it is asked for every morning CHARGE is computed for, including
 * past ones, so the history a rank uses is capped the same way the person saw it.
 */
class ScoreEngine(
    private val series: Map<String, Series>,
    private val caps: (LocalDate) -> ChargeCap? = { null },
    private val weights: Map<DomainKey, Double> = CHARGE_WEIGHTS,
) {
    private val rawCache = HashMap<Pair<DomainKey, LocalDate>, DomainRaw>()
    private val fatigueCache = HashMap<LocalDate, DomainRaw>()
    private val domainCache = HashMap<Pair<DomainKey, LocalDate>, Double?>()
    private val chargeRawCache = HashMap<LocalDate, Double?>()
    private val chargeCache = HashMap<LocalDate, Pair<Double?, Double?>>() // (uncapped ranked, percentile)
    private val rankedAgainst = HashMap<LocalDate, Int>()
    private val recordDays = HashSet<LocalDate>()

    /** The first morning any series has data for: nothing before it is scored. */
    private val firstDay: LocalDate? = series.values.flatMap { it.keys }.minOrNull()

    private fun saturated(day: LocalDate) = ansStateOf(series, day) == AnsState.SATURATED

    fun domainRaw(key: DomainKey, day: LocalDate): DomainRaw = rawCache.getOrPut(key to day) {
        val spec = ScoreSpecs.ALL.getValue(key)
        if (key == DomainKey.RECOVERY && saturated(day)) {
            // The upside guard: high HRV during a SATURATED pattern is not freshness, so neither HRV nor RECOVERY
            // goes above the top of normal that morning.
            combineDomain(spec, series, day, upside = false, inputCap = mapOf(ScoreKeys.HRV to ScoreScale.TOP_OF_NORMAL))
        } else {
            combineDomain(spec, series, day)
        }
    }

    private fun fatigueRaw(day: LocalDate): DomainRaw = fatigueCache.getOrPut(day) { combineDomain(ScoreSpecs.LOAD_FATIGUE, series, day) }

    private fun history(day: LocalDate, value: (LocalDate) -> Double?): List<Double> {
        val first = firstDay ?: return emptyList()
        return (1..RANK_WINDOW_DAYS).asSequence()
            .map { day.minusDays(it.toLong()) }
            .takeWhile { !it.isBefore(first) }
            .mapNotNull(value)
            .toList()
    }

    /** A domain's score for [day], or null when PARTIAL. */
    fun domainScoreValue(key: DomainKey, day: LocalDate): Double? = domainCache.getOrPut(key to day) {
        val domain = domainRaw(key, day)
        val raw = domain.raw ?: return@getOrPut null
        if (!key.upside) return@getOrPut raw
        val past = history(day) { domainRaw(key, it).raw }
        // A guarded morning (SATURATED) stays at or under the top of normal after ranking too.
        if (past.size < MIN_RANK_HISTORY) raw else ScoreScale.fromHistory(raw, past, upside = domain.upside)
    }

    private fun isCalibrating(key: DomainKey, day: LocalDate): Boolean =
        key.upside && history(day) { domainRaw(key, it).raw }.size < MIN_RANK_HISTORY

    /** What each domain contributes to CHARGE on [day] (LOAD through its fatigue side). */
    internal fun chargeInputs(day: LocalDate): Map<DomainKey, Double> = buildMap {
        for (key in DomainKey.entries) {
            val value = if (key == DomainKey.LOAD) fatigueRaw(day).raw else domainScoreValue(key, day)
            if (value != null) put(key, value)
        }
    }

    private fun chargeRaw(day: LocalDate): Double? = chargeRawCache.getOrPut(day) {
        val inputs = chargeInputs(day)
        if (DomainKey.SLEEP !in inputs && DomainKey.RECOVERY !in inputs) return@getOrPut null
        val w = inputs.keys.sumOf { weights.getValue(it) }
        inputs.entries.sumOf { (k, v) -> v * weights.getValue(k) } / w
    }

    private fun chargeRanked(day: LocalDate): Pair<Double?, Double?> = chargeCache.getOrPut(day) {
        val raw = chargeRaw(day) ?: return@getOrPut null to null
        val past = history(day) { chargeRaw(it) }
        if (past.size < MIN_RANK_HISTORY) raw to null
        else {
            rankedAgainst[day] = past.size
            if (raw > past.max()) recordDays += day
            ScoreScale.fromHistory(raw, past) to ScoreScale.rankPercentile(raw, past)
        }
    }

    /** CHARGE for [day] as the person saw it: ranked, then capped. */
    fun chargeValue(day: LocalDate): Double? {
        val uncapped = chargeRanked(day).first ?: return null
        val cap = caps(day)
        return if (cap != null) minOf(uncapped, cap.ceiling) else uncapped
    }

    fun score(day: LocalDate): DayScores {
        val domains = DomainKey.entries.associateWith { key ->
            DomainScore(
                key = key,
                score = domainScoreValue(key, day),
                raw = domainRaw(key, day),
                calibrating = isCalibrating(key, day),
                previous = domainScoreValue(key, day.minusDays(1)),
            )
        }
        val inputs = chargeInputs(day)
        val raw = chargeRaw(day)
        val (uncapped, percentile) = chargeRanked(day)
        val cap = caps(day)
        val final = uncapped?.let { if (cap != null) minOf(it, cap.ceiling) else it }
        // Drivers explain the uncapped CHARGE; a cap is reported on its own.
        val drivers = if (raw == null || uncapped == null) emptyList() else drivers(inputs, raw, uncapped)
        val charge = ChargeScore(
            day = day, score = final, uncapped = uncapped, raw = raw, percentile = percentile,
            drivers = drivers, domainsUsed = inputs.keys, cap = cap?.takeIf { uncapped != null && uncapped > it.ceiling },
            calibrating = raw != null && percentile == null, previous = chargeValue(day.minusDays(1)),
            rankedAgainst = rankedAgainst[day], record = day in recordDays,
        )
        return DayScores(day, charge, domains, ansStateOf(series, day), loadDirectionOf(series, day))
    }

    /** CHARGE for each morning from [from] to [to], for the 30-day strip. */
    fun chargeStrip(from: LocalDate, to: LocalDate): List<Pair<LocalDate, Double?>> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { it to chargeValue(it) }.toList()

    /** A domain's score for each morning from [from] to [to]. */
    fun domainStrip(key: DomainKey, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Double?>> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { it to domainScoreValue(key, it) }.toList()

    private fun drivers(inputs: Map<DomainKey, Double>, raw: Double, ranked: Double): List<Driver> {
        // Each domain's weighted distance from a typical day; these sum exactly to raw - 80. They are scaled so they
        // sum to the distance the person sees (ranked - 80), which ranking stretches or shrinks. Close to 80 the two
        // can fall on opposite sides; the raw contributions are kept then rather than flipping every sign.
        val w = inputs.keys.sumOf { weights.getValue(it) }
        val contributions = inputs.mapValues { (k, v) -> weights.getValue(k) / w * (v - ScoreScale.TYPICAL) }
        val rawDistance = raw - ScoreScale.TYPICAL
        val rankedDistance = ranked - ScoreScale.TYPICAL
        val k = if (abs(rawDistance) >= 1.0 && rawDistance * rankedDistance > 0) rankedDistance / rawDistance else 1.0
        return contributions.map { (key, c) -> Driver(key, c * k) }.sortedByDescending { abs(it.points) }
    }
}
