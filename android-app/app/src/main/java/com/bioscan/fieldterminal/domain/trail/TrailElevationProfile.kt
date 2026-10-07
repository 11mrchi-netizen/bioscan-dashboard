package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.RoutePoint
import com.bioscan.fieldterminal.domain.TimePoint

// Live check found Session Detail's TRAIL card had no route-shape
// visualization at all (RouteMiniMap is a flat 2D map, TrailCard was text
// stat lines only) -- this is the elevation-vs-distance profile that was
// missing. Kept separate from computeTrailSessionState() rather than having
// that function also return it: the Analysis Layer 2 contract it publishes
// is a dimension map, not a place for a raw point series, and the UI is the
// only consumer of this shape. Reuses the same smoothing pass DAV-134's
// terrain segmentation already runs, so the profile matches the same
// climbs/descents the rest of the TRAIL card's numbers are computed from.
// TimePoint's first field is keyed by cumulative distance in meters here,
// not elapsed time -- LineChart itself is axis-agnostic (PerformanceChartCard
// already feeds it elapsed seconds), so no chart changes are needed to plot
// this as an elevation profile instead of a time series.

// 28/9: elevation profile (still keyed by cumulative distance, unchanged)
// plus, at each of the same points, the nearest-in-time HR/pace/cadence
// sample -- lets the TRAIL card's elevation chart overlay those signals on
// the same distance x-axis (ui/components/TrailElevationChart.kt) without a
// second (dual) y-axis: each overlay is drawn normalized to the chart's own
// height instead. Nearest-sample by offsetSeconds (binary search; these
// series are already time-ordered) rather than interpolating -- performance
// signals are noisier than elevation, so snapping to the closest real
// reading is more honest than inventing an in-between value. Replaces the
// single-series elevationProfile() this file used to export (its only
// caller, TrailCard, needs the overlays now).
data class TrailChartPoint(
    val distanceM: Long,
    val elevationM: Double,
    val heartRate: Double?,
    val paceMinPerKm: Double?,
    val cadenceSpm: Double?,
)

private const val OVERLAY_MATCH_TOLERANCE_SECONDS = 30L

private fun nearestValue(series: List<TimePoint>, offsetSeconds: Long): Double? {
    if (series.isEmpty()) return null
    var lo = 0
    var hi = series.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (series[mid].offsetSeconds < offsetSeconds) lo = mid + 1 else hi = mid
    }
    val nearest = listOfNotNull(series.getOrNull(lo), series.getOrNull(lo - 1))
        .minByOrNull { kotlin.math.abs(it.offsetSeconds - offsetSeconds) }
        ?: return null
    return if (kotlin.math.abs(nearest.offsetSeconds - offsetSeconds) <= OVERLAY_MATCH_TOLERANCE_SECONDS) nearest.value else null
}

fun elevationProfileWithOverlays(
    routePoints: List<RoutePoint>,
    heartRate: List<TimePoint>,
    speedKmh: List<TimePoint>,
    cadenceSpm: List<TimePoint>,
): List<TrailChartPoint> =
    smoothElevation(trackpointsFromRoutePoints(routePoints)).mapNotNull { sp ->
        val elevation = sp.smoothedElevationM ?: return@mapNotNull null
        val t = sp.trackpoint.offsetSeconds
        val speed = nearestValue(speedKmh, t)
        TrailChartPoint(
            distanceM = sp.trackpoint.cumulativeDistanceM.toLong(),
            elevationM = elevation,
            heartRate = nearestValue(heartRate, t),
            paceMinPerKm = speed?.takeIf { it > 0 }?.let { 60.0 / it },
            cadenceSpm = nearestValue(cadenceSpm, t),
        )
    }
