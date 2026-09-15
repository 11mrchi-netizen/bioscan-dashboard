package com.bioscan.fieldterminal.domain

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Step 14 (Map tab). Real data from the signed-in user's own Google Calendar
// -- same colorId '8' == training-session convention index.html's
// fetchNextSession() already established (confirmed there, not guessed here;
// more reliable than matching emoji/title text, which can vary) -- and,
// when its description links one, a GPX route file from Drive. Ported 1:1
// from index.html's classifySessionType()/parseGpxLink()/parseGpxPoints(),
// except daysUntilSession(): the web hardcodes Asia/Taipei there specifically
// to correct for an arbitrary browser's timezone; this uses the device's own
// zone instead, matching every other date computation already in this app.
data class NextSession(
    val title: String,
    val startIso: String,
    val description: String,
    val kind: String,
    val gpxLink: String?,
)

data class GpxPoint(val lat: Double, val lon: Double, val elevationM: Double?)

fun classifySessionKind(summary: String): String = when {
    summary.contains("🏋️") -> "strength" // 🏋️
    summary.contains("⛰️") -> "hill" // ⛰️
    summary.contains("🏃") -> // 🏃
        if (Regex("trail|hill|off-road", RegexOption.IGNORE_CASE).containsMatchIn(summary)) "trail run" else "run"
    else -> "session"
}

fun parseGpxLink(description: String?): String? =
    Regex("""https://drive\.google\.com/\S+""").find(description ?: "")?.value

fun parseSessionZonedDateTime(startIso: String): ZonedDateTime =
    try {
        OffsetDateTime.parse(startIso).atZoneSameInstant(ZoneId.systemDefault())
    } catch (e: Exception) {
        LocalDate.parse(startIso).atStartOfDay(ZoneId.systemDefault())
    }

fun daysUntilSession(startIso: String): Long {
    val today = LocalDate.now()
    val sessionDate = parseSessionZonedDateTime(startIso).toLocalDate()
    return ChronoUnit.DAYS.between(today, sessionDate)
}

// GPX is plain XML -- trkpt (recorded track) is the common case, rtept
// (planned route) is the fallback, same as the web dashboard's
// parseGpxPoints(). No library needed for either.
fun parseGpxPoints(xmlText: String): List<GpxPoint> {
    val factory = DocumentBuilderFactory.newInstance()
    val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText)))
    var nodes = doc.getElementsByTagName("trkpt")
    if (nodes.length == 0) nodes = doc.getElementsByTagName("rtept")

    val points = mutableListOf<GpxPoint>()
    for (i in 0 until nodes.length) {
        val el = nodes.item(i) as? Element ?: continue
        val lat = el.getAttribute("lat").toDoubleOrNull()
        val lon = el.getAttribute("lon").toDoubleOrNull()
        if (lat != null && lon != null) {
            val elevation = el.getElementsByTagName("ele").item(0)?.textContent?.toDoubleOrNull()
            points.add(GpxPoint(lat, lon, elevation))
        }
    }
    if (points.isEmpty()) throw IllegalStateException("No track/route points found in this GPX file.")
    return points
}

// Real distance summed from the route's own points (haversine, km) -- same
// "compute from real data, don't invent" principle as sumDistanceKmSince()
// for logged runs.
fun routeDistanceKm(points: List<GpxPoint>): Double {
    if (points.size < 2) return 0.0
    var total = 0.0
    for (i in 1 until points.size) total += haversineKm(points[i - 1], points[i])
    return total
}

// Only computed when the GPX file actually carries elevation data -- absent
// rather than fabricated when it doesn't (most phone-recorded GPX exports do
// include <ele>, but not all).
fun routeElevationGainM(points: List<GpxPoint>): Double? {
    val elevations = points.mapNotNull { it.elevationM }
    if (elevations.size < 2) return null
    var gain = 0.0
    for (i in 1 until elevations.size) {
        val delta = elevations[i] - elevations[i - 1]
        if (delta > 0) gain += delta
    }
    return gain
}

private fun haversineKm(a: GpxPoint, b: GpxPoint): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 2 * earthRadiusKm * asin(sqrt(h))
}
