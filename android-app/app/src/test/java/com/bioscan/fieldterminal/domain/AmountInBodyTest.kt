package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AmountInBodyTest {
    private val t0 = Instant.parse("2026-11-01T08:00:00Z")

    @Test
    fun aDoseHalvesEachHalfLifeAndDosesAddUp() {
        val one = listOf(LoggedDose(t0, 10.0))
        assertEquals(10.0, amountInBody(one, 17.5, t0), 1e-9)
        assertEquals(5.0, amountInBody(one, 17.5, t0.plus(Duration.ofMinutes(1050))), 1e-6)
        assertEquals(0.0, amountInBody(one, 17.5, t0.minusSeconds(60)), 1e-9)
        // Daily 2.5 mg with a 17.5 h half-life settles near 2.5 / (1 - 0.5^(24/17.5)) ≈ 4.0 mg just after a dose.
        val daily = (0 until 10).map { LoggedDose(t0.plus(Duration.ofDays(it.toLong())), 2.5) }
        assertEquals(4.0, amountInBody(daily, 17.5, t0.plus(Duration.ofDays(9))), 0.1)
    }

    @Test
    fun theCurveKeepsEachPeakAndTheWindowFollowsTheHalfLife() {
        val doses = listOf(LoggedDose(t0.plus(Duration.ofMinutes(37)), 10.0))
        val curve = amountCurve(doses, 17.5, t0, t0.plus(Duration.ofDays(1)))
        assertTrue(curve.any { it.first == doses[0].at && it.second == 10.0 })
        assertEquals(3, curveWindowDays(17.5))
        assertEquals(40, curveWindowDays(192.0))
        assertEquals(60, curveWindowDays(1000.0))
    }
}
