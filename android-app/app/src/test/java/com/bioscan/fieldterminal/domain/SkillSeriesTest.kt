package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SkillSeriesTest {

    // 2026-10-12 is a Monday.
    private val monday = LocalDate.of(2026, 10, 12)

    @Test
    fun weeksStartOnMondayAndQuietWeeksAreZeroFilled() {
        val items = listOf(monday.minusDays(1) to 10.0, monday to 5.0, monday.plusDays(3) to 7.0)
        val buckets = bucketWeekly(items, { it.first }, { it.second }, from = monday.minusDays(14), to = monday.plusDays(6))
        assertEquals(listOf(monday.minusDays(14), monday.minusDays(7), monday), buckets.map { it.weekStart })
        assertEquals(listOf(0.0, 10.0, 12.0), buckets.map { it.value })
        assertEquals(listOf(0, 1, 2), buckets.map { it.count })
    }

    @Test
    fun maxAggregateKeepsTheLongestRunPerWeek() {
        val items = listOf(monday to 8.0, monday.plusDays(2) to 21.0)
        val buckets = bucketWeekly(items, { it.first }, { it.second }, monday, monday.plusDays(6), WeekAggregate.MAX)
        assertEquals(21.0, buckets.single().value, 1e-9)
    }

    @Test
    fun trailingAverageUsesOnlyExistingWeeksAtTheStart() {
        val buckets = listOf(10.0, 20.0, 30.0, 40.0, 50.0).mapIndexed { i, v -> WeekBucket(monday.plusWeeks(i.toLong()), v, 1) }
        assertEquals(listOf(10.0, 15.0, 20.0, 25.0, 35.0), trailingWeeklyAverage(buckets, 4))
    }

    @Test
    fun e1rmUsesTheSameEpleyRuleAndTwelveRepGateAsStrengthLoad() {
        assertEquals(100.0 * (1 + 5 / 30.0), setE1rmKg(5, 100.0)!!, 1e-9)
        assertEquals(100.0 * (1 + 12 / 30.0), setE1rmKg(12, 100.0)!!, 1e-9)
        assertNull(setE1rmKg(13, 100.0))
        assertNull(setE1rmKg(0, 100.0))
        assertNull(setE1rmKg(5, 0.0))
    }

    @Test
    fun seriesFoldsSpellingsAndKeepsTheBestSetPerDay() {
        val d1 = monday
        val d2 = monday.plusDays(7)
        val sets = listOf(
            LoggedSet(d1, "Overhead Press", 5, 50.0),
            LoggedSet(d1, "Overhead Press", 3, 55.0),
            LoggedSet(d2, "Standing Military Press", 5, 55.0),
            LoggedSet(d1, "Squat", 5, 100.0),
        )
        val series = liftE1rmSeries(sets)
        val press = series.first { it.name.contains("Press") }
        assertEquals(2, press.points.size)
        // Day 1: max(50*(1+5/30), 55*(1+3/30)) = max(58.33, 60.5) = 60.5
        assertEquals(60.5, press.points[0].second, 1e-9)
        assertEquals("Standing Military Press", press.name)
        assertEquals(press, series.first()) // most-trained lift (2 days) first
    }

    @Test
    fun prTimelineOnlyListsStrictImprovements() {
        val series = listOf(
            LiftSeries("squat", "Squat", listOf(monday to 100.0, monday.plusDays(7) to 100.0, monday.plusDays(14) to 110.0, monday.plusDays(21) to 105.0)),
        )
        val prs = e1rmPrTimeline(series)
        assertEquals(listOf(100.0, 110.0), prs.map { it.e1rmKg })
        assertNull(prs[0].previousKg)
        assertEquals(100.0, prs[1].previousKg!!, 1e-9)
    }

    @Test
    fun tonnageAndTrainingDaysBucketPerWeek() {
        val sets = listOf(
            LoggedSet(monday, "Squat", 5, 100.0),
            LoggedSet(monday, "Squat", 5, 100.0),
            LoggedSet(monday.plusDays(2), "Bench Press", 10, 50.0),
        )
        assertEquals(2500.0, weeklyTonnageKg(sets, monday, monday.plusDays(6)).single().value, 1e-9)
        assertEquals(2.0, weeklyStrengthDays(sets, monday, monday.plusDays(6)).single().value, 1e-9)
        assertTrue(weeklyStrengthDays(emptyList(), monday, monday.plusDays(6)).single().count == 0)
    }
}
