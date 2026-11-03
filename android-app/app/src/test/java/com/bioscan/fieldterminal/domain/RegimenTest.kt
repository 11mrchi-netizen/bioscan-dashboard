package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RegimenTest {
    private val d = LocalDate.of(2026, 11, 3) // a Tuesday

    @Test
    fun oldRowsReadExactlyAsBefore() {
        val daily = regimenSpec("morning", null, "5 g")
        assertEquals(ScheduleType.DAILY, daily.type)
        assertEquals(listOf("morning"), daily.slots)
        assertEquals(5.0, daily.amount!!, 0.0)
        assertEquals("g", daily.unit)
        val iron = regimenSpec("morning", 3, "36 mg, 3x/week")
        assertEquals(ScheduleType.EVERY_N_DAYS, iron.type)
        assertTrue(iron.isDueOn(d, null))
        assertFalse(iron.isDueOn(d, d.minusDays(2)))
        assertTrue(iron.isDueOn(d, d.minusDays(3)))
        assertEquals("36 mg", iron.doseTextOn(d))
        val prn = regimenSpec("as-needed", null, "150 ml")
        assertTrue(prn.asNeeded)
        assertFalse(prn.isDueOn(d, null))
        assertEquals(0.0, prn.dosesPerDay(), 0.0)
    }

    @Test
    fun theDoseTextParsesAndPrintsLikeTheBackfill() {
        assertEquals(3.0 to "g", parseDoseText("3 Gr"))
        assertEquals(500.0 to "mg", parseDoseText("500mg"))
        assertEquals(2.5 to "mg", parseDoseText("2,5 mg"))
        assertNull(parseDoseText("one scoop"))
        assertEquals("2.5", formatAmount(2.5))
        assertEquals("3000", formatAmount(3000.0))
    }

    @Test
    fun weekdaysAndSeveralSlots() {
        val s = regimenSpec("morning", null, "", doseAmount = 250.0, doseUnit = "mcg", slots = listOf("night", "morning"),
            schedule = ScheduleJson("weekdays", days = listOf(1, 3, 5)))
        assertEquals(listOf("morning", "night"), s.slots) // kept in day order
        assertFalse(s.isDueOn(d, null)) // Tuesday
        assertTrue(s.isDueOn(d.plusDays(1), null))
        assertEquals("Mon, Wed, Fri · morning + night", s.scheduleWords())
        assertEquals(listOf("morning"), s.slotsDone(1))
        assertEquals(2 * 3 / 7.0, s.dosesPerDay(), 1e-9)
        assertEquals(d.plusDays(1), s.nextDueAfter(d, null))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), s.weekdays)
    }

    @Test
    fun aCycleSkipsItsOffDays() {
        val s = regimenSpec("morning", null, "", doseAmount = 5.0, doseUnit = "g", slots = listOf("morning"),
            schedule = ScheduleJson("daily", cycle = CycleJson(onDays = 56, offDays = 28, start = "2026-09-01")))
        // 1 Sep + 56 days = 27 Oct: off until 24 Nov.
        val state = s.cycleOn(d)!!
        assertFalse(state.on)
        assertEquals(LocalDate.of(2026, 11, 24), state.nextChange)
        assertFalse(s.isDueOn(d, null))
        assertTrue(s.isDueOn(LocalDate.of(2026, 11, 24), null))
        assertEquals("8 weeks on, 4 weeks off", s.cycle!!.words())
        assertEquals(56.0 / 84, s.dosesPerDay(), 1e-9)
        assertEquals("Off cycle until 24 Nov", s.stateWords(d))
    }

    @Test
    fun doseStepsChangeTheDoseOnTheirDays() {
        val s = regimenSpec("morning", null, "", doseAmount = 2.5, doseUnit = "mg", slots = listOf("morning"),
            schedule = ScheduleJson("weekdays", days = listOf(2)),
            doseSteps = DoseStepsJson("2026-11-03", listOf(DoseStepJson(4, 2.5), DoseStepJson(4, 5.0), DoseStepJson(null, 7.5))))
        assertEquals(2.5, s.amountOn(d)!!, 0.0)
        assertEquals("5 mg", s.doseTextOn(d.plusWeeks(4)))
        assertEquals("7.5 mg", s.doseTextOn(d.plusWeeks(30)))
        assertEquals("2.5 mg for 4 weeks, then 5 mg for 4 weeks, then 7.5 mg", s.stepsWords())
        assertEquals("2.5 mg until 30 Nov, then 5 mg", s.stateWords(d))
        assertEquals(2.5, s.amountOn(d.minusDays(1))!!, 0.0) // before the steps: the item's own dose
    }

    @Test
    fun plannedDaysCountTheCalendarAndTheItemsDates() {
        val daily = regimenSpec("morning", null, "5 g", startDate = "2026-11-01")
        assertEquals(3, daily.plannedDays(LocalDate.of(2026, 10, 25), d)) // 1-3 Nov
        val everyThree = regimenSpec("morning", 3, "36 mg")
        assertEquals(4, everyThree.plannedDays(d, d.plusDays(9))) // 10 days -> ceil(10/3)
        val legacy = everyThree.legacyColumns(d)
        assertEquals(LegacyColumns("36 mg", "morning", 3), legacy)
        assertEquals("as-needed", regimenSpec("as-needed", null, "150 ml").legacyColumns(d).timeOfDay)
    }
}
