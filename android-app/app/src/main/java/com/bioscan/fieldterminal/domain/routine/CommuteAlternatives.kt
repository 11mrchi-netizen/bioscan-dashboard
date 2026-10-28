package com.bioscan.fieldterminal.domain.routine

import java.util.PriorityQueue

// Routine intelligence R5 (docs/routine-intelligence/01-research-and-plan.md, section 10): other ways to make the usual
// commute, from Taipei's MRT stations and travel times and YouBike docks (TDX, through the transit-alternatives function).
// Everything is computed here, on the phone: TDX only ever serves whole station lists, never a point of the person's
// (plan section 8.3, rule 3). Pure.
//
// Estimates, labelled as such on screen: street distance is the straight line times ROUTE_FACTOR, walking 80 m/min,
// YouBike 220 m/min, half an average headway's wait at the platform, a fixed transfer walk. The measured commute is the
// yardstick each option is compared with.

data class MetroStation(val id: String, val name: String, val lat: Double, val lon: Double)

// One direction of one hop between adjacent stations: running time plus the dwell at the station left.
data class MetroHop(val fromId: String, val toId: String, val runSec: Int, val stopSec: Int = 0)

data class BikeDock(val id: String, val lat: Double, val lon: Double, val rentable: Int? = null, val returnable: Int? = null)

data class CommuteOption(
    val kind: OptionKind,
    val minutes: Double,
    val activeMinutes: Double,
    // Stations or docks used, for the card's line ("Zhongshan → Taipei 101").
    val via: List<String>,
)

enum class OptionKind(val label: String) {
    METRO("MRT"),
    METRO_LONGER_WALK("MRT, walking to a farther station"),
    METRO_BIKE("MRT, then YouBike"),
    BIKE("YouBike all the way"),
    WALK("Walk all the way"),
}

const val WALK_M_PER_MIN = 80.0
const val BIKE_M_PER_MIN = 220.0
const val ROUTE_FACTOR = 1.3
const val PLATFORM_WAIT_MIN = 3.0
const val TRANSFER_MIN = 4.0
const val BIKE_HANDLING_MIN = 2.0
private const val TRANSFER_RADIUS_M = 250.0
private const val MAX_STATION_WALK_M = 1500.0
private const val DOCK_NEAR_M = 300.0

private fun streetM(aLat: Double, aLon: Double, bLat: Double, bLon: Double) = haversineM(aLat, aLon, bLat, bLon) * ROUTE_FACTOR
private fun walkMin(m: Double) = m / WALK_M_PER_MIN
private fun bikeMin(m: Double) = m / BIKE_M_PER_MIN

// Riding minutes between every pair of stations reachable from [from] (Dijkstra over hops, both ways, plus transfer walks
// between different station ids within 250 m: line interchanges carry separate ids per line).
internal fun rideMinutes(stations: List<MetroStation>, hops: List<MetroHop>, from: String): Map<String, Double> {
    val edges = mutableMapOf<String, MutableList<Pair<String, Double>>>()
    fun add(a: String, b: String, min: Double) { edges.getOrPut(a) { mutableListOf() } += b to min }
    for (h in hops) {
        val m = (h.runSec + h.stopSec) / 60.0
        add(h.fromId, h.toId, m)
        add(h.toId, h.fromId, m)
    }
    for (a in stations) for (b in stations) {
        if (a.id != b.id && haversineM(a.lat, a.lon, b.lat, b.lon) <= TRANSFER_RADIUS_M) add(a.id, b.id, TRANSFER_MIN)
    }
    val dist = mutableMapOf(from to 0.0)
    val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
    queue += from to 0.0
    while (queue.isNotEmpty()) {
        val (node, d) = queue.poll() ?: break
        if (d > (dist[node] ?: Double.MAX_VALUE)) continue
        for ((next, w) in edges[node].orEmpty()) {
            val nd = d + w
            if (nd < (dist[next] ?: Double.MAX_VALUE)) {
                dist[next] = nd
                queue += next to nd
            }
        }
    }
    return dist
}

private data class MetroLeg(val origin: MetroStation, val exit: MetroStation, val minutes: Double, val activeMinutes: Double)

private fun stationsNear(stations: List<MetroStation>, lat: Double, lon: Double, k: Int): List<MetroStation> =
    stations.map { it to streetM(lat, lon, it.lat, it.lon) }
        .filter { it.second <= MAX_STATION_WALK_M }
        .sortedBy { it.second }
        .take(k)
        .map { it.first }

