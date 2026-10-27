package com.bioscan.fieldterminal.domain.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Routine intelligence R3: places from visits, trips between them, and a day of mobility.
class PlacesTripsMobilityTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val home = 25.0330 to 121.5654
    private val office = 25.0478 to 121.5170
    private val d = LocalDate.of(2026, 10, 20) // a Tuesday

    private fun t(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun stay(p: Pair<Double, Double>, northM: Double = 0.0) = Stay(0, 1, p.first + northM / 111_000.0, p.second, 2, 1.0, false)

    @Test
    fun staysJoinTheNearestPlaceOrStartANewOne() {
        var n = 0
        val known = listOf(Place("home", home.first, home.second, label = "Home", category = PlaceCategory.HOME))
        val result = assignStaysToPlaces(
            listOf(stay(home, 60.0), stay(office), stay(office, 80.0), stay(home, 400.0)),
            known,
        ) { "new${n++}" }
        assertEquals(listOf("home", "new0", "new0", "new1"), result.placeIds)
        assertEquals(2, result.created.size)
    }

    @Test
    fun workIsSuggestedFromWeekdayOfficeHoursOnly() {
        val week = (0L..4L).map { d.minusDays(1).plusDays(it) } // Mon..Fri
        val visits = week.map { PlaceVisitTime("office", t(it, 8, 30), t(it, 18, 30)) } +
            PlaceVisitTime("gym", t(d.plusDays(4), 9), t(d.plusDays(4), 12)) + // Saturday: not counted
            week.map { PlaceVisitTime("home", t(it, 19), t(it, 23)) }
        assertEquals("office", suggestWorkPlace(visits, homeIds = setOf("home"), zone = zone))
        // One day of 9 h is not enough evidence.
        assertNull(suggestWorkPlace(visits.take(1), setOf("home"), zone))
    }

    @Test
    fun osmTagsMapToCategoriesAndUnknownTagsGiveNothing() {
        assertEquals(PlaceCategory.FOOD, categoryFromOsm("amenity", "cafe"))
        assertEquals(PlaceCategory.EXERCISE, categoryFromOsm("leisure", "fitness_centre"))
        assertEquals(PlaceCategory.TRANSIT, categoryFromOsm("railway", "station"))
        assertNull(categoryFromOsm("building", "yes"))
        assertNull(categoryFromOsm(null, null))
    }

    private val homeVisit = TripVisit(t(d, 0), t(d, 8), home.first, home.second, "home")
    private val officeVisit = TripVisit(t(d, 8, 40), t(d, 18), office.first, office.second, "office")

    @Test
    fun aTripHasLegsFromTransitionsAndActiveMinutes() {
        val transitions = listOf(
            TransitionEvent(t(d, 7, 58), "walking", true), // just before leaving: how the trip began
            TransitionEvent(t(d, 8, 10), "in_vehicle", true),
            TransitionEvent(t(d, 8, 30), "walking", true),
            TransitionEvent(t(d, 8, 35), "walking", true), // a repeat: still one walking leg
        )
        val trip = tripsBetween(listOf(officeVisit, homeVisit), transitions, emptyList()).single()
        assertEquals(40.0, trip.minutes, 1e-9)
        assertEquals(listOf(TripLeg("walking", 10.0), TripLeg("in_vehicle", 20.0), TripLeg("walking", 10.0)), trip.legs)
        assertEquals(20.0, trip.activeMinutes, 1e-9)
        // No fixes: the straight line between the two places, about 5 km.
        assertTrue(trip.distanceKm in 4.5..5.5)
        val categories = mapOf("home" to PlaceCategory.HOME, "office" to PlaceCategory.WORK)
        assertTrue(isCommute(trip) { categories[it] })
    }

    @Test
    fun withoutTransitionsATripIsOneUnknownLegAndLongGapsAreNotTrips() {
        val trip = tripsBetween(listOf(homeVisit, officeVisit), emptyList(), emptyList()).single()
        assertEquals(listOf(TripLeg(UNKNOWN_MODE, 40.0)), trip.legs)
        val nextDay = TripVisit(t(d.plusDays(1), 9), t(d.plusDays(1), 10), home.first, home.second, "home")
        assertTrue(tripsBetween(listOf(officeVisit, nextDay), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun sunriseAndSunsetAreRealistic() {
        // At the equator on the March equinox the day is a little over 12 h (refraction), centred near 12:07 UTC.
        val (rise, set) = sunTimes(LocalDate.of(2026, 3, 20), 0.0, 0.0)!!
        val length = Duration.between(rise, set).toMinutes()
        assertTrue("equinox day $length min", length in 720..735)
        // Taipei in late October: roughly 11 h 20 min of daylight, sunrise around 05:55 local.
        val (tRise, tSet) = sunTimes(d, home.first, home.second)!!
        assertTrue(Duration.between(tRise, tSet).toMinutes() in 670L..700L)
        assertEquals(5, tRise.atZone(zone).hour)
        assertNull(sunTimes(LocalDate.of(2026, 12, 21), 80.0, 0.0)) // polar night
    }

    @Test
    fun aWorkdayOfMobility() {
        val categories = mapOf("home" to PlaceCategory.HOME, "office" to PlaceCategory.WORK)
        fun visit(v: TripVisit) = DayVisit(v.startMs, v.endMs, v.lat, v.lon, v.placeId, categories[v.placeId])
        val evening = TripVisit(t(d, 18, 40), t(d.plusDays(1), 0), home.first, home.second, "home")
        val visits = listOf(homeVisit, officeVisit, evening)
        val transitions = listOf(TransitionEvent(t(d, 8), "walking", true), TransitionEvent(t(d, 18), "walking", true))
        val trips = tripsBetween(visits, transitions, emptyList())
        val day = mobilityDay(
            date = d, zone = zone, visits = visits.map(::visit), trips = trips, home = home,
            placeFirstSeen = mapOf("office" to Instant.ofEpochMilli(t(d.minusDays(2), 9)), "home" to Instant.ofEpochMilli(t(d.minusDays(60), 9))),
            previousSameWeekdays = listOf(hourlyPlaceVector(d.minusDays(7), zone, visits.map(::visit).map { it.copy(startMs = it.startMs - 7 * 86_400_000L, endMs = it.endMs - 7 * 86_400_000L) })),
        )
        assertEquals(100, day.coveragePct)
        assertEquals(DayType.WORKDAY, day.dayType)
        assertEquals(2, day.nPlaces)
        assertEquals(1, day.newPlaces)
        assertEquals(560.0, day.minutesByCategory["work"]!!, 1e-9)
        assertEquals(80.0, day.minutesByCategory["transit"]!!, 1e-9)
        assertEquals(560.0 + 80.0, day.timeFromHomeMin, 1e-9)
        // The 08:00 walk is in daylight; the 18:00 walk home starts after sunset (about 17:20 in late October).
        assertEquals(40.0, day.outdoorDaylightMin, 1e-9)
        assertEquals(1.0, day.routineSimilarity!!, 1e-9)
        assertTrue(day.entropyNorm!! in 0.9..1.0) // nearly even split of time between two places
        assertTrue(day.radiusGyrationKm!! in 2.0..3.0)
    }

    @Test
    fun aDayWithLittleDataIsUnknownNotHome() {
        val day = mobilityDay(d, zone, listOf(DayVisit(t(d, 8), t(d, 12), home.first, home.second, "home", PlaceCategory.HOME)), emptyList(), home, emptyMap(), emptyList())
        assertEquals(16, day.coveragePct)
        assertEquals(DayType.UNKNOWN, day.dayType)
        assertEquals(1200.0, day.minutesByCategory["unknown"]!!, 1e-9)
    }
}
