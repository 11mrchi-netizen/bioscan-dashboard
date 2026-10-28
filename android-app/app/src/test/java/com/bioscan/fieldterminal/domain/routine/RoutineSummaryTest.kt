package com.bioscan.fieldterminal.domain.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

// Routine intelligence R5: what Lab › ROUTINE, Today and the Log draw, and the commute alternatives.
class RoutineSummaryTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val d = LocalDate.of(2026, 10, 20)
    private fun t(h: Int, m: Int = 0, day: LocalDate = d) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun theWeekStripGroupsCategoriesAndAMissingDayIsUnknown() {
        val strip = weekStrip(
            mapOf(d to (mapOf("home" to 800.0, "work" to 500.0, "transit" to 80.0, "food" to 40.0, "unlabelled" to 20.0) to 100)),
            yesterday = d, days = 2,
        )
        assertEquals(listOf(d.minusDays(1), d), strip.map { it.date })
        assertEquals(mapOf(StripGroup.UNKNOWN to 1440.0), strip[0].minutes)
        assertEquals(60.0, strip[1].minutes[StripGroup.OTHER]!!, 1e-9)
        assertEquals(800.0, strip[1].minutes[StripGroup.HOME]!!, 1e-9)
    }

    @Test
    fun commutesSplitByDirection() {
        fun trip(from: String, to: String, min: Long, active: Double) =
            Trip(0, min * 60_000, from, to, 5.0, listOf(TripLeg("walking", active), TripLeg("in_vehicle", min - active)))
        val s = commuteSummary(listOf(trip("home", "work", 40, 10.0), trip("home", "work", 50, 12.0), trip("home", "work", 44, 8.0), trip("work", "home", 60, 5.0)), setOf("home"))
        val toWork = s.toWork!!
        assertEquals(3, toWork.n)
        assertEquals(44.0, toWork.medianMin, 1e-9)
        assertEquals(40.0, toWork.minMin, 1e-9)
        assertEquals(10.0, toWork.medianActiveMin, 1e-9)
        assertEquals(60.0, s.toHome!!.medianMin, 1e-9)
    }

    @Test
    fun phoneWeekAveragesOnlyWhatWasMeasured() {
        val w = phoneWeek(listOf(PhoneDaySlice(d, 200.0, 20.0, 0), PhoneDaySlice(d.plusDays(1), 100.0, null, 2), PhoneDaySlice(d.plusDays(2), 150.0, 40.0, null)))!!
        assertEquals(150.0, w.meanScreenMin, 1e-9)
        assertEquals(30.0, w.meanBedWindowMin!!, 1e-9)
        assertEquals(2, w.nightsMeasured)
        assertEquals(1, w.nightsUnlocked)
        assertNull(phoneWeek(emptyList()))
    }

    @Test
    fun aGymVisitWithoutASessionIsUnlogged() {
        val gym = PlacedVisit(t(18), t(19, 15), "gym", "Gym", PlaceCategory.EXERCISE)
        val logged = PlacedVisit(t(7), t(8, 10), "gym", "Gym", PlaceCategory.EXERCISE)
        val café = PlacedVisit(t(12), t(13), "cafe", "Café", PlaceCategory.FOOD)
        val short = PlacedVisit(t(20), t(20, 20), "gym", "Gym", PlaceCategory.EXERCISE)
        val out = unloggedExerciseVisits(listOf(gym, logged, café, short), sessionStartsMs = listOf(t(7, 5)), nowMs = t(23))
        assertEquals(listOf(gym), out)
        // An open visit (still there) is not listed yet.
        assertTrue(unloggedExerciseVisits(listOf(gym), emptyList(), nowMs = t(19)).isEmpty())
    }

    @Test
    fun todaysLinesStateFactsAndLeaveOutWhatIsUnknown() {
        val lines = routineLines(RoutineYesterday(d, 92, 780.0, 540.0, 75.0, 42.0, 38.0, 25.0))
        assertEquals(listOf("Home 13.0 h · work 9.0 h · on the move 1.3 h", "Commute 42 min, usual 38 min", "Phone in the hour before sleep: 25 min"), lines)
        assertEquals(emptyList<String>(), routineLines(RoutineYesterday(d, 30, 780.0, null, null, null, null, null)))
    }

    // A straight line of six stations 0.006° (about 670 m) apart going north, two minutes between each, and a second line
    // crossing at S2 with its own station id there.
    private val line = (0..5).map { i -> MetroStation("L$i", "S$i", 25.00 + 0.006 * i, 121.50) }
    private val cross = listOf(MetroStation("X0", "S2 (other line)", 25.012, 121.50), MetroStation("X1", "East", 25.012, 121.52))
    private val hops = line.zipWithNext().map { (a, b) -> MetroHop(a.id, b.id, 120) } + MetroHop("X0", "X1", 150)
    private val home = 25.0005 to 121.5005 // next to S0
    private val work = 25.0305 to 121.5005 // next to S5

    @Test
    fun rideTimesFollowTheLineAndTransfersCostAWalk() {
        val ride = rideMinutes(line + cross, hops, "L0")
        assertEquals(10.0, ride["L5"]!!, 1e-9)
        assertEquals(4.0 + 4.0 + 2.5, ride["X1"]!!, 1e-9) // two hops, the interchange walk, one hop on the other line
    }

    @Test
    fun theMetroOptionWalksRidesAndWalks() {
        val options = commuteOptions(home, work, line + cross, hops, docks = emptyList())
        val metro = options.first { it.kind == OptionKind.METRO }
        assertEquals(listOf("S0", "S5"), metro.via)
        assertTrue("short walks at both ends: ${metro.activeMinutes}", metro.activeMinutes < 3)
        assertEquals(PLATFORM_WAIT_MIN + 10.0 + metro.activeMinutes, metro.minutes, 1e-9)
        // A farther origin (S1) adds walking for under 8 minutes more.
        val longer = options.first { it.kind == OptionKind.METRO_LONGER_WALK }
        assertEquals("S1", longer.via.first())
        assertTrue(longer.activeMinutes > metro.activeMinutes + 2 && longer.minutes <= metro.minutes + 8)
        // About 4.3 km by street: too far to walk, close enough to cycle (but no docks given).
        assertTrue(options.none { it.kind == OptionKind.WALK || it.kind == OptionKind.BIKE })
    }

    @Test
    fun youBikeOptionsNeedDocksWithBikesAndRoom() {
        val docks = listOf(
            BikeDock("home", 25.0006, 121.5006, rentable = 3, returnable = 5),
            BikeDock("s2", 25.0121, 121.5001, rentable = 4, returnable = 2),
            BikeDock("work", 25.0304, 121.5004, rentable = 0, returnable = 6),
        )
        val options = commuteOptions(home, work, line + cross, hops, docks)
        val bike = options.first { it.kind == OptionKind.BIKE }
        assertEquals(bike.minutes, bike.activeMinutes, 1e-9)
        val metroBike = options.first { it.kind == OptionKind.METRO_BIKE }
        assertEquals("S2", metroBike.via.last()) // the only station with a dock, about 2.7 km from work by street
        // No room at the work dock: no YouBike options.
        val full = docks.map { if (it.id == "work") it.copy(returnable = 0) else it }
        assertTrue(commuteOptions(home, work, line + cross, hops, full).none { it.kind == OptionKind.BIKE || it.kind == OptionKind.METRO_BIKE })
    }
}
