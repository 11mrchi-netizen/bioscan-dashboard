package com.bioscan.fieldterminal.domain.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class PreventionTest {
    private val checklist = parseChecklist(File("../../supabase/functions/_shared/risk/home-checklist.json").readText())
    private val preventive = parsePreventive(File("../../supabase/functions/_shared/risk/preventive.json").readText())
    private val today = LocalDate.of(2026, 11, 19)

    @Test
    fun checklistDefinitionsAreComplete() {
        assertTrue(checklist.items.size >= 6)
        for (i in checklist.items) {
            assertTrue(i.id, i.choices.size >= 2)
            assertTrue(i.id, i.flag.isNotEmpty() && i.flag.all { f -> i.choices.any { it.first == f } })
            assertTrue(i.id, i.source.isNotBlank() && i.fix.isNotBlank())
        }
    }

    @Test
    fun aFlaggedAnswerShowsItsFixAndOldAnswersAreAskedAgain() {
        val states = checklist.states(
            listOf(
                ChecklistAnswer("gas_cooking", "gas_hood", today.minusMonths(2)),
                ChecklistAnswer("gas_cooking", "gas_no_hood", today.minusMonths(1)),
                ChecklistAnswer("damp_mould", "no", today.minusMonths(13)),
            ),
            today,
        ).associateBy { it.item.id }
        assertTrue("the latest answer counts", states.getValue("gas_cooking").flagged)
        assertFalse(states.getValue("gas_cooking").stale)
        assertFalse(states.getValue("damp_mould").flagged)
        assertTrue("asked again after 12 months", states.getValue("damp_mould").stale)
        assertTrue(states.getValue("radon").stale)
        assertNull(states.getValue("radon").answer)
    }

    @Test
    fun preventiveDefinitionsAreComplete() {
        val layers = PREVENTIVE_LAYERS.map { it.first }.toSet()
        for (i in preventive.items) {
            assertTrue(i.id, i.layer in layers)
            assertTrue(i.id, i.sex in setOf("any", "female", "male"))
            assertTrue(i.id, i.ageMin <= i.ageMax && i.source.isNotBlank())
            i.methodList.forEach { m -> assertTrue("${i.id} ${m.code}", m.months > 0) }
        }
    }

    private val man45 = PreventiveContext(age = 45, sex = "male", bmi = 23.0, smoking = "never", prevent10y = 0.03, autoDates = emptyMap())

    @Test
    fun theScheduleFitsAgeSexAndConditions() {
        val ids = preventive.statuses(emptyList(), man45, today).map { it.item.id }
        assertTrue(ids.containsAll(listOf("bp_check", "lipids", "colorectal", "hep_c", "hiv", "lpa", "hep_b", "flu", "tdap", "dental", "eye")))
        assertFalse("not female screens", ids.contains("breast") || ids.contains("cervical"))
        assertFalse("BMI under 25", ids.contains("diabetes"))
        assertFalse("never smoked", ids.contains("lung_ldct") || ids.contains("aaa"))
        assertFalse("not 50 yet", ids.contains("shingles"))
        assertFalse("PREVENT low", ids.contains("cac"))
        val woman = preventive.statuses(emptyList(), man45.copy(sex = "female", bmi = 27.0, prevent10y = 0.08), today).map { it.item.id }
        assertTrue(woman.containsAll(listOf("breast", "cervical", "diabetes", "cac")))
        val unknown = preventive.statuses(emptyList(), man45.copy(age = 60, smoking = null), today).first { it.item.id == "lung_ldct" }
        assertEquals(PreventiveState.CHECK, unknown.state)
    }

    @Test
    fun dueDatesComeFromRecordsOrTheDataAndTheMethodSetsTheInterval() {
        val records = listOf(
            PreventiveRecord("colorectal", LocalDate.of(2024, 6, 1), "colonoscopy", false, LocalDate.of(2024, 6, 1)),
            PreventiveRecord("flu", LocalDate.of(2025, 10, 1), null, false, LocalDate.of(2025, 10, 1)),
            PreventiveRecord("hep_c", LocalDate.of(2020, 1, 1), null, false, LocalDate.of(2020, 1, 1)),
            PreventiveRecord("hep_b", null, null, true, LocalDate.of(2026, 1, 1)),
        )
        val ctx = man45.copy(autoDates = mapOf("bp" to today.minusDays(10), "lab:total cholesterol" to LocalDate.of(2026, 1, 14)))
        val s = preventive.statuses(records, ctx, today).associateBy { it.item.id }
        assertEquals(PreventiveState.UPCOMING, s.getValue("colorectal").state)
        assertEquals(LocalDate.of(2034, 6, 1), s.getValue("colorectal").next)
        assertEquals(PreventiveState.DUE, s.getValue("flu").state)
        assertEquals(PreventiveState.DONE, s.getValue("hep_c").state)
        assertEquals(PreventiveState.NOT_APPLICABLE, s.getValue("hep_b").state)
        assertEquals("from your data", s.getValue("bp_check").lastFrom)
        assertEquals(PreventiveState.UPCOMING, s.getValue("lipids").state)
        assertEquals(LocalDate.of(2031, 1, 14), s.getValue("lipids").next)
        assertEquals(PreventiveState.DUE, s.getValue("lpa").state)
    }
}
