@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class)

package com.bioscan.fieldterminal.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate

// Shoes (docs/zepp-integration/03-stress-days-and-shoes.md). Zepp tags a run with the code of the shoes picked on the
// watch or in the app (gearId) and sends no names, so the person keeps a shoe list and gives each shoe its code. Pure:
//   - a run's shoes: the person's own choice for that run first, then the shoe its Zepp code is matched to; a code no
//     shoe has is UNKNOWN (matched from the run or from Setup), no code and no choice is NONE;
//   - a shoe's km (the km it had when added, plus its runs), wear against its retirement distance, its bests;
//   - the records set in it: a run that beat the best of every run before it (road: longest, fastest of 5 km or more;
//     trail: longest, biggest climb, fastest grade-adjusted of 10 km or more, as RunRecords.kt reads them).
// Bike rides carry no code: only runs (and sessions the person gave shoes) are counted.

enum class ShoeUse(val code: String, val label: String) {
    ROAD("road", "Road"), TRAIL("trail", "Trail"), TREADMILL("treadmill", "Treadmill"), ANY("any", "Any");

    companion object {
        fun of(code: String?): ShoeUse = entries.firstOrNull { it.code == code } ?: ROAD
    }
}

@Serializable
data class Shoe(
    val id: Long,
    val name: String,
    val use: String = "road",
    val zeppGearId: String? = null,
    val startKm: Double = 0.0,
    val retireKm: Double = 700.0,
    val startedOn: LocalDate? = null,
    val retiredOn: LocalDate? = null,
    val notes: String? = null,
    /** The shoe's icon: a small square JPEG, base64. */
    val photoJpeg: String? = null,
) {
    val retired: Boolean get() = retiredOn != null
}

/** One run (or a session given shoes) as the shoes see it. */
@Serializable
data class ShoeRun(
    val sessionId: Long,
    val date: LocalDate,
    val distanceKm: Double,
    val trail: Boolean = false,
    val gearId: String? = null,
    /** The person's own choice of shoes for this session. */
    val chosenShoeId: Long? = null,
    val zeppSportType: String? = null,
)

sealed interface ShoeAssignment {
    data class Chosen(val shoe: Shoe) : ShoeAssignment
    data class Matched(val shoe: Shoe) : ShoeAssignment
    data class Unknown(val gearId: String) : ShoeAssignment
    data object None : ShoeAssignment
}

val ShoeAssignment.shoe: Shoe? get() = when (this) {
    is ShoeAssignment.Chosen -> shoe
    is ShoeAssignment.Matched -> shoe
    else -> null
}

fun assignShoe(run: ShoeRun, shoes: List<Shoe>): ShoeAssignment {
    run.chosenShoeId?.let { id -> shoes.firstOrNull { it.id == id }?.let { return ShoeAssignment.Chosen(it) } }
    val code = run.gearId?.takeIf { it.isNotBlank() } ?: return ShoeAssignment.None
    return shoes.firstOrNull { it.zeppGearId == code }?.let { ShoeAssignment.Matched(it) } ?: ShoeAssignment.Unknown(code)
}

enum class ShoeWear(val label: String) { FRESH("Fresh"), WORN("Nearly due"), DUE("Due to replace") }

fun wearOf(totalKm: Double, retireKm: Double): ShoeWear = when {
    retireKm <= 0 -> ShoeWear.FRESH
    totalKm >= retireKm -> ShoeWear.DUE
    totalKm >= retireKm * 0.8 -> ShoeWear.WORN
    else -> ShoeWear.FRESH
}

/** A record set in a pair of shoes: the run beat the best of every run before it. [standing]: still the best. */
data class ShoeRecord(val label: String, val sessionId: Long, val date: LocalDate, val value: Double, val unit: String, val previous: Double, val standing: Boolean)

data class ShoeStats(
    val shoe: Shoe,
    val runs: List<ShoeRun>,
    val runKm: Double,
    val totalKm: Double,
    val wear: ShoeWear,
    val first: LocalDate?,
    val last: LocalDate?,
    val longest: ShoeRun?,
    val records: List<ShoeRecord>,
) {
    val wearShare: Double get() = if (shoe.retireKm > 0) totalKm / shoe.retireKm else 0.0
}

