package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// Heart-rate zones, three ways. The same five zones (plus below Z1) are drawn on a session's heart-rate chart and
// donut and in the weekly intensity split; what changes is where their edges sit:
//  - MAX: % of maximum heart rate (50/60/70/80/90), the common default. It rests on one number and ignores resting
//    heart rate and where the person's thresholds are.
//  - HRR: % of heart-rate reserve (Karvonen 1957): resting + share x (max - resting). Moves with fitness through
//    resting heart rate.
//  - LTHR: % of lactate-threshold heart rate (Friel's run zones: Z1 under 85, Z2 85-89, Z3 90-94, Z4 95-99, Z5 from
//    100). Anchored to a metabolic landmark, which tracks intensity better than % of max (Mann, Lamberts and Lambert,
//    Sports Med 2013). The threshold is Zepp's estimate from runs, so it applies to running only.
//  - AUTO picks LTHR for a run with a recent estimate, else HRR, else MAX.
// The anchors are taken as they were on the session's date (zoneAnchorsAsOf), so an old session keeps the zones it
// had then.

enum class ZoneMethod(val key: String, val label: String) {
    AUTO("auto", "AUTO"),
    LTHR("lthr", "LTHR"),
    HRR("hrr", "HRR"),
    MAX("max", "% MAX"),
    ;

