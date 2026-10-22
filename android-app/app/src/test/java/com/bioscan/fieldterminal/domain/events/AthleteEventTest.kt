package com.bioscan.fieldterminal.domain.events

import com.bioscan.fieldterminal.domain.trail.CoursePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class AthleteEventTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val day = LocalDate.of(2026, 11, 8)
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(day.atTime(h, m), zone)
    private val race = AthleteEvent(
        name = "Synthetic Half", kind = EventKind.RACE, sport = EventSport.RUN, date = day, start = at(9, 30), targetTimeMin = 100.0,
    )

    @Test
    fun aValidEventHasNoProblems() {
        assertEquals(emptyList<String>(), validateEvent(race))
    }

    @Test
    fun anEventNeedsANameAndADuration() {
        val problems = validateEvent(race.copy(name = " ", targetTimeMin = null))
        assertTrue(problems.any { "name" in it })
        assertTrue(problems.any { "target time" in it })
    }

    @Test
    fun theTargetTimeWinsOverTheExpectedDuration() {
        assertEquals(100.0, race.copy(expectedDurationMin = 120.0).plannedDurationMin!!, 0.0)
        assertEquals(120.0, race.copy(targetTimeMin = null, expectedDurationMin = 120.0).plannedDurationMin!!, 0.0)
    }

    @Test
    fun aidStationsMustLieOnTheCourse() {
        val withCourse = race.copy(distanceKm = null, course = listOf(CoursePoint(0.0, 100.0), CoursePoint(21100.0, 120.0)))
        assertEquals(emptyList<String>(), validateEvent(withCourse.copy(aidStations = listOf(AidStation("Km 10", 10.0)))))
        assertTrue(validateEvent(withCourse.copy(aidStations = listOf(AidStation("Far", 30.0)))).isNotEmpty())
    }

    @Test
    fun aStartOnAnotherDayIsRejected() {
        assertTrue(validateEvent(race.copy(start = at(9).plusDays(1))).isNotEmpty())
    }

    @Test
    fun theSessionClosestToTheStartIsTheEvent() {
        val sessions = listOf(
            EventSessionCandidate(1, "run", at(8, 40), 15.0), // warm-up
            EventSessionCandidate(2, "run", at(9, 31), 98.0),
            EventSessionCandidate(3, "walk", at(9, 30), 100.0), // wrong type
        )
        assertEquals(2L, matchEventToSession(race, sessions)!!.id)
    }

    @Test
    fun nothingWithinTwoHoursOrOnTheDayMeansNoMatch() {
        assertNull(matchEventToSession(race, listOf(EventSessionCandidate(1, "run", at(15), 100.0))))
        assertNull(matchEventToSession(race, listOf(EventSessionCandidate(1, "run", at(9, 30).plusDays(1), 100.0))))
    }

    @Test
    fun withoutAStartTimeTheLongestSessionOfTheDayIsTheEvent() {
        val hike = race.copy(kind = EventKind.HIKE_DAY, sport = EventSport.HIKE, start = null)
        val sessions = listOf(EventSessionCandidate(1, "walk", at(8), 30.0), EventSessionCandidate(2, "hike", at(10), 300.0))
        assertEquals(2L, matchEventToSession(hike, sessions)!!.id)
    }

    @Test
    fun raceTitlesAreRecognisedAndTrainingTitlesAreNot() {
        assertTrue(looksLikeRace("Mezza maratona di Esempio"))
        assertTrue(looksLikeRace("🏁 Synthetic 10K"))
        assertTrue(looksLikeRace("Skyrace del Monte"))
        assertFalse(looksLikeRace("Trail run 🏔️"))
        assertFalse(looksLikeRace("Easy run"))
        assertFalse(looksLikeRace("Grace's birthday")) // "race" inside a word
        assertFalse(looksLikeRace(null))
    }

    @Test
    fun upcomingOpenEventsComeFirst() {
        val past = race.copy(name = "Past", date = day.minusDays(30), status = EventStatus.DONE)
        val later = race.copy(name = "Later", date = day.plusDays(30))
        assertEquals(listOf("Synthetic Half", "Later", "Past"), sortEvents(listOf(past, later, race), day.minusDays(1)).map { it.name })
    }

    @Test
    fun durationsReadInTheUsualWays() {
        assertEquals(105.0, parseHoursMinutes("1:45")!!, 0.0)
        assertEquals(105.0, parseHoursMinutes("1h45")!!, 0.0)
        assertEquals(120.0, parseHoursMinutes("2h")!!, 0.0)
        assertEquals(95.0, parseHoursMinutes("95")!!, 0.0)
        assertEquals(90.5, parseHoursMinutes("1:30:30")!!, 0.001)
        assertNull(parseHoursMinutes(""))
        assertNull(parseHoursMinutes("soon"))
        assertEquals("1:45", formatHoursMinutes(105.0))
        assertEquals("12:05", formatHoursMinutes(725.2))
    }

    @Test
    fun theNextEventIsTheSoonestOpenOneNotYetStarted() {
        val now = at(10)
        val started = race.copy(name = "Started", start = at(9, 30))
        val later = race.copy(name = "Later", date = day.plusDays(3), start = null)
        val far = race.copy(name = "Far", date = day.plusDays(30))
        val dns = race.copy(name = "DNS", date = day.plusDays(1), status = EventStatus.DNS)
        assertEquals("Later", nextUpcomingEvent(listOf(started, later, far, dns), now)!!.name)
        assertNull(nextUpcomingEvent(listOf(far), now))
    }

    @Test
    fun anEventTakesNextUpWhenItComesFirstOrTheCalendarIsEmpty() {
        val now = at(7)
        assertTrue(eventTakesNextUp(race, null, now))
        assertTrue(eventTakesNextUp(race, at(12), now)) // race 09:30 before a 12:00 calendar entry
        assertFalse(eventTakesNextUp(race, at(8), now)) // the 08:00 entry comes first
        assertFalse(eventTakesNextUp(race.copy(date = day.plusDays(5), start = null), at(12), now)) // days away, calendar has today
        assertFalse(eventTakesNextUp(null, at(12), now))
    }
}
