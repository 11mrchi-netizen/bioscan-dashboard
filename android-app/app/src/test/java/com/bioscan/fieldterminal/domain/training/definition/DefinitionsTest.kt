package com.bioscan.fieldterminal.domain.training.definition

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Placeholder program only: nothing here comes from a book.
class DefinitionsTest {
    private val module = """
    {"kind":"strength_module","key":"example.two_day","title":"Example two-day","family":"example","domain":"max_strength",
     "sessions_per_week":2,"day_positions":[1,4],"block_lengths":[2],
     "slots":[{"id":"A","role":"press","standard":"Press"},{"id":"B","role":"squat","standard":"Squat"}],
     "sessions":[{"id":"D1","label":"One","slots":["A","B"]},{"id":"D4","label":"Two","slots":["A","B"]}],
     "variants":[{"key":"std","title":"Standard","weeks":[
       {"week":1,"sessions":[{"session":"D1","items":[{"slot":"A","sets":{"min":3,"max":5},"reps":5,"load":{"kind":"pct_1rm","value":70}}]}]},
       {"week":2,"kind":"peak","sessions":[{"session":"D1","items":[{"slot":"A","sets":1,"reps":3,"load":{"kind":"pct_1rm","value":85},"technique":"peak"}]}]}]}],
     "peak":{"techniques":["peak","amsap"]},
     "progression":{"unit":"lb","upper":{"min":2.5,"max":5},"lower":{"min":5,"max":10},"after_weeks":{"min":3,"max":6}}}
    """.trimIndent()

    private val protocol = """
    {"kind":"conditioning_protocol","key":"example.protocol","title":"Example protocol","protocol_type":"polarized",
     "budget":{"low_intensity_minutes_per_week":{"min":100,"max":160},"session_min_minutes":25,"high_intensity_per_week":1},
     "suggested":{"lic":["example.run"],"hic":["example.hills"]},"pairs_well_with":["example.two_day"]}
    """.trimIndent()

    private val session = """
    {"kind":"conditioning_session","key":"example.run","title":"Example run","category":"lic","domains":["aerobic_base"],
     "prescription":{"by":["minutes"],"minutes":{"min":30,"max":90}},"structure":{"type":"continuous"}}
    """.trimIndent()

    private val hills = """
    {"kind":"conditioning_session","key":"example.hills","title":"Example hills","category":"hic","domains":["anaerobic_capacity"],
     "prescription":{"by":["rounds"],"rounds":{"min":5,"max":10}},"structure":{"type":"repeats","steps":["sprint up","walk down"]}}
    """.trimIndent()

    private val composition = """
    {"kind":"composition","key":"example.pairing","title":"Example pairing","weeks":{"min":6,"max":12},
     "strength":{"choose_from":["example.two_day"]},"conditioning":{"choose_from":["example.protocol"]}}
    """.trimIndent()

    @Test
    fun aValidModuleParsesAndValidates() {
        val d = parseDefinition(module) as StrengthModuleDef
        assertEquals(2, d.variants.single().weeks.size)
        assertEquals(3.0, d.variants.single().weeks[0].sessions[0].items[0].sets!!.min, 0.0)
        assertTrue(validateDefinition(d).isEmpty())
    }

    @Test
    fun roundTripIsLossless() {
        val d = parseDefinition(module)
        assertEquals(d, parseDefinition(encodeDefinition(d)))
    }

    @Test
    fun unknownFieldsAreRejected() {
        try {
            parseDefinition(module.replace("\"family\":\"example\"", "\"family\":\"example\",\"typo\":1"))
            fail("expected a SerializationException")
        } catch (_: SerializationException) {}
    }

    @Test
    fun scalarAndRangeNumbersBothParse() {
        val d = parseDefinition(module) as StrengthModuleDef
        val items = d.variants.single().weeks.flatMap { it.sessions }.flatMap { it.items }
        assertEquals(5.0, items[0].reps!!.min, 0.0)
        assertTrue(items[0].reps!!.isFixed)
        assertEquals(3.0, items[0].sets!!.min, 0.0)
        assertEquals(5.0, items[0].sets!!.max, 0.0)
    }

    @Test
    fun structuralMistakesAreReported() {
        val badSlot = parseDefinition(module.replace("\"slot\":\"A\",\"sets\":{\"min\":3", "\"slot\":\"Z\",\"sets\":{\"min\":3"))
        assertTrue(validateDefinition(badSlot).any { "unknown slot 'Z'" in it })

        val noPeak = parseDefinition(module.replace(Regex(",\\s*\"peak\":\\{[^}]*\\}"), ""))
        assertTrue(validateDefinition(noPeak).toString(), validateDefinition(noPeak).any { "no peak definition" in it })

        val badWeek = parseDefinition(module.replace("{\"week\":2,", "{\"week\":3,"))
        assertTrue(validateDefinition(badWeek).any { "numbered 1.." in it })

        val badLoad = parseDefinition(module.replace("\"value\":70", "\"value\":null").replace("\"load\":{\"kind\":\"pct_1rm\",\"value\":null}", "\"load\":{\"kind\":\"pct_1rm\"}"))
        assertTrue(validateDefinition(badLoad).any { "pct_1rm needs value" in it })
    }

    @Test
    fun aConsistentSetValidatesAndReferencesAreChecked() {
        val defs = listOf(module, protocol, session, hills, composition).map(::parseDefinition)
        val ok = validateSet(defs)
        assertTrue(ok.errors.toString(), ok.ok)
        assertTrue(ok.warnings.isEmpty())

        val broken = validateSet(defs.filter { it.key != "example.protocol" })
        assertTrue(broken.errors.any { "unknown conditioning protocol 'example.protocol'" in it })

        val missingSession = validateSet(defs.filter { it.key != "example.hills" })
        assertTrue(missingSession.warnings.any { "example.hills" in it })
    }

    @Test
    fun aPolarizedProtocolNeedsItsBudget() {
        val d = parseDefinition(protocol.replace("\"high_intensity_per_week\":1", "\"session_min_minutes\":25")
            .replace("\"low_intensity_minutes_per_week\":{\"min\":100,\"max\":160},", ""))
        val errors = validateDefinition(d)
        assertTrue(errors.any { "low-intensity weekly budget" in it })
    }

    @Test
    fun clusterSlotsCarryTheirPickRange() {
        val d = parseDefinition(module.replace("{\"id\":\"A\",\"role\":\"press\",\"standard\":\"Press\"}", "{\"id\":\"A\",\"role\":\"press\",\"standard\":\"Press\",\"pick\":{\"min\":2,\"max\":3}}")) as StrengthModuleDef
        assertEquals(3.0, d.slots[0].pick!!.max, 0.0)
        assertTrue(validateDefinition(d).isEmpty())
        assertTrue(renderDefinition(d).contains("[cluster: pick 2-3]"))
    }

    @Test
    fun theRenderingStatesTheStructure() {
        val text = renderDefinition(parseDefinition(module))
        assertTrue(text.contains("Week 1"))
        assertTrue(text.contains("3-5 x 5 70% 1RM"))
        assertTrue(text.contains("[peak]"))
        assertTrue(text.contains("Progression: +2.5-5 lb upper"))
    }
}
