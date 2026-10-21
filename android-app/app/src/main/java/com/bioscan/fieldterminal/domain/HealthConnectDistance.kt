package com.bioscan.fieldterminal.domain

// Health Connect distance for one workout. Each app's distance records are summed for the workout's window and the
// largest single app's total kept (summing across apps double-counted, DAV-79). That still doubled runs whose source
// app wrote the same workout, and its distance records, twice (most runs from January to mid-August 2026, a 37 km
// "longest run" that Zepp has as 18 km): within one app, a stretch of time is now counted once, and a record reaching
// outside the workout's window counts only for the part inside it.

/** One distance record: its interval (epoch millis) and metres. */
data class DistanceInterval(val startMs: Long, val endMs: Long, val meters: Double)

/** Metres of one app's records inside [windowStartMs, windowEndMs], each stretch of time counted once. */
fun distanceWithoutOverlap(records: List<DistanceInterval>, windowStartMs: Long, windowEndMs: Long): Double {
    var total = 0.0
    var coveredUntil = Long.MIN_VALUE
    val seenInstants = mutableSetOf<Pair<Long, Double>>()
    for (r in records.sortedWith(compareBy<DistanceInterval> { it.startMs }.thenByDescending { it.endMs })) {
        if (r.meters <= 0.0) continue
        if (r.endMs <= r.startMs) {
            // An instant record: inside the window, counted once per (time, value).
            if (r.startMs in windowStartMs..windowEndMs && seenInstants.add(r.startMs to r.meters)) total += r.meters
            continue
        }
        val start = maxOf(r.startMs, windowStartMs, coveredUntil)
        val end = minOf(r.endMs, windowEndMs)
        if (end > start) total += r.meters * (end - start).toDouble() / (r.endMs - r.startMs).toDouble()
        coveredUntil = maxOf(coveredUntil, r.endMs)
    }
    return total
}

/** The largest single app's distance in metres (apps by package name), each app's records counted without overlap. */
fun workoutDistanceMeters(byApp: Map<String, List<DistanceInterval>>, windowStartMs: Long, windowEndMs: Long): Double =
    byApp.values.maxOfOrNull { distanceWithoutOverlap(it, windowStartMs, windowEndMs) } ?: 0.0
