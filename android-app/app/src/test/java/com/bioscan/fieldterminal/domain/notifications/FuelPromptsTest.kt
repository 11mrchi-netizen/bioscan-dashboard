package com.bioscan.fieldterminal.domain.notifications

import com.bioscan.fieldterminal.data.model.NotificationPrefsRow
import com.bioscan.fieldterminal.domain.events.EventKind
import com.bioscan.fieldterminal.domain.events.EventPriority
import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.PlanSource
import com.bioscan.fieldterminal.domain.fuel.PlannedDay
import com.bioscan.fieldterminal.domain.fuel.PlannedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class FuelPromptsTest {
    private val zone = ZoneId.systemDefault()
    private val d = LocalDate.of(2026, 11, 4)
    private val prefs = NotificationPrefsRow(quietStart = "23:00", quietEnd = "07:00")
    private fun at(day: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(day.atTime(h, m), zone)
    private fun item(day: LocalDate, h: Int, min: Double, i: Intensity, race: EventPriority? = null) = PlannedItem(
        if (race != null) PlanSource.EVENT else PlanSource.PLANNED_SESSION, "s$h${day.dayOfMonth}", "Session", "run", at(day, h), min, i, "test", 600.0,
        eventId = race?.let { 1L }, eventKind = race?.let { EventKind.RACE }, eventPriority = race,
    )
    private fun all(vararg items: PlannedItem) = fuelPrompts(listOf(PlannedDay(d, items.toList())), "fuel_session", "fuel_event")

    @Test
    fun aKeySessionGetsAPreAndAPostPrompt() {
        val prompts = all(item(d, 18, 60.0, Intensity.HARD))
        assertEquals(listOf(LocalDateTime.of(d, java.time.LocalTime.of(15, 0)), LocalDateTime.of(d, java.time.LocalTime.of(19, 30))), prompts.map { it.first.at })
        // Easy or short sessions get none.
        assertTrue(all(item(d, 18, 60.0, Intensity.EASY), item(d, 7, 40.0, Intensity.HARD)).isEmpty())
    }

    @Test
    fun dueMeansInTheWindowAndNotShownYet() {
        val prompts = all(item(d, 18, 60.0, Intensity.HARD))
        val now = LocalDateTime.of(d, java.time.LocalTime.of(15, 10))
        val due = dueFuelPrompts(prompts, prefs, now.minusMinutes(30), now, emptySet())
        assertEquals(1, due.size)
        assertTrue(due.single().title.startsWith("Fuel for"))
        assertTrue(dueFuelPrompts(prompts, prefs, now.minusMinutes(30), now, setOf(due.single().key)).isEmpty())
    }

    @Test
    fun aQuietHourPromptMovesToTheWindowEndOrIsDropped() {
        val seven = LocalDateTime.of(d, java.time.LocalTime.of(7, 5))
        // 08:00 session: the pre prompt (05:00) is in quiet hours and moves to 07:00, an hour before the start.
        val moved = dueFuelPrompts(all(item(d, 8, 60.0, Intensity.HARD)), prefs, seven.minusMinutes(30), seven, emptySet())
        assertEquals(LocalDateTime.of(d, java.time.LocalTime.of(7, 0)), moved.single { it.key.endsWith(":pre") }.at)
        assertTrue(moved.single().body.contains("08:00"))
        // 07:20 session: 07:00 would be 20 minutes before the start, too late to help: dropped.
        val late = all(item(d, 7, 60.0, Intensity.HARD).copy(start = at(d, 7, 20)))
        assertTrue(dueFuelPrompts(late, prefs, seven.minusMinutes(30), seven, emptySet()).none { it.key.endsWith(":pre") })
    }

    @Test
    fun anARaceGetsItsRaceWeekPromptsInsteadOfTheSessionPreMeal() {
        val race = item(d, 9, 210.0, Intensity.RACE, EventPriority.A)
        val prompts = fuelPrompts(listOf(PlannedDay(d, listOf(race))), "fuel_session", "fuel_event")
        val keys = prompts.map { it.first.key.substringAfterLast(':') }
        assertTrue(keys.containsAll(listOf("load1", "load2", "eve", "drink", "breakfast", "post")))
        assertTrue("pre" !in keys)
        assertEquals(LocalDateTime.of(d.minusDays(2), java.time.LocalTime.of(9, 0)), prompts.first { it.first.key.endsWith("load1") }.first.at)
        // A C race is a hard session: no loading.
        val c = fuelPrompts(listOf(PlannedDay(d, listOf(race.copy(eventPriority = EventPriority.C)))), "fuel_session", "fuel_event")
        assertTrue(c.none { it.first.kind == "fuel_event" })
    }

    @Test
    fun nothingWhenNotificationsAreOff() {
        val prompts = all(item(d, 18, 60.0, Intensity.HARD))
        val now = LocalDateTime.of(d, java.time.LocalTime.of(15, 10))
        assertTrue(dueFuelPrompts(prompts, prefs.copy(masterEnabled = false), now.minusMinutes(30), now, emptySet()).isEmpty())
    }
}
