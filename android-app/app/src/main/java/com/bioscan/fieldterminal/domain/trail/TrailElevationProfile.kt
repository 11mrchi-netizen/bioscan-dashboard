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
fun elevationProfile(routePoints: List<RoutePoint>): List<TimePoint> =
    smoothElevation(trackpointsFromRoutePoints(routePoints)).mapNotNull { sp ->
        sp.smoothedElevationM?.let { TimePoint(sp.trackpoint.cumulativeDistanceM.toLong(), it) }
    }
