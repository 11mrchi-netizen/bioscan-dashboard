package com.bioscan.fieldterminal.domain.routine

// Routine intelligence R3: trips between consecutive visits. A trip runs from one visit's end to the next visit's start;
// its legs come from the activity transitions in that span (each entered activity lasts until the next one), its distance
// from the trip's fixes (straight line between the two places when it has none). Gaps longer than maxTripMs are not
// trips: the collector was off, or a stay went unrecorded.

data class TransitionEvent(val ts: Long, val activity: String, val enter: Boolean)

data class TripVisit(val startMs: Long, val endMs: Long, val lat: Double, val lon: Double, val placeId: String?)

data class TripLeg(val mode: String, val minutes: Double)

data class Trip(
    val startMs: Long,
    val endMs: Long,
    val fromPlaceId: String?,
    val toPlaceId: String?,
    val distanceKm: Double,
    val legs: List<TripLeg>,
) {
    val minutes: Double get() = (endMs - startMs) / 60_000.0
    // Minutes on foot or on a bicycle: the part of a trip that is exercise.
    val activeMinutes: Double get() = legs.filter { it.mode in ACTIVE_MODES }.sumOf { it.minutes }
}

val ACTIVE_MODES = setOf("walking", "running", "on_bicycle")
const val UNKNOWN_MODE = "unknown"

// A transition that arrived just before the visit ended still says how the trip began.
private const val LEAD_MS = 5 * 60_000L

fun tripsBetween(
    visits: List<TripVisit>,
    transitions: List<TransitionEvent>,
    fixes: List<Fix>,
    maxTripMs: Long = 6 * 60 * 60_000L,
): List<Trip> {
    val sorted = visits.sortedBy { it.startMs }
    val enters = transitions.filter { it.enter }.sortedBy { it.ts }
    val sortedFixes = fixes.sortedBy { it.ts }
    return sorted.zipWithNext().mapNotNull { (a, b) ->
        val start = a.endMs
        val end = b.startMs
        if (end <= start || end - start > maxTripMs) return@mapNotNull null
        Trip(
            startMs = start,
            endMs = end,
            fromPlaceId = a.placeId,
            toPlaceId = b.placeId,
            distanceKm = tripDistanceKm(a, b, sortedFixes.filter { it.ts in start..end }),
            legs = legsFor(start, end, enters),
        )
    }
}

internal fun legsFor(start: Long, end: Long, enters: List<TransitionEvent>): List<TripLeg> {
    // The activity in force at the start (the last one entered at most LEAD_MS before), then each change inside the trip.
    val opening = enters.lastOrNull { it.ts in (start - LEAD_MS)..start }
    val inside = enters.filter { it.ts in (start + 1) until end }
    val changes = listOfNotNull(opening?.copy(ts = start)) + inside
    if (changes.isEmpty()) return listOf(TripLeg(UNKNOWN_MODE, (end - start) / 60_000.0))
    val legs = mutableListOf<TripLeg>()
    if (changes.first().ts > start) legs += TripLeg(UNKNOWN_MODE, (changes.first().ts - start) / 60_000.0)
    changes.forEachIndexed { i, c ->
        val until = changes.getOrNull(i + 1)?.ts ?: end
        legs += TripLeg(c.activity, (until - c.ts) / 60_000.0)
    }
    // Consecutive legs of one mode (a repeated transition, or the opening one repeated inside) are one leg. A pause in
    // between (walking, still at a light, walking) stays three legs: the still minutes are waiting, not walking.
    return legs.filter { it.minutes > 0 }.fold(mutableListOf()) { acc, leg ->
        val last = acc.lastOrNull()
        if (last != null && last.mode == leg.mode) acc[acc.lastIndex] = last.copy(minutes = last.minutes + leg.minutes) else acc += leg
        acc
    }
}

private fun tripDistanceKm(a: TripVisit, b: TripVisit, fixes: List<Fix>): Double {
    val points = listOf(a.lat to a.lon) + fixes.map { it.lat to it.lon } + listOf(b.lat to b.lon)
    return points.zipWithNext().sumOf { (p, q) -> haversineM(p.first, p.second, q.first, q.second) } / 1000.0
}

// A commute is a trip between a home place and a work place, either way.
fun isCommute(trip: Trip, categoryOf: (String?) -> PlaceCategory?): Boolean {
    val ends = setOf(categoryOf(trip.fromPlaceId), categoryOf(trip.toPlaceId))
    return ends == setOf(PlaceCategory.HOME, PlaceCategory.WORK)
}

data class ActiveTravelWeek(val lastWeekMin: Double, val previousWeekMin: Double, val onFootMin: Double, val bicycleMin: Double, val trips: Int)

// Routine intelligence R4: the minutes on foot or on a bicycle in trips between places over the last 7 days, and the 7
// before, for Training › Load. Shown beside training load, never added to it: the session-load model needs a session's
// effort, and a walk to the station has none recorded. A trip counts in the week it started.
fun activeTravel(trips: List<Trip>, nowMs: Long): ActiveTravelWeek {
    val week = 7 * 86_400_000L
    val last = trips.filter { it.startMs in (nowMs - week) until nowMs }
    val previous = trips.filter { it.startMs in (nowMs - 2 * week) until (nowMs - week) }
    val legs = last.flatMap { it.legs }
    return ActiveTravelWeek(
        lastWeekMin = last.sumOf { it.activeMinutes },
        previousWeekMin = previous.sumOf { it.activeMinutes },
        onFootMin = legs.filter { it.mode == "walking" || it.mode == "running" }.sumOf { it.minutes },
        bicycleMin = legs.filter { it.mode == "on_bicycle" }.sumOf { it.minutes },
        trips = last.count { it.activeMinutes > 0 },
    )
}
