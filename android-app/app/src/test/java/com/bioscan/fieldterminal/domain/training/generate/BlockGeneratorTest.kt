package com.bioscan.fieldterminal.domain.training.generate

import com.bioscan.fieldterminal.domain.training.ResolvedLoad
import com.bioscan.fieldterminal.domain.training.definition.parseDefinition
import com.bioscan.fieldterminal.domain.training.definition.TemplateDef
import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import com.bioscan.fieldterminal.domain.training.definition.ConditioningProtocolDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

// Placeholder program only: nothing here comes from a book.
class BlockGeneratorTest {
    private val module = """
    {"kind":"strength_module","key":"example.two_day","title":"Example two-day","family":"example","domain":"max_strength",
     "sessions_per_week":2,"day_positions":[1,4],"block_lengths":[2],
     "slots":[{"id":"A","role":"press","standard":"Press"},{"id":"B","role":"squat","standard":"Squat"},{"id":"C","role":"accessory","standard":"Accessory","pick":{"min":2,"max":3},"optional":true}],
     "sessions":[{"id":"D1","label":"One","slots":["A","B"]},{"id":"D4","label":"Two","slots":["A","B","C"]}],
     "variants":[{"key":"std","title":"Standard","weeks":[
       {"week":1,"sessions":[
         {"session":"D1","items":[{"slot":"A","sets":{"min":3,"max":5},"reps":5,"load":{"kind":"pct_1rm","value":70}},{"slot":"B","sets":3,"reps":5,"load":{"kind":"pct_1rm","value":70}}]},
         {"session":"D4","items":[{"slot":"A","sets":3,"reps":5,"load":{"kind":"pct_1rm","value":70}},{"slot":"C","sets":3,"reps":10,"load":{"kind":"pct_1rm","value":60}}]}]},
       {"week":2,"kind":"peak","sessions":[
         {"session":"D1","items":[{"slot":"A","sets":1,"reps":3,"load":{"kind":"pct_1rm","value":85},"technique":"peak"}]},
         {"session":"D4","items":[{"slot":"B","sets":1,"reps":3,"load":{"kind":"pct_1rm","value":85},"technique":"peak"}]}]}]}],
     "peak":{"techniques":["peak","none"],"note":"Peak note."},
     "progression":{"unit":"lb","upper":5,"lower":10,"after_weeks":{"min":3,"max":6}}}
    """.trimIndent()

    private val session = """
    {"kind":"conditioning_session","key":"example.run","title":"Example run","category":"lic","domains":["aerobic_base"],
     "prescription":{"by":["minutes"],"minutes":{"min":30,"max":90}},"structure":{"type":"continuous"}}
    """.trimIndent()
    private val hills = """
    {"kind":"conditioning_session","key":"example.hills","title":"Example hills","category":"hic","domains":["anaerobic_capacity"],
     "prescription":{"by":["rounds"],"rounds":{"min":5,"max":10}},"structure":{"type":"repeats"}}
    """.trimIndent()

    private val template = """
    {"kind":"template","key":"example.grid","title":"Example grid","weeks":5,"domains":["max_strength"],
     "variables":{"MS":{"kind":"strength_module","choose_from":["example.two_day"]}},
     "grid":[
      {"week":1,"days":[{"day":1,"cells":[{"ref":"strength:${'$'}MS"}]},{"day":2,"cells":[{"ref":"cond:example.run","params":{"minutes":{"min":30,"max":60}}}]},{"day":4,"cells":[{"ref":"strength:${'$'}MS"}]}]},
      {"week":2,"days":[{"day":1,"cells":[{"ref":"strength:${'$'}MS"}]},{"day":2,"cells":[{"ref":"cond:example.run"}]},{"day":4,"cells":[{"ref":"strength:${'$'}MS"}]}]},
      {"week":3,"kind":"deload","counts_toward_block":false,"days":[{"day":1,"cells":[{"ref":"inline","label":"Deload"}]}]},
      {"week":4,"days":[{"day":1,"cells":[{"ref":"strength:${'$'}MS"}]},{"day":2,"cells":[{"ref":"cond:${'$'}HILL"}]},{"day":4,"cells":[{"ref":"strength:${'$'}MS"}]}]},
      {"week":5,"days":[{"day":1,"cells":[{"ref":"strength:${'$'}MS"}]},{"day":4,"cells":[{"ref":"strength:${'$'}MS"}]}]}]}
    """.trimIndent()

