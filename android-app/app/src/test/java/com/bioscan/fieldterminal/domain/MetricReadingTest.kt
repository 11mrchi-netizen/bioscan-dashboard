package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.score.InputDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MetricReadingTest {
    private val today = LocalDate.of(2026, 11, 3)

    private fun steady(value: (Int) -> Double, days: Int = 60) = (1..days).associate { today.minusDays(it.toLong()) to value(it) }

    @Test
    fun readsTheLatestAgainstTheNormalBand() {
        val history = steady({ 50.0 + (it % 5) - 2 }) + (today to 60.0)
        val r = readMetric(history, today, minSd = 1.0)
        assertEquals(today, r.latestDate)
        assertEquals(60.0, r.latest!!, 1e-9)
        val (lo, hi) = r.normal!!
        assertTrue(lo < 50 && hi > 50 && hi < 60)
        assertEquals(InputDirection.ABOVE, r.position)
        assertEquals(10.0, r.change!!, 1.0)
    }

    @Test
    fun aMissingTodayFallsBackToTheLatestRecentReading() {
        val history = steady({ 50.0 })
        val r = readMetric(history, today, minSd = 1.0)
        assertEquals(today.minusDays(1), r.latestDate)
        assertEquals(InputDirection.NORMAL, r.position)
    }

    @Test
    fun tooLittleHistoryHasNoBandAndAStaleReadingIsNone() {
        val short = steady({ 50.0 }, days = 5)
        assertNull(readMetric(short, today).normal)
        assertNull(readMetric(short, today).position)
        val stale = mapOf(today.minusDays(30) to 50.0)
        assertNull(readMetric(stale, today).latest)
    }

    @Test
    fun logBandsAreMultiplicative() {
        // HRV-like: the band is symmetric around the median on the log scale, so it reaches further above than below in ms.
        val history = steady({ 40.0 + (it * 7 % 11) * 2 })
        val r = readMetric(history, today, log = true, minSd = 0.03)
        val (lo, hi) = r.normal!!
        val logs = (2..61).mapNotNull { history[today.minusDays(it.toLong())] }.map { kotlin.math.ln(it) }.sorted()
        val med = kotlin.math.exp((logs[logs.size / 2] + logs[(logs.size - 1) / 2]) / 2)
        assertEquals(kotlin.math.ln(hi / med), kotlin.math.ln(med / lo), 1e-6)
        assertTrue(hi - med > med - lo)
    }
}
