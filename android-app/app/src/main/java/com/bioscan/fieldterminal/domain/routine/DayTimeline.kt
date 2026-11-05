package com.bioscan.fieldterminal.domain.routine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Routine intelligence, day map and commute analysis (docs/routine-intelligence/README.md, "Day map and commute").
// Pure: what one day looked like (visits, trips and the stretches nothing was recorded), door-to-door commutes through
// short stops, and how those commutes are spread. Views of what was recorded, not findings.

data class TimelineVisit(val startMs: Long, val endMs: Long, val lat: Double, val lon: Double, val placeId: String?)

sealed interface TimelineItem {
    val startMs: Long
    val endMs: Long
    val minutes: Double get() = (endMs - startMs) / 60_000.0

    // A visit clipped to the day; startsBefore / endsAfter say the stay runs past midnight.
    data class Visit(
        override val startMs: Long,
        override val endMs: Long,
        val visit: TimelineVisit,
        val startsBefore: Boolean,
        val endsAfter: Boolean,
    ) : TimelineItem

    data class Move(override val startMs: Long, override val endMs: Long, val trip: Trip, val commute: Boolean) : TimelineItem

    // Neither a visit nor a trip covers this stretch: the collector was off, or the phone saw no stay.
    data class Gap(override val startMs: Long, override val endMs: Long) : TimelineItem
}

// The day [date] from midnight to midnight (or to [nowMs] for today), in order, with gaps of at least [minGapMs] marked.
fun dayTimeline(
    date: LocalDate,
    zone: ZoneId,
    visits: List<TimelineVisit>,
    trips: List<Trip>,
    nowMs: Long,
    isCommute: (Trip) -> Boolean = { false },
    minGapMs: Long = 5 * 60_000L,
): List<TimelineItem> {
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), nowMs)
    if (dayEnd <= dayStart) return emptyList()
    val items = mutableListOf<TimelineItem>()
    visits.filter { it.endMs > dayStart && it.startMs < dayEnd }.forEach { v ->
        val startsBefore = v.startMs < dayStart
        val endsAfter = v.endMs > dayEnd
        items += TimelineItem.Visit(maxOf(v.startMs, dayStart), minOf(v.endMs, dayEnd), v, startsBefore, endsAfter)
    }
    trips.filter { it.endMs > dayStart && it.startMs < dayEnd }.forEach { t ->
        items += TimelineItem.Move(maxOf(t.startMs, dayStart), minOf(t.endMs, dayEnd), t, isCommute(t))
    }
    items.sortBy { it.startMs }
    val out = mutableListOf<TimelineItem>()
    var cursor = dayStart
    for (item in items) {
        if (item.startMs - cursor >= minGapMs) out += TimelineItem.Gap(cursor, item.startMs)
        out += item
        cursor = maxOf(cursor, item.endMs)
    }
    if (dayEnd - cursor >= minGapMs) out += TimelineItem.Gap(cursor, dayEnd)
    return out
}

// Share of the day (to now, for today) a visit or a trip covers.
fun timelineCoverage(items: List<TimelineItem>): Double {
    val total = items.sumOf { it.endMs - it.startMs }
    if (total <= 0) return 0.0
    return items.filter { it !is TimelineItem.Gap }.sumOf { it.endMs - it.startMs }.toDouble() / total
}

// ---- Door-to-door commutes ----

enum class CommuteWay(val label: String) { TO_WORK("To work"), TO_HOME("To home") }

// How a commute's minutes were spent. STOP is time at a place on the way (a coffee, a drop-off).
enum class ModeGroup(val label: String) {
    ON_FOOT("On foot"),
    BICYCLE("Bicycle"),
    VEHICLE("Vehicle"),
    WAITING("Waiting"),
    STOP("Stops"),
    UNKNOWN("Unknown"),
}

fun modeGroup(mode: String): ModeGroup = when (mode) {
    "walking", "running" -> ModeGroup.ON_FOOT
    "on_bicycle" -> ModeGroup.BICYCLE
    "in_vehicle" -> ModeGroup.VEHICLE
    "still" -> ModeGroup.WAITING
    else -> ModeGroup.UNKNOWN
}

