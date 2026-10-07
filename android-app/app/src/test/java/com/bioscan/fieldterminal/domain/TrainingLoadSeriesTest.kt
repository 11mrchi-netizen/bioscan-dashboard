package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TrainingLoadSeriesTest {

    @Test
    fun lastPointMatchesEvaluateTrainingLoad() {
        val asOf = LocalDate.of(2026, 9, 25)
        val loads = (0 until 90 step 2).map { asOf.minusDays(it.toLong()) to (200.0 + it * 3 % 150) }

        val eval = evaluateTrainingLoad(loads, asOf)
        val last = trainingLoadSeries(loads, asOf).last()

        assertEquals(asOf, last.date)
        assertEquals(eval.ctl!!, last.ctl, 1e-9)
        assertEquals(eval.atl!!, last.atl, 1e-9)
        assertEquals(eval.tsb!!, last.tsb, 1e-9)
    }

    @Test
    fun emptyLoadsGiveEmptySeries() {
        assertEquals(0, trainingLoadSeries(emptyList()).size)
    }
}
