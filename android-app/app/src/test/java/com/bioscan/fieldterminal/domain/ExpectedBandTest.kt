package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.BandEwma
import com.bioscan.fieldterminal.data.model.BandParams
import com.bioscan.fieldterminal.data.model.BandPoint
import com.bioscan.fieldterminal.data.model.ExpectedBandPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln

// Analysis review phase C: placing this morning against the published expected band. Synthetic values only.
class ExpectedBandTest {
    private val today = LocalDate.of(2026, 10, 15) // a Thursday

    // A log-scale HRV band centred on 50 ms with sigma 0.1 (80% band about 44-57 ms, 99% about 39-65 ms).
    private fun hrvPoint(date: LocalDate, value: Double?, flag: String? = null, side: String? = null, excluded: Boolean = false): BandPoint {
        val mu = ln(50.0)
        val s = 0.1
        return BandPoint(
            date = date.toString(), value = value, expected = 50.0,
            lo80 = exp(mu - 1.3 * s), hi80 = exp(mu + 1.3 * s), lo99 = exp(mu - 2.8 * s), hi99 = exp(mu + 2.8 * s),
            mu = mu, sigma = s, z = value?.let { (ln(it) - mu) / s }, flag = flag, side = side, excluded = excluded,
        )
    }

    private fun hrv(recent: List<BandPoint>, next: BandPoint? = null, ewma: BandEwma = BandEwma(0.3, 1.008, 0.0, null, true), state: String = "ready") =
        ExpectedBandPayload(metric = "hrv", unit = "ms", transform = "ln", better = "higher", state = state, params = BandParams(df = 60), recent = recent, next = next, ewma = ewma)

    @Test
    fun aReadingTheServerAlreadyJudgedKeepsItsFlag() {
        val p = hrv(listOf(hrvPoint(today, 46.0, flag = "run", side = "low")))
        val r = morningReadout(p, today, null)!!
        assertEquals(BandFlag(BandFlagKind.Run, low = true), r.flag)
        assertEquals(BandPosition.Within, r.position)
        assertEquals(StateWord(MetricState.Warning, "LOW LATELY"), expectedStateWord(r))
    }

    @Test
    fun aReadingAfterTheRunIsPlacedWithThePublishedCentreAndWidthAndStepsTheRunChart() {
        val yesterday = today.minusDays(1)
        val ewma = BandEwma(lambda = 0.3, limit = 1.008, value = -0.9, date = yesterday.toString(), runsActive = true)
        val p = hrv(listOf(hrvPoint(yesterday, 44.0)), next = hrvPoint(today, null), ewma = ewma)
        // z = -1.5: inside the 99% band, but 0.3 * -1.5 + 0.7 * -0.9 = -1.08 crosses the limit.
        val value = exp(ln(50.0) - 0.15)
        val r = morningReadout(p, today, today to value)!!
        assertEquals(BandFlag(BandFlagKind.Run, low = true), r.flag)
        assertEquals(BandPosition.Below, r.position)
        assertEquals(-13.9, r.difference, 0.1)
        assertTrue(r.differenceIsPercent)
        // Runs are not flagged while the band is provisional.
        val quiet = morningReadout(p.copy(ewma = ewma.copy(runsActive = false)), today, today to value)!!
        assertNull(quiet.flag)
        // Beyond the 99% band is an extreme flag on its own.
        val extreme = morningReadout(p, today, today to 36.0)!!
        assertEquals(BandFlag(BandFlagKind.Extreme, low = true), extreme.flag)
        assertEquals("UNUSUALLY LOW", expectedStateWord(extreme).word)
    }

    @Test
    fun insideTheBandReadsAsExpectedWithNoRarity() {
        val r = morningReadout(hrv(listOf(hrvPoint(today, 52.0))), today, null)!!
        assertEquals(StateWord(MetricState.Neutral, "AS EXPECTED"), expectedStateWord(r))
        assertNull(r.tailProbability)
        assertNull(r.rarityLine())
        assertEquals("52 ms, as expected for a Thursday.", r.headline())
        assertTrue(r.bandLine().startsWith("Expected 44–57 ms: 8 mornings in 10"))
    }

    @Test
    fun outsideTheBandStatesSizeAndRarityButIsNotFlaggedOrColoured() {
        val r = morningReadout(hrv(listOf(hrvPoint(today, 42.0))), today, null)!!
        assertEquals(BandPosition.Below, r.position)
        assertNull(r.flag)
        assertEquals(StateWord(MetricState.Neutral, "BELOW EXPECTED"), expectedStateWord(r))
        assertEquals("42 ms, 16% below what was expected for a Thursday.", r.headline())
        // z = ln(42/50) / 0.1 = -1.74: one-sided about 4.3% (t, 60 df), one morning in 23.
        assertEquals("A morning this low comes about once every 3 weeks when nothing has changed.", r.rarityLine())
    }