private fun nearestDock(docks: List<BikeDock>, lat: Double, lon: Double, needRent: Boolean): Pair<BikeDock, Double>? =
    docks.filter { d -> if (needRent) (d.rentable ?: 1) > 0 else (d.returnable ?: 1) > 0 }
        .map { it to streetM(lat, lon, it.lat, it.lon) }
        .filter { it.second <= DOCK_NEAR_M }
        .minByOrNull { it.second }

fun commuteOptions(
    from: Pair<Double, Double>,
    to: Pair<Double, Double>,
    stations: List<MetroStation>,
    hops: List<MetroHop>,
    docks: List<BikeDock>,
): List<CommuteOption> {
    val (fLat, fLon) = from
    val (tLat, tLon) = to
    val options = mutableListOf<CommuteOption>()

    // MRT, from each of the three nearest stations at either end.
    val origins = stationsNear(stations, fLat, fLon, 3)
    val exits = stationsNear(stations, tLat, tLon, 3)
    val legs = origins.flatMap { o ->
        val ride = rideMinutes(stations, hops, o.id)
        exits.mapNotNull { e ->
            val r = ride[e.id] ?: return@mapNotNull null
            if (o.id == e.id) return@mapNotNull null
            val walk = walkMin(streetM(fLat, fLon, o.lat, o.lon)) + walkMin(streetM(e.lat, e.lon, tLat, tLon))
            MetroLeg(o, e, walk + PLATFORM_WAIT_MIN + r, walk)
        }
    }
    val best = legs.minByOrNull { it.minutes }
    if (best != null) {
        options += CommuteOption(OptionKind.METRO, best.minutes, best.activeMinutes, listOf(best.origin.name, best.exit.name))
        // The most walking for at most 8 minutes more, from another station.
        legs.filter { it.origin.id != best.origin.id && it.minutes <= best.minutes + 8 && it.activeMinutes > best.activeMinutes + 2 }
            .maxByOrNull { it.activeMinutes }
            ?.let { options += CommuteOption(OptionKind.METRO_LONGER_WALK, it.minutes, it.activeMinutes, listOf(it.origin.name, it.exit.name)) }
    }

    // MRT to a station 1 to 4 km from work, then YouBike from a dock at that station to one near work.
    val endDock = nearestDock(docks, tLat, tLon, needRent = false)
    if (endDock != null) {
        origins.forEach { o ->
            val ride = rideMinutes(stations, hops, o.id)
            stations.forEach { s ->
                val r = ride[s.id] ?: return@forEach
                val bikeM = streetM(s.lat, s.lon, endDock.first.lat, endDock.first.lon)
                if (s.id == o.id || bikeM < 1000 || bikeM > 4000) return@forEach
                val startDock = nearestDock(docks, s.lat, s.lon, needRent = true) ?: return@forEach
                val walk = walkMin(streetM(fLat, fLon, o.lat, o.lon)) + walkMin(startDock.second) + walkMin(endDock.second)
                val bike = bikeMin(bikeM) + BIKE_HANDLING_MIN
                val total = walk + PLATFORM_WAIT_MIN + r + bike
                val option = CommuteOption(OptionKind.METRO_BIKE, total, walk + bike, listOf(o.name, s.name))
                val current = options.firstOrNull { it.kind == OptionKind.METRO_BIKE }
                if (current == null || total < current.minutes) {
                    options.removeAll { it.kind == OptionKind.METRO_BIKE }
                    options += option
                }
            }
        }
    }

    // YouBike the whole way, up to 8 km.
    val direct = streetM(fLat, fLon, tLat, tLon)
    val homeDock = nearestDock(docks, fLat, fLon, needRent = true)
    if (homeDock != null && endDock != null && direct <= 8000) {
        val bikeM = streetM(homeDock.first.lat, homeDock.first.lon, endDock.first.lat, endDock.first.lon)
        val walk = walkMin(homeDock.second) + walkMin(endDock.second)
        val bike = bikeMin(bikeM) + BIKE_HANDLING_MIN
        options += CommuteOption(OptionKind.BIKE, walk + bike, walk + bike, emptyList())
    }

    // Walking, up to 3 km.
    if (direct <= 3000) options += CommuteOption(OptionKind.WALK, walkMin(direct), walkMin(direct), emptyList())

    return options.sortedBy { it.minutes }
}
