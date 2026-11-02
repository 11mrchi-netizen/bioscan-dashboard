package com.bioscan.fieldterminal.domain.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Random

class ScoreScaleTest {
    @Test
    fun anchorsLandOnTheirScores() {
        assertEquals(80.0, ScoreScale.fromPercentile(0.50), 0.01)
        assertEquals(100.0, ScoreScale.fromPercentile(0.90), 0.01)
        assertEquals(60.0, ScoreScale.fromPercentile(0.25), 0.01)
        assertEquals(40.0, ScoreScale.fromPercentile(0.10), 0.01)
        assertEquals(125.0, ScoreScale.fromPercentile(0.97), 0.05)
        assertEquals(150.0, ScoreScale.fromPercentile(0.9995), 0.01)
    }

    @Test
    fun scaleIsMonotonicAndBounded() {
        var last = -1.0
        var z = -5.0
        while (z <= 5.0) {
            val s = ScoreScale.fromZ(z)
            assertTrue(s >= last)
            assertTrue(s in 0.0..150.0)
            last = s
            z += 0.05
        }
    }

    @Test
    fun noUpsideStopsAtTheTopOfNormal() {
        assertEquals(100.0, ScoreScale.fromZ(3.0, upside = false), 1e-9)
        assertEquals(ScoreScale.fromZ(0.0), ScoreScale.fromZ(0.0, upside = false), 1e-9)
    }

    @Test
    fun toZInvertsFromZ() {
        for (s in listOf(10.0, 40.0, 72.5, 100.0, 131.0)) assertEquals(s, ScoreScale.fromZ(ScoreScale.toZ(s)), 1e-6)
    }

    @Test
    fun bands() {
        assertEquals(ScoreBand.LOW, ScoreScale.band(39.9))
        assertEquals(ScoreBand.BELOW_NORMAL, ScoreScale.band(40.0))
        assertEquals(ScoreBand.NORMAL, ScoreScale.band(60.0))
        assertEquals(ScoreBand.PRIMED, ScoreScale.band(100.0))
        assertEquals(ScoreBand.SUPERCHARGED, ScoreScale.band(125.0))
    }

    @Test
    fun aNewRecordIsPlacedByHowFarOutItIs() {
        val history = (0 until 180).map { 50.0 + (it % 20) } // 50..69, robust spread ~7.4
        val justAbove = ScoreScale.fromHistory(69.5, history)
        val farAbove = ScoreScale.fromHistory(120.0, history)
        val atRecord = ScoreScale.fromHistory(69.0, history)
        assertTrue("just above $justAbove", justAbove < 150.0 && justAbove >= atRecord)
        assertEquals(150.0, farAbove, 1e-9)
        // Inside the range it is the rank.
        assertEquals(ScoreScale.fromPercentile(ScoreScale.rankPercentile(60.0, history)), ScoreScale.fromHistory(60.0, history), 1e-9)
        val justBelow = ScoreScale.fromHistory(49.5, history)
        assertTrue(justBelow > 0.0 && justBelow <= ScoreScale.fromHistory(50.0, history))
    }

    @Test
    fun rankPercentileCountsTiesHalf() {
        assertEquals(0.5, ScoreScale.rankPercentile(5.0, listOf(5.0)), 1e-9)
        assertEquals((3 + 0.5) / 5.0, ScoreScale.rankPercentile(10.0, listOf(1.0, 2.0, 3.0, 20.0)), 1e-9)
    }
}

class ScoreInputsTest {
    private val day = LocalDate.of(2026, 11, 3)

    private fun history(values: List<Double>, today: Double): Series =
        values.withIndex().associate { (i, v) -> day.minusDays((values.size - i).toLong()) to v } + (day to today)

    private val flat60 = List(60) { i -> 50.0 + (i % 5) - 2.0 } // median 50, robust SD 1.4826

    @Test
    fun baselineAtTheMedianIsATypicalDay() {
        val spec = InputSpec("x", "X", 1.0, InputKind.Baseline(betterHigher = true))
        val s = scoreInput(spec, history(flat60, 50.0), day)!!
        assertEquals(80.0, s.score, 1e-6)
        assertEquals(InputDirection.NORMAL, s.direction)
        assertEquals(50.0, s.reference!!, 1e-9)
    }

