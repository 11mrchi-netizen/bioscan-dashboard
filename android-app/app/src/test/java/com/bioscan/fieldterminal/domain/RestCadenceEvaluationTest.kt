package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RestCadenceEvaluationTest {

    @Test
    fun weeklyHistoryFlagsTheSameDeloadWeekEvaluateRestCadenceFinds() {
        val asOf = LocalDate.of(2026, 9, 29)
        // 16 weeks of constant daily load, except week index 5 (a clear deload).
        val loads = (0 until 112).map { daysAgo ->
            val weekIndex = daysAgo / 7
            asOf.minusDays(daysAgo.toLong()) to if (weekIndex == 5) 50.0 / 7 else 300.0 / 7
        }

        val history = weeklyRestCadenceHistory(loads, weeks = 8, asOf = asOf)
        assertEquals(8, history.size)
        assertEquals((0..7).toList(), history.map { it.weekIndex })

        val deloadWeek = history.single { it.isDeload }
        assertEquals(5, deloadWeek.weekIndex)
        assertFalse(history.first { it.weekIndex == 0 }.isDeload)

        // evaluateRestCadence's own search must agree on the same week.
        val eval = evaluateRestCadence(loads, tsb = 0.0, tsbGateMet = true, asOf = asOf)
        assertEquals(5, eval.weeksSinceDeload)
        assertEquals(DeloadCadenceFlag.Soft, eval.deloadCadenceFlag)
    }

    @Test
    fun emptyHistoryGivesNoDeloadWeeks() {
        val history = weeklyRestCadenceHistory(emptyList(), weeks = 8, asOf = LocalDate.of(2026, 9, 29))
        assertEquals(8, history.size)
        assertTrue(history.none { it.isDeload })
    }
}
