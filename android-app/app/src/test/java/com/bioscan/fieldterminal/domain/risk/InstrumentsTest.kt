package com.bioscan.fieldterminal.domain.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class InstrumentsTest {
    private val defs = parseInstruments(File("../../supabase/functions/_shared/instruments/instruments.json").readText())

    @Test
    fun everyPublishedCaseScoresAsWrittenTheSameCasesTheConsoleChecks() {
        assertTrue(defs.cases.size >= 10)
        for (c in defs.cases) {
            val s = defs.score(defs.instrument(c.instrument)!!, c.answers)!!
            assertEquals("${c.instrument} ${c.answers}", c.score, s.score, 1e-9)
            assertEquals("${c.instrument} ${c.answers}", c.band, s.band?.label)
            assertEquals("${c.instrument} ${c.answers}", c.flags, s.flags.map { it.id })
        }
    }

    @Test
    fun aWrongAnswerIsNotScored() {
        val phq = defs.instrument("phq9")!!
        assertNull(defs.score(phq, listOf(0.0, 0.0)))
        assertNull(defs.score(phq, List(8) { 0.0 } + 4.0))
        assertNull(defs.score(defs.instrument("work_hours")!!, listOf(200.0)))
    }

    @Test
    fun theSelfHarmItemFlagsWhateverTheTotal() {
        val s = defs.score(defs.instrument("phq9")!!, List(8) { 0.0 } + 1.0)!!
        assertEquals("Minimal", s.band?.label)
        assertEquals(listOf("self_harm"), s.flags.map { it.id })
    }

    @Test
    fun dueDatesFollowEachInterval() {
        val today = LocalDate.of(2026, 11, 19)
        val phq = defs.instrument("phq9")!!
        assertTrue(phq.isDue(null, today))
        assertFalse(phq.isDue(today.minusDays(27), today))
        assertTrue(phq.isDue(today.minusDays(28), today))
        val due = defs.dueToday(mapOf("phq9" to today, "gad7" to today, "who5" to today, "ucla3" to today), today).map { it.id }
        assertEquals(listOf("cbi_work", "work_hours"), due)
    }

    @Test
    fun summariesGiveTheLatestResultItsStateThePreviousScoreAndTheDueDate() {
        val today = LocalDate.of(2026, 11, 19)
        val answers = listOf(
            InstrumentAnswer("phq9", today.minusDays(40), 11.0, "Moderate", emptyList()),
            InstrumentAnswer("phq9", today.minusDays(10), 6.0, "Mild", emptyList()),
            InstrumentAnswer("who5", today.minusDays(30), 64.0, "Good", emptyList()),
        )
        val byId = defs.summaries(answers, today).associateBy { it.instrument.id }
        val phq = byId.getValue("phq9")
        assertEquals(6.0, phq.latest!!.score, 1e-9)
        assertEquals("warn", phq.state)
        assertEquals(11.0, phq.previous!!, 1e-9)
        assertEquals(2, phq.series.size)
        assertEquals(today.plusDays(18), phq.dueOn)
        assertFalse(phq.due)
        assertTrue(byId.getValue("who5").due)
        assertEquals("ok", byId.getValue("who5").state)
        assertTrue(byId.getValue("ucla3").due)
        assertNull(byId.getValue("ucla3").latest)
    }

    @Test
    fun aDueQuestionnaireReminderOpensMeExposures() {
        assertEquals(
            com.bioscan.fieldterminal.domain.notifications.NotificationDestination.EXPOSURES,
            com.bioscan.fieldterminal.domain.notifications.notificationDestination(com.bioscan.fieldterminal.data.model.NotificationKind.QUESTIONNAIRE_DUE),
        )
        assertTrue(com.bioscan.fieldterminal.data.model.NotificationKind.QUESTIONNAIRE_DUE in com.bioscan.fieldterminal.data.model.NotificationKind.ALL)
    }
}
