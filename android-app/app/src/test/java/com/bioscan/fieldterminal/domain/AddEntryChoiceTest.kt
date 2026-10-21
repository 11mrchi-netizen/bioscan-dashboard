package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AddEntryChoiceTest {

    @Test
    fun everyChoiceLeadsSomewhereAndFuelChoicesOpenTheirForm() {
        assertEquals(AddEntryTarget.Form(AddEntryType.Fuel, FuelSubType.Drink), AddEntryChoice.Drink.target)
        assertEquals(AddEntryTarget.Form(AddEntryType.Fuel, FuelSubType.Endurance), AddEntryChoice.RunFuel.target)
        assertEquals(AddEntryTarget.Form(AddEntryType.Ostrc), AddEntryChoice.InjuryCheckIn.target)
        assertEquals(AddEntryTarget.InjuryForm, AddEntryChoice.Injury.target)
        assertEquals(AddEntryTarget.IllnessForm, AddEntryChoice.Illness.target)
        assertEquals(AddEntryTarget.EventForm, AddEntryChoice.Event.target)
        // Every form the sheet has is reachable from the picker.
        val reachable = AddEntryChoice.entries.mapNotNull { (it.target as? AddEntryTarget.Form)?.type }.toSet()
        assertEquals(AddEntryType.entries.toSet(), reachable)
    }

    @Test
    fun recentChoicesAreNewestFirstDistinctAndCapped() {
        var recent = emptyList<AddEntryChoice>()
        recent = recordRecentChoice(recent, AddEntryChoice.Food)
        recent = recordRecentChoice(recent, AddEntryChoice.Drink)
        recent = recordRecentChoice(recent, AddEntryChoice.Food)
        assertEquals(listOf(AddEntryChoice.Food, AddEntryChoice.Drink), recent)
        recent = recordRecentChoice(recent, AddEntryChoice.Note)
        recent = recordRecentChoice(recent, AddEntryChoice.Stool)
        assertEquals(listOf(AddEntryChoice.Stool, AddEntryChoice.Note, AddEntryChoice.Food), recent)
    }

    @Test
    fun storedRecentsRoundTripAndUnknownNamesAreDropped() {
        val recent = listOf(AddEntryChoice.Supplements, AddEntryChoice.Wellbeing)
        assertEquals(recent, parseRecentChoices(formatRecentChoices(recent)))
        assertEquals(listOf(AddEntryChoice.Note), parseRecentChoices("Fuel,Note,,Note"))
        assertTrue(parseRecentChoices(null).isEmpty())
    }

    @Test
    fun theEntrySheetShowsATimeOnlyWhereItIsReal() {
        val today = LocalDate.of(2026, 10, 14)
        val meal = LogEntry(1, LogSource.Meal, LogEntryKind.Food, LocalDateTime.of(2026, 10, 14, 7, 20), "Oats", null)
        val sleep = LogEntry(2, LogSource.Sleep, LogEntryKind.Sleep, LocalDateTime.of(2026, 10, 13, 7, 30), "Sleep", null)
        val checkIn = LogEntry(3, LogSource.Ostrc, LogEntryKind.Ostrc, LocalDateTime.of(2026, 10, 9, 8, 15), "Knee", null)
        assertEquals("FOOD · TODAY · 07:20", logEntryWhen(meal, today))
        assertEquals("SLEEP · YESTERDAY", logEntryWhen(sleep, today))
        assertEquals("CHECK-IN · FRI 9 OCT", logEntryWhen(checkIn, today))
    }
}
