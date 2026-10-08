package com.bioscan.fieldterminal.domain.training

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

// Placeholder data only.
class PlanComparisonTest {
    private val prescription = Json.parseToJsonElement(
        """[
        {"type":"lift","slot":"A","exercise":"Press","role":"main","sets":{"min":3,"max":5},"reps":5,"load":{"kind":"pct_1rm","pct":70,"target_kg":56.0,"loadable_kg":55.0}},
        {"type":"lift","slot":"B","exercise":"Pull","role":"main","sets":4,"reps":3,"load":{"kind":"added","pct":80,"added_kg":20.0}},
        {"type":"lift","slot":"C","exercise":"Row","role":"primary","load":{"kind":"work_up_rm","min_rm":2,"max_rm":3}},
        {"type":"conditioning","label":"Easy run","minutes":{"min":30,"max":60}},
        {"type":"note","text":"x"}]""",
    )

    @Test
    fun theFrozenPrescriptionReadsBack() {
        val lifts = parsePlannedLifts(prescription)
        assertEquals(listOf("Press", "Pull", "Row"), lifts.map { it.exercise })
        assertEquals(55.0, lifts[0].targetKg!!, 0.001)
        assertEquals(20.0, lifts[1].targetKg!!, 0.001)
        assertEquals(null, lifts[2].targetKg)
        assertEquals(30.0, parsePlannedConditioning(prescription)!!.minutes!!.min, 0.001)
    }

    @Test
    fun theVerdictIsAPlainStatement() {
        val press = parsePlannedLifts(prescription)[0]
        fun v(vararg s: Pair<Int, Double>) = compareToPlan(press, s.map { LoggedSet(it.first, it.second) })
        assertEquals(PlanVerdict.Done, v(10 to 20.0, 5 to 55.0, 5 to 55.0, 5 to 55.0))        // a warm-up set is ignored
        assertEquals(PlanVerdict.Done, v(5 to 55.0, 5 to 57.5, 5 to 55.0))                      // within one step
        assertEquals(PlanVerdict.Above, v(5 to 60.0, 5 to 60.0, 5 to 60.0))
        assertEquals(PlanVerdict.Below, v(5 to 50.0, 5 to 50.0, 5 to 50.0))
        assertEquals(PlanVerdict.Short, v(5 to 55.0, 5 to 55.0))                                // two sets, three planned
        assertEquals(PlanVerdict.Short, v(5 to 55.0, 4 to 55.0, 5 to 55.0))                     // a short rep
        assertEquals(PlanVerdict.NotLogged, compareToPlan(press, emptyList()))
        assertEquals(PlanVerdict.Open, compareToPlan(parsePlannedLifts(prescription)[2], listOf(LoggedSet(3, 90.0))))
    }
}
