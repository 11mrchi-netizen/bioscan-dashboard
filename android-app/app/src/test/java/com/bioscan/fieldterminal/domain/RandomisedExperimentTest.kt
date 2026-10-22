package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random

class RandomisedExperimentTest {
    private val start = LocalDate.of(2026, 11, 2)

    @Test
    fun testPeriodSchedule_pairsBackToBackWithBothOrders() {
        val phases = drawPeriodSchedule(Random(1), start, 14, 4)
        assertEquals(8, phases.size)
        for (p in 0 until 4) assertEquals(listOf("A", "B"), phases.filter { it.pair == p }.map { it.label }.sorted())
        assertEquals("2026-11-02", phases[0].start)
        assertEquals("2026-11-15", phases[0].end)
        assertEquals("2026-11-16", phases[1].start)
        assertEquals(start.plusDays(8 * 14 - 1L).toString(), phases.last().end)
        // One generator drawn many times (a fresh java.util.Random per small seed starts almost the same way every time).
        val random = Random(5)
        val firstLabels = (0 until 60).map { drawPeriodSchedule(random, start, 14, 1)[0].label }
        assertEquals(setOf("A", "B"), firstLabels.toSet())
        assertEquals(8, drawPeriodSchedule(java.security.SecureRandom(), start, 14, 4).size)
    }

    @Test
    fun testDailySchedule_threeThenFourWithDaysEachWeek() {
        val days = drawDailySchedule(Random(2), start, 8)
        assertEquals(56, days.size)
        for (w in 0 until 8) assertEquals(if (w % 2 == 0) 3 else 4, days.subList(w * 7, w * 7 + 7).count { it.arm == "B" })
        assertEquals("2026-11-02", days.first().date)
        assertEquals("2026-12-27", days.last().date)
    }

    @Test
    fun testProtocols_carryVersionZoneAndSchedule() {
        val pairs = randomisedPairsProtocol(Random(3), start, 14, 4, 3, 2.0, blinded = true)
        assertEquals("randomized_pairs", pairs.design)
        assertEquals(2, pairs.version)
        assertEquals(2.0, pairs.zone!!, 1e-9)
        assertEquals(3, pairs.washoutDays)
        assertTrue(pairs.blinded!!)
        assertEquals(8, pairs.phases.size)
        assertNotNull(pairs.randomization)

        val behaviour = ExperimentBehaviour("caffeine_pm_any", 0.0, "no caffeine after 14:00")
        val daily = dailyProtocol(Random(4), start, 8, 10.0, 1, behaviour)
        assertEquals("micro_randomized", daily.design)
        assertEquals(56, daily.assignments!!.size)
        assertEquals(1, daily.lag)
        assertFalse("alcohol days stay in a daily design", daily.excludeAlcoholDays)
        assertTrue(daily.phases.isEmpty())
    }

    @Test
    fun testPower_pairsAndDaily() {
        // HRV: about 0.096 log units of day-to-day noise with lag-one correlation 0.5.
        val hrv = MdePreviewRow(metric = "hrv", unit = "%", sigma = 0.096, rho = 0.5, usableDays = 120)
        val four = pairsMde(hrv, 14, 3, 4)
        val six = pairsMde(hrv, 14, 3, 6)
        assertTrue("$four", four in 13.0..18.0)
        assertTrue("$six", six in 9.0..12.5)
        assertTrue(six < four)
        // Sleep: 0.6 h of noise, eight weeks of days in random order: under half an hour.
        val sleep = MdePreviewRow(metric = "sleep_hours", unit = "h", sigma = 0.6, rho = 0.3, usableDays = 120)
        val eight = dailyMde(sleep, 8)
        assertTrue("$eight", eight in 24.0..32.0)
        assertTrue(dailyMde(sleep, 12) < eight)
        val line = dailyPowerLine(sleep, 8, 10.0)
        assertTrue(line, line.startsWith("Can detect about ±"))
        assertTrue(line, line.contains("12 weeks would get to"))
        assertTrue(line, line.contains("would read UNCLEAR"))
    }

    @Test
    fun testTodayAssignment_periodsDaysAndBlinding() {
        val phases = listOf(
            ExperimentPhase("B", "2026-11-02", "2026-11-15", 0),
            ExperimentPhase("A", "2026-11-16", "2026-11-29", 0),
        )
        val pairs = ExperimentProtocol(design = "randomized_pairs", phases = phases, washoutDays = 3, version = 2, zone = 2.0)
        assertEquals("Starts 2026-11-02", todayAssignment(pairs, LocalDate.of(2026, 11, 1))!!.headline)
        val washoutDay = todayAssignment(pairs, LocalDate.of(2026, 11, 3))!!
        assertEquals("Today: WITH · take it", washoutDay.headline)
        assertTrue(washoutDay.detail!!, washoutDay.detail!!.contains("washout"))
        val later = todayAssignment(pairs, LocalDate.of(2026, 11, 20))!!
        assertEquals("Today: WITHOUT · don't take it", later.headline)
        assertFalse(later.detail!!.contains("washout"))
        assertNull("over", todayAssignment(pairs, LocalDate.of(2026, 12, 1)))
        val blinded = todayAssignment(pairs.copy(blinded = true), LocalDate.of(2026, 11, 20))!!
        assertEquals("Today: take today's organiser dose", blinded.headline)
        assertFalse(blinded.headline.contains("WITHOUT"))

        val daily = ExperimentProtocol(
            design = "micro_randomized", phases = emptyList(), version = 2, zone = 10.0, lag = 1,
            assignments = listOf(DailyAssignment("2026-11-02", "B"), DailyAssignment("2026-11-03", "A")),
            behaviour = ExperimentBehaviour("caffeine_pm_any", 0.0, "no caffeine after 14:00"),
        )
        assertEquals("Today: WITH · no caffeine after 14:00", todayAssignment(daily, LocalDate.of(2026, 11, 2))!!.headline)
        assertEquals("Today: WITHOUT · no rule, as you usually would", todayAssignment(daily, LocalDate.of(2026, 11, 3))!!.headline)
        assertNull("a version-1 protocol has no draw", todayAssignment(pairs.copy(version = null), LocalDate.of(2026, 11, 3)))
    }

    @Test
    fun testFourWordsAndChoices() {
        assertEquals("CLEAR BENEFIT", fourWordLabel("clear_benefit"))
        assertEquals("NO MEANINGFUL EFFECT", fourWordLabel("negligible"))
        assertNull(fourWordLabel("effect_detected"))
        assertEquals("caffeine_pm_any", dailyChoiceFor("caffeine_pm_sleep")!!.behaviour.series)
        assertNull("slow exposures are not same-day choices", dailyChoiceFor("sleep_hrv"))
        assertTrue(DAILY_CHOICES.all { it.behaviour.withValue == 0.0 && it.lag == 1 })
        assertEquals("±2%", zoneText(2.0, "%"))
        assertEquals("±0.5 points", zoneText(0.5, "points"))
        assertEquals("±0.25 points", zoneText(0.25, "points"))
        assertTrue(randomisedProblems("hrv", 2.0, 14, 3).isEmpty())
        assertEquals(1, randomisedProblems("hrv", null).size)
        assertEquals(1, randomisedProblems("hrv", 2.0, 7, 4).size)
    }
}
