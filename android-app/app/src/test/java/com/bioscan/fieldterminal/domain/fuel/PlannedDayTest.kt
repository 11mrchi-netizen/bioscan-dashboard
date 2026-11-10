package com.bioscan.fieldterminal.domain.fuel

import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.events.EventKind
import com.bioscan.fieldterminal.domain.events.EventPriority
import com.bioscan.fieldterminal.domain.events.EventSport
import com.bioscan.fieldterminal.domain.events.EventStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class PlannedDayTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val today = LocalDate.of(2026, 11, 4)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(d.atTime(h, m), zone)
    private val now = at(today, 6)
    private val history = DemandHistory(weightKg = 70.0, restingHr = 50.0, maxHr = 190.0)

    private fun resolve(
        events: List<AthleteEvent> = emptyList(),
        planned: List<PlannedSessionInput> = emptyList(),
        calendar: List<CalendarSessionInput> = emptyList(),
        recorded: List<RecordedSessionInput> = emptyList(),
        from: LocalDate = today,
        days: Int = 3,
        n: ZonedDateTime = now,
    ) = resolvePlannedDays(from, days, events, planned, calendar, recorded, history, n)

    @Test
    fun aDayWithNothingPlannedIsRest() {
        val days = resolve()
        assertEquals(3, days.size)
        assertTrue(days.all { it.isRest })
    }

    @Test
    fun plannedSessionsTakeTheirClassFromTheDomainAndBecomeLongPastNinetyMinutes() {
        val day = resolve(planned = listOf(
            PlannedSessionInput(1, today, LocalTime.of(18, 0), 50.0, "anaerobic_capacity", "Track"),
            PlannedSessionInput(2, today.plusDays(2), LocalTime.of(8, 0), 120.0, "aerobic_base", "Sunday run"),
        ))
        assertEquals(Intensity.HARD, day[0].items.single().intensity)
        assertEquals(Intensity.LONG, day[2].items.single().intensity)
        assertTrue(day[2].items.single().intensityReason.contains("120 min"))
    }

    @Test
    fun calendarTrainingEventsFillInWhenNothingElseCoversThem() {
        val cal = listOf(
            CalendarSessionInput("g1", "🏃 Intervals 6x800", at(today, 18), at(today, 19), "8"),
            CalendarSessionInput("g2", "Dinner", at(today, 20), at(today, 22), "1"), // not training colour
        )
        val item = resolve(calendar = cal)[0].items.single()
        assertEquals(PlanSource.CALENDAR, item.source)
        assertEquals(Intensity.HARD, item.intensity)
        // Timed from the text (6 × 800 m with warm-up, recoveries and cool-down), not the one-hour entry.
        assertTrue(item.durationMin > 60.0)
    }

    @Test
    fun aCalendarEntryPushedFromAPlannedSessionIsNotCountedTwice() {
        val planned = listOf(PlannedSessionInput(1, today, LocalTime.of(18, 0), 60.0, "aerobic_base", "Easy", gcalEventId = "g1"))
        val cal = listOf(CalendarSessionInput("g1", "🏃 Easy", at(today, 18), at(today, 19), "8"))
        assertEquals(1, resolve(planned = planned, calendar = cal)[0].items.size)
    }

    @Test
    fun anEventIsARaceAndWinsOverTheSessionItReplaces() {
        val race = AthleteEvent(
            id = 7, name = "Synthetic 10K", kind = EventKind.RACE, sport = EventSport.RUN, priority = EventPriority.A,
            date = today.plusDays(1), start = at(today.plusDays(1), 9), targetTimeMin = 45.0,
        )
        val planned = listOf(PlannedSessionInput(1, today.plusDays(1), LocalTime.of(9, 30), 40.0, "aerobic_base", "Easy"))
        val day = resolve(events = listOf(race), planned = planned)[1]
        val item = day.items.single()
        assertEquals(Intensity.RACE, item.intensity)
        assertEquals(7L, item.eventId)
    }

    @Test
    fun anUntimedEventAndItsCalendarEntryAreOneSessionAndTheRecordingIsTheEvent() {
        // The 7 November case: an event with no start time and the same run in the calendar were counted as two.
        val long = AthleteEvent(id = 2, name = "Almost 30km", kind = EventKind.LONG_SESSION, sport = EventSport.TRAIL_RUN, priority = EventPriority.C,
            date = today, targetTimeMin = 300.0)
        val cal = listOf(CalendarSessionInput("g9", "🏃 Long Run — 27.4km", at(today, 7), at(today, 12), "8"))
        val planned = resolve(events = listOf(long), calendar = cal)[0]
        assertEquals(1, planned.items.size)
        assertEquals(at(today, 7), planned.items.single().start)
        val run = RecordedSessionInput(21, "run", at(today, 9, 46), 322.0, caloriesActive = 2900.0)
        val done = resolve(events = listOf(long), calendar = cal, recorded = listOf(run), n = at(today, 16))[0]
        val item = done.items.single()
        assertTrue(item.recorded)
        assertEquals(2L, item.eventId)
        assertEquals(2900.0, done.sessionKcal, 0.0)
    }

    @Test
    fun aCancelledEventIsNotPlannedFor() {
        val race = AthleteEvent(id = 7, name = "X", kind = EventKind.RACE, sport = EventSport.RUN, date = today, targetTimeMin = 45.0, status = EventStatus.CANCELLED)
        assertTrue(resolve(events = listOf(race))[0].isRest)
    }

    @Test
    fun aRecordingReplacesWhatWasPlannedAndKeepsAnEventsClass() {
        val race = AthleteEvent(id = 7, name = "Synthetic Half", kind = EventKind.RACE, sport = EventSport.RUN, priority = EventPriority.B,
            date = today, start = at(today, 9), targetTimeMin = 100.0)
        val run = RecordedSessionInput(11, "run", at(today, 9, 2), 98.0, caloriesActive = 1100.0, avgHr = 175.0)
        val item = resolve(events = listOf(race), recorded = listOf(run), n = at(today, 13))[0].items.single()
        assertTrue(item.recorded)
        assertEquals(Intensity.RACE, item.intensity)
        assertEquals(1100.0, item.kcal!!, 0.0)
        assertEquals(7L, item.eventId)
    }

    @Test
    fun aRecordingTakesItsClassFromItsOwnHeartRate() {
        val planned = listOf(PlannedSessionInput(1, today, LocalTime.of(7, 0), 45.0, "aerobic_base", "Easy"))
        // 160 bpm with resting 50 and max 190 is 79 % of reserve: HARD, whatever was planned.
        val run = RecordedSessionInput(11, "run", at(today, 7, 10), 45.0, avgHr = 160.0)
        val item = resolve(planned = planned, recorded = listOf(run), n = at(today, 9))[0].items.single()
        assertEquals(Intensity.HARD, item.intensity)
        assertEquals("Easy", item.title)
    }

    @Test
    fun aPastDayIsWhatWasRecordedAndWalksDoNotCount() {
        val yesterday = today.minusDays(1)
        val planned = listOf(PlannedSessionInput(1, yesterday, LocalTime.of(18, 0), 60.0, "speed", "Skipped track"))
        val walk = RecordedSessionInput(12, "walk", at(yesterday, 12), 40.0)
        val strength = RecordedSessionInput(13, "strength", at(yesterday, 19), 55.0, rpe = 8)
        val day = resolve(planned = planned, recorded = listOf(walk, strength), from = yesterday, days = 1)[0]
        val item = day.items.single()
        assertEquals("strength", item.type)
        assertEquals(Intensity.HARD, item.intensity)
    }

    @Test
    fun todaysPlanStaysUntilARecordingReplacesIt() {
        val planned = listOf(PlannedSessionInput(1, today, LocalTime.of(7, 0), 45.0, "aerobic_base", "Easy"))
        val item = resolve(planned = planned, n = at(today, 12))[0].items.single()
        assertEquals(PlanSource.PLANNED_SESSION, item.source)
    }

    @Test
    fun energyUsesTheOwnHistoryWhenThereIsEnoughOfIt() {
        val own = DemandHistory(kcalPerMinByType = mapOf("run" to listOf(11.0, 12.0, 13.0)), weightKg = 70.0)
        assertEquals(12.0 * 60, estimateSessionKcal("run", 60.0, Intensity.MODERATE, 70.0, own.kcalPerMinByType)!!, 0.001)
        // Without history: (9.8 - 1) MET x 3.5 x 70 / 200 = 10.78 kcal/min.
        assertEquals(10.78 * 60, estimateSessionKcal("run", 60.0, Intensity.MODERATE, 70.0, emptyMap())!!, 0.01)
        assertNull(estimateSessionKcal("run", 60.0, Intensity.MODERATE, null, emptyMap()))
    }

    @Test
    fun theUsualClassComesFromTheMedianHeartRateLoad() {
        val easy = intensityForTypical("run", mapOf("run" to listOf(0.9, 1.0, 1.1)))
        assertEquals(Intensity.EASY, easy!!.intensity)
        val hard = intensityForTypical("run", mapOf("run" to listOf(2.1, 2.2, 2.5)))
        assertEquals(Intensity.HARD, hard!!.intensity)
        assertNull(intensityForTypical("run", mapOf("run" to listOf(2.0))))
    }

    @Test
    fun titlesAndRpeReadAsExpected() {
        assertEquals(Intensity.HARD, intensityForTitle("Ripetute 10x400")!!.intensity)
        assertEquals(Intensity.EASY, intensityForTitle("Recovery jog")!!.intensity)
        assertEquals(Intensity.LONG, intensityForTitle("Long run")!!.intensity)
        assertNull(intensityForTitle("Run"))
        assertEquals(Intensity.MODERATE, intensityForRecorded(5, null, null, null)!!.intensity)
        assertNotNull(intensityForRecorded(null, 120.0, 50.0, 190.0))
        assertNull(intensityForRecorded(null, 120.0, null, 190.0))
    }

    @Test
    fun strengthStaysItsClassWhenLong() {
        val c = finalIntensity("strength", 100.0, Classified(Intensity.MODERATE, "planned as hypertrophy"))
        assertEquals(Intensity.MODERATE, c.intensity)
    }

    @Test
    fun aSessionDoneLaterThanPlannedIsStillThatSessionNotAnExtra() {
        // Planned for 07:00, done in the evening: it replaces the plan, keeps its title, and nothing is added.
        val planned = listOf(PlannedSessionInput(1, today, LocalTime.of(7, 0), 45.0, "max_strength", "Kettlebell circuit"))
        val late = RecordedSessionInput(21, "strength", at(today, 18, 40), 35.0)
        val item = resolve(planned = planned, recorded = listOf(late), n = at(today, 20))[0].items.single()
        assertTrue(item.recorded)
        assertEquals("Kettlebell circuit", item.title)
        assertEquals(18, item.start!!.hour)
    }

    @Test
    fun twoPlansAndTwoRecordingsPairClosestFirstAndOnlyTheUnexpectedOneIsExtra() {
        val planned = listOf(
            PlannedSessionInput(1, today, LocalTime.of(7, 0), 45.0, "aerobic_base", "Easy run"),
            PlannedSessionInput(2, today, LocalTime.of(18, 0), 45.0, "max_strength", "Gym"),
        )
        val recorded = listOf(
            RecordedSessionInput(31, "run", at(today, 11, 30), 40.0), // the morning run, late
            RecordedSessionInput(32, "strength", at(today, 17, 50), 50.0), // the gym, on time
            RecordedSessionInput(33, "run", at(today, 20, 0), 30.0), // nothing planned is left for it: an extra
        )
        val items = resolve(planned = planned, recorded = recorded, n = at(today, 21))[0].items
        assertEquals(3, items.size)
        assertTrue(items.all { it.recorded })
        assertEquals("Easy run", items.single { it.sourceId == "session:31" }.title)
        assertEquals("Gym", items.single { it.sourceId == "session:32" }.title)
        assertEquals("Run", items.single { it.sourceId == "session:33" }.title)
    }

    @Test
    fun aDifferentKindOfSessionIsNotTakenForThePlan() {
        // A planned strength session and a recorded run: the run is extra, the gym session stays planned today.
        val planned = listOf(PlannedSessionInput(1, today, LocalTime.of(7, 0), 45.0, "max_strength", "Gym"))
        val items = resolve(planned = planned, recorded = listOf(RecordedSessionInput(41, "run", at(today, 7, 5), 30.0)), n = at(today, 9))[0].items
        assertEquals(2, items.size)
        assertEquals(1, items.count { !it.recorded && it.title == "Gym" })
    }
}
