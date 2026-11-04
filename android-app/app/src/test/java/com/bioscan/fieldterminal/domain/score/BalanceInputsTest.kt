package com.bioscan.fieldterminal.domain.score

import com.bioscan.fieldterminal.data.model.HrPoint
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BalanceInputsTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val today = LocalDate.of(2026, 11, 4)

    // Each day: 60 bpm awake, 48 bpm from 00:00 to 07:00 local; asleep 00:00-07:00, so the dip is 20 %.
    private fun hr(d: LocalDate) = WearableAnalysisRow(d.toString(), hrv = 50.0 + (d.dayOfMonth % 3), rhr = 48.0,
        hrTimeseries = (0 until 1440).map { m -> HrPoint(m, if (m < 7 * 60) 48 else 60) })
    private fun night(morning: LocalDate) = SleepAnalysisRow(morning.toString(), 7.0,
        bedtime = ZonedDateTime.of(morning.atStartOfDay(), zone).toInstant().toString(),
        wakeTime = ZonedDateTime.of(morning.atTime(7, 0), zone).toInstant().toString())

    @Test
    fun theNightHeartRateDipAndMealTimingAreBuilt() {
        val days = (0..30).map { today.minusDays(it.toLong()) }
        val meals = days.drop(1).flatMap { d ->
            listOf(8, 13, 19).map { h -> ZonedDateTime.of(d.atTime(h, (d.dayOfMonth * 7) % 40), zone).toInstant() }
        }
        val series = assembleScoreSeries(ScoreSources(wearable = days.map(::hr), sleep = days.map(::night), mealTimes = meals, zone = zone), today)
        assertEquals(20.0, series[ScoreKeys.HR_DIP]!![today]!!, 0.01)
        val spread = series[ScoreKeys.MEAL_TIMING_SPREAD]!![today]
        assertNotNull(spread)
        assertTrue(spread!! in 1.0..20.0)
    }

    @Test
    fun balanceStillScoresOnADayWithoutZeppStress() {
        val days = (0..40).map { today.minusDays(it.toLong()) }
        val meals = days.drop(1).flatMap { d -> listOf(8, 19).map { h -> ZonedDateTime.of(d.atTime(h, (d.dayOfMonth * 7) % 40), zone).toInstant() } }
        val wellbeing = days.map { WellbeingAnalysisRow(it.toString(), mood = 5 + it.dayOfMonth % 3, stress = 2 + it.dayOfMonth % 2) }
        // No stress days at all: stressed/relaxed time and wind-down are missing.
        val series = assembleScoreSeries(ScoreSources(wearable = days.map(::hr), sleep = days.map(::night), wellbeing = wellbeing, mealTimes = meals, zone = zone), today)
        val balance = combineDomain(ScoreSpecs.BALANCE, series, today)
        assertTrue("coverage ${balance.coverage}", balance.coverage >= ScoreSpecs.MIN_COVERAGE)
        assertNotNull(balance.raw)
    }
}
