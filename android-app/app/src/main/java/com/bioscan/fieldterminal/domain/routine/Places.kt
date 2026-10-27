package com.bioscan.fieldterminal.domain.routine

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

// Routine intelligence R3 (docs/routine-intelligence/01-research-and-plan.md, section 3.1): visits become places. A visit
// belongs to the nearest known place whose circle holds its centroid; a visit no place holds starts a new one there. The
// person names places and gives them a category; the app only suggests (home from Setup's home location, work from where
// weekday office hours are spent, a category from OpenStreetMap's tags around the point).

enum class PlaceCategory(val key: String, val label: String) {
    HOME("home", "Home"),
    WORK("work", "Work"),
    EXERCISE("exercise", "Exercise"),
    FOOD("food", "Food"),
    SOCIAL("social", "Social"),
    SHOP("shop", "Shop"),
    HEALTH("health", "Health"),
    TRANSIT("transit", "Transit"),
    OUTDOOR("outdoor", "Outdoor"),
    OTHER("other", "Other");

    companion object {
        fun of(key: String?): PlaceCategory? = entries.firstOrNull { it.key == key }
    }
}

data class Place(
    val id: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double = DEFAULT_PLACE_RADIUS_M,
    val label: String? = null,
    val category: PlaceCategory? = null,
)

const val DEFAULT_PLACE_RADIUS_M = 150.0

data class PlaceAssignment(val placeIds: List<String>, val created: List<Place>)

// Each stay's place id, in order, and the places created on the way (a later stay in the same batch can join a place an
// earlier one created).
fun assignStaysToPlaces(stays: List<Stay>, known: List<Place>, newId: () -> String): PlaceAssignment {
    val places = known.toMutableList()
    val created = mutableListOf<Place>()
    val ids = stays.map { s ->
        val nearest = places
            .map { it to haversineM(s.lat, s.lon, it.lat, it.lon) }
            .filter { (p, d) -> d <= p.radiusM }
            .minByOrNull { it.second }
            ?.first
        nearest?.id ?: Place(newId(), s.lat, s.lon).also { places += it; created += it }.id
    }
    return PlaceAssignment(ids, created)
}

data class PlaceVisitTime(val placeId: String, val startMs: Long, val endMs: Long)

// The place where the most weekday time between 09:00 and 18:00 was spent, if that is at least minHours over the visits
// given (two weeks of visits in practice) and it is not home. A suggestion only: the person confirms it.
fun suggestWorkPlace(visits: List<PlaceVisitTime>, homeIds: Set<String>, zone: ZoneId, minHours: Double = 15.0): String? {
    val byPlace = mutableMapOf<String, Long>()
    for (v in visits) {
        if (v.placeId in homeIds) continue
        var day = Instant.ofEpochMilli(v.startMs).atZone(zone).toLocalDate()
        val lastDay = Instant.ofEpochMilli(v.endMs).atZone(zone).toLocalDate()
        while (!day.isAfter(lastDay)) {
            if (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY) {
                val from = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                val to = day.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
                val overlap = (minOf(v.endMs, to) - maxOf(v.startMs, from)).coerceAtLeast(0)
                byPlace[v.placeId] = (byPlace[v.placeId] ?: 0L) + overlap
            }
            day = day.plusDays(1)
        }
    }
    val best = byPlace.maxByOrNull { it.value } ?: return null
    return best.key.takeIf { best.value / 3_600_000.0 >= minHours }
}

// OpenStreetMap's class and type of the nearest feature (Nominatim reverse: "amenity"/"restaurant") to a category.
// Unknown or ambiguous tags give null: no suggestion is better than a wrong one.
fun categoryFromOsm(osmClass: String?, osmType: String?): PlaceCategory? {
    val c = osmClass?.lowercase() ?: return null
    val t = osmType?.lowercase().orEmpty()
    return when {
        c == "amenity" && t in setOf("restaurant", "cafe", "fast_food", "food_court", "bar", "pub", "ice_cream") -> PlaceCategory.FOOD
        c == "amenity" && t in setOf("hospital", "clinic", "doctors", "dentist", "pharmacy") -> PlaceCategory.HEALTH
        c == "healthcare" -> PlaceCategory.HEALTH
        c == "amenity" && t in setOf("bus_station", "ferry_terminal", "taxi", "bicycle_rental") -> PlaceCategory.TRANSIT
        c == "railway" || c == "public_transport" -> PlaceCategory.TRANSIT
        c == "leisure" && t in setOf("fitness_centre", "sports_centre", "stadium", "pitch", "swimming_pool", "track") -> PlaceCategory.EXERCISE
        c == "leisure" && t in setOf("park", "nature_reserve", "garden") -> PlaceCategory.OUTDOOR
        c == "natural" || (c == "highway" && t in setOf("path", "footway", "track")) -> PlaceCategory.OUTDOOR
        c == "shop" -> PlaceCategory.SHOP
        c == "office" -> PlaceCategory.WORK
        c == "amenity" && t in setOf("cinema", "theatre", "community_centre", "events_venue", "nightclub") -> PlaceCategory.SOCIAL
        else -> null
    }
}