data class CommuteJourney(
    val way: CommuteWay,
    val departMs: Long,
    val arriveMs: Long,
    val trips: List<Trip>,
    // The places stopped at on the way, with the minutes spent at each.
    val stops: List<Pair<String?, Double>>,
) {
    val minutes: Double get() = (arriveMs - departMs) / 60_000.0
    val distanceKm: Double get() = trips.sumOf { it.distanceKm }
    val activeMinutes: Double get() = trips.sumOf { it.activeMinutes }
    val byMode: Map<ModeGroup, Double>
        get() {
            val m = mutableMapOf<ModeGroup, Double>()
            trips.flatMap { it.legs }.forEach { l -> m[modeGroup(l.mode)] = (m[modeGroup(l.mode)] ?: 0.0) + l.minutes }
            val stopMin = stops.sumOf { it.second }
            if (stopMin > 0) m[ModeGroup.STOP] = stopMin
            return m
        }
}

// Home to Work (or back) door to door: a trip leaving one, then trips through stops of at most [maxStopMs] each, until
// one arrives at the other, all within [maxJourneyMs]. A longer stop (dinner on the way home) ends the chain: that is two
// outings, not a commute. Trips must be in time order and come from consecutive visits (trips are built that way).
fun commuteJourneys(
    trips: List<Trip>,
    categoryOf: (String?) -> PlaceCategory?,
    maxStopMs: Long = 15 * 60_000L,
    maxJourneyMs: Long = 3 * 60 * 60_000L,
): List<CommuteJourney> {
    val sorted = trips.sortedBy { it.startMs }
    val out = mutableListOf<CommuteJourney>()
    var i = 0
    while (i < sorted.size) {
        val first = sorted[i]
        val origin = categoryOf(first.fromPlaceId)
        val target = when (origin) {
            PlaceCategory.HOME -> PlaceCategory.WORK
            PlaceCategory.WORK -> PlaceCategory.HOME
            else -> null
        }
        if (target == null) { i++; continue }
        var j = i
        var found = false
        while (j < sorted.size) {
            val leg = sorted[j]
            if (leg.endMs - first.startMs > maxJourneyMs) break
            val dest = categoryOf(leg.toPlaceId)
            if (dest == target) { found = true; break }
            if (dest == origin) break
            val next = sorted.getOrNull(j + 1) ?: break
            if (next.startMs - leg.endMs > maxStopMs) break
            j++
        }
        if (found) {
            val chain = sorted.subList(i, j + 1).toList()
            val stops = chain.zipWithNext().map { (a, b) -> a.toPlaceId to (b.startMs - a.endMs) / 60_000.0 }
            out += CommuteJourney(
                way = if (target == PlaceCategory.WORK) CommuteWay.TO_WORK else CommuteWay.TO_HOME,
                departMs = first.startMs,
                arriveMs = chain.last().endMs,
                trips = chain,
                stops = stops,
            )
            i = j + 1
        } else {
            i++
        }
    }
    return out
}

data class CommuteStats(
    val way: CommuteWay,
    val n: Int,
    val medianMin: Double,
    val q1Min: Double,
    val q3Min: Double,
    val minMin: Double,
    val maxMin: Double,
    // Minutes after local midnight.
    val medianDepartMinOfDay: Double,
    val medianByMode: Map<ModeGroup, Double>,
    val medianDistanceKm: Double,
    val byDeparture: DepartureSplit?,
)

// The journeys split at the median departure time: how long the earlier and the later ones took. Descriptive only: a few
// weeks of commutes cannot say whether leaving later causes anything.
data class DepartureSplit(val splitMinOfDay: Double, val earlyN: Int, val earlyMedianMin: Double, val lateN: Int, val lateMedianMin: Double)

internal fun quantile(xs: List<Double>, q: Double): Double {
    val s = xs.sorted()
    if (s.size == 1) return s[0]
    val pos = q * (s.size - 1)
    val lo = pos.toInt()
    val hi = minOf(lo + 1, s.size - 1)
    return s[lo] + (s[hi] - s[lo]) * (pos - lo)
}

