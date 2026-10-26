package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HeatAndSweatTest {
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(d: LocalDate, h: Int) = ZonedDateTime.of(d.atTime(h, 0), zone)

    @Test
    fun theHeatIndexMatchesTheNwsTable() {
        // NWS table: 90 °F at 70 % humidity reads 106 °F (41.1 °C); 80 °F at 40 % reads 80 °F.
        assertEquals(41.1, heatIndexC(32.22, 70.0), 0.6)
        assertEquals(26.7, heatIndexC(26.67, 40.0), 0.6)
        // Below about 27 °C it is close to the air temperature.
        assertEquals(20.0, heatIndexC(20.0, 50.0), 1.0)
    }

    @Test
    fun heatBandsFollowTheHeatIndex() {
        assertEquals(HeatBand.COLD, heatBand(2.0, 80.0))
        assertEquals(HeatBand.MILD, heatBand(18.0, 50.0))
        assertEquals(HeatBand.HOT, heatBand(29.0, 50.0))
        assertEquals(HeatBand.VERY_HOT, heatBand(32.0, 70.0))
    }

    @Test
    fun itIsCoolerHigherUp() {
        assertEquals(13.5, tempAtElevation(20.0, 1000.0), 1e-9)
    }

    @Test
    fun typicalWeatherIsTheMedianOfPastYearsPerHour() {
        val d = LocalDate.of(2026, 6, 20)
        val samples = (1..10).flatMap { y ->
            listOf(HourWeather(at(d.minusYears(y.toLong()), 9), 15.0 + y, humidityPct = 60.0), HourWeather(at(d.minusYears(y.toLong()), 10), 20.0 + y))
        }
        val series = climatology(samples, listOf(at(d, 9), at(d, 10), at(d, 3)), years = 10)
        assertEquals(WeatherSource.TYPICAL, series.source)
        assertEquals(2, series.hours.size) // no 03:00 samples
        assertEquals(20.5, series.hours[0].tempC, 1e-9)
        assertEquals(24.1, series.hours[0].tempP90C!!, 1e-9)
        assertTrue(series.note!!.contains("not a forecast"))
    }

    @Test
    fun aHotHumidRaceIsSummarisedWithItsAdvice() {
        val d = LocalDate.of(2026, 7, 1)
        val hours = (8..11).map { HourWeather(at(d, it), 26.0 + it - 8, humidityPct = 75.0, dewPointC = 22.0, solarWm2 = 700.0) }
        val s = summariseHeat(hours, WeatherSource.FORECAST)!!
        assertEquals(HeatBand.VERY_HOT, s.band)
        assertTrue(s.strongSun)
        assertTrue(s.lines.any { "pre-cool" in it })
        assertNull(summariseHeat(emptyList(), WeatherSource.NONE))
    }

    private fun test(rate: Double, temp: Double) = SweatTest(
        LocalDate.of(2026, 6, 1), "run", Intensity.MODERATE, 60.0, 70.0, 70.0 - rate / 1000, 0.0, 0.0, temp,
    )

    @Test
    fun aSweatTestGivesARate() {
        val t = SweatTest(LocalDate.of(2026, 6, 1), "run", Intensity.HARD, 90.0, 70.4, 69.4, 500.0, 100.0)
        // (1.0 kg + 0.5 L - 0.1 L) over 1.5 h = 933 ml/h
        assertEquals(933.3, sweatRateMlPerHour(t)!!, 0.1)
        assertEquals(1.42, bodyMassLossPct(t), 0.01)
        assertTrue(sweatTestProblems(t).isEmpty())
        assertTrue(sweatTestProblems(t.copy(durationMin = 20.0)).isNotEmpty())
        assertTrue(sweatTestProblems(t.copy(weightAfterKg = 60.0)).isNotEmpty())
    }

    @Test
    fun withoutTestsThePriorIsWideAndTestsMoveAndNarrowIt() {
        val none = sweatProfile(emptyList(), 70.0)
        val prior = predictSweatRate(none, Intensity.MODERATE, "run", 20.0, 70.0)
        assertEquals(800.0, prior.mlPerHour, 1e-9)
        assertEquals(480.0, prior.lowMlPerHour, 1e-9)
        assertTrue(prior.basis.contains("no sweat test"))
        // Three tests at 1.2 L/h in conditions the prior puts at 0.8 L/h: everything moves by 1.5.
        val tests = listOf(test(1200.0, 20.0), test(1200.0, 20.0), test(1200.0, 20.0))
        val own = predictSweatRate(sweatProfile(tests, 70.0), Intensity.MODERATE, "run", 20.0, 70.0)
        assertEquals(1200.0, own.mlPerHour, 1.0)
        assertEquals(1020.0, own.lowMlPerHour, 1.0)
        // Heat raises it 3 % per °C.
        assertEquals(1200.0 * 1.3, predictSweatRate(sweatProfile(tests, 70.0), Intensity.MODERATE, "run", 30.0, 70.0).mlPerHour, 1.0)
    }

    @Test
    fun drinkingNeverExceedsSweatAndKeepsTheLossSmall() {
        val sweat = SweatEstimate(1000.0, 750.0, 1250.0, "test")
        val plan = fluidPlan(sweat, 180.0, 70.0, HeatBand.MILD, saltySweater = false)
        assertTrue(plan.mlPerHourHigh <= 750.0 * 0.8)
        assertTrue(plan.mlPerHourLow >= 300.0)
        assertEquals(500.0, plan.sodiumMgPerL, 0.0)
        assertEquals(700.0, fluidPlan(sweat, 180.0, 70.0, HeatBand.HOT, false).sodiumMgPerL, 0.0)
        assertEquals(1000.0, fluidPlan(sweat, 180.0, 70.0, HeatBand.MILD, true).sodiumMgPerL, 0.0)
        assertEquals(0.0, fluidPlan(sweat, 45.0, 70.0, HeatBand.MILD, false).mlPerHourLow, 0.0)
    }
}
