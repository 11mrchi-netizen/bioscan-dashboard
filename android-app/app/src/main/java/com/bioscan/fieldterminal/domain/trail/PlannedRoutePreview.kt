package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.analysis.InputCompleteness
import java.security.MessageDigest

// DAV-148. Pure orchestration for Phase 2 -- same split as
// TrailSessionState.kt (Phase 1): I/O (download, save) stays in
// data/PlannedRouteRepository.kt, everything computable from a GPX string
// alone lives here, so it's directly unit-testable without a live Drive
// download. Deliberately produces course-demand metrics only (Mountain
// Index, KM-effort, grade distribution) -- never a performance metric,
// since nothing has happened yet to measure performance on.

const val PLANNED_ROUTE_PREVIEW_MODEL_VERSION = "1"

data class PlannedRoutePreview(
    val gpxChecksum: String,
    val courseDemand: CourseDemand,
    val gradeDistributionByBand: Map<String, Double>,
    val confidenceTier: String,
)

fun computePlannedRoutePreview(gpxText: String): PlannedRoutePreview {
    val trackpoints = trackpointsFromGpx(gpxText)
    val smoothed = smoothElevation(trackpoints)
    val segments = segmentClimbsAndDescents(smoothed)
    val demand = computeCourseDemand(trackpoints, segments)
    val distributionByBand = gradeBandDistribution(smoothed).mapKeys { it.key.name }.mapValues { it.value.distanceM }

    val breadth = InputCompleteness(
        present = if (demand.elevationGainM != null) setOf("gpx") else emptySet(),
        ideal = setOf("gpx"),
    )

    return PlannedRoutePreview(
        gpxChecksum = sha256(gpxText),
        courseDemand = demand,
        gradeDistributionByBand = distributionByBand,
        confidenceTier = breadth.tier.name.lowercase(),
    )
}

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
