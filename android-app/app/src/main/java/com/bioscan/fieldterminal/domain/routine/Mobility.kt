package com.bioscan.fieldterminal.domain.routine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Routine intelligence R3 (docs/routine-intelligence/01-research-and-plan.md, section 4): one local day (00:00 to 24:00)
// of places and trips as the mobility_daily row. Time no visit or trip covers is unknown, never home: coverage_pct says how
// much of the day the numbers rest on, and the analysis leaves days under 80 % out (plan section 8.4).

data class DayVisit(
    val startMs: Long,
    val endMs: Long,
    val lat: Double,
    val lon: Double,
    val placeId: String?,
    val category: PlaceCategory?,
)

enum class DayType(val key: String) { WORKDAY("workday"), HOME_DAY("home_day"), MIXED("mixed"), TRAVEL("travel"), UNKNOWN("unknown") }

data class MobilityDay(
    val date: LocalDate,
    val coveragePct: Int,
    // Minutes per place category key, plus "unlabelled" (a place without a category), "transit" and "unknown".
    val minutesByCategory: Map<String, Double>,
    val nPlaces: Int,
    val newPlaces: Int,
    val entropyNorm: Double?,
    val radiusGyrationKm: Double?,
    val timeFromHomeMin: Double,
    val outdoorDaylightMin: Double,
    val routineSimilarity: Double?,
    val dayType: DayType,
    val daylightHours: Double?,
)

private const val DAY_MIN = 1440.0
private const val TRAVEL_KM = 50.0

private fun overlapMin(s: Long, e: Long, from: Long, to: Long) = (minOf(e, to) - maxOf(s, from)).coerceAtLeast(0) / 60_000.0

fun mobilityDay(
    date: LocalDate,
    zone: ZoneId,
    visits: List<DayVisit>,
    trips: List<Trip>,
    home: Pair<Double, Double>?,
    // When each place was first seen, for new_places (first seen in the 7 days up to this one).
    placeFirstSeen: Map<String, Instant>,
    // The same weekday's hourly place vectors from earlier weeks, newest first (routineSimilarity).
    previousSameWeekdays: List<List<String?>>,
): MobilityDay {
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val dayVisits = visits.filter { overlapMin(it.startMs, it.endMs, from, to) > 0 }
    val dayTrips = trips.filter { overlapMin(it.startMs, it.endMs, from, to) > 0 }

    val byCategory = mutableMapOf<String, Double>()
    for (v in dayVisits) {
        val key = v.category?.key ?: "unlabelled"
        byCategory[key] = (byCategory[key] ?: 0.0) + overlapMin(v.startMs, v.endMs, from, to)
    }
    val transit = dayTrips.sumOf { overlapMin(it.startMs, it.endMs, from, to) }
    if (transit > 0) byCategory["transit"] = transit
    val covered = byCategory.values.sum().coerceAtMost(DAY_MIN)
    byCategory["unknown"] = DAY_MIN - covered

    val byPlace = dayVisits.groupBy { it.placeId ?: "visit:${it.startMs}" }
        .mapValues { (_, vs) -> vs.sumOf { overlapMin(it.startMs, it.endMs, from, to) } }
    val entropy = if (byPlace.size >= 2) {
        val total = byPlace.values.sum()
        -byPlace.values.sumOf { val p = it / total; if (p > 0) p * ln(p) else 0.0 } / ln(byPlace.size.toDouble())
    } else if (byPlace.size == 1) 0.0 else null

    val radius = if (dayVisits.isEmpty()) null else {
        val w = dayVisits.map { overlapMin(it.startMs, it.endMs, from, to) }
        val total = w.sum()
        val cLat = dayVisits.indices.sumOf { dayVisits[it].lat * w[it] } / total
        val cLon = dayVisits.indices.sumOf { dayVisits[it].lon * w[it] } / total
        sqrt(dayVisits.indices.sumOf { w[it] * haversineM(dayVisits[it].lat, dayVisits[it].lon, cLat, cLon).let { d -> d * d } } / total) / 1000.0
    }

    val homeMin = byCategory[PlaceCategory.HOME.key] ?: 0.0
    val sun = home?.let { sunTimes(date, it.first, it.second) } ?: dayVisits.firstOrNull()?.let { sunTimes(date, it.lat, it.lon) }
    val outdoor = if (sun == null) 0.0 else {
        val (rise, set) = sun
        val dayFrom = maxOf(from, rise.toEpochMilli())
        val dayTo = minOf(to, set.toEpochMilli())
        val onFoot = dayTrips.sumOf { t -> activeLegSpans(t).sumOf { (s, e) -> overlapMin(s, e, dayFrom, dayTo) } }
        val outdoorVisits = dayVisits.filter { it.category == PlaceCategory.OUTDOOR }.sumOf { overlapMin(it.startMs, it.endMs, dayFrom, dayTo) }
        onFoot + outdoorVisits
    }

    val farFromHome = home != null && dayVisits.any { haversineM(it.lat, it.lon, home.first, home.second) / 1000.0 > TRAVEL_KM }
    val coveragePct = (covered / DAY_MIN * 100).toInt()
    val dayType = when {
        coveragePct < 50 -> DayType.UNKNOWN
        farFromHome -> DayType.TRAVEL
        (byCategory[PlaceCategory.WORK.key] ?: 0.0) >= 180 -> DayType.WORKDAY
        homeMin >= 0.8 * covered -> DayType.HOME_DAY
        else -> DayType.MIXED
    }

    val vector = hourlyPlaceVector(date, zone, dayVisits)
    val windowStart = date.minusDays(6).atStartOfDay(zone).toInstant()
    val windowEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
    return MobilityDay(
        date = date,
        coveragePct = coveragePct,
        minutesByCategory = byCategory.filterValues { it > 0 },
        nPlaces = byPlace.keys.count { !it.startsWith("visit:") },
        newPlaces = byPlace.keys.count { id -> placeFirstSeen[id]?.let { !it.isBefore(windowStart) && it.isBefore(windowEnd) } == true },
        entropyNorm = entropy,
        radiusGyrationKm = radius,
        timeFromHomeMin = (covered - homeMin).coerceAtLeast(0.0),
        outdoorDaylightMin = outdoor,
        routineSimilarity = routineSimilarity(vector, previousSameWeekdays),
        dayType = dayType,
        daylightHours = sun?.let { (r, s) -> (s.toEpochMilli() - r.toEpochMilli()) / 3_600_000.0 },
    )
}

