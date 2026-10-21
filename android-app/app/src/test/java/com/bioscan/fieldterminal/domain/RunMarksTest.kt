package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RunMarksTest {
    private val day = LocalDate.of(2026, 10, 1)

    private fun history(vararg values: Double) = values.mapIndexed { i, v -> day.minusDays(2L * (values.size - i)) to v }

    @Test
    fun aChangeInsideTheUsualSpreadIsAsUsual() {
        val ef = history(1.10, 1.12, 1.14, 1.11, 1.13)
        val same = markAgainstUsual(1.13, ef, day, RunBaseline.ROAD, EF_RULE)
        assertEquals("AS USUAL", same.word)
        assertEquals(1.12, same.usual!!, 1e-9)
        assertEquals(MarkTone.IMPROVED, markAgainstUsual(1.25, ef, day, RunBaseline.ROAD, EF_RULE).tone)
        assertEquals("REGRESSED", markAgainstUsual(1.00, ef, day, RunBaseline.ROAD, EF_RULE).word)
    }

    @Test
    fun lowerIsBetterForDecouplingAndVerticalRatio() {
        val dec = history(4.0, 5.0, 6.0, 5.0)
        assertEquals("IMPROVED", markAgainstUsual(1.0, dec, day, RunBaseline.ROAD, DECOUPLING_RULE).word)
        assertEquals(MarkTone.REGRESSED, markAgainstUsual(10.0, dec, day, RunBaseline.ROAD, DECOUPLING_RULE).tone)
        val vr = history(8.0, 8.1, 7.9)
        assertEquals("IMPROVED", markAgainstUsual(7.2, vr, day, RunBaseline.ROAD, VERTICAL_RATIO_RULE).word)
    }

    @Test
    fun paceAndPowerOnlySayWhichWay() {
        val pace = history(5.5, 5.6, 5.5, 5.4)
        val fast = markAgainstUsual(5.0, pace, day, RunBaseline.ROAD, PACE_RULE)
        assertEquals("FASTER", fast.word)
        assertEquals(MarkTone.NEUTRAL, fast.tone)
        assertEquals("SLOWER", markAgainstUsual(6.2, pace, day, RunBaseline.ROAD, PACE_RULE).word)
        assertEquals("HIGHER", markAgainstUsual(300.0, history(250.0, 255.0, 248.0), day, RunBaseline.ROAD, POWER_RULE).word)
    }

    @Test
    fun aQuietStretchDoesNotFlagNoise() {
        // Five identical runs: no spread at all, so the floor (3 spm) decides.
        val cadence = history(170.0, 170.0, 170.0, 170.0, 170.0)
        assertEquals("AS USUAL", markAgainstUsual(172.0, cadence, day, RunBaseline.ROAD, CADENCE_RULE).word)
        assertEquals("HIGHER", markAgainstUsual(175.0, cadence, day, RunBaseline.ROAD, CADENCE_RULE).word)
    }

    @Test
    fun withTooFewEarlierRunsTheMarkIsBuilding() {
        assertEquals(MarkTone.BUILDING, markAgainstUsual(1.2, history(1.1, 1.2), day, RunBaseline.ROAD, EF_RULE).tone)
        // Trail: three trail runs spread over 45 days are enough within 60; road's 28 days sees only one.
        val trail = listOf(day.minusDays(45) to 1.0, day.minusDays(30) to 1.0, day.minusDays(10) to 1.0)
        assertEquals("AS USUAL", markAgainstUsual(1.01, trail, day, RunBaseline.TRAIL, EF_RULE).word)
        assertEquals(MarkTone.BUILDING, markAgainstUsual(1.01, trail, day, RunBaseline.ROAD, EF_RULE).tone)
    }

    // A runner who keeps 170 spm at every pace: stride (cm) = speed (m/min) / cadence × 100.
    private fun strideHistory(): List<StridePacePoint> =
        listOf(6.5, 6.0, 5.5, 5.0, 4.5, 6.2, 5.8, 5.2).mapIndexed { i, pace ->
            val speed = 1000.0 / pace
            StridePacePoint(day.minusDays(5L * (i + 1)), pace, speed / 170.0 * 100) // a steady 170 spm
        }

    @Test
    fun strideIsReadForThePace() {
        val h = strideHistory()
        val usualAt55 = 1000.0 / 5.5 / 170.0 * 100
        assertEquals("USUAL FOR THIS PACE", strideForPace(usualAt55, 5.5, h, day).word)
        val long = strideForPace(usualAt55 * 1.12, 5.5, h, day)
        assertEquals("LONG FOR THIS PACE", long.word)
        assertEquals(MarkTone.CAUTION, long.tone)
        assertEquals(usualAt55, long.usual!!, 0.5)
        assertEquals("SHORT FOR THIS PACE", strideForPace(usualAt55 * 0.88, 5.5, h, day).word)
        // A fast stride is long in absolute terms but usual for its pace.
        assertEquals("USUAL FOR THIS PACE", strideForPace(1000.0 / 4.6 / 170.0 * 100, 4.6, h, day).word)
    }

    @Test
    fun strideOutsideThePacesSeenOrWithFewRunsHasNoVerdict() {
        val h = strideHistory()
        assertEquals("PACE OUTSIDE USUAL RANGE", strideForPace(100.0, 3.5, h, day).word)
        assertEquals(MarkTone.BUILDING, strideForPace(100.0, 5.5, h.take(5), day).tone)
    }

    @Test
    fun theSessionFindsItsRunAndComparesWithItsOwnKind() {
        fun row(id: Long, daysAgo: Long, cat: RunCategory, pace: Double, ef: Double?) =
            RunHistoryRow(id, day.minusDays(daysAgo), 10.0, cat, pace, null, ef, null, null, null, null)
        val rows = listOf(
            row(1, 9, RunCategory.EASY, 6.0, 1.10), row(2, 7, RunCategory.EASY, 6.1, 1.12), row(3, 5, RunCategory.EASY, 6.0, 1.11),
            row(4, 8, RunCategory.QUALITY, 4.5, 1.15), row(5, 6, RunCategory.QUALITY, 4.4, 1.16), row(6, 4, RunCategory.QUALITY, 4.5, 1.14),
            row(7, 0, RunCategory.EASY, 6.0, 1.11),
        )
        val run = findRun(rows, 99, day, 10.2)!!
        assertEquals(7L, run.id)
        assertNull(findRun(rows, 99, day, 14.0))
        val marks = runDynamicsMarks(run, rows)
        // Against easy runs only: as usual, though quality runs are much faster.
        assertEquals("AS USUAL", marks.pace!!.word)
        assertEquals("easy", marks.categoryLabel)
        assertNull(marks.power)
        // The efficiency factor on road keeps its 6-run gate (6 earlier road runs here).
        assertEquals("AS USUAL", marks.efficiencyFactor!!.word)
    }
}
