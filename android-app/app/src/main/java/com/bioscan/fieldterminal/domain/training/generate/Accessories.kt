package com.bioscan.fieldterminal.domain.training.generate

import com.bioscan.fieldterminal.domain.training.definition.LoadDto
import com.bioscan.fieldterminal.domain.training.definition.NumRange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// Accessory work (Nov 2026): exercises the lifter adds to a strength module's sessions on top of the book's slots
// (curls 3 x 10-12 @ RPE 8 on session A). They ride along with the module's weeks: full in normal and peak weeks, half
// the sets (rounded up) in deload, taper and easy weeks, none in test weeks. Stored in the block's
// choices.clusters.<module>.accessories as { "<session id>" | "*": [ {exercise, sets, reps, load, rest_s, note} ] };
// "*" means every session of the module. Ported one to one in training-core/accessories.ts.

const val ALL_SESSIONS = "*"

data class Accessory(
    val exercise: String,
    val sets: Int,
    val reps: NumRange? = null,
    val load: LoadDto = LoadDto("none"),
    val restS: Int? = null,
    val note: String? = null,
)

/** The accessories of [sessionId]: the ones for every session first, then the session's own. */
fun accessoriesFor(choice: ModuleChoice, sessionId: String?): List<Accessory> =
    choice.accessories[ALL_SESSIONS].orEmpty() + (sessionId?.let { choice.accessories[it] }.orEmpty())

/** Sets of an accessory in a week of [weekKind] (the variant week's kind), or 0 when it is left out. */
fun accessorySets(sets: Int, weekKind: String): Int = when (weekKind) {
    "test" -> 0
    "deload", "taper", "easy" -> (sets + 1) / 2
    else -> sets
}

private fun JsonElement.range(): NumRange? = when (this) {
    is JsonPrimitive -> doubleOrNull?.let { NumRange.of(it) }
    is JsonObject -> {
        val lo = (this["min"] as? JsonPrimitive)?.doubleOrNull
        val hi = (this["max"] as? JsonPrimitive)?.doubleOrNull
        if (lo != null && hi != null && lo <= hi) NumRange(lo, hi) else null
    }
    else -> null
}

private fun JsonObject.loadDto(): LoadDto {
    fun d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    fun i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    return LoadDto(kind = (this["kind"] as? JsonPrimitive)?.contentOrNull ?: "none", value = d("value"), minRm = i("min_rm"), maxRm = i("max_rm"), kg = d("kg"), rpe = d("rpe"))
}

/** Reads choices.clusters.<module>.accessories; anything unreadable is dropped, never thrown. */
fun accessoriesFromJson(el: JsonElement?): Map<String, List<Accessory>> {
    val o = el as? JsonObject ?: return emptyMap()
    return o.mapNotNull { (session, v) ->
        val list = (v as? JsonArray)?.mapNotNull { a ->
            val x = a as? JsonObject ?: return@mapNotNull null
            val name = (x["exercise"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val sets = (x["sets"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            val load = (x["load"] as? JsonObject)?.loadDto() ?: LoadDto("none")
            if (runCatching { load.toSpec() }.isFailure) return@mapNotNull null
            Accessory(name, sets, x["reps"]?.range(), load, (x["rest_s"] as? JsonPrimitive)?.intOrNull, (x["note"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() })
        }.orEmpty()
        if (list.isEmpty()) null else session to list
    }.toMap()
}

private fun NumRange.json(): JsonElement = if (isFixed) JsonPrimitive(min) else buildJsonObject { put("min", min); put("max", max) }

fun accessoriesJson(map: Map<String, List<Accessory>>): JsonObject = buildJsonObject {
    map.filterValues { it.isNotEmpty() }.forEach { (session, list) ->
        put(session, buildJsonArray {
            list.forEach { a ->
                add(buildJsonObject {
                    put("exercise", a.exercise); put("sets", a.sets)
                    a.reps?.let { put("reps", it.json()) }
                    put("load", buildJsonObject {
                        put("kind", a.load.kind)
                        a.load.value?.let { put("value", it) }; a.load.kg?.let { put("kg", it) }; a.load.rpe?.let { put("rpe", it) }
                        a.load.minRm?.let { put("min_rm", it) }; a.load.maxRm?.let { put("max_rm", it) }
                    })
                    a.restS?.let { put("rest_s", it) }
                    a.note?.let { put("note", it) }
                })
            }
        })
    }
}

/** "3 x 10-12 @ RPE 8" */
fun accessoryLine(a: Accessory): String {
    fun n(x: Double) = if (x % 1.0 == 0.0) x.toLong().toString() else x.toString()
    val reps = a.reps?.let { if (it.isFixed) n(it.min) else "${n(it.min)}-${n(it.max)}" }
    val load = when (a.load.kind) {
        "rpe" -> a.load.rpe?.let { "RPE ${n(it)}" }
        "pct_1rm" -> a.load.value?.let { "${n(it)}% 1RM" }
        "fixed" -> a.load.kg?.let { "${n(it)} kg" }
        "bodyweight" -> "bodyweight"
        else -> null
    }
    return "${a.sets}" + (reps?.let { " x $it" } ?: " sets") + (load?.let { " @ $it" } ?: "")
}

