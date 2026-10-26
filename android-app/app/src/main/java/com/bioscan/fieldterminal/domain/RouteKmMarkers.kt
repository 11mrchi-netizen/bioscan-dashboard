package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.trail.haversineMeters

// Run session page, ROUTE: where each whole kilometre falls on the track, interpolated between the two GPS points that
// bracket it, for a numbered marker on the map.
data class KmMarker(val km: Int, val lat: Double, val lon: Double)

fun routeKmMarkers(points: List<RoutePoint>): List<KmMarker> {
    if (points.size < 2) return emptyList()
    val out = mutableListOf<KmMarker>()
    var cumulative = 0.0
    var next = 1
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val step = haversineMeters(a.lat, a.lon, b.lat, b.lon)
        while (step > 0 && cumulative + step >= next * 1000.0) {
            val f = (next * 1000.0 - cumulative) / step
            out += KmMarker(next, a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
            next++
        }
        cumulative += step
    }
    return out
}
