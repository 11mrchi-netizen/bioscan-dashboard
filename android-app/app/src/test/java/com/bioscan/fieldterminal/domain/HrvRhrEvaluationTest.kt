package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

// Analysis Layer 3 / R1: evaluateSwcStream's optional windowStart restarts a baseline after a confirmed series break.
// There was no direct unit test for the SWC machinery before this file.
class HrvRhrEvaluationTest {
    private val asOf = LocalDate.of(2026, 10, 12)

    // 90 days ending asOf: the first 60 around 50, the last 30 around 80 (a regime change 30 days before asOf).
    private val series: List<Pair<LocalDate, Double>> =
        (0 until 90).map { i -> asOf.minusDays((89 - i).toLong()) to (if (i < 60) 50.0 else 80.0) + (i % 3) }

    private val breakDate: LocalDate = asOf.minusDays(29) // first day of the new regime

    @Test
    fun testShift_withoutWindowStart_readsAsShiftUp() {
        assertEquals(EvalState.ShiftUp, evaluateSwcStream(series, asOf).state)
    }

    @Test
    fun testWindowStart_restartsBaseline_asBuildingWithRealCount() {
        val e = evaluateSwcStream(series, asOf, windowStart = breakDate)
        assertEquals(EvalState.Building, e.state)
        assertEquals(Confidence(30, 60), e.confidence)
    }

    @Test
    fun testWindowStart_null_isIdenticalToDefault() {
        assertEquals(evaluateSwcStream(series, asOf), evaluateSwcStream(series, asOf, windowStart = null))
    }

    @Test
    fun testWindowStart_afterAsOf_isNoData() {
        assertEquals(EvalState.NoData, evaluateSwcStream(series, asOf, windowStart = asOf.plusDays(1)).state)
    }

    @Test
    fun testWindowStart_isInclusive() {
        // Starting exactly on the first day keeps that day: 30 readings, not 29.
        assertEquals(30, evaluateSwcStream(series, asOf, windowStart = breakDate).confidence.have)
    }

    @Test
    fun testHrvRhrAndSleepWrappers_passWindowStartThrough() {
        assertEquals(EvalState.Building, evaluateHrv(series, asOf, windowStart = breakDate).state)
        assertEquals(EvalState.Building, evaluateRhr(series, asOf, windowStart = breakDate).state)
        assertEquals(EvalState.Building, evaluateSleepDuration(series, asOf, windowStart = breakDate).state)
        assertEquals(EvalState.Building, evaluateSleepStage(series, asOf, windowStart = breakDate).state)
        // and without a start they still resolve a real state over the full history
        assertEquals(EvalState.ShiftUp, evaluateRhr(series, asOf).state)
    }
}