/** A Zepp code no shoe has yet, with what was run in it, for matching it to a shoe. */
data class UnknownGear(val gearId: String, val runs: Int, val km: Double, val first: LocalDate, val last: LocalDate, val trailRuns: Int)

fun shoeStats(shoe: Shoe, runs: List<ShoeRun>, shoes: List<Shoe>, records: List<RecordEvent>): ShoeStats {
    val mine = runs.filter { assignShoe(it, shoes).shoe?.id == shoe.id }.sortedByDescending { it.date }
    val km = mine.sumOf { it.distanceKm }
    val ids = mine.map { it.sessionId }.toSet()
    return ShoeStats(
        shoe = shoe, runs = mine, runKm = km, totalKm = km + shoe.startKm, wear = wearOf(km + shoe.startKm, shoe.retireKm),
        first = mine.minOfOrNull { it.date }, last = mine.maxOfOrNull { it.date }, longest = mine.maxByOrNull { it.distanceKm },
        records = records.filter { it.sessionId in ids }
            .map { ShoeRecord(it.label, it.sessionId, it.date, it.value, it.unit, it.previous, it.standing) }
            .sortedByDescending { it.date },
    )
}

fun unknownGear(runs: List<ShoeRun>, shoes: List<Shoe>): List<UnknownGear> =
    runs.mapNotNull { r -> (assignShoe(r, shoes) as? ShoeAssignment.Unknown)?.let { it.gearId to r } }
        .groupBy({ it.first }, { it.second })
        .map { (code, rs) -> UnknownGear(code, rs.size, rs.sumOf { it.distanceKm }, rs.minOf { it.date }, rs.maxOf { it.date }, rs.count { it.trail }) }
        .sortedByDescending { it.last }

// ---- Records over time ------------------------------------------------------------------------------------------------

/** A run that beat the best of every run before it on one record; [standing]: no later run beat it. */
data class RecordEvent(val label: String, val sessionId: Long, val date: LocalDate, val value: Double, val unit: String, val previous: Double, val standing: Boolean)

/** The inputs a record needs from one run (RunPoint's fields). */
data class RecordInput(val sessionId: Long, val date: LocalDate, val distanceKm: Double, val paceMinPerKm: Double, val trail: Boolean, val elevationGainM: Double?, val gapMinPerKm: Double?)

private class Track(val label: String, val unit: String, val higherBetter: Boolean, val select: (RecordInput) -> Double?)

private val RECORD_TRACKS = listOf(
    Track("Longest road run", "km", true) { r -> r.distanceKm.takeIf { !r.trail } },
    Track("Fastest road run (5 km+)", "min/km", false) { r -> r.paceMinPerKm.takeIf { !r.trail && r.distanceKm >= ROAD_FASTEST_MIN_KM && it > 0 } },
    Track("Longest trail run", "km", true) { r -> r.distanceKm.takeIf { r.trail } },
    Track("Biggest climb", "m", true) { r -> r.elevationGainM?.takeIf { r.trail && it > 0 } },
    Track("Fastest grade-adjusted trail (10 km+)", "min/km", false) { r -> r.gapMinPerKm?.takeIf { r.trail && r.distanceKm >= TRAIL_FASTEST_MIN_KM && it > 0 } },
)

/** Every record set over [runs], oldest first. The first run of a kind sets nothing: there is nothing yet to beat. */
fun recordEvents(runs: List<RecordInput>): List<RecordEvent> {
    val ordered = runs.sortedWith(compareBy({ it.date }, { it.sessionId }))
    return RECORD_TRACKS.flatMap { t ->
        var best: Double? = null
        val events = mutableListOf<RecordEvent>()
        for (r in ordered) {
            val v = t.select(r) ?: continue
            val b = best
            if (b == null) { best = v; continue }
            if (if (t.higherBetter) v > b else v < b) {
                events += RecordEvent(t.label, r.sessionId, r.date, v, t.unit, b, standing = false)
                best = v
            }
        }
        events.mapIndexed { i, e -> if (i == events.lastIndex) e.copy(standing = true) else e }
    }.sortedBy { it.date }
}
