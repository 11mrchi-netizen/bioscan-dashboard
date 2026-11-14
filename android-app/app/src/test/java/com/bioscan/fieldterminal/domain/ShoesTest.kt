package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ShoesTest {
    private val d = LocalDate.of(2026, 11, 1)
    private val road = Shoe(1, "Road pair", zeppGearId = "AAA", startKm = 50.0, retireKm = 200.0)
    private val trail = Shoe(2, "Trail pair", use = "trail", zeppGearId = "BBB")
    private val shoes = listOf(road, trail)

    private fun run(id: Long, km: Double, gear: String? = null, chosen: Long? = null, day: Int = 0) =
        ShoeRun(id, d.plusDays(day.toLong()), km, gearId = gear, chosenShoeId = chosen)

    @Test
    fun theOwnChoiceWinsThenTheCodeThenUnknown() {
        assertEquals(ShoeAssignment.Chosen(trail), assignShoe(run(1, 5.0, gear = "AAA", chosen = 2), shoes))
        assertEquals(ShoeAssignment.Matched(road), assignShoe(run(2, 5.0, gear = "AAA"), shoes))
        assertEquals(ShoeAssignment.Unknown("ZZZ"), assignShoe(run(3, 5.0, gear = "ZZZ"), shoes))
        assertEquals(ShoeAssignment.None, assignShoe(run(4, 5.0), shoes))
        // a chosen shoe that was deleted falls back to the code
        assertEquals(ShoeAssignment.Matched(road), assignShoe(run(5, 5.0, gear = "AAA", chosen = 99), shoes))
    }

    @Test
    fun kmCountTheStartAndWearFollowsTheRetirementDistance() {
        val runs = listOf(run(1, 60.0, "AAA"), run(2, 50.0, "AAA", day = 1), run(3, 20.0, "AAA", chosen = 2, day = 2))
        val s = shoeStats(road, runs, shoes, emptyList())
        assertEquals(110.0, s.runKm, 1e-9)
        assertEquals(160.0, s.totalKm, 1e-9)
        assertEquals(ShoeWear.WORN, s.wear)
        assertEquals(1L, s.longest!!.sessionId) // the 60 km run
        assertEquals(ShoeWear.DUE, wearOf(200.0, 200.0))
        assertEquals(ShoeWear.FRESH, wearOf(10.0, 200.0))
    }

    @Test
    fun unknownCodesAreGroupedForMatching() {
        val runs = listOf(run(1, 10.0, "ZZZ"), run(2, 5.0, "ZZZ", day = 3), run(3, 8.0, "AAA"))
        val u = unknownGear(runs, shoes).single()
        assertEquals("ZZZ", u.gearId)
        assertEquals(2, u.runs)
        assertEquals(15.0, u.km, 1e-9)
        assertEquals(d.plusDays(3), u.last)
    }

    @Test
    fun aRecordIsARunThatBeatEveryRunBeforeIt() {
        fun r(id: Long, day: Int, km: Double, pace: Double) = RecordInput(id, d.plusDays(day.toLong()), km, pace, false, null, null)
        val events = recordEvents(listOf(r(1, 0, 10.0, 6.0), r(2, 1, 12.0, 5.5), r(3, 2, 8.0, 5.8), r(4, 3, 15.0, 5.2)))
        val longest = events.filter { it.label == "Longest road run" }
        assertEquals(listOf(2L, 4L), longest.map { it.sessionId })
        assertEquals(10.0, longest.first().previous, 1e-9)
        assertTrue(longest.last().standing)
        val fastest = events.filter { it.label.startsWith("Fastest road") }
        assertEquals(listOf(2L, 4L), fastest.map { it.sessionId })
        // the records land on the shoes the runs were in
        val runs = listOf(run(2, 12.0, "AAA"), run(4, 15.0, "BBB"))
        assertEquals(2, shoeStats(road, runs, shoes, events).records.size)
        assertEquals(listOf(true, true), shoeStats(trail, runs, shoes, events).records.map { it.standing })
    }
}
