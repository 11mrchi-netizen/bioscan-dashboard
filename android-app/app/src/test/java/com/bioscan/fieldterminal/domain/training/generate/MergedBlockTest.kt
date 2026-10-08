package com.bioscan.fieldterminal.domain.training.generate

import com.bioscan.fieldterminal.domain.training.ResolvedLoad
import com.bioscan.fieldterminal.domain.training.definition.ConditioningProtocolDef
import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import com.bioscan.fieldterminal.domain.training.definition.parseDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

// Placeholder program only: nothing here comes from a book.
class MergedBlockTest {
    private fun week(n: Int, pct: Int) = """{"week":$n,"sessions":[
        {"session":"D1","items":[{"slot":"A","sets":3,"reps":5,"load":{"kind":"pct_1rm","value":$pct}},{"slot":"B","sets":3,"reps":5,"load":{"kind":"pct_1rm","value":$pct}}]},
        {"session":"D3","items":[{"slot":"A","sets":3,"reps":5,"load":{"kind":"pct_1rm","value":$pct}},{"slot":"H","sets":1,"reps":5,"load":{"kind":"pct_1rm","value":$pct}}]},
        {"session":"D5","items":[{"slot":"A","sets":3,"reps":5,"load":{"kind":"pct_1rm","value":$pct}},{"slot":"B","sets":3,"reps":5,"load":{"kind":"pct_1rm","value":$pct}}]}]}"""

    private val module = """
    {"kind":"strength_module","key":"example.three_day","title":"Example three-day","family":"example","domain":"max_strength",
     "sessions_per_week":3,"day_positions":[1,3,5],"block_lengths":[3],
     "slots":[{"id":"A","role":"press","standard":"Press"},{"id":"B","role":"squat","standard":"Squat"},{"id":"H","role":"hinge","standard":"Deadlift"}],
     "sessions":[{"id":"D1","label":"One","slots":["A","B"]},{"id":"D3","label":"Three","slots":["A","H"]},{"id":"D5","label":"Five","slots":["A","B"]}],
     "variants":[{"key":"std","title":"Standard","weeks":[${week(1, 70)},${week(2, 75)},${week(3, 80)}]}],
     "progression":{"unit":"lb","upper":5,"lower":10,"after_weeks":{"min":3,"max":6}}}
    """.trimIndent()

    private val protocol = """
    {"kind":"conditioning_protocol","key":"example.black","title":"Example black","protocol_type":"polarized",
     "budget":{"low_intensity_minutes_per_week":{"min":120,"max":180},"session_min_minutes":30,"high_intensity_per_week":1,"min_rest_days_per_week":1},
     "default_layout":[{"day":2,"kind":"lic"},{"day":4,"kind":"lic"},{"day":6,"kind":"hic"}],
     "suggested":{"lic":["example.run"],"hic":["example.hills"]}}
    """.trimIndent()

    private val run = """
    {"kind":"conditioning_session","key":"example.run","title":"Example run","category":"lic","domains":["aerobic_base"],
     "prescription":{"by":["minutes"],"minutes":{"min":30,"max":90}},"structure":{"type":"continuous"}}
    """.trimIndent()
    private val hills = """
    {"kind":"conditioning_session","key":"example.hills","title":"Example hills","category":"hic","domains":["anaerobic_capacity"],
     "prescription":{"by":["rounds"],"rounds":{"min":5,"max":10}},"structure":{"type":"repeats"}}
    """.trimIndent()

    private val defs = listOf(module, protocol, run, hills).map(::parseDefinition)
    private val idx = DefinitionIndex(defs)
    private val strength = idx.strength("example.three_day")!!
    private val black = idx.protocol("example.black")!!
    private val monday = LocalDate.of(2026, 1, 5)
    private val maxes = mapOf("press" to MaxEntry(oneRmKg = 80.0), "squat" to MaxEntry(oneRmKg = 100.0), "deadlift" to MaxEntry(oneRmKg = 120.0))
    private val lookup: (String) -> MaxEntry? = { maxes[it.trim().lowercase()] }
    private val choices = GenChoices(startDate = monday)

    private fun suggestion() = suggestLayout(strength, black, DayOfWeek.MONDAY)
    private fun bp(layout: List<DayPlan> = suggestion(), timeline: List<WeekKind> = defaultTimeline(3, 8)) = BlockBlueprint(strength.key, black.key, layout, timeline)

    // ---- layout ----
    @Test
    fun theSuggestedWeekKeepsTheTemplatesDaysAndTheProtocolsLayout() {
        val l = suggestion()
        assertEquals(listOf(0, null, 1, null, 2, null, null), l.map { it.strengthSession })
        assertEquals(listOf(null, "lic", null, "lic", null, "hic", null), l.map { it.conditioning?.kind })
        assertEquals(7, l.size)
        assertTrue(layoutWarnings(l, strength, black).toString(), layoutWarnings(l, strength, black).isEmpty())
    }

    @Test
    fun aProtocolWithoutALayoutFillsFreeDays() {
        val p = parseDefinition(protocol.replace(Regex("\"default_layout\":\\[[^\\]]*\\],"), "")) as ConditioningProtocolDef
        val l = suggestLayout(strength, p, DayOfWeek.MONDAY, conditioningPerWeek = 2)
        assertEquals(2, l.count { it.conditioning != null })
        assertTrue(l.filter { it.conditioning != null }.none { it.strengthSession != null })
    }

