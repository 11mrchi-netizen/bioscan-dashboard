package com.bioscan.fieldterminal.domain.training

import com.bioscan.fieldterminal.domain.median
import com.bioscan.fieldterminal.domain.training.definition.NumRange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

// DAV-346. Reads a planned session's frozen prescription (planned_sessions.prescription) and
// compares what the lifter logged with it, so a workout's page can say what was planned and
// how the session went against it. No scoring: a verdict is a plain statement.

data class PlannedLift(
    val exercise: String,
    val sets: NumRange?,
    val reps: NumRange?,
    val targetKg: Double?, // the load to move: total barbell load, or added weight for weighted calisthenics
    val loadKind: String,  // pct_1rm | added | pct_max_reps | work_up_rm | fixed | bodyweight | none | unresolved
    val pct: Double?,
    val technique: String?,
    val note: String?,
)

data class PlannedConditioning(val label: String, val category: String?, val minutes: NumRange?)

data class LoggedSet(val reps: Int, val kg: Double)

enum class PlanVerdict(val text: String) {
    Done("AS PLANNED"),
    Above("ABOVE PLAN"),
    Below("BELOW PLAN"),
    Short("SHORT OF SETS OR REPS"),
    NotLogged("NOT LOGGED"),
    Open("NO FIXED TARGET"),
}

private fun JsonElement?.range(): NumRange? = when (this) {
    is JsonPrimitive -> doubleOrNull?.let { NumRange.of(it) }
    is JsonObject -> {
        val lo = (this["min"] as? JsonPrimitive)?.doubleOrNull
        val hi = (this["max"] as? JsonPrimitive)?.doubleOrNull
        if (lo != null && hi != null) NumRange(lo, hi) else null
    }
    else -> null
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

fun parsePlannedLifts(prescription: JsonElement?): List<PlannedLift> =
    (prescription as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        if (o.str("type") != "lift") return@mapNotNull null
        val load = o["load"] as? JsonObject
        val kind = load?.str("kind") ?: "none"
        val target = when (kind) {
            "pct_1rm", "fixed" -> load?.num("loadable_kg")
            "added" -> load?.num("added_kg")
            else -> null
        }
        PlannedLift(
            exercise = o.str("exercise") ?: return@mapNotNull null, sets = o["sets"].range(), reps = o["reps"].range(),
            targetKg = target, loadKind = kind, pct = load?.num("pct"), technique = o.str("technique"), note = o.str("note"),
        )
    }

fun parsePlannedConditioning(prescription: JsonElement?): PlannedConditioning? =
    (prescription as? JsonArray).orEmpty().firstNotNullOfOrNull { e ->
        val o = e as? JsonObject ?: return@firstNotNullOfOrNull null
        if (o.str("type") != "conditioning") return@firstNotNullOfOrNull null
        PlannedConditioning(o.str("label") ?: "Conditioning", o.str("category"), o["minutes"].range())
    }

// Working sets are the ones near the target load; lighter ones are warm-ups. Loads within the
// tolerance (one loadable step) of the target count as on target.
fun compareToPlan(planned: PlannedLift, logged: List<LoggedSet>, toleranceKg: Double = 2.5): PlanVerdict {
    if (logged.isEmpty()) return PlanVerdict.NotLogged
    val target = planned.targetKg
    if (target == null || planned.loadKind == "work_up_rm") return PlanVerdict.Open
    val work = logged.filter { it.kg >= target * 0.5 }
    if (work.isEmpty()) return PlanVerdict.Below
    val minSets = planned.sets?.min?.toInt() ?: 1
    val minReps = planned.reps?.min?.toInt() ?: 1
    if (work.size < minSets || work.any { it.reps < minReps }) return PlanVerdict.Short
    val typical = median(work.map { it.kg })
    return when {
        typical > target + toleranceKg -> PlanVerdict.Above
        typical < target - toleranceKg -> PlanVerdict.Below
        else -> PlanVerdict.Done
    }
}
