package com.bioscan.fieldterminal.domain.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

// Day map and commute analysis: the day's timeline, door-to-door commutes through short stops, their spread, and the
// helpers behind marking and merging places.
class DayTimelineTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val d = LocalDate.of(2026, 11, 3) // a Tuesday
    private fun t(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun t(h: Int, m: Int = 0) = t(d, h, m)

    private val categories = mapOf("home" to PlaceCategory.HOME, "work" to PlaceCategory.WORK, "cafe" to PlaceCategory.FOOD)
    private val categoryOf: (String?) -> PlaceCategory? = { categories[it] }

    private fun trip(from: String?, to: String?, s: Long, e: Long, legs: List<TripLeg> = listOf(TripLeg("in_vehicle", (e - s) / 60_000.0))) =
        Trip(s, e, from, to, 5.0, legs)

    @Test
    fun timelineClipsToTheDayAndMarksGaps() {
        val visits = listOf(
            TimelineVisit(t(d.minusDays(1), 21), t(8, 30), 0.0, 0.0, "home"),
            TimelineVisit(t(9, 20), t(12, 0), 0.0, 0.0, "work"),
            TimelineVisit(t(22, 0), t(d.plusDays(1), 7), 0.0, 0.0, "home"),
        )
        val trips = listOf(trip("home", "work", t(8, 30), t(9, 20)))
        val items = dayTimeline(d, zone, visits, trips, nowMs = t(d.plusDays(2), 0), isCommute = { true })
        assertEquals(5, items.size)
        val first = items[0] as TimelineItem.Visit
        assertEquals(t(0, 0), first.startMs)
        assertTrue(first.startsBefore)
        assertTrue((items[1] as TimelineItem.Move).commute)
        assertEquals(t(12, 0), (items[3] as TimelineItem.Gap).startMs)
        assertEquals(t(22, 0), (items[3] as TimelineItem.Gap).endMs)
        assertTrue((items[4] as TimelineItem.Visit).endsAfter)
        assertEquals(14.0 / 24.0, timelineCoverage(items), 1e-9)
    }

    @Test
    fun todayEndsNow() {
        val visits = listOf(TimelineVisit(t(0, 0), t(9, 0), 0.0, 0.0, "home"))
        val items = dayTimeline(d, zone, visits, emptyList(), nowMs = t(9, 3))
        assertEquals(1, items.size) // 3 minutes to now is under the gap threshold
    }

    @Test
    fun aShortStopKeepsTheCommuteADinnerEndsIt() {
        val trips = listOf(
            // Morning: home, a 10-minute coffee, work.
            trip("home", "cafe", t(8, 40), t(8, 50)),
            trip("cafe", "work", t(9, 0), t(9, 35)),
            // Evening: work, an hour at dinner, home. Two outings, not a commute.
            trip("work", "cafe", t(19, 0), t(19, 20)),
            trip("cafe", "home", t(20, 20), t(20, 50)),
        )
        val js = commuteJourneys(trips, categoryOf)
        assertEquals(1, js.size)
        val j = js[0]
        assertEquals(CommuteWay.TO_WORK, j.way)
        assertEquals(55.0, j.minutes, 1e-9)
        assertEquals(listOf("cafe" to 10.0), j.stops)
        assertEquals(10.0, j.byMode[ModeGroup.STOP]!!, 1e-9)
        assertEquals(45.0, j.byMode[ModeGroup.VEHICLE]!!, 1e-9)
    }

    @Test
    fun directTripsAndLoopsHomeAreHandled() {
        val trips = listOf(
            trip("home", "home", t(7, 0), t(8, 0)), // a run from home: not a commute
            trip("home", "work", t(8, 30), t(9, 20), listOf(TripLeg("walking", 10.0), TripLeg("still", 5.0), TripLeg("in_vehicle", 35.0))),
            trip("work", "home", t(18, 0), t(18, 55)),
        )
        val js = commuteJourneys(trips, categoryOf)
        assertEquals(listOf(CommuteWay.TO_WORK, CommuteWay.TO_HOME), js.map { it.way })
        assertEquals(10.0, js[0].byMode[ModeGroup.ON_FOOT]!!, 1e-9)
        assertEquals(5.0, js[0].byMode[ModeGroup.WAITING]!!, 1e-9)
        assertTrue(js[0].stops.isEmpty())
    }

    @Test
    fun withoutAHomePlaceNothingIsACommute() {
        val trips = listOf(trip("home", "work", t(8, 30), t(9, 20)))
        assertTrue(commuteJourneys(trips, categoryOf = { id -> if (id == "home") PlaceCategory.WORK else categories[id] }).isEmpty())
    }

    @Test
    fun statsSplitByDepartureOnlyWithEnoughJourneys() {
        val days = (0L..7L).map { d.plusDays(it) }
        // Leaving at 08:30 takes 45 min, at 09:30 takes 60.
        val trips = days.mapIndexed { i, day ->
            val h = if (i % 2 == 0) 8 else 9
            trip("home", "work", t(day, h, 30), t(day, h, 30) + (if (h == 8) 45 else 60) * 60_000L)
        }
        val stats = commuteStats(commuteJourneys(trips, categoryOf), zone)
        val s = stats.getValue(CommuteWay.TO_WORK)
        assertEquals(8, s.n)
        assertEquals(52.5, s.medianMin, 1e-9)
        val split = assertNotNullAndGet(s.byDeparture)
        assertEquals(45.0, split.earlyMedianMin, 1e-9)
        assertEquals(60.0, split.lateMedianMin, 1e-9)
        assertNull(commuteStats(commuteJourneys(trips.take(4), categoryOf), zone).getValue(CommuteWay.TO_WORK).byDeparture)
    }

    private fun <T> assertNotNullAndGet(x: T?): T { assertNotNull(x); return x!! }

    @Test
    fun aMarkedPlaceTakesTheVisitsInsideItsCircleThatAreCloserToIt() {
        val home = Place("home", 25.0, 121.5, label = "Home", category = PlaceCategory.HOME)
        val learned = Place("shop", 25.0 + 300 / 111_000.0, 121.5)
        val gym = Place("gym", 25.0 + 220 / 111_000.0, 121.5, radiusM = 100.0)
        val visits = listOf(
            PlacedPoint(1, 25.0 + 20 / 111_000.0, 121.5, "home"), // well inside home: stays
            PlacedPoint(2, 25.0 + 200 / 111_000.0, 121.5, "home"), // inside the gym, closer to it than to home: moves
            PlacedPoint(3, 25.0 + 250 / 111_000.0, 121.5, "shop"), // inside the gym, closer to it than to the shop: moves
            PlacedPoint(4, 25.0 + 240 / 111_000.0, 121.5, null), // no place yet: moves
            PlacedPoint(5, 25.0 + 400 / 111_000.0, 121.5, "shop"), // outside the gym: stays
        )
        assertEquals(listOf(2L, 3L, 4L), visitsForNewPlace(visits, gym, listOf(home, learned)).map { it.startMs })
    }

    @Test
    fun mergeCandidatesAreNearestFirst() {
        val a = Place("a", 25.0, 121.5)
        val b = Place("b", 25.0 + 500 / 111_000.0, 121.5)
        val c = Place("c", 25.0 + 100 / 111_000.0, 121.5)
        val far = Place("far", 25.1, 121.5)
        assertEquals(listOf("c", "b"), mergeCandidates(a, listOf(a, b, c, far)).map { it.first.id })
    }

    @Test
    fun relabellingFlipsOnlyTheFlagsThatChange() {
        val trips = listOf(
            trip("home", "work", t(8, 30), t(9, 20)) to false, // home was labelled Work: now a commute
            trip("work", "cafe", t(12, 0), t(12, 10)) to false,
            trip("cafe", "home", t(13, 0), t(13, 10)) to true, // stale flag
        )
        val changes = commuteFlagChanges(trips, categoryOf)
        assertEquals(listOf(true, false), changes.map { it.second })
        assertEquals(listOf(t(8, 30), t(13, 0)), changes.map { it.first.startMs })
    }

    @Test
    fun homeWorkProblemNamesTheMislabelledSetupPlace() {
        val setup = Place("s", 25.0, 121.5, label = "Work", category = PlaceCategory.WORK)
        val office = Place("o", 25.1, 121.6, category = PlaceCategory.WORK)
        val msg = homeWorkProblem(listOf(setup, office), homeSettingId = "s")
        assertNotNull(msg)
        assertTrue(msg!!.contains("Setup home location is labelled Work"))
        assertNull(homeWorkProblem(listOf(setup.copy(label = "Home", category = PlaceCategory.HOME), office), "s"))
        assertTrue(homeWorkProblem(listOf(setup.copy(category = PlaceCategory.HOME)), "s")!!.contains("Work"))
    }
}