// The active legs of a trip as time spans, laid end to end from the trip's start.
private fun activeLegSpans(t: Trip): List<Pair<Long, Long>> {
    var cursor = t.startMs
    return t.legs.mapNotNull { leg ->
        val s = cursor
        cursor += (leg.minutes * 60_000).toLong()
        if (leg.mode in ACTIVE_MODES) s to cursor else null
    }
}

// For each hour of the day, the place where at least 30 minutes of it were spent (null otherwise).
fun hourlyPlaceVector(date: LocalDate, zone: ZoneId, visits: List<DayVisit>): List<String?> = (0 until 24).map { h ->
    val from = date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    val to = from + 3_600_000L
    visits.filter { it.placeId != null }
        .groupBy { it.placeId!! }
        .mapValues { (_, vs) -> vs.sumOf { overlapMin(it.startMs, it.endMs, from, to) } }
        .filterValues { it >= 30 }
        .maxByOrNull { it.value }?.key
}

// How much today follows the same weekday of earlier weeks: per earlier day, the share of hours known on both days spent
// at the same place (only days sharing at least 12 known hours count); the mean of those. Null without such a day.
fun routineSimilarity(today: List<String?>, earlier: List<List<String?>>, minSharedHours: Int = 12): Double? {
    val shares = earlier.mapNotNull { other ->
        val both = today.indices.filter { today[it] != null && other.getOrNull(it) != null }
        if (both.size < minSharedHours) null else both.count { today[it] == other[it] }.toDouble() / both.size
    }
    return if (shares.isEmpty()) null else shares.average()
}

// Sunrise and sunset (NOAA's general solar position formulas, refraction included), or null in polar day or night.
fun sunTimes(date: LocalDate, lat: Double, lon: Double): Pair<Instant, Instant>? {
    val gamma = 2 * Math.PI / 365 * (date.dayOfYear - 1)
    val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
    val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
        0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
    val latR = Math.toRadians(lat)
    val cosHa = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
    if (cosHa !in -1.0..1.0) return null
    val ha = Math.toDegrees(acos(cosHa))
    val midnightUtc = date.atStartOfDay(ZoneId.of("UTC")).toInstant()
    val riseMin = 720 - 4 * (lon + ha) - eqTime
    val setMin = 720 - 4 * (lon - ha) - eqTime
    return midnightUtc.plusSeconds((riseMin * 60).toLong()) to midnightUtc.plusSeconds((setMin * 60).toLong())
}

