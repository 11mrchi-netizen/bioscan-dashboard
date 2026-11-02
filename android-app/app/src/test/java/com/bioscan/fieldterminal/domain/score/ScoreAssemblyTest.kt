package com.bioscan.fieldterminal.domain.score

import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.EveningCheckin
import com.bioscan.fieldterminal.domain.withNothingElse
import com.bioscan.fieldterminal.domain.stress.StressDailySummary
import com.bioscan.fieldterminal.domain.stress.StressDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ScoreAssemblyTest {
    private val zone = ZoneOffset.UTC
    private val monday = LocalDate.of(2026, 11, 2)
    private val tuesday = monday.plusDays(1)

    @Test
    fun nightReadingsKeepTheirMorningAndDayInputsMoveToTheNextMorning() {
        val series = assembleScoreSeries(
            ScoreSources(
                wearable = listOf(WearableAnalysisRow(date = tuesday.toString(), hrv = 58.0, rhr = 47.0)),
                sleep = listOf(SleepAnalysisRow(date = tuesday.toString(), hours = 8.0, bedtime = "2026-11-02T23:05:00Z", wakeTime = "2026-11-03T07:05:00Z", deepMin = 80.0, remMin = 90.0)),
                wellbeing = listOf(WellbeingAnalysisRow(date = tuesday.toString(), energy = 4, mood = 4)),
                nutrition = listOf(DailyNutrition(monday.toString(), calories = 2600.0, proteinG = 150.0, fatG = 80.0, carbsG = 300.0, mealCount = 4)),
                hydrationMl = mapOf(monday to 3000.0),
                targets = { FuelTargets(2600.0, 150.0, 300.0, 3000.0) },
                checkins = listOf(EveningCheckin(monday, drinks = 2, caffeineAfter14 = false, stress = 2, lateScreen = true)),
                stressDays = listOf(StressDay(monday, emptyList(), StressDailySummary(30, 80, 5, 40, 30, 20, 10))),
                zone = zone,
            ),
            today = tuesday,
        )
        assertEquals(58.0, series.getValue(ScoreKeys.HRV)[tuesday]!!, 1e-9)
        assertEquals(8.0, series.getValue(ScoreKeys.SLEEP_HOURS)[tuesday]!!, 1e-9)
        assertEquals(170.0, series.getValue(ScoreKeys.SLEEP_DEEP_REM)[tuesday]!!, 1e-9)
        // Midpoint 03:05 is 15h05 after noon the day before.
        assertEquals(15 * 60 + 5.0, series.getValue(ScoreKeys.SLEEP_MIDPOINT)[tuesday]!!, 1e-9)
        assertEquals(4.0, series.getValue(ScoreKeys.ENERGY)[tuesday]!!, 1e-9)
        // Monday's food, water, check-in and daytime stress feed Tuesday morning.
        assertEquals(1.0, series.getValue(ScoreKeys.ENERGY_RATIO)[tuesday]!!, 1e-9)
        assertEquals(1.0, series.getValue(ScoreKeys.WATER_RATIO)[tuesday]!!, 1e-9)
        assertNull(series.getValue(ScoreKeys.ENERGY_RATIO)[monday])
        assertEquals(2.0, series.getValue(ScoreKeys.DRINKS)[tuesday]!!, 1e-9)
        assertEquals(1.0, series.getValue(ScoreKeys.LATE_SCREEN)[tuesday]!!, 1e-9)
        assertEquals(2.0, series.getValue(ScoreKeys.FELT_STRESS)[tuesday]!!, 1e-9) // the evening answer wins
        assertEquals(30.0, series.getValue(ScoreKeys.STRESSED_SHARE)[tuesday]!!, 1e-9)
        assertEquals(40.0, series.getValue(ScoreKeys.RELAXED_SHARE)[tuesday]!!, 1e-9)
    }

    @Test
    fun sleepDebtAgainstAtLeastSevenHours() {
        val nights = (0 until 7).map { i ->
            SleepAnalysisRow(date = monday.minusDays(i.toLong()).toString(), hours = 6.0)
        }
        val series = assembleScoreSeries(ScoreSources(sleep = nights, zone = zone), today = monday)
        assertEquals(7.0, series.getValue(ScoreKeys.SLEEP_DEBT)[monday]!!, 1e-9) // one hour short, seven nights
    }

    @Test
    fun daysWithoutRestCountTrainingDaysUpToYesterday() {
        val loads = (1..5).map { monday.minusDays(it.toLong()) to 80.0 } + (monday.minusDays(6) to 10.0)
        val series = assembleScoreSeries(ScoreSources(sessionLoads = loads, zone = zone), today = tuesday)
        // Morning of Monday: Sunday back to Wednesday trained (5 days), Tuesday before was light.
        assertEquals(5.0, series.getValue(ScoreKeys.DAYS_WITHOUT_REST)[monday]!!, 1e-9)
        // Morning of Tuesday: Monday itself had nothing, so the streak is broken.
        assertEquals(0.0, series.getValue(ScoreKeys.DAYS_WITHOUT_REST)[tuesday]!!, 1e-9)
        assertTrue(series.getValue(ScoreKeys.TSB).containsKey(tuesday))
    }

    @Test
    fun gutTimingFrequencyAndForm() {
        val stool = (0 until 8).flatMap { i ->
            val d = monday.minusDays(i.toLong())
            listOf(StoolAnalysisRow("${d}T08:${(10 + i).toString().padStart(2, '0')}:00Z", 4))
        } + StoolAnalysisRow("${monday}T19:00:00Z", 6)
        val series = assembleScoreSeries(ScoreSources(stool = stool, zone = zone), today = tuesday)
        assertEquals(8 * 60 + 10.0, series.getValue(ScoreKeys.GUT_TIMING)[tuesday]!!, 1e-9) // Monday's first, 08:10
        assertEquals(2.0, series.getValue(ScoreKeys.GUT_FREQUENCY)[tuesday]!!, 1e-9)
        assertTrue(series.getValue(ScoreKeys.GUT_TIMING_SPREAD)[tuesday]!! < 5.0)
        // Monday 4 and 6, Sunday 4, Saturday 4.
        assertEquals((4 + 6 + 4 + 4) / 4.0, series.getValue(ScoreKeys.GUT_FORM)[tuesday]!!, 1e-9)
    }

    @Test
    fun capsCoverTheirEpisode() {
        val caps = capsFrom(listOf(Triple("Sore throat", monday, null)))
        assertNull(caps(monday.minusDays(1)))
        assertEquals("Sore throat · day 2", caps(tuesday)!!.reason)
        val injury = capsFrom(listOf(Triple("Knee", monday, monday.plusDays(6))))
        assertFalse(injury(monday.plusDays(7)) != null)
    }

    @Test
    fun gutBurdenFromTheCheckInOrTheStoolEntries() {
        assertNull(gutBurden(null, null))
        assertNull(gutBurden(EveningCheckin(monday), null))
        assertEquals(0.0, gutBurden(EveningCheckin(monday, gutComfort = 4, gutSymptoms = emptyList()), null)!!, 1e-9)
        assertEquals(2.0, gutBurden(EveningCheckin(monday, gutComfort = 3, gutSymptoms = listOf("bloating", "gas")), null)!!, 1e-9)
        assertEquals(2.4, gutBurden(null, 6)!!, 1e-9)
        // The larger of the two when both exist.
        assertEquals(2.4, gutBurden(EveningCheckin(monday, gutComfort = 4), 6)!!, 1e-9)
    }

    @Test
    fun gutSymptomsFeedTheNextMorning() {
        val series = assembleScoreSeries(
            ScoreSources(
                checkins = listOf(EveningCheckin(monday, gutComfort = 2, gutSymptoms = listOf("cramps"))),
                stool = listOf(StoolAnalysisRow("${monday}T08:00:00Z", 4, discomfort = 1)),
                zone = zone,
            ),
            today = tuesday,
        )
        assertEquals(2.5, series.getValue(ScoreKeys.GUT_SYMPTOMS)[tuesday]!!, 1e-9)
        assertNull(series.getValue(ScoreKeys.GUT_SYMPTOMS)[monday])
    }

    @Test
    fun acuteGutDaysFromNauseaOrRepeatedLiquidStools() {
        val sunday = monday.minusDays(1)
        val days = acuteGutDays(
            checkins = listOf(EveningCheckin(sunday, gutSymptoms = listOf("nausea")), EveningCheckin(monday, gutSymptoms = listOf("gas"))),
            stool = listOf(
                StoolAnalysisRow("${monday}T08:00:00Z", 7), StoolAnalysisRow("${monday}T11:00:00Z", 7),
                StoolAnalysisRow("${tuesday}T09:00:00Z", 7),
            ),
            zone = zone,
        )
        assertEquals(setOf(sunday, monday), days)
    }

    @Test
    fun nothingElseMarksNoSymptomsButNeverInventsComfort() {
        val done = EveningCheckin(monday).withNothingElse()
        assertEquals(emptyList<String>(), done.gutSymptoms)
        assertNull(done.gutComfort)
    }
}