    private val protocol = """
    {"kind":"conditioning_protocol","key":"example.protocol","title":"Example protocol","protocol_type":"polarized",
     "budget":{"low_intensity_minutes_per_week":{"min":100,"max":160},"session_min_minutes":25,"high_intensity_per_week":1},
     "default_layout":[{"day":2,"kind":"lic"},{"day":3,"kind":"lic"},{"day":6,"kind":"hic"}],
     "suggested":{"lic":["example.run"],"hic":["example.hills"]}}
    """.trimIndent()

    private val defs = listOf(module, session, hills, template, protocol).map(::parseDefinition)
    private val idx = DefinitionIndex(defs)
    private val monday = LocalDate.of(2026, 1, 5)
    private val maxes = mapOf("press" to MaxEntry(oneRmKg = 80.0), "squat" to MaxEntry(oneRmKg = 100.0))
    private val lookup: (String) -> MaxEntry? = { maxes[it.trim().lowercase()] }
    private val choices = GenChoices(startDate = monday, variables = mapOf("MS" to "example.two_day", "HILL" to "example.hills"))

    private fun tpl() = generateTemplateBlock(idx.template("example.grid")!!, idx, choices, maxes = lookup)

    @Test
    fun aTemplateExpandsToDatedSessions() {
        val b = tpl()
        assertEquals(5, b.calendarWeeks)
        assertEquals(4, b.countedWeeks)
        assertEquals(LocalDate.of(2026, 2, 8), b.endDate)
        val w1 = b.sessions.filter { it.weekIndex == 1 }
        assertEquals(listOf(monday, monday.plusDays(1), monday.plusDays(3)), w1.map { it.date })
        assertEquals(listOf(1, 2, 3), b.sessions.take(3).map { it.sequenceNo })
        assertTrue(b.sessions.single { it.weekIndex == 3 }.let { !it.countsTowardBlock && it.workKind == "deload" })
    }

