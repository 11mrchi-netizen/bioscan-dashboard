package com.bioscan.fieldterminal.domain.score

import com.bioscan.fieldterminal.domain.ScorecardVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random
import kotlin.math.exp

class ScoreQualityTest {
    private val start = LocalDate.of(2026, 1, 1)
    private val today = LocalDate.of(2026, 11, 3)

    /**
     * [days] mornings of domain scores ending the day before [today]'s month, and HRV whose next-morning value follows
     * [driver]'s score by [effect] (0 = no relation).
     */
    private fun data(days: Int, driver: DomainKey?, effect: Double, seed: Long = 11): Pair<Map<DomainKey, Map<LocalDate, Double>>, Series> {
        val r = Random(seed)
        val first = today.withDayOfMonth(1).minusDays(days.toLong() + 1)
        val domains = DomainKey.entries.associateWith { HashMap<LocalDate, Double>() }
        val hrv = HashMap<LocalDate, Double>()
        // 30 earlier mornings of HRV so the residual has its 28-day baseline from the first scored morning.
        for (i in -30 until days + 1) hrv[first.plusDays(i.toLong())] = 50.0 * exp(0.05 * r.nextGaussian())
        for (i in 0 until days) {
            val d = first.plusDays(i.toLong())
            for (k in DomainKey.entries) domains.getValue(k)[d] = 80.0 + 20.0 * r.nextGaussian()
            if (driver != null) {
                val z = (domains.getValue(driver).getValue(d) - 80.0) / 20.0
                hrv[d.plusDays(1)] = 50.0 * exp(effect * z + 0.03 * r.nextGaussian())
            }
        }
        return domains to hrv
    }

    @Test
    fun tooFewMorningsKeepTheDefaults() {
        val (domains, hrv) = data(80, DomainKey.SLEEP, 0.1)
        val w = fitChargeWeights(domains, hrv, today)
        assertFalse(w.personal)
        assertEquals(CHARGE_WEIGHTS, w.weights)
        assertTrue(w.days < MIN_FIT_DAYS)
        assertTrue(w.sentence.contains("you have"))
    }

    @Test
    fun aDomainThatForecastsTheOutcomeGainsWeight() {
        val (domains, hrv) = data(200, DomainKey.SLEEP, 0.1)
        val w = fitChargeWeights(domains, hrv, today)
        assertTrue(w.sentence, w.personal)
        assertEquals(1.0, w.weights.values.sum(), 1e-9)
        assertTrue(w.weights.getValue(DomainKey.SLEEP) > CHARGE_WEIGHTS.getValue(DomainKey.SLEEP) + 0.1)
        // Pulled halfway to the defaults and floored: no domain is dropped.
        assertTrue(w.weights.values.all { it >= MIN_WEIGHT * 0.8 })
        assertEquals(today.withDayOfMonth(1).minusDays(1), w.fittedThrough)
    }

    @Test
    fun noiseNeverStraysFarFromTheDefaults() {
        for (seed in 1L..5L) {
            val (domains, hrv) = data(200, null, 0.0, seed)
            val w = fitChargeWeights(domains, hrv, today)
            // Either the defaults, or (adopted by chance) still at least half of each default after shrinking.
            for (k in DomainKey.entries) assertTrue("$seed $k ${w.weights}", w.weights.getValue(k) >= 0.45 * CHARGE_WEIGHTS.getValue(k))
        }
    }

    @Test
    fun theFitIgnoresTheCurrentMonth() {
        val (domains, hrv) = data(200, DomainKey.SLEEP, 0.1)
        // Mornings in the current month, with an opposite relation, must not change the fit.
        val late = domains.mapValues { (_, m) -> m + (today.minusDays(1) to 20.0) }
        val hrvLate = hrv + (today to 200.0)
        assertEquals(fitChargeWeights(domains, hrv, today).weights, fitChargeWeights(late, hrvLate, today).weights)
    }

    @Test
    fun everyScoreGetsAScorecard() {
        val r = Random(3)
        val days = 220
        val series = HashMap<String, HashMap<LocalDate, Double>>()
        fun put(k: String, d: LocalDate, v: Double) { series.getOrPut(k) { HashMap() }[d] = v }
        for (i in 0 until days) {
            val d = start.plusDays(i.toLong())
            put(ScoreKeys.HRV, d, 50.0 * exp(0.08 * r.nextGaussian()))
            put(ScoreKeys.RHR, d, 50.0 + 1.5 * r.nextGaussian())
            put(ScoreKeys.SLEEP_HOURS, d, 7.2 + 0.5 * r.nextGaussian())
            put(ScoreKeys.SLEEP_DEEP_REM, d, 150.0 + 15 * r.nextGaussian())
            put(ScoreKeys.TSB, d, -10.0 + 8 * r.nextGaussian())
            put(ScoreKeys.DAYS_WITHOUT_REST, d, (i % 5).toDouble())
            put(ScoreKeys.STRESSED_SHARE, d, 25.0 + 5 * r.nextGaussian())
            put(ScoreKeys.RELAXED_SHARE, d, 30.0 + 5 * r.nextGaussian())
        }
        val engine = ScoreEngine(series)
        val last = start.plusDays(days - 1L)
        val history = engine.history(last.minusDays(QUALITY_HISTORY_DAYS - 1L), last)
        val q = scoreQuality(history, series, emptyMap(), defaultWeights())
        assertNotNull(q.charge)
        assertTrue(q.charge!!.days > 0)
        // BALANCE has only two of its inputs here, under its coverage gate, so it is PARTIAL and has no scorecard.
        assertEquals(setOf(DomainKey.SLEEP, DomainKey.RECOVERY, DomainKey.LOAD), q.domains.keys)
        assertTrue(q.domains.values.all { it.verdict in ScorecardVerdict.entries })
        assertEquals(null, q.chargePlanDone)
        // CHARGE combined LOAD through its fatigue side, recorded for the weight fit.
        assertTrue(history.chargeInputs.getValue(DomainKey.LOAD).isNotEmpty())
    }
}
