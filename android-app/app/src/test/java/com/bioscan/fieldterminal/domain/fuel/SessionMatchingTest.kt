package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SessionMatchingTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val today = LocalDate.of(2026, 11, 10)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(d.atTime(h, m), zone)
    private val history = DemandHistory(weightKg = 70.0, restingHr = 50.0, maxHr = 190.0)

    private fun cal(id: String, d: LocalDate, title: String) = CalendarSessionInput(id, title, at(d, 7), at(d, 8), "8")
    private fun rec(id: Long, d: LocalDate, h: Int, m: Int, min: Double, km: Double?, type: String = "run") =
        RecordedSessionInput(id, type, at(d, h, m), min, caloriesActive = min * 10, distanceKm = km)

    private fun resolve(
        calendar: List<CalendarSessionInput>,
        recorded: List<RecordedSessionInput>,
        links: Map<Long, StoredLink> = emptyMap(),
        from: LocalDate = today.minusDays(2),
        days: Int = 4,
    ) = resolvePlannedDays(from, days, emptyList(), emptyList(), calendar, recorded, history, at(today, 21), links = links)

    @Test
    fun aShorterLaterRunIsStillTheDaysPlan() {
        // 10 November: 19.3 km planned at 07:00, 13 km run at 07:55.
        val day = resolve(listOf(cal("a", today, "🏃 LSS — 19.3km (Dajia Riverside)")), listOf(rec(1, today, 7, 55, 76.0, 13.0)))[2]
        val item = day.items.single()
        assertTrue(item.recorded)
        assertEquals("calendar:a", item.planRef)
        assertTrue(item.matchConfidence!! >= LOW_MATCH_CONFIDENCE)
        assertFalse(item.matchUncertain)
    }

    @Test
    fun theLongPlanGoesToTheDaysLongestRecording() {
        val d = today.minusDays(1)
        val cal = listOf(cal("long", d, "🏃 Long Run — 27.4km"))
        val recs = listOf(rec(1, d, 7, 10, 25.0, 4.0), rec(2, d, 9, 46, 322.0, 30.2))
        val items = resolve(cal, recs)[1].items
        assertEquals("calendar:long", items.first { it.sourceId == "session:2" }.planRef)
        assertNull(items.first { it.sourceId == "session:1" }.planRef)
    }

    @Test
    fun anotherSportIsNeverTakenForTheRun() {
        val d = today.minusDays(1)
        val items = resolve(listOf(cal("r", d, "🏃 LSS — 9.7km")), listOf(rec(3, d, 17, 0, 104.0, 3.3, type = "ride")))[1].items
        assertNull(items.single().planRef)
    }

    @Test
    fun theAnswerWinsAndCanReachTheDayBefore() {
        // The Saturday long run done on Sunday: linked by hand, it leaves Saturday.
        val sat = today.minusDays(2)
        val sun = today.minusDays(1)
        val cal = listOf(cal("sat", sat, "🏃 Long Run — 25km"), cal("sun", sun, "💪 Strength-Endurance — 60%"))
        val recs = listOf(rec(5, sun, 8, 0, 240.0, 25.5))
        val link = StoredLink(5, "calendar:sat", manual = true)
        val days = resolve(cal, recs, mapOf(5L to link))
        val run = days[1].items.single { it.recorded }
        assertEquals("calendar:sat", run.planRef)
        assertTrue(run.matchManual)
        assertTrue(days[0].items.isEmpty())
        // The day's own plans stay offered for a change of mind.
        assertEquals(listOf("calendar:sun"), days[1].plans.map { it.sourceId })
    }

    @Test
    fun notPlannedIsKeptAsAnAnswer() {
        val d = today.minusDays(1)
        val days = resolve(listOf(cal("r", d, "🏃 LSS — 8.0km")), listOf(rec(6, d, 7, 30, 50.0, 8.0)), mapOf(6L to StoredLink(6, null, manual = true)))
        val item = days[1].items.single()
        assertNull(item.planRef)
        assertTrue(item.matchManual)
    }

    @Test
    fun anAutomaticLinkToAPlanThatIsGoneIsScoredAgain() {
        val d = today.minusDays(1)
        val days = resolve(listOf(cal("new", d, "🏃 LSS — 8.0km")), listOf(rec(7, d, 7, 30, 50.0, 8.0)), mapOf(7L to StoredLink(7, "calendar:old", manual = false)))
        assertEquals("calendar:new", days[1].items.single().planRef)
    }

    @Test
    fun twoPlansTwoRecordingsPairByCostNotByOrder() {
        val d = today.minusDays(1)
        val cal = listOf(
            CalendarSessionInput("easy", "🏃 Easy — 5km", at(d, 7), at(d, 8), "8"),
            CalendarSessionInput("long", "🏃 Long Run — 21km", at(d, 16), at(d, 17), "8"),
        )
        // The long run done in the morning, the easy one in the afternoon.
        val recs = listOf(rec(8, d, 7, 30, 130.0, 21.3), rec(9, d, 17, 0, 32.0, 5.1))
        val items = resolve(cal, recs)[1].items
        assertEquals("calendar:long", items.first { it.sourceId == "session:8" }.planRef)
        assertEquals("calendar:easy", items.first { it.sourceId == "session:9" }.planRef)
    }

    @Test
    fun costGrowsWithDistanceAndHours() {
        val plan = PlannedItem(PlanSource.CALENDAR, "calendar:x", "Run", "run", at(today, 7), 60.0, Intensity.EASY, "", 500.0, distanceKm = 10.0)
        val close = matchCost(plan, rec(1, today, 7, 10, 60.0, 10.0), zone, longest = true)!!
        val far = matchCost(plan, rec(1, today, 12, 0, 60.0, 5.0), zone, longest = true)!!
        assertTrue(far > close)
        assertNull(matchCost(plan, rec(1, today, 7, 0, 60.0, null, type = "strength"), zone, longest = true))
    }
}