    @Test
    fun baselineFollowsDirection() {
        val higher = InputSpec("x", "X", 1.0, InputKind.Baseline(betterHigher = true))
        val lower = InputSpec("x", "X", 1.0, InputKind.Baseline(betterHigher = false))
        val up = scoreInput(higher, history(flat60, 55.0), day)!!
        val down = scoreInput(lower, history(flat60, 55.0), day)!!
        assertTrue(up.score > 100)
        assertTrue(down.score < 40)
        assertEquals(InputDirection.ABOVE, up.direction)
        assertEquals(InputDirection.ABOVE, down.direction) // the value is above; the score says whether that is good
    }

    @Test
    fun baselineNeedsHistory() {
        val spec = InputSpec("x", "X", 1.0, InputKind.Baseline(betterHigher = true))
        assertNull(scoreInput(spec, history(List(5) { 50.0 }, 50.0), day))
        assertNull(scoreInput(spec, mapOf(day.minusDays(1) to 50.0), day))
    }

    @Test
    fun stabilityPeaksAtUsual() {
        val spec = InputSpec("x", "X", 1.0, InputKind.Stability())
        assertEquals(100.0, scoreInput(spec, history(flat60, 50.0), day)!!.score, 1e-6)
        val off = scoreInput(spec, history(flat60, 56.0), day)!!
        val offOther = scoreInput(spec, history(flat60, 44.0), day)!!
        assertTrue(off.score < 80)
        assertEquals(off.score, offOther.score, 1e-6)
    }

    @Test
    fun targetsScoreTheTopOfNormalWhenMet() {
        val atLeast = InputSpec("p", "Protein", 1.0, InputKind.AtLeast(1.0, 0.2))
        assertEquals(100.0, scoreInput(atLeast, mapOf(day to 1.3), day)!!.score, 1e-6)
        assertEquals(InputDirection.ON_TARGET, scoreInput(atLeast, mapOf(day to 1.3), day)!!.direction)
        val short = scoreInput(atLeast, mapOf(day to 0.8), day)!!
        assertEquals(InputDirection.SHORT, short.direction)
        assertTrue(short.score in 60.0..90.0)

        val band = InputSpec("e", "Energy", 1.0, InputKind.Band(0.9, 1.1, 0.15))
        assertEquals(100.0, scoreInput(band, mapOf(day to 1.0), day)!!.score, 1e-6)
        assertEquals(InputDirection.OVER, scoreInput(band, mapOf(day to 1.4), day)!!.direction)
        assertEquals(
            scoreInput(band, mapOf(day to 0.6), day)!!.score,
            scoreInput(band, mapOf(day to 1.4), day)!!.score, 1e-6,
        )
    }
}

class ScoreDomainsTest {
    private val day = LocalDate.of(2026, 11, 3)

    @Test
    fun missingInputsShareTheirWeight() {
        // Only the two protein-like targets present, both met: coverage is low, so FUEL is PARTIAL.
        val series = mapOf(ScoreKeys.FUEL_LOGGED_SHARE to mapOf(day to 1.0), ScoreKeys.PROTEIN_RATIO to mapOf(day to 1.2))
        val raw = combineDomain(ScoreSpecs.FUEL, series, day)
        assertNull(raw.raw)
        assertTrue(raw.coverage < ScoreSpecs.MIN_COVERAGE)
    }

    @Test
    fun fuelIntakeNeedsACompleteEnoughLog() {
        val full = mapOf(
            ScoreKeys.FUEL_LOGGED_SHARE to mapOf(day to 1.0),
            ScoreKeys.ENERGY_RATIO to mapOf(day to 1.0),
            ScoreKeys.PROTEIN_RATIO to mapOf(day to 1.0),
            ScoreKeys.CARB_RATIO to mapOf(day to 1.0),
            ScoreKeys.WATER_RATIO to mapOf(day to 1.0),
        )
        assertEquals(100.0, combineDomain(ScoreSpecs.FUEL, full, day).raw!!, 1e-6)
        val halfLogged = full + (ScoreKeys.FUEL_LOGGED_SHARE to mapOf(day to 0.4))
        assertNull(combineDomain(ScoreSpecs.FUEL, halfLogged, day).raw)
    }