    @Test
    fun editedLayoutsAreWarnedAboutNeverBlocked() {
        val base = suggestion()
        fun with(day: DayOfWeek, f: (DayPlan) -> DayPlan) = base.map { if (it.weekday == day) f(it) else it }
        // hard conditioning the day after the deadlift session (Wednesday)
        val afterHinge = with(DayOfWeek.THURSDAY) { it.copy(conditioning = CondSlot("hic")) }
        assertTrue(layoutWarnings(afterHinge, strength, black).any { "deadlift" in it })
        // a session left off the week
        val unplaced = with(DayOfWeek.FRIDAY) { it.copy(strengthSession = null) }
        assertTrue(layoutWarnings(unplaced, strength, black).any { "not on a day yet" in it })
        // strength back to back although the template spaces its sessions
        val adjacent = with(DayOfWeek.TUESDAY) { it.copy(strengthSession = 1) }.map { if (it.weekday == DayOfWeek.WEDNESDAY) it.copy(strengthSession = null) else it }
        assertTrue(layoutWarnings(adjacent, strength, black).any { "back to back" in it })
        // no rest day left
        val noRest = base.map { if (it.isRest) it.copy(conditioning = CondSlot("lic")) else it }
        assertTrue(layoutWarnings(noRest, strength, black).any { "rest day" in it })
        // no low-intensity work at all
        val noLic = base.map { it.copy(conditioning = it.conditioning?.takeIf { c -> c.kind != "lic" }) }
        assertTrue(layoutWarnings(noLic, strength, black).any { "LIC" in it })
    }

    // ---- timeline ----
    @Test
    fun theDefaultTimelineIsBlocksThenInvisibleDeloads() {
        assertEquals("NNNDNNND", defaultTimeline(3, 8).joinToString("") { it.label.take(1) })
        assertEquals("TNNNDNNN", defaultTimeline(3, 8, startWithTest = true).joinToString("") { it.label.take(1) })
        assertEquals(8, weeksForBlocks(2, 3))
        assertEquals(7, weeksForBlocks(2, 3, trailingDeload = false))
        assertEquals(6, countedWeeks(defaultTimeline(3, 8)))
        assertEquals(WeekKind.Deload, cycleWeekKind(WeekKind.Normal))
        assertEquals(WeekKind.Normal, cycleWeekKind(WeekKind.Test))
        assertEquals("NNNDNNNDNN", resizeTimeline(defaultTimeline(3, 8), 3, 10).joinToString("") { it.label.take(1) })
    }

    // ---- merged generation ----
    @Test
    fun deloadWeeksAreInvisiblePauseTheCycleAndDropHardConditioning() {
        val b = generateMergedBlock(bp(), idx, choices, maxes = lookup)
        assertEquals(8, b.calendarWeeks)
        assertEquals(6, b.countedWeeks)
        for (w in listOf(4, 8)) {
            val s = b.sessions.filter { it.weekIndex == w }
            assertTrue(s.none { it.countsTowardBlock })
            assertTrue(s.none { it.conditioning?.category == "hic" })
            assertEquals(1, s.count { it.moduleRef == "inline" })
        }
        // week 5 restarts the template at its week 1 (70%) and the first block's end projected the maxes
        val w5Press = b.sessions.first { it.weekIndex == 5 && it.moduleRef.startsWith("strength") }.items.first { it.slot == "A" }
        assertEquals(70.0, (w5Press.load as ResolvedLoad.Barbell).pct, 0.001)
        assertTrue(w5Press.projected)
        assertTrue(b.progressions.all { it.weekIndex == 5 })
        assertTrue(b.progressions.isNotEmpty())
    }

    @Test
    fun hardConditioningOnlyAppearsInNormalWeeks() {
        val b = generateMergedBlock(bp(), idx, choices, maxes = lookup)
        assertEquals(listOf(1, 2, 3, 5, 6, 7), b.sessions.filter { it.conditioning?.category == "hic" }.map { it.weekIndex })
    }

    @Test
    fun theLayoutDecidesTheDatesAndTwoADaysUseTheSecondSlot() {
        val moved = suggestion().map {
            when (it.weekday) {
                DayOfWeek.MONDAY -> it.copy(strengthSession = null)
                DayOfWeek.TUESDAY -> it.copy(strengthSession = 0)        // strength on Tuesday, with the LIC run: a two-a-day
                else -> it
            }
        }
        val b = generateMergedBlock(bp(moved), idx, choices, maxes = lookup)
        val tuesday = b.sessions.filter { it.weekIndex == 1 && it.date == monday.plusDays(1) }
        assertEquals(listOf(1, 2), tuesday.sortedBy { it.slotInDay }.map { it.slotInDay })
        assertEquals(setOf("strength", "cond"), tuesday.map { it.moduleRef.substringBefore(':') }.toSet())
        assertTrue(b.sessions.none { it.weekIndex == 1 && it.date == monday && it.moduleRef.startsWith("strength") })
    }

    @Test
    fun aTestWeekSchedulesMaxTestsAndDoesNotProjectTheNextBlock() {
        val b = generateMergedBlock(bp(timeline = defaultTimeline(3, 8, startWithTest = true)), idx, choices, maxes = lookup)
        val tests = b.sessions.filter { it.weekIndex == 1 && it.workKind == "test" }
        assertEquals(3, tests.size)
        assertTrue(tests.first().items.all { it.load is ResolvedLoad.WorkUp })
        assertFalse(tests.first().items.isEmpty())
        assertEquals(7, b.countedWeeks) // the test week counts, the deload does not
    }

    @Test
    fun aStrengthOnlyBlockNeedsNoProtocol() {
        val onlyStrength = BlockBlueprint(strength.key, null, suggestLayout(strength, null, DayOfWeek.MONDAY), defaultTimeline(3, 4))
        val b = generateMergedBlock(onlyStrength, idx, choices, maxes = lookup)
        assertTrue(b.sessions.none { it.conditioning != null })
        assertEquals(10, b.sessions.size) // 3 sessions x 3 weeks, plus one inline deload session
    }
}