    @Test
    fun restingHeartRateAndSleepUseUnitsAndTheirOwnDirection() {
        val rhr = ExpectedBandPayload(
            metric = "rhr", unit = "bpm", transform = "none", better = "lower", state = "ready", params = BandParams(df = 60),
            recent = listOf(BandPoint(today.toString(), 61.0, 56.0, 53.4, 58.6, 50.4, 61.6, 56.0, 2.0, 2.5, "run", "high")),
        )
        val r = morningReadout(rhr, today, null)!!
        assertEquals("61 bpm, 5 bpm above what was expected for a Thursday.", r.headline())
        assertEquals(MetricState.Warning, expectedStateWord(r).state) // a high run is unfavorable for resting HR
        assertEquals(MetricState.Neutral, expectedStateWord(r.copy(flag = BandFlag(BandFlagKind.Run, low = true))).state)

        val sleep = ExpectedBandPayload(
            metric = "sleep_hours", unit = "h", transform = "none", better = "range", state = "ready", params = BandParams(df = 60),
            recent = listOf(BandPoint(today.toString(), 6.0, 7.2, 6.4, 8.0, 5.6, 8.8, 7.2, 0.6, -2.0, "run", "low")),
        )
        val s = morningReadout(sleep, today, null)!!
        assertEquals("6.0 h, 1.2 h shorter than what was expected for a Thursday.", s.headline())
        assertEquals(MetricState.Warning, expectedStateWord(s).state) // a short run outside 7-9 h
        assertEquals(MetricState.Neutral, expectedStateWord(s.copy(value = 7.4)).state) // a flag inside 7-9 h stays neutral
        assertTrue(s.rarityLine()!!.startsWith("A night this short"))
    }

    @Test
    fun nothingIsSaidWithoutABandOrAReading() {
        assertNull(morningReadout(hrv(listOf(hrvPoint(today, 50.0)), state = "building"), today, null))
        assertNull(morningReadout(hrv(listOf(hrvPoint(today, null))), today, today.minusDays(1) to 50.0))
        assertNull(morningReadout(hrv(listOf(hrvPoint(today.minusDays(1), 50.0))), today, null))
        val ill = morningReadout(hrv(listOf(hrvPoint(today, 38.0, flag = null, excluded = true))), today, null)!!
        assertEquals(StateWord(MetricState.Neutral, "ILLNESS LOGGED"), expectedStateWord(ill))
        assertNull(ill.flagLine())
    }

    @Test
    fun naturalFrequencies() {
        assertNull(naturalFrequency(0.3))
        assertEquals("about once every 10 mornings", naturalFrequency(0.1))
        assertEquals("about once every 3 weeks", naturalFrequency(1 / 21.0))
        assertEquals("about once a month", naturalFrequency(1 / 30.0))
        assertEquals("about once every two months", naturalFrequency(1 / 60.0))
        assertEquals("about once every 4 months", naturalFrequency(1 / 120.0))
        assertEquals("less than once a year", naturalFrequency(1 / 500.0))
    }

    @Test
    fun studentTMatchesKnownQuantiles() {
        assertEquals(0.5, studentTCdf(0.0, 10.0), 1e-12)
        assertEquals(0.975, studentTCdf(2.228138852, 10.0), 1e-6)
        assertEquals(0.025, studentTCdf(-2.228138852, 10.0), 1e-6)
        assertEquals(0.975, studentTCdf(1.959964, 1e6), 1e-5)
        assertEquals(0.995, studentTCdf(3.499483, 7.0), 1e-5)
    }

    @Test
    fun theLeadIsAFlaggedUnfavorableMetricElseHrv() {
        val base = morningReadout(hrv(listOf(hrvPoint(today, 50.0))), today, null)!!
        val rhrFlag = base.copy(metric = "rhr", better = "lower", flag = BandFlag(BandFlagKind.Extreme, low = false))
        val sleepCalm = base.copy(metric = "sleep_hours", better = "range")
        assertEquals("rhr", leadReadout(listOf(base, rhrFlag, sleepCalm))!!.metric)
        assertEquals("hrv", leadReadout(listOf(sleepCalm, base))!!.metric)
        assertNotNull(leadReadout(listOf(sleepCalm)))
    }
}