    @Test
    fun aCriticalInputCapsItsDomain() {
        val series = mapOf(
            ScoreKeys.FUEL_LOGGED_SHARE to mapOf(day to 1.0),
            ScoreKeys.ENERGY_RATIO to mapOf(day to 0.4), // far under need
            ScoreKeys.PROTEIN_RATIO to mapOf(day to 1.5),
            ScoreKeys.CARB_RATIO to mapOf(day to 1.5),
            ScoreKeys.WATER_RATIO to mapOf(day to 1.5),
        )
        val raw = combineDomain(ScoreSpecs.FUEL, series, day)
        val energy = raw.inputs.first { it.spec.key == ScoreKeys.ENERGY_RATIO }.score
        assertTrue(raw.raw!! <= energy + CRITICAL_HEADROOM + 1e-9)
    }

    @Test
    fun noUpsideDomainsStopAt100() {
        val series = mapOf(ScoreKeys.TSB to mapOf(day to -10.0), ScoreKeys.DAYS_WITHOUT_REST to mapOf(day to 1.0))
        assertEquals(100.0, combineDomain(ScoreSpecs.LOAD, series, day).raw!!, 1e-6)
    }
}

class ScoreEngineTest {
    private val start = LocalDate.of(2026, 1, 1)
    private val days = 240

    /** A person whose nights and days wander around their norm, with optional overrides on the last morning. */
    private fun person(seed: Long = 7, last: Map<String, Double> = emptyMap()): Map<String, Series> {
        val r = Random(seed)
        val out = HashMap<String, HashMap<LocalDate, Double>>()
        fun put(key: String, d: LocalDate, v: Double) { out.getOrPut(key) { HashMap() }[d] = v }
        for (i in 0 until days) {
            val d = start.plusDays(i.toLong())
            put(ScoreKeys.HRV, d, 50.0 * kotlin.math.exp(0.08 * r.nextGaussian()))
            put(ScoreKeys.RHR, d, 50.0 + 1.5 * r.nextGaussian())
            put(ScoreKeys.SLEEP_HOURS, d, 7.2 + 0.5 * r.nextGaussian())
            put(ScoreKeys.SLEEP_DEEP_REM, d, 150.0 + 15 * r.nextGaussian())
            put(ScoreKeys.SLEEP_MIDPOINT, d, 190.0 + 20 * r.nextGaussian())
            put(ScoreKeys.TSB, d, -10.0 + 8 * r.nextGaussian())
            put(ScoreKeys.DAYS_WITHOUT_REST, d, (i % 5).toDouble())
            put(ScoreKeys.STRESSED_SHARE, d, 25.0 + 5 * r.nextGaussian())
            put(ScoreKeys.RELAXED_SHARE, d, 30.0 + 5 * r.nextGaussian())
            put(ScoreKeys.FUEL_LOGGED_SHARE, d, 1.0)
            put(ScoreKeys.ENERGY_RATIO, d, 1.0 + 0.1 * r.nextGaussian())
            put(ScoreKeys.PROTEIN_RATIO, d, 1.0 + 0.15 * r.nextGaussian())
            put(ScoreKeys.CARB_RATIO, d, 1.0 + 0.15 * r.nextGaussian())
            put(ScoreKeys.WATER_RATIO, d, 1.0 + 0.15 * r.nextGaussian())
        }
        val lastDay = start.plusDays(days - 1L)
        last.forEach { (k, v) -> put(k, lastDay, v) }
        return out
    }

    private val lastDay get() = start.plusDays(days - 1L)

    @Test
    fun aTypicalHistoryCentresOnEighty() {
        val engine = ScoreEngine(person())
        val strip = engine.chargeStrip(lastDay.minusDays(59), lastDay).mapNotNull { it.second }
        assertEquals(60, strip.size)
        val median = strip.sorted()[30]
        assertTrue("median $median", median in 65.0..95.0)
        val above100 = strip.count { it >= 100 }
        assertTrue("$above100 of 60 above 100", above100 in 1..15)
    }

    @Test
    fun aGreatMorningIsPrimedOrBetter() {
        val engine = ScoreEngine(person(last = mapOf(
            ScoreKeys.HRV to 64.0, ScoreKeys.RHR to 46.5, ScoreKeys.SLEEP_HOURS to 8.4, ScoreKeys.SLEEP_DEEP_REM to 185.0,
            ScoreKeys.STRESSED_SHARE to 12.0, ScoreKeys.RELAXED_SHARE to 42.0,
        )))
        val day = engine.score(lastDay)
        assertTrue("charge ${day.charge.score}", day.charge.score!! >= 100)
        assertEquals(AnsState.BALANCED, day.ansState)
        assertTrue(day.domains.getValue(DomainKey.RECOVERY).score!! > 100)
    }

