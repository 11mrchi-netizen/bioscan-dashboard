package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.parseGpxPoints
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

// DAV-133, part 2 (Phase 2). The GPX-file adapter into the same shared
// Trackpoint model part 1 built for Health Connect. Reuses the existing
// parseGpxPoints()/GpxPoint (domain/NextSession.kt) for lat/lon/elevation
// rather than a second XML parser; only timestamp extraction is new here,
// kept as its own small parse pass rather than changing parseGpxPoints()'s
// already-shipped signature (the Map tab's MapRepository already depends on
// its current return shape).
//
// A planned route's GPX often has no per-point <time> at all (an
// rtept-only planned route, vs. a real recorded trkpt track -- doc 01) --
// when that's true, offsetSeconds becomes a synthetic strictly-increasing
// index rather than a real elapsed time. This is safe for Phase 2's own use
// (Mountain Index/KM-effort/grade distribution's distance figures are all
// distance-and-elevation-only, never time-dependent) but means a Phase-2
// trackpoint stream must never be fed into a Phase-1-only performance
// function (VAM/GAP/durability), which genuinely need real elapsed time.
fun trackpointsFromGpx(xmlText: String): List<Trackpoint> {
    val points = parseGpxPoints(xmlText)
    val timestamps = parseGpxTimestamps(xmlText)
    val hasRealTimes = timestamps.size == points.size && timestamps.all { it != null }
    val startInstant = timestamps.firstOrNull()

    var cumulative = 0.0
    return points.mapIndexed { index, point ->
        if (index > 0) {
            val prev = points[index - 1]
            cumulative += haversineMeters(prev.lat, prev.lon, point.lat, point.lon)
        }
        val offsetSeconds = if (hasRealTimes && startInstant != null) {
            Duration.between(startInstant, timestamps[index]!!).seconds
        } else {
            index.toLong()
        }
        Trackpoint(
            offsetSeconds = offsetSeconds,
            lat = point.lat,
            lon = point.lon,
            elevationM = point.elevationM,
            cumulativeDistanceM = cumulative,
        )
    }
}

// Same trkpt-then-rtept selection parseGpxPoints() uses, but reads <time>
// instead of lat/lon/ele. Kept unfiltered (one entry per node, null where
// <time> is absent or unparseable) so a caller can size-check it against
// parseGpxPoints()'s own (possibly shorter, if some points had unparseable
// coordinates) output before trusting the alignment.
private fun parseGpxTimestamps(xmlText: String): List<Instant?> {
    val factory = DocumentBuilderFactory.newInstance()
    val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText)))
    var nodes = doc.getElementsByTagName("trkpt")
    if (nodes.length == 0) nodes = doc.getElementsByTagName("rtept")

    val timestamps = mutableListOf<Instant?>()
    for (i in 0 until nodes.length) {
        val el = nodes.item(i) as? Element ?: continue
        val timeText = el.getElementsByTagName("time").item(0)?.textContent
        timestamps.add(timeText?.let { runCatching { Instant.parse(it) }.getOrNull() })
    }
    return timestamps
}
