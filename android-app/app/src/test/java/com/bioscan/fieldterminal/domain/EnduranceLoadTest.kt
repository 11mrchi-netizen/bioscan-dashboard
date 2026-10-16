package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

// TRIMP golden values, shared with the server (analysis-core/trainingLoad.test.ts) and the console
// (console/src/lib/training.test.ts): the three must agree to the digit.
class EnduranceLoadTest {

    private fun session(min: Double, avg: Double, max: Double? = null) = EnduranceSessionInput(min, null, avg, max, null, null, null)

    @Test
    fun goldenValues() {
        // An easy hour at 148 bpm against resting 53 and a personal maximum of 196.
        assertEquals(91.34231903347344, trimp(session(60.0, 148.0, 160.0), 53.0, 196.0)!!, 1e-9)
        // What dividing by the session's own peak (160) used to give: about twice as much.
        assertEquals(187.49991909716343, trimp(session(60.0, 148.0), 53.0, 160.0)!!, 1e-9)
        assertEquals(112.37154241030903, trimp(session(40.0, 175.0), 53.0, 196.0)!!, 1e-9)
        // An average above the maximum is capped at the maximum.
        assertEquals(130.96240261038238, trimp(session(30.0, 210.0), 53.0, 196.0)!!, 1e-9)
    }

    @Test
    fun missingOrImpossibleInputsGiveNothing() {
        assertNull(trimp(session(60.0, 148.0), null, 196.0))
        assertNull(trimp(session(60.0, 148.0), 53.0, null))
        assertNull(trimp(session(60.0, 50.0), 53.0, 196.0)) // average below resting
        assertNull(trimp(session(0.0, 148.0), 53.0, 196.0))
        assertNull(trimp(session(60.0, 148.0), 60.0, 60.0))
    }

    @Test
    fun anchorsAreTakenOnTheSessionsDate() {
        val d = LocalDate.of(2026, 6, 1)
        val anchors = TrimpAnchors(
            sessionMaxima = listOf(d.minusDays(20) to 190.0, d.minusDays(10) to 186.0, d.plusDays(5) to 199.0, d.plusDays(6) to 198.0),
            restingDaily = listOf(d.minusDays(40) to 60.0, d.minusDays(1) to 52.0, d.plusDays(10) to 48.0),
        )
        assertEquals(186.0, anchors.maxOn(d)!!, 0.0)
        assertEquals(198.0, anchors.maxOn(d.plusDays(6))!!, 0.0)
        assertEquals(52.0, anchors.restingOn(d)!!, 0.0)
        assertEquals(50.0, anchors.restingOn(d.plusDays(10))!!, 0.0) // median of 52 and 48
        // Before the first resting reading: the median of the first 30 days that have one (only the 60 here).
        assertEquals(60.0, anchors.restingOn(d.minusDays(100))!!, 0.0)
        assertEquals(trimp(session(60.0, 148.0), 52.0, 186.0), anchors.trimpOn(d, session(60.0, 148.0)))
    }
}
