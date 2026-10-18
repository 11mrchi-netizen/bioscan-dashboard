package com.bioscan.fieldterminal.domain.fuel

import com.bioscan.fieldterminal.domain.events.AidStation
import com.bioscan.fieldterminal.domain.trail.CoursePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class RaceFuelPlanTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val day = LocalDate.of(2026, 9, 13)
    private val start = ZonedDateTime.of(day.atTime(8, 0), zone)
    private val kg = 70.0
    private val noTests = sweatProfile(emptyList(), kg)

    // A synthetic 50 km mountain course: 10 km flat, 10 km climbing 1,000 m, 10 km down, 20 km flat.
    private val mountain = (0..500).map { i ->
        val d = i * 100.0
        val e = when {
            d <= 10_000 -> 500.0
            d <= 20_000 -> 500.0 + (d - 10_000) / 10
            d <= 30_000 -> 1500.0 - (d - 20_000) / 10
            else -> 500.0
        }
        CoursePoint(d, e)
    }
    private val flat21 = (0..211).map { CoursePoint(it * 100.0, 100.0) }

    private fun hours(temp: Double, humidity: Double, from: ZonedDateTime = start, n: Int = 12) =
        (0 until n).map { HourWeather(from.plusHours(it.toLong() - 1), temp, humidityPct = humidity) }

    private fun input(
        course: List<CoursePoint> = mountain,
        duration: Double = 420.0,
        weather: WeatherSeries = WeatherSeries(WeatherSource.FORECAST, hours(18.0, 50.0)),
        stations: List<AidStation> = emptyList(),
        settings: RaceFuelSettings = RaceFuelSettings(),
        history: IntakeHistoryResult? = null,
        products: List<FuelProductOption> = emptyList(),
    ) = RaceFuelInput(
        "Synthetic Trail", "run", Intensity.RACE, start, duration, duration * 0.95, duration * 1.05, "your target time",
        course = course, distanceKm = course.last().distanceM / 1000, aidStations = stations, weather = weather,
        sweat = noTests, weightKg = kg, settings = settings, history = history, products = products, loadingDays = duration > 90,
    )

    @Test
    fun timeIsSpreadByTheCostOfTheTerrain() {
        val times = timeAlongCourse(mountain, 420.0)
        assertEquals(420.0, times.last(), 1e-6)
        val climb = times[200] - times[100] // the 10 km climb
        val flatStart = times[100] - times[0] // the first 10 flat km
        val descent = times[300] - times[200]
        assertEquals(1.66, climb / flatStart, 0.02) // Minetti at 10 %: 5.97 / 3.6 J/kg/m
        assertTrue(descent < flatStart)
        // Never faster than 1.25x flat downhill.
        assertTrue(descent >= flatStart * 0.8 - 1e-6)
    }

    @Test
    fun aLongRaceIsFuelledAtTheToleratedRateAfterARampedFirstHour() {
        val plan = planRaceFuel(input())
        assertEquals(60.0, plan.carbsPerHourHigh, 0.0)
        val firstHour = plan.steps.filter { it.elapsedMin <= 60 }.sumOf { it.carbsG }
        val thirdHour = plan.steps.filter { it.elapsedMin > 120 && it.elapsedMin <= 180 }.sumOf { it.carbsG }
        assertTrue(firstHour < thirdHour)
        // About 60 g/h over the race, within a gel.
        assertEquals(60.0 * 7 - 15, plan.totalCarbsG, 30.0)
        assertTrue(plan.steps.first().items.any { "gel" in it })
        assertTrue(plan.assumptions.any { "generic" in it })
    }

    @Test
    fun fluidNeverExceedsTheSweatAndHeatRaisesIt() {
        val mild = planRaceFuel(input())
        val hot = planRaceFuel(input(weather = WeatherSeries(WeatherSource.FORECAST, hours(31.0, 65.0))))
        assertTrue(hot.totalFluidMl > mild.totalFluidMl)
        assertTrue(hot.sodiumMgPerL > mild.sodiumMgPerL)
        assertTrue(hot.fluidMlPerHourHigh <= hot.sweat.lowMlPerHour * MAX_REPLACEMENT_SHARE + 1e-6)
        assertTrue(hot.before.any { "Pre-cool" in it.text })
        assertNotNull(hot.heat)
        assertEquals(HeatBand.VERY_HOT, hot.heat!!.band)
    }

    @Test
    fun itIsCoolerOnTheSummit() {
        val plan = planRaceFuel(input())
        val low = plan.steps.first { (it.km ?: 0.0) < 5 }.tempC!!
        val summit = plan.steps.minBy { kotlin.math.abs((it.km ?: 0.0) - 20.0) }.tempC!!
        assertEquals(6.5, low - summit, 1.5)
    }

    @Test
    fun legsBetweenRefillsSayWhatToCarry() {
        val stations = listOf(AidStation("Col", 20.0, water = true, food = true), AidStation("Valley", 35.0, water = true))
        val plan = planRaceFuel(input(stations = stations))
        assertEquals(listOf("Start", "Col", "Valley"), plan.legs.map { it.from })
        assertEquals(listOf("Col", "Valley", "Finish"), plan.legs.map { it.to })
        assertTrue(plan.legs.first().items.values.sum() > 0)
        assertTrue(plan.legs.first().bottlesMl >= plan.legs.first().fluidMl)
        assertEquals(plan.totalCarbsG, plan.legs.sumOf { it.carbsG }, 1e-6)
    }

    @Test
    fun cutoffsAreChecked() {
        val plan = planRaceFuel(input(stations = listOf(AidStation("Col", 20.0, cutoffMin = 150.0))))
        assertTrue(plan.warnings.any { "Col" in it && "cutoff" in it })
    }

    @Test
    fun aShortLoadingCarriesIntoBreakfastAndAnEarlyStart() {
        val history = IntakeHistoryResult(emptyList(), 200.0, 140.0, earlyFuelling = true, preHydrationBoost = true, notes = listOf("short"))
        val plan = planRaceFuel(input(history = history))
        assertEquals(15.0, plan.steps.first().elapsedMin, 0.0)
        assertTrue(plan.before.any { "210 g" in it.text }) // 3 g/kg breakfast
        assertTrue(plan.before.any { "7 ml/kg" in it.text })
    }

    @Test
    fun caffeineIsOptInAndComesLateInLongRaces() {
        assertEquals(0.0, planRaceFuel(input()).totalCaffeineMg, 0.0)
        val plan = planRaceFuel(input(settings = RaceFuelSettings(caffeineOk = true)))
        assertEquals(210.0, plan.totalCaffeineMg, 1e-9)
        val doses = plan.steps.filter { it.caffeineMg > 0 }
        assertEquals(2, doses.size)
        assertTrue(doses.all { it.elapsedMin > 0.35 * 420 })
        // A short race: before the start instead.
        val short = planRaceFuel(input(course = flat21, duration = 85.0, settings = RaceFuelSettings(caffeineOk = true)))
        assertTrue(short.steps.none { it.caffeineMg > 0 })
        assertTrue(short.before.any { "Caffeine" in it.text })
    }

    @Test
    fun aDrinkMixCoversFluidAndCarbohydrateTogether() {
        val mix = FuelProductOption(1, "Mix 500", "drink_mix", 40.0, fluidMl = 150.0, sodiumMg = 300.0)
        val gel = FuelProductOption(2, "Gel 30", "gel", 30.0, fructoseG = 10.0)
        val plan = planRaceFuel(input(products = listOf(mix, gel)))
        assertTrue(plan.steps.any { s -> s.items.any { "Mix 500" in it } })
        assertTrue(plan.warnings.none { "fructose" in it })
    }

    @Test
    fun aTypicalClimateIsSaidSo() {
        val typical = WeatherSeries(WeatherSource.TYPICAL, hours(22.0, 55.0), "Typical weather for these dates over the last 10 years, not a forecast.")
        val plan = planRaceFuel(input(weather = typical))
        assertEquals(WeatherSource.TYPICAL, plan.weatherSource)
        assertTrue(plan.assumptions.any { "not a forecast" in it })
    }

    @Test
    fun afterTheRaceRefuelRehydrateAndWeighIn() {
        val plan = planRaceFuel(input())
        assertTrue(plan.after.any { "sweat test" in it.text })
        assertTrue(plan.after.any { "70-84 g" in it.text })
    }

    @Test
    fun durationRangesFollowWhatIsKnown() {
        assertEquals(95.0, durationRange(100.0, null, null)!!.second.first, 1e-9)
        assertEquals(90.0, durationRange(null, 100.0, null)!!.second.first, 1e-9)
        val est = TimeEstimate(100.0, 92.0, 110.0, "history")
        assertEquals("history", durationRange(null, null, est)!!.third)
        assertNull(durationRange(null, null, null))
    }

    @Test
    fun aRunTimeEstimateComesFromRecentRuns() {
        val runs = (1..8).map { RecentRun(10.0, 100.0, 55.0) } // 5 min per km-effort
        val est = estimateRunTime(21.1, 200.0, runs)!!
        // 23.1 km-effort x 5 min x (23.1 / 11)^0.06
        assertEquals(23.1 * 5 * Math.pow(23.1 / 11, 0.06), est.minutes, 0.5)
        assertNull(estimateRunTime(21.1, 200.0, runs.take(3)))
    }
}