    companion object {
        fun of(key: String?): ZoneMethod = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

enum class HeartRateZone(val label: String) {
    BelowZ1("Below Z1"),
    Z1("Zone 1 — Very light"),
    Z2("Zone 2 — Light"),
    Z3("Zone 3 — Moderate"),
    Z4("Zone 4 — Hard"),
    Z5("Zone 5 — Maximum"),
}

private val ZONES = listOf(HeartRateZone.Z1, HeartRateZone.Z2, HeartRateZone.Z3, HeartRateZone.Z4, HeartRateZone.Z5)

/** The person's anchors on one date, each with where it came from (shown under the chart). */
data class ZoneAnchors(
    val maxHr: Double? = null,
    val restingHr: Double? = null,
    val lthr: Double? = null,
    val lthrDate: LocalDate? = null,
    val asOf: LocalDate? = null,
)

/** Where each zone starts, in bpm, for one resolved method (never AUTO). */
data class ZoneBounds(val method: ZoneMethod, val lowerBpm: List<Double>) {
    init {
        require(method != ZoneMethod.AUTO && lowerBpm.size == 5)
    }

    /** "141–156" for a zone; the last one open-ended ("185+"). */
    fun rangeText(zone: HeartRateZone): String {
        val i = ZONES.indexOf(zone)
        if (i < 0) return "under ${lowerBpm[0].roundToInt()}"
        val lo = lowerBpm[i].roundToInt()
        return if (i == ZONES.lastIndex) "$lo+" else "$lo–${lowerBpm[i + 1].roundToInt() - 1}"
    }
}

private val MAX_SHARES = listOf(0.50, 0.60, 0.70, 0.80, 0.90)
private val HRR_SHARES = listOf(0.50, 0.60, 0.70, 0.80, 0.90)
private val LTHR_SHARES = listOf(0.0, 0.85, 0.90, 0.95, 1.00)

/** An LTHR estimate older than this is not used by AUTO. */
const val LTHR_MAX_AGE_DAYS = 120L

/** Sports Zepp's running threshold applies to. */
fun usesRunningThreshold(sport: String?): Boolean = sport == "run"

/** The zone edges for [method], or null when its inputs are missing. AUTO resolves to the best available. */
fun zoneBounds(method: ZoneMethod, anchors: ZoneAnchors, sport: String?): ZoneBounds? = when (method) {
    ZoneMethod.AUTO -> resolveAuto(anchors, sport)?.let { zoneBounds(it, anchors, sport) }
    ZoneMethod.MAX -> anchors.maxHr?.takeIf { it > 0 }?.let { max -> ZoneBounds(method, MAX_SHARES.map { it * max }) }
    ZoneMethod.HRR -> {
        val max = anchors.maxHr
        val rest = anchors.restingHr
        if (max == null || rest == null || max <= rest) null
        else ZoneBounds(method, HRR_SHARES.map { rest + it * (max - rest) })
    }
    ZoneMethod.LTHR -> anchors.lthr?.takeIf { it > 0 && usesRunningThreshold(sport) }?.let { lt ->
        // Z1 starts where HRR's would (or at half the threshold without a resting heart rate): below it is rest.
        val z1 = zoneBounds(ZoneMethod.HRR, anchors, sport)?.lowerBpm?.first() ?: (0.5 * lt)
        ZoneBounds(method, listOf(minOf(z1, 0.85 * lt - 1)) + LTHR_SHARES.drop(1).map { it * lt })
    }
}

/** What AUTO uses for this session: LTHR for a run with a recent estimate, else HRR, else MAX, else nothing. */
fun resolveAuto(anchors: ZoneAnchors, sport: String?): ZoneMethod? {
    val lthrFresh = anchors.lthr != null && usesRunningThreshold(sport) &&
        (anchors.lthrDate == null || anchors.asOf == null || !anchors.lthrDate.isBefore(anchors.asOf.minusDays(LTHR_MAX_AGE_DAYS)))
    return when {
        lthrFresh -> ZoneMethod.LTHR
        zoneBounds(ZoneMethod.HRR, anchors, sport) != null -> ZoneMethod.HRR
        zoneBounds(ZoneMethod.MAX, anchors, sport) != null -> ZoneMethod.MAX
        else -> null
    }
}

/** The methods this session can show (for the switch). */
fun availableMethods(anchors: ZoneAnchors, sport: String?): List<ZoneMethod> =
    listOf(ZoneMethod.LTHR, ZoneMethod.HRR, ZoneMethod.MAX).filter { zoneBounds(it, anchors, sport) != null }

fun heartRateZoneFor(hr: Double, bounds: ZoneBounds): HeartRateZone =
    ZONES.indices.lastOrNull { hr >= bounds.lowerBpm[it] }?.let { ZONES[it] } ?: HeartRateZone.BelowZ1

data class HeartRateZoneBreakdown(val zone: HeartRateZone, val seconds: Long)

// Standard "time in zone" method: each inter-sample interval's duration is
// attributed to the zone of its starting sample, then summed per zone.
fun heartRateZoneBreakdown(points: List<TimePoint>, bounds: ZoneBounds): List<HeartRateZoneBreakdown> {
    if (points.size < 2) return emptyList()
    val sorted = points.sortedBy { it.offsetSeconds }
    return sorted.zipWithNext { a, b -> heartRateZoneFor(a.value, bounds) to (b.offsetSeconds - a.offsetSeconds) }
        .groupBy({ it.first }, { it.second })
        .map { (zone, durations) -> HeartRateZoneBreakdown(zone, durations.sum()) }
        .sortedBy { it.zone.ordinal }
}

// ---- Anchors as of a date --------------------------------------------------------------------------------------

/** Session maxima older than this are not used for the max. */
const val MAX_HR_WINDOW_MONTHS = 24L
private val PLAUSIBLE_MAX = 120.0..230.0

/**
 * The maximum heart rate on [asOf]: the second-highest session maximum of the 24 months up to it, so one optical
 * spike cannot set every zone (the highest when only one session exists).
 */
fun robustMaxHr(sessionMaxima: List<Pair<LocalDate, Double>>, asOf: LocalDate): Double? {
    val from = asOf.minusMonths(MAX_HR_WINDOW_MONTHS)
    val values = sessionMaxima
        .filter { (d, v) -> !d.isAfter(asOf) && !d.isBefore(from) && v in PLAUSIBLE_MAX }
        .map { it.second }
        .sortedDescending()
    return values.getOrNull(1) ?: values.firstOrNull()
}

/** Resting heart rate on [asOf]: the median of the 30 days up to the latest day with a value on or before it. */
fun restingHrAsOf(daily: List<Pair<LocalDate, Double>>, asOf: LocalDate): Double? {
    val upTo = daily.filter { !it.first.isAfter(asOf) && it.second in 25.0..120.0 }
    val last = upTo.maxOfOrNull { it.first } ?: return null
    val window = upTo.filter { !it.first.isBefore(last.minusDays(29)) }.map { it.second }.sorted()
    if (window.isEmpty()) return null
    val m = window.size / 2
    return if (window.size % 2 == 1) window[m] else (window[m - 1] + window[m]) / 2
}

/** The latest lactate-threshold estimate on or before [asOf], with its date. */
fun lthrAsOf(series: List<Pair<LocalDate, Double>>, asOf: LocalDate): Pair<LocalDate, Double>? =
    series.filter { !it.first.isAfter(asOf) && it.second in 100.0..220.0 }.maxByOrNull { it.first }

fun zoneAnchorsAsOf(
    asOf: LocalDate,
    sessionMaxima: List<Pair<LocalDate, Double>>,
    restingDaily: List<Pair<LocalDate, Double>>,
    lthrSeries: List<Pair<LocalDate, Double>>,
): ZoneAnchors {
    val lt = lthrAsOf(lthrSeries, asOf)
    return ZoneAnchors(
        maxHr = robustMaxHr(sessionMaxima, asOf),
        restingHr = restingHrAsOf(restingDaily, asOf),
        lthr = lt?.second,
        lthrDate = lt?.first,
        asOf = asOf,
    )
}

private val DAY = DateTimeFormatter.ofPattern("d MMM")

/** One line naming what a method's zones rest on, for the chart caption. */
fun anchorsCaption(method: ZoneMethod, anchors: ZoneAnchors): String {
    fun bpm(v: Double?) = v?.roundToInt()?.toString() ?: "–"
    val max = "max ${bpm(anchors.maxHr)} (2nd highest session, 24 months)"
    val rest = "resting ${bpm(anchors.restingHr)} (30-day median)"
    val lt = "LTHR ${bpm(anchors.lthr)}" + (anchors.lthrDate?.let { " (Zepp, ${it.format(DAY)})" } ?: "")
    return when (method) {
        ZoneMethod.LTHR -> "% of threshold · $lt"
        ZoneMethod.HRR -> "% of heart-rate reserve · $rest · $max"
        ZoneMethod.MAX -> "% of maximum · $max"
        ZoneMethod.AUTO -> ""
    }
}
