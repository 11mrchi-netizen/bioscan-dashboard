package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.RoutePoint
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.domain.analysis.StateDimension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// This is an orchestration test, not a re-verification of the underlying
// formulas -- every formula already has its own hand-verified fixture
// (TerrainSegmentationTest, CourseDemandTest, etc.). What's new here is the
// wiring: does computeTrailSessionState() call the right pieces and, when
// data is genuinely insufficient, does it publish NoData rather than a
// fabricated value.
class TrailSessionStateTest {

    // 21 points, ~50m apart along a meridian (0.00044966 deg latitude ~= 50m
    // at R=6,371,000m): climbs 0->50m over the first 10 steps, descends
    // 50->-10m over the next 10. Long enough that DAV-134's real 5-point
    // moving average doesn't span the whole route (a route this short was
    // this test's own first bug: a 5-point route smoothed with a 5-point
    // window flattens to one constant value, destroying the whole climb --
    // caught by an early version of this fixture producing a flat 0.0
    // Mountain Index instead of a real one).
    private val climbAndDescentRoute = (0..20).map { i ->
        val elevation = if (i <= 10) 5.0 * i else 50.0 - 6.0 * (i - 10)
        RoutePoint(offsetSeconds = i * 20L, lat = i * 0.00044966, lon = 0.0, elevationM = elevation)
    }

    @Test
    fun testComputeTrailSessionState_publishesCourseDemandFromARealRoute() {
        val state = computeTrailSessionState(
            sessionId = 11774,
            date = LocalDate.of(2026, 9, 20),
            routePoints = climbAndDescentRoute,
            heartRate = emptyList(),
            powerW = emptyList(),
            speedKmh = emptyList(),
        )

        assertEquals(11774L, state.sessionId)
        assertEquals(TRAIL_DIMENSION_NAMES, state.dimensions.keys.map { it.name }.toSet())

        // Loose bounds, not tight equality: this test checks wiring through
        // the *real* smoothing/segmentation pipeline (unlike the isolated
        // unit tests elsewhere, which feed pre-smoothed literals), so a few
        // meters of smoothing rounding near the climb/descent kink is
        // expected and not what this test is verifying.
        val mountainIndex = state.dimensions.getValue(StateDimension.MOUNTAIN_INDEX)
        assertEquals(EvalState.Stable, mountainIndex.evalState)
        assertNotNull(mountainIndex.value)
        assertTrue(mountainIndex.value!! in 30.0..60.0)

        val gain = state.dimensions.getValue(StateDimension.ELEVATION_GAIN_M)
        assertTrue(gain.value!! in 35.0..55.0)
        val loss = state.dimensions.getValue(StateDimension.ELEVATION_LOSS_M)
        assertTrue(loss.value!! in 45.0..65.0)

        // Only one real climb exists in this fixture -- consistency needs two.
        val consistency = state.dimensions.getValue(StateDimension.CLIMB_CONSISTENCY)
        assertNull(consistency.value)
        assertEquals(EvalState.NoData, consistency.evalState)

        // No HR data was supplied -- efficiency can't be computed without it.
        val efficiency = state.dimensions.getValue(StateDimension.UPHILL_EFFICIENCY)
        assertNull(efficiency.value)
        assertEquals(EvalState.NoData, efficiency.evalState)
    }

    @Test
    fun testComputeTrailSessionState_noRouteDataIsAllNoData() {
        val state = computeTrailSessionState(
            sessionId = 1,
            date = LocalDate.of(2026, 9, 20),
            routePoints = emptyList(),
            heartRate = listOf(TimePoint(0, 140.0)),
            powerW = emptyList(),
            speedKmh = listOf(TimePoint(0, 10.0)),
        )

        state.dimensions.values.forEach {
            assertNull(it.value)
            assertEquals(EvalState.NoData, it.evalState)
        }
    }

    private companion object {
        val TRAIL_DIMENSION_NAMES = setOf(
            "MOUNTAIN_INDEX", "KM_EFFORT", "ELEVATION_GAIN_M", "ELEVATION_LOSS_M", "AVERAGE_VAM",
            "CLIMB_CONSISTENCY", "DESCENT_CONSISTENCY", "UPHILL_EFFICIENCY", "DOWNHILL_EFFICIENCY",
            "UPHILL_RUN_PERCENT", "VAM_DEGRADATION", "PACE_DEGRADATION", "HR_DECOUPLING",
        )
    }
}
