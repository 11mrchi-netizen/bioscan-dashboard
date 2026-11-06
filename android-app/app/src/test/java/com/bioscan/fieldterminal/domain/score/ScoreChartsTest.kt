package com.bioscan.fieldterminal.domain.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random

class ScoreChartsTest {
    private val day = LocalDate.of(2026, 11, 3)

    private fun input(kind: InputKind, value: Double, reference: Double?, spread: Double?, direction: InputDirection = InputDirection.NORMAL) =
        InputScore(InputSpec("k", "K", 1.0, kind), value, reference, spread, 0.0, 80.0, direction)

    @Test
    fun baselineBulletShadesOneSpreadEitherSide() {
        val b = bulletOf(input(InputKind.Baseline(betterHigher = true), 52.0, 50.0, 2.0))!!
        assertEquals(48.0, b.zoneLo, 1e-9)
        assertEquals(52.0, b.zoneHi, 1e-9)
        assertEquals(44.0, b.min, 1e-9)
        assertEquals(56.0, b.max, 1e-9)
        assertNull(b.tick)
        assertEquals(1.0, b.fraction(56.0), 1e-9)
    }

    @Test
    fun logBaselineBulletIsInValueUnits() {
        val b = bulletOf(input(InputKind.Baseline(betterHigher = true, log = true), 50.0, 50.0, 0.1))!!
        assertTrue(b.zoneLo < 50.0 && b.zoneHi > 50.0)
        assertEquals(50.0 / b.zoneLo, b.zoneHi / 50.0, 1e-9) // symmetric on the log scale
    }

    @Test
    fun targetBulletsMarkTheTarget() {
        val atLeast = bulletOf(input(InputKind.AtLeast(1.0, 0.2), 0.8, 1.0, null, InputDirection.SHORT))!!
        assertEquals(1.0, atLeast.tick!!, 1e-9)
        assertEquals(1.0, atLeast.zoneLo, 1e-9)
        assertTrue(atLeast.fraction(0.8) < atLeast.fraction(1.0))
        val atMost = bulletOf(input(InputKind.AtMost(3.0, 1.5), 5.0, 3.0, null, InputDirection.OVER))!!
        assertEquals(3.0, atMost.zoneHi, 1e-9)
        assertTrue(atMost.max >= 5.0)
        val band = bulletOf(input(InputKind.Band(-25.0, 5.0, 10.0), -40.0, -25.0, null, InputDirection.SHORT))!!
        assertEquals(-45.0, band.min, 1e-9) // two scale-steps below the band
        assertEquals(-25.0, band.zoneLo, 1e-9)
    }

    private fun series(n: Int, f: (Int) -> Double): Series = (0 until n).associate { day.minusDays((n - 1 - it).toLong()) to f(it) }

    @Test
    fun ansPointsReadTheMorningAgainstItsBaseline() {
        val r = Random(5)
        val s = mapOf(
            ScoreKeys.HRV to series(80) { if (it == 79) 70.0 else 50.0 * kotlin.math.exp(0.05 * r.nextGaussian()) },
            ScoreKeys.RHR to series(80) { if (it == 79) 46.0 else 50.0 + r.nextGaussian() },
        )
        val points = ansPoints(s, day, 14)
        assertEquals(14, points.size)
        assertEquals(day, points.last().day)
        assertTrue(points.last().hrvZ > 2)
        assertTrue(points.last().rhrZ < -2)
    }

    @Test
    fun metricStatsSayHowTodaySits() {
        val r = Random(7)
        val s = series(120) { if (it == 119) 70.0 else 50.0 + 2 * r.nextGaussian() }
        val stats = metricStats(s, day, log = false, minSd = 0.5)
        assertNotNull(stats.sevenDay)
        assertTrue(stats.rarerThan!! > 0.95)
        assertEquals(30, stats.withReading)
        assertTrue(stats.insideBand!! in 10..30)
    }

    @Test
    fun relatedInputsFindWhatMovesTogether() {
        val r = Random(9)
        val x = (0 until 120).map { r.nextGaussian() }
        val s = mapOf(
            "a" to series(120) { x[it] },
            "b" to series(120) { 2 * x[it] + 0.3 * r.nextGaussian() },
            "c" to series(120) { r.nextGaussian() },
        )
        val rel = relatedInputs(s, "a", day, mapOf("a" to "A", "b" to "B", "c" to "C"))
        assertEquals("b", rel.first().key)
        assertTrue(rel.first().r > 0.9)
        assertFalse(rel.any { it.key == "a" })
    }

    @Test
    fun sparklineKeepsOrderAndSkipsGaps() {
        val s = mapOf(day to 3.0, day.minusDays(2) to 1.0)
        assertEquals(listOf(day.minusDays(2) to 1.0, day to 3.0), sparkline(s, day, 5))
    }
}
