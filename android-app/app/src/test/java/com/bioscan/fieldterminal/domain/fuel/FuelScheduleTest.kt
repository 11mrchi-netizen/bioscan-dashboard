package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class FuelScheduleTest {
    private val gel = FuelBlock("g", "Gel", "gel", startMin = 30, intervalMin = 30, count = 10, carbsG = 25.0, sodiumMg = 50.0)
    private val bottle = FuelBlock("w", "Water with electrolytes", "electrolyte_drink", startMin = 20, intervalMin = 20, count = 15, fluidMl = 250.0, sodiumMg = 100.0)
    private val pre = FuelBlock("p", "Pre-workout", "preworkout", startMin = -30, intervalMin = 0, count = 1, carbsG = 10.0, caffeineMg = 200.0)

    @Test
    fun blocksExpandIntoTimeOrderedIntakes() {
        val out = expandSchedule(listOf(gel, pre))
        assertEquals(11, out.size)
        assertEquals(ScheduledIntake("p", 0, -30), out.first())
        assertEquals(30, out[1].elapsedMin)
        assertEquals(300, out.last().elapsedMin)
        assertEquals(300, gel.endMin)
    }

    @Test
    fun totalsAddUpAndPreStartIntakeStaysOutOfTheRates() {
        val t = scheduleTotals(listOf(gel, bottle, pre), 330)
        assertEquals(260.0, t.carbsG, 1e-9)                     // 250 from gels, 10 pre-start
        assertEquals(10.0, t.preStartCarbsG, 1e-9)
        assertEquals(250.0 / 5.5, t.carbsPerHour!!, 1e-9)       // the pre-start 10 g is not in the hourly rate
        assertEquals(3750.0, t.fluidMl, 1e-9)
        assertEquals(500.0 + 1500.0, t.sodiumMg, 1e-9)
        assertEquals(200.0, t.caffeineMg, 1e-9)
        assertEquals(26, t.intakes)
    }

    @Test
    fun warningsNameWhatIsOff() {
        val tooMuch = gel.copy(count = 30, intervalMin = 15, startMin = 0)         // 25 g every 15 min = 100 g/h
        val w = scheduleWarnings(listOf(tooMuch), 330, 60.0)
        assertTrue(w.any { "above the 60" in it })
        assertTrue(w.any { "after the planned finish" in it })
        assertTrue(scheduleWarnings(listOf(gel), 330, 60.0).isEmpty())
    }

    @Test
    fun anUnsetIntervalIsFlagged() {
        assertTrue(scheduleWarnings(listOf(gel.copy(intervalMin = 0)), 330, 60.0).any { "how often" in it })
    }

    // ---- debrief ----

    @Test
    fun tenOfTwelveTakenMarksTheLastTwoSkipped() {
        val block = gel.copy(count = 12, intervalMin = 20)
        val rows = debriefRows(42, listOf(block), listOf(BlockActual("g", taken = 10)))
        assertEquals(12, rows.size)
        assertEquals(10, rows.count { it.status == "consumed" })
        assertEquals(listOf("skipped", "skipped"), rows.takeLast(2).map { it.status })
        assertEquals("debrief:42:g:11", rows.last().clientKey)
        assertEquals(30 * 60 + 11 * 20 * 60, rows.last().elapsedSeconds)
    }

    @Test
    fun anAsPlannedBlockIsAllConsumedAndHalfAGelScalesTheNutrients() {
        assertTrue(debriefRows(1, listOf(gel), emptyList()).all { it.status == "consumed" })
        val half = debriefRows(1, listOf(gel), listOf(BlockActual("g", amountScale = 0.5)))
        assertEquals(12.5, half.first().carbsG!!, 1e-9)
        assertEquals(25.0, half.first().sodiumMg!!, 1e-9)
    }

    @Test
    fun moreThanPlannedAddsIntakesAfterTheLast() {
        val rows = debriefRows(1, listOf(gel.copy(count = 2)), listOf(BlockActual("g", taken = 4)))
        assertEquals(4, rows.size)
        assertEquals(listOf(30, 60, 90, 120), rows.map { it.elapsedSeconds / 60 })
        assertTrue(rows.all { it.status == "consumed" })
    }

    @Test
    fun savingAgainProducesTheSameKeys() {
        val a = debriefRows(7, listOf(gel, bottle), listOf(BlockActual("g", taken = 8))).map { it.clientKey }
        val b = debriefRows(7, listOf(gel, bottle), listOf(BlockActual("g", taken = 8))).map { it.clientKey }
        assertEquals(a, b)
        assertEquals(a.size, a.toSet().size)
        assertTrue(a.all { it.startsWith(debriefKeyPrefix(7)) })
    }

    @Test
    fun theScheduleHeldWhenEnoughOfTheCarbohydrateWasTaken() {
        assertEquals(ScheduleVerdict.HELD, scheduleAdherence(listOf(gel), emptyList()).verdict)
        val nine = scheduleAdherence(listOf(gel), listOf(BlockActual("g", taken = 9)))
        assertEquals(0.9, nine.carbShare!!, 1e-9)
        assertEquals(ScheduleVerdict.HELD, nine.verdict)
        val seven = scheduleAdherence(listOf(gel), listOf(BlockActual("g", taken = 7)))
        assertEquals(ScheduleVerdict.PARTLY, seven.verdict)
        assertEquals("Gel", seven.slipped.single().label)
        assertEquals(ScheduleVerdict.NOT_HELD, scheduleAdherence(listOf(gel), listOf(BlockActual("g", taken = 3))).verdict)
    }

    @Test
    fun extrasDoNotMakeUpForAMissedBlock() {
        val a = scheduleAdherence(listOf(gel, pre), listOf(BlockActual("g", taken = 12), BlockActual("p", taken = 0)))
        // planned 260 g; gels 250 (all planned taken) + 50 extra; the pre-workout's 10 g missed.
        assertEquals(250.0 / 260.0, a.carbShare!!, 1e-9)
        assertEquals(50.0, a.extraCarbsG, 1e-9)
        assertEquals(300.0, a.takenCarbsG, 1e-9)
        assertEquals(0.0, a.takenCaffeineMg, 0.0)
    }

    @Test
    fun noCarbohydratePlannedMeansNoVerdict() {
        val a = scheduleAdherence(listOf(bottle), emptyList())
        assertNull(a.verdict)
        assertEquals(3750.0, a.takenFluidMl, 1e-9)
    }

    // ---- seeding from the planner ----

    private fun plan(products: List<FuelProductOption>, temp: Double): RaceFuelPlan {
        val zone = ZoneId.of("Europe/Rome")
        val start = ZonedDateTime.of(LocalDate.of(2026, 9, 13).atTime(8, 0), zone)
        return planRaceFuel(
            RaceFuelInput(
                "Test", "run", Intensity.RACE, start, 300.0, 285.0, 315.0, "target",
                weather = WeatherSeries(WeatherSource.FORECAST, (0 until 8).map { HourWeather(start.plusHours(it.toLong() - 1), temp, humidityPct = 60.0) }),
                sweat = sweatProfile(emptyList(), 70.0), weightKg = 70.0,
                settings = RaceFuelSettings(caffeineOk = true), products = products,
            ),
        )
    }

    @Test
    fun seededBlocksReproduceAPlanWithoutADrinkMixExactly() {
        val p = plan(listOf(FuelProductOption(2, "Chews", "chew", carbsG = 22.0, sodiumMg = 40.0)), 30.0)
        val blocks = seedBlocks(p)
        val t = scheduleTotals(blocks, 300)
        assertEquals(p.totalCarbsG, t.carbsG, 0.5)
        assertEquals(p.totalFluidMl, t.fluidMl, 1.0)
        assertEquals(p.totalSodiumMg, t.sodiumMg, 1.0)
        assertEquals(p.totalCaffeineMg, t.caffeineMg, 1.0)
        assertTrue(blocks.any { it.fuelForm == FuelForm.ELECTROLYTE_DRINK || it.fuelForm == FuelForm.WATER })
        assertTrue(blocks.any { it.fuelForm == FuelForm.CHEW })
    }

    @Test
    fun aDrinkMixBringsItsOwnFluidAndSodium() {
        val drink = FuelProductOption(1, "Mix", "drink_mix", carbsG = 40.0, fluidMl = 250.0, sodiumMg = 150.0)
        val chew = FuelProductOption(2, "Chews", "chew", carbsG = 22.0, sodiumMg = 40.0)
        val p = plan(listOf(drink, chew), 30.0)
        val blocks = seedBlocks(p)
        val t = scheduleTotals(blocks, 300)
        assertEquals(p.totalCarbsG, t.carbsG, 0.5)
        assertEquals(p.totalCaffeineMg, t.caffeineMg, 1.0)
        // Whole servings with their own fluid and sodium: the schedule covers at least what the plan asks.
        assertTrue(t.fluidMl >= p.totalFluidMl - 1.0)
        assertTrue(t.sodiumMg >= p.totalSodiumMg - 1.0)
        assertTrue(blocks.any { it.fuelForm == FuelForm.DRINK_MIX })
    }
}
