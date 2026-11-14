@file:UseSerializers(com.bioscan.fieldterminal.domain.ZonedDateTimeSerializer::class, com.bioscan.fieldterminal.domain.LocalDateSerializer::class)
package com.bioscan.fieldterminal.domain.fuel

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// Which plan a recording was (docs/fuel-planning/README.md, "Matching recordings to plans"). What is done often differs
// from what was planned: later in the morning than the calendar's 07:00, 13 km where 19.3 were planned, a run where a
// hike was. So a recording is not matched by its start alone but scored against each plan of its day:
//   - the kind of session: the same type costs nothing, a compatible one (a planned run done as a hike) 0.8;
//     an incompatible one is never matched;
//   - the start: 0.15 for each hour apart, at most 1.5 (a plan with no time: 0.6);
//   - the size: distance when both have one (1.2 per unit of log ratio: 13 km for 19.3 costs 0.47), else duration
//     (0.8 per unit of log ratio, plan durations being estimates); at most 2;
//   - the role: a long plan (LONG, or 90 minutes and over) is the day's longest recording (-0.3), or not (+0.6).
// Leaving a recording unmatched costs [MATCH_NONE_COST]; of all the ways to pair a day's recordings with its plans, the
// cheapest is taken (exhaustively for a normal day). Confidence is exp(-0.7 × cost), at least 0.8 when the two are each
// other's only possible pair. Stored links (session_plan_links, and the links events and planned sessions carry) come
// first: the person's answer always wins, and an automatic match is kept once its day is over.

const val MATCH_NONE_COST = 2.0
/** Under this a match is shown as one to check. */
const val LOW_MATCH_CONFIDENCE = 0.5
private const val ONLY_PAIR_CONFIDENCE = 0.8
private const val EXHAUSTIVE_MAX = 6

/** The stored answer for one recording: the plan it was ([planRef], a plan item's sourceId), or none ("not planned"). */
@Serializable
data class StoredLink(
    val sessionId: Long,
    val planRef: String?,
    val manual: Boolean,
    val confidence: Double? = null,
    val planTitle: String? = null,
)

data class PlanMatch(val plan: PlannedItem, val confidence: Double, val stored: Boolean, val manual: Boolean)

/** What pairing [rec] with [plan] costs, or null when they cannot be the same session. [longest]: [rec] is the day's longest. */
fun matchCost(plan: PlannedItem, rec: RecordedSessionInput, zone: ZoneId, longest: Boolean): Double? {
    if (!compatibleTypes(plan.type, rec.type)) return null
    var cost = if (plan.type == rec.type) 0.0 else 0.8
    val start = rec.start.withZoneSameInstant(zone)
    cost += plan.start?.let { min(abs(Duration.between(it, start).toMinutes()) / 60.0 * 0.15, 1.5) } ?: 0.6
    val pk = plan.distanceKm
    val rk = rec.distanceKm
    cost += when {
        pk != null && rk != null && pk > 0 && rk > 0 -> min(abs(ln(rk / pk)) * 1.2, 2.0)
        plan.durationMin > 0 && rec.durationMin > 0 -> min(abs(ln(rec.durationMin / plan.durationMin)) * 0.8, 2.0)
        else -> 0.5
    }
    if (plan.intensity == Intensity.LONG || plan.durationMin >= LONG_SESSION_MIN) cost += if (longest) -0.3 else 0.6
    return max(cost, 0.0)
}

/** The cheapest pairing of [recs] with [plans] (each plan at most once), by recording id. */
fun assignMatches(plans: List<PlannedItem>, recs: List<RecordedSessionInput>, zone: ZoneId): Map<Long, PlanMatch> {
    if (plans.isEmpty() || recs.isEmpty()) return emptyMap()
    val longestId = recs.maxByOrNull { it.durationMin }?.id
    val cost = recs.map { r -> plans.map { p -> matchCost(p, r, zone, r.id == longestId)?.takeIf { it < MATCH_NONE_COST } } }
    val pick = IntArray(recs.size) { -1 }

    if (recs.size <= EXHAUSTIVE_MAX && plans.size <= EXHAUSTIVE_MAX) {
        var best = Double.MAX_VALUE
        val current = IntArray(recs.size) { -1 }
        val used = BooleanArray(plans.size)
        fun search(i: Int, total: Double) {
            if (total >= best) return
            if (i == recs.size) {
                best = total
                current.copyInto(pick)
                return
            }
            for (j in plans.indices) {
                val c = cost[i][j] ?: continue
                if (used[j]) continue
                used[j] = true; current[i] = j
                search(i + 1, total + c)
                used[j] = false; current[i] = -1
            }
            search(i + 1, total + MATCH_NONE_COST)
        }
        search(0, 0.0)
    } else {
        // A crowded day: closest pairs first.
        val used = BooleanArray(plans.size)
        recs.indices.flatMap { i -> plans.indices.mapNotNull { j -> cost[i][j]?.let { Triple(i, j, it) } } }
            .sortedBy { it.third }
            .forEach { (i, j, _) -> if (pick[i] < 0 && !used[j]) { pick[i] = j; used[j] = true } }
    }

    return recs.indices.mapNotNull { i ->
        val j = pick[i].takeIf { it >= 0 } ?: return@mapNotNull null
        val c = cost[i][j]!!
        val onlyPair = cost[i].count { it != null } == 1 && cost.count { it[j] != null } == 1
        val confidence = exp(-0.7 * c).let { if (onlyPair) max(it, ONLY_PAIR_CONFIDENCE) else it }.coerceIn(0.0, 1.0)
        recs[i].id to PlanMatch(plans[j], confidence, stored = false, manual = false)
    }.toMap()
}

/** Whether a recording of [recordedType] can be a plan of [plannedType]. */
fun compatibleTypes(plannedType: String, recordedType: String): Boolean = when (plannedType) {
    recordedType -> true
    "run" -> recordedType in setOf("run", "hike", "other")
    "hike" -> recordedType in setOf("hike", "walk", "run")
    "other" -> recordedType != "walk"
    "strength" -> recordedType in setOf("strength", "other")
    else -> false
}