    @Test
    fun weekdayChoicesMoveTheSessions() {
        val b = generateTemplateBlock(idx.template("example.grid")!!, idx, choices.copy(weekdays = mapOf(1 to DayOfWeek.TUESDAY, 4 to DayOfWeek.SATURDAY)), maxes = lookup)
        val w1 = b.sessions.filter { it.weekIndex == 1 && it.moduleRef.startsWith("strength") }
        assertEquals(listOf(LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 10)), w1.map { it.date })
    }

    @Test
    fun loadsResolveToLoadableWeightsAndStepUpAtTheNextBlock() {
        val b = tpl()
        fun pressKg(week: Int) = (b.sessions.first { it.weekIndex == week && it.moduleRef.startsWith("strength") }.items.first { it.slot == "A" }.load as ResolvedLoad.Barbell).loadable.totalKg
        assertEquals(55.0, pressKg(1), 0.001) // 70% of 80 = 56 -> 55
        assertEquals(57.5, pressKg(4), 0.001) // 82.5 after +2.5 -> 57.75 -> 57.5
        assertEquals(2, b.progressions.size)
        assertEquals(listOf(82.5, 105.0), b.progressions.sortedBy { it.exercise }.map { it.toKg })
    }

    @Test
    fun theSecondSessionOfAWeekUsesTheModulesSecondSession() {
        val b = tpl()
        val w2 = b.sessions.filter { it.weekIndex == 2 && it.moduleRef.startsWith("strength") }
        assertEquals(listOf("Example two-day: One", "Example two-day: Two"), w2.map { it.title })
        assertTrue(w2.all { it.notes.contains("Peak note.") })
    }

    @Test
    fun missingMaxesWarnAndLeaveLoadsUnresolved() {
        val b = generateTemplateBlock(idx.template("example.grid")!!, idx, choices, maxes = { null })
        assertTrue(b.warnings.any { "No a 1RM for Press" in it || "1RM for Press" in it })
        val a = b.sessions.first { it.moduleRef.startsWith("strength") }.items.first { it.slot == "A" }
        assertTrue(a.load is ResolvedLoad.Unresolved)
    }

    @Test
    fun clusterSlotsProduceOneItemPerChosenExercise() {
        val c = choices.copy(modules = mapOf("example.two_day" to ModuleChoice(exercises = mapOf("C" to listOf("Curl", "Row")))))
        val b = generateTemplateBlock(idx.template("example.grid")!!, idx, c, maxes = lookup)
        val d4 = b.sessions.first { it.weekIndex == 1 && it.daySlot == 4 }
        assertEquals(listOf("Curl", "Row"), d4.items.filter { it.slot == "C" }.map { it.exercise })
    }

    @Test
    fun anUnchosenVariableBecomesAPlaceholderWithAWarning() {
        val b = generateTemplateBlock(idx.template("example.grid")!!, idx, choices.copy(variables = mapOf("MS" to "example.two_day")), maxes = lookup)
        assertTrue(b.warnings.any { "choose a session" in it.lowercase() })
    }

    @Test
    fun aComposedBlockAddsADeloadAfterEachStrengthBlockAndFillsConditioning() {
        val c = GenChoices(startDate = monday, weeks = 5)
        val b = generateComposedBlock(idx.strength("example.two_day"), idx.protocol("example.protocol"), idx, c, maxes = lookup)
        assertEquals(5, b.calendarWeeks)
        assertEquals(4, b.countedWeeks) // week 3 is the deload
        assertTrue(b.sessions.filter { it.weekIndex == 3 && it.moduleRef == "inline" }.size == 1)
        val w1Cond = b.sessions.filter { it.weekIndex == 1 && it.conditioning != null }
        assertEquals(listOf(2, 3, 6), w1Cond.map { it.daySlot })
        assertEquals("example.hills", w1Cond.last().conditioning!!.sessionKey)
        // lic budget 100 minimum split over two sessions, floored to 5, never below the 25 minute floor
        assertEquals(50.0, w1Cond.first().conditioning!!.minutes!!.min, 0.001)
    }

    @Test
    fun aCadenceOnlyProtocolRunsItsHighIntensityEveryNthWeek() {
        val p = parseDefinition(protocol.replace("\"high_intensity_per_week\":1", "\"high_intensity_every_n_weeks\":2")) as ConditioningProtocolDef
        val b = generateComposedBlock(null, p, idx, GenChoices(startDate = monday, weeks = 4), maxes = lookup)
        val hicWeeks = b.sessions.filter { it.conditioning?.category == "hic" }.map { it.weekIndex }
        assertEquals(listOf(1, 3), hicWeeks)
    }

    @Test
    fun theIncrementConvertsPoundsToTheLoadableStep() {
        val module = idx.strength("example.two_day")!!
        val inc = module.progression!!
        assertEquals(2.5, progressionIncrementKg(inc.upper, inc.unit, 2.5), 0.001)
        assertEquals(5.0, progressionIncrementKg(inc.lower, inc.unit, 2.5), 0.001)
    }

    @Test
    fun requirementsListTheVariablesModulesDaysAndSlotsToAskAbout() {
        val t = idx.template("example.grid")!!
        val vars = templateVariables(t)
        assertEquals(listOf("MS", "HILL"), vars.map { it.name })
        assertEquals(listOf("example.two_day"), vars.first().options)
        assertEquals(listOf("example.two_day"), templateModuleKeys(t, mapOf("MS" to "example.two_day")))
        assertEquals(emptyList<String>(), templateModuleKeys(t, emptyMap()))
        assertEquals(listOf(1, 2, 4), templateDayPositions(t))
        assertEquals(listOf("A", "B", "C"), slotsToChoose(idx.strength("example.two_day")!!).map { it.id })
    }

    @Test
    fun blockDomainsFollowTheSessionMix() {
        val b = tpl()
        val d = deriveBlockDomains(b.sessions)
        assertEquals("max_strength", d.first().domain)
        assertEquals("primary", d.first().role)
        assertTrue(d.any { it.domain == "aerobic_base" || it.domain == "anaerobic_capacity" })
    }

    @Test
    fun itemsReadAsOneLine() {
        val b = tpl()
        val a = b.sessions.first { it.moduleRef.startsWith("strength") }.items.first { it.slot == "A" }
        assertEquals("Press 3-5 x 5 @ 55 kg (15+2.5 per side)", a.text())
        assertEquals("Example run · 30-60 min", b.sessions.first { it.conditioning != null }.summaryLines().single())
    }

    @Test
    fun smallBookIncrementsNeverRoundBelowOneStep() {
        assertEquals(2.5, progressionIncrementKg(com.bioscan.fieldterminal.domain.training.definition.NumRange.of(5), "lb", 2.5), 0.001)
        assertEquals(2.5, progressionIncrementKg(com.bioscan.fieldterminal.domain.training.definition.NumRange(2.5, 5.0), "lb", 2.5), 0.001)
        assertEquals(5.0, progressionIncrementKg(com.bioscan.fieldterminal.domain.training.definition.NumRange.of(10), "lb", 2.5), 0.001)
    }
}