fun minuteOfDay(ms: Long, zone: ZoneId): Double {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    return t.hour * 60.0 + t.minute + t.second / 60.0
}

fun commuteStats(journeys: List<CommuteJourney>, zone: ZoneId, minForSplit: Int = 6): Map<CommuteWay, CommuteStats> =
    journeys.groupBy { it.way }.mapValues { (way, js) ->
        val mins = js.map { it.minutes }
        val departs = js.map { minuteOfDay(it.departMs, zone) }
        val modes = ModeGroup.entries.associateWith { g -> quantile(js.map { it.byMode[g] ?: 0.0 }, 0.5) }.filterValues { it > 0 }
        val split = if (js.size >= minForSplit) {
            val at = quantile(departs, 0.5)
            val early = js.filter { minuteOfDay(it.departMs, zone) < at }.map { it.minutes }
            val late = js.filter { minuteOfDay(it.departMs, zone) >= at }.map { it.minutes }
            if (early.size >= 3 && late.size >= 3) DepartureSplit(at, early.size, quantile(early, 0.5), late.size, quantile(late, 0.5)) else null
        } else null
        CommuteStats(
            way = way,
            n = js.size,
            medianMin = quantile(mins, 0.5),
            q1Min = quantile(mins, 0.25),
            q3Min = quantile(mins, 0.75),
            minMin = mins.min(),
            maxMin = mins.max(),
            medianDepartMinOfDay = quantile(departs, 0.5),
            medianByMode = modes,
            medianDistanceKm = quantile(js.map { it.distanceKm }, 0.5),
            byDeparture = split,
        )
    }

// ---- Places the person marks or merges ----

data class PlacedPoint(val startMs: Long, val lat: Double, val lon: Double, val placeId: String?)

// The visits a newly marked place should take over: those whose centre lies inside its circle and closer to it than to
// the place they belong to now (or that belong to none).
fun visitsForNewPlace(visits: List<PlacedPoint>, newPlace: Place, places: List<Place>): List<PlacedPoint> {
    val byId = places.associateBy { it.id }
    return visits.filter { v ->
        val d = haversineM(v.lat, v.lon, newPlace.lat, newPlace.lon)
        if (d > newPlace.radiusM) return@filter false
        val current = v.placeId?.let { byId[it] } ?: return@filter true
        d < haversineM(v.lat, v.lon, current.lat, current.lon)
    }
}

// Places to merge [place] into, nearest first, within [withinM].
fun mergeCandidates(place: Place, places: List<Place>, withinM: Double = 1_000.0): List<Pair<Place, Double>> =
    places.filter { it.id != place.id }
        .map { it to haversineM(place.lat, place.lon, it.lat, it.lon) }
        .filter { it.second <= withinM }
        .sortedBy { it.second }

// Trips whose stored commute flag no longer matches the places' categories (after a place is relabelled or merged).
fun commuteFlagChanges(trips: List<Pair<Trip, Boolean>>, categoryOf: (String?) -> PlaceCategory?): List<Pair<Trip, Boolean>> =
    trips.mapNotNull { (trip, stored) ->
        val now = isCommute(trip, categoryOf)
        if (now != stored) trip to now else null
    }

// A plain-language check of the Home and Work labels, for the screens that need both. Null when both are fine.
fun homeWorkProblem(places: List<Place>, homeSettingId: String?): String? {
    val homes = places.filter { it.category == PlaceCategory.HOME }
    val works = places.filter { it.category == PlaceCategory.WORK }
    val fromSetup = homeSettingId?.let { id -> places.firstOrNull { it.id == id } }
    return when {
        homes.isEmpty() && fromSetup != null && fromSetup.category != PlaceCategory.HOME ->
            "No place is labelled Home. The place made from your Setup home location is labelled " +
                "${fromSetup.category?.label ?: "nothing"}${fromSetup.label?.let { " (\"$it\")" } ?: ""}. Commutes need one Home and one Work place."
        homes.isEmpty() -> "No place is labelled Home. Commutes need one Home and one Work place."
        works.isEmpty() -> "No place is labelled Work. Commutes need one Home and one Work place."
        else -> null
    }
}
