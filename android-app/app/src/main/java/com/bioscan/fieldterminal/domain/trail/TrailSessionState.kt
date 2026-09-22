package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.RoutePoint
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.domain.analysis.DimensionState
import com.bioscan.fieldterminal.domain.analysis.InputCompleteness
import com.bioscan.fieldterminal.domain.analysis.ObservationRef
import com.bioscan.fieldterminal.domain.analysis.Provenance
import com.bioscan.fieldterminal.domain.analysis.SessionStateObject
import com.bioscan.fieldterminal.domain.analysis.StateDimension
import java.time.LocalDate

// DAV-144. Pure orchestration -- runs a completed trail run's already-
// fetched Health Connect route + time series through every domain/trail/*.kt
// piece built in this milestone and publishes the session-level summary
// through the shared Analysis Layer 2 contract. No I/O: SessionDetailScreen
// already holds everything this needs (header, detail, routePoints) from
// its own existing loadHeader/loadTimeSeries/checkRouteAvailability calls --
// this is the same "pure function over already-fetched data" split
// NutritionDailyState.kt established for milestone 06.
//
// Every dimension here is a same-session raw/derived figure, not a
// multi-day baseline -- Confidence(1,1) once real data exists, Confidence(0,1)
// on NoData, matching DAV-180's own reasoning: depth doesn't have a
// meaningful answer for a single session, breadth (real route data present
// or not) carries the whole story.

const val TRAIL_SESSION_STATE_MODEL_VERSION = "1"
private const val ROUTE_SOURCE = "health_connect_route"

fun computeTrailSessionState(
    sessionId: Long,
    date: LocalDate,
    routePoints: List<RoutePoint>,
    heartRate: List<TimePoint>,
    powerW: List<TimePoint>,
    speedKmh: List<TimePoint>,
): SessionStateObject {
    val trackpoints = trackpointsFromRoutePoints(routePoints)
    val smoothed = smoothElevation(trackpoints)
    val segments = segmentClimbsAndDescents(smoothed)
    val courseDemand = computeCourseDemand(trackpoints, segments)
    val climbs = computeUphillPerformance(segments, heartRate, powerW, speedKmh)
    val descents = computeDownhillPerformance(segments, heartRate, powerW, speedKmh)
    val locomotion = classifyLocomotion(smoothed)
    val uphillEfficiency = computeUphillEfficiency(climbs)
    val downhillEfficiency = computeDownhillEfficiency(descents)

    fun scalar(value: Double?): DimensionState = DimensionState(
        value = value,
        evalState = if (value == null) EvalState.NoData else EvalState.Stable,
        confidence = if (value == null) Confidence(0, 1) else Confidence(1, 1),
        breadth = InputCompleteness(
            present = if (value != null) setOf(ROUTE_SOURCE) else emptySet(),
            ideal = setOf(ROUTE_SOURCE),
        ),
        provenance = Provenance(origin = ROUTE_SOURCE, algorithm = "trail_engine", algorithmVersion = TRAIL_SESSION_STATE_MODEL_VERSION),
        contributingObservations = listOf(ObservationRef("exercise_sessions", sessionId.toString())),
    )

    fun average(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.average()

    val dimensions = mapOf(
        StateDimension.MOUNTAIN_INDEX to scalar(courseDemand.mountainIndex),
        StateDimension.KM_EFFORT to scalar(courseDemand.kmEffort),
        StateDimension.ELEVATION_GAIN_M to scalar(courseDemand.elevationGainM),
        StateDimension.ELEVATION_LOSS_M to scalar(courseDemand.elevationLossM),
        StateDimension.AVERAGE_VAM to scalar(average(climbs.map { it.vamMetersPerHour })),
        StateDimension.CLIMB_CONSISTENCY to scalar(climbConsistency(climbs)),
        StateDimension.DESCENT_CONSISTENCY to scalar(descentConsistency(descents)),
        StateDimension.UPHILL_EFFICIENCY to scalar(average(uphillEfficiency.map { it.metersPerHourPerBpm })),
        StateDimension.DOWNHILL_EFFICIENCY to scalar(average(downhillEfficiency.map { it.metersPerHourPerBpm })),
        StateDimension.UPHILL_RUN_PERCENT to scalar(locomotion.uphillRunPercent),
        StateDimension.VAM_DEGRADATION to scalar(computeVamDegradationPercent(segments)),
        StateDimension.PACE_DEGRADATION to scalar(computeUphillPaceDegradationPercent(segments, heartRate, powerW, speedKmh)),
        StateDimension.HR_DECOUPLING to scalar(computeHrDecouplingPercent(heartRate, speedKmh)),
    )

    return SessionStateObject(sessionId = sessionId, date = date, dimensions = dimensions)
}
