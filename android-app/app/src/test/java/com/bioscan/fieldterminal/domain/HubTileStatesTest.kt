package com.bioscan.fieldterminal.domain

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class HubTileStatesTest {
    private val two = LocalTime.of(14, 0) // half of the 07:00-21:00 waking day gone

    @Test
    fun healthIsTheWorstPartAndIgnoresPartsStillBuilding() {
        assertEquals(MetricState.Warning, healthHubState(MetricState.Optimal, MetricState.Warning, MetricState.Neutral))
        assertEquals(MetricState.Optimal, healthHubState(MetricState.Building, MetricState.Optimal, null))
        assertEquals(MetricState.Neutral, healthHubState(MetricState.Optimal, MetricState.Unavailable, MetricState.Neutral))
        assertEquals(MetricState.Building, healthHubState(MetricState.Building, MetricState.Unavailable, null))
        assertEquals(MetricState.Unavailable, healthHubState(null, null, null))
    }

    private fun nutrition(kcal: Double?, water: Double?, now: LocalTime = two, goal: Double? = 2400.0, loaded: Boolean = true) =
        nutritionHubState(loaded, kcal, goal, water, waterTargetMl = 2500.0, now = now)

    @Test
    fun nutritionIsJudgedAgainstWhereTheDayShouldBeByNow() {
        // By 14:00 the pace expects 1,200 kcal and 1,250 mL.
        assertEquals(MetricState.Optimal, nutrition(1100.0, 1100.0)) // 92 % and 88 %
        assertEquals(MetricState.Neutral, nutrition(800.0, 1100.0)) // 67 %: behind, not far behind
        assertEquals(MetricState.Warning, nutrition(500.0, 1100.0)) // 42 %
        assertEquals(MetricState.Warning, nutrition(1100.0, null)) // no water logged
        // From 21:00 the whole target counts.
        assertEquals(MetricState.Optimal, nutrition(2300.0, 2000.0, now = LocalTime.of(22, 0)))
        assertEquals(MetricState.Neutral, nutrition(2300.0, 1900.0, now = LocalTime.of(22, 0)))
    }

    @Test
    fun beforeTenOnlyCaloriesPastTheGoalCount() {
        assertEquals(MetricState.Neutral, nutrition(null, null, now = LocalTime.of(9, 0)))
        assertEquals(MetricState.Warning, nutrition(2700.0, null, now = LocalTime.of(9, 0))) // over 2,640
        assertEquals(MetricState.Warning, nutrition(2700.0, 3000.0))
    }

    @Test
    fun withoutACalorieGoalWaterAloneDecidesAndAnUnreadDayIsUnavailable() {
        assertEquals(MetricState.Optimal, nutrition(null, 1100.0, goal = null))
        assertEquals(MetricState.Warning, nutrition(5000.0, 400.0, goal = null))
        assertEquals(MetricState.Unavailable, nutrition(1100.0, 1100.0, loaded = false))
    }

    private fun supps(suggested: String?, scheduled: Int = 4) =
        SupplementsToday(dueByBucket = suggested?.let { mapOf(it to 2) } ?: emptyMap(), scheduledCount = scheduled, suggestedBucket = suggested, nextBucket = null)

    @Test
    fun labFollowsTodaysSupplements() {
        assertEquals(MetricState.Optimal, labHubState(supps(null), two))
        assertEquals(MetricState.Neutral, labHubState(supps("afternoon"), two))
        assertEquals(MetricState.Warning, labHubState(supps("morning"), two)) // the morning stack was missed
        assertEquals(MetricState.Unavailable, labHubState(supps(null, scheduled = 0), two))
        assertEquals(MetricState.Unavailable, labHubState(null, two))
    }
}