    @Test
    fun aRoughMorningIsLowAndDriversExplainIt() {
        val engine = ScoreEngine(person(last = mapOf(
            ScoreKeys.HRV to 36.0, ScoreKeys.RHR to 55.0, ScoreKeys.SLEEP_HOURS to 5.4, ScoreKeys.TSB to -40.0,
        )))
        val day = engine.score(lastDay)
        val charge = day.charge
        assertTrue("charge ${charge.score}", charge.score!! < 60)
        assertEquals(AnsState.SYMPATHETIC, day.ansState)
        assertEquals(LoadDirection.HIGH, day.loadDirection)
        // Drivers sum to the distance from a typical day, and the biggest pull is down.
        assertEquals(charge.uncapped!! - ScoreScale.TYPICAL, charge.drivers.sumOf { it.points }, 0.5)
        assertTrue(charge.drivers.first().points < 0)
    }

    @Test
    fun aCapHoldsChargeDown() {
        val engine = ScoreEngine(person(last = mapOf(ScoreKeys.HRV to 64.0, ScoreKeys.SLEEP_HOURS to 8.4)), caps = { d ->
            if (d == lastDay) ChargeCap("Sore throat · day 2") else null
        })
        val charge = engine.score(lastDay).charge
        assertEquals(CAP_CEILING, charge.score!!, 1e-9)
        assertNotNull(charge.cap)
        assertTrue(charge.uncapped!! > CAP_CEILING)
    }

    @Test
    fun saturatedHrvGetsNoUpside() {
        // HRV up a lot, resting HR down too, with a sharply unstable week: SATURATED, so RECOVERY stays at or below 100.
        val base = person(last = mapOf(ScoreKeys.HRV to 70.0, ScoreKeys.RHR to 47.0))
        val cv = (0 until days).associate { start.plusDays(it.toLong()) to 4.0 + (it % 3) * 0.2 } + (lastDay to 12.0)
        val engine = ScoreEngine(base + (ScoreKeys.HRV_CV to cv))
        val day = engine.score(lastDay)
        assertEquals(AnsState.SATURATED, day.ansState)
        assertTrue(day.domains.getValue(DomainKey.RECOVERY).score!! <= 100.0 + 1e-9)
    }

    @Test
    fun chargeNeedsSleepOrRecovery() {
        val only = person().filterKeys { it == ScoreKeys.TSB || it == ScoreKeys.DAYS_WITHOUT_REST }
        assertNull(ScoreEngine(only).score(lastDay).charge.score)
    }

    @Test
    fun earlyDaysCalibrate() {
        val engine = ScoreEngine(person())
        val early = engine.score(start.plusDays(20))
        // Baselines need 14 earlier readings, so day 20 scores, but ranking needs 28 earlier mornings.
        assertTrue(early.charge.calibrating)
        assertFalse(engine.score(lastDay).charge.calibrating)
    }

    @Test
    fun partialDomainsAreLeftOutNotScoredLow() {
        val noFood = person().mapValues { (k, v) -> if (k == ScoreKeys.FUEL_LOGGED_SHARE) v + (lastDay to 0.2) else v }
        val day = ScoreEngine(noFood).score(lastDay)
        assertTrue(day.domains.getValue(DomainKey.FUEL).partial)
        assertFalse(DomainKey.FUEL in day.charge.domainsUsed)
        assertNotNull(day.charge.score)
    }

    @Test
    fun statusIsFinalOnceClosedOrAtNoon() {
        val d = LocalDate.of(2026, 11, 3)
        assertEquals(ChargeStatus.PROVISIONAL, chargeStatus(d, false, d.atTime(7, 12)))
        assertEquals(ChargeStatus.FINAL, chargeStatus(d, true, d.atTime(7, 12)))
        assertEquals(ChargeStatus.FINAL, chargeStatus(d, false, d.atTime(12, 0)))
        assertEquals(ChargeStatus.FINAL, chargeStatus(d.minusDays(1), false, LocalDateTime.of(d, java.time.LocalTime.of(7, 0))))
    }

    @Test
    fun trendThreshold() {
        assertEquals(ScoreTrend.UP, trendOf(5.0))
        assertEquals(ScoreTrend.SAME, trendOf(-2.0))
        assertEquals(ScoreTrend.DOWN, trendOf(-3.0))
        assertNull(trendOf(null))
    }
}
