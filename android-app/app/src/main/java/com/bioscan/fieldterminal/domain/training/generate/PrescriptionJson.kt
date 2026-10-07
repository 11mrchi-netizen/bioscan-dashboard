package com.bioscan.fieldterminal.domain.training.generate

import com.bioscan.fieldterminal.domain.training.ResolvedLoad
import com.bioscan.fieldterminal.domain.training.definition.NumRange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// DAV-345. The frozen form of a generated session's prescription (planned_sessions.prescription).
// An array of typed entries: "lift" (one per exercise), "conditioning" and "note". Written once
// at generation; the completion form (DAV-346) reads it back to prefill the session.

private fun NumRange.json(): JsonElement = if (isFixed) JsonPrimitive(min) else buildJsonObject { put("min", min); put("max", max) }

fun ResolvedLoad.toJson(): JsonObject = buildJsonObject {
    when (val l = this@toJson) {
        is ResolvedLoad.Barbell -> {
            put("kind", "pct_1rm"); put("pct", l.pct); put("target_kg", l.targetKg); put("loadable_kg", l.loadable.totalKg)
            put("bar_kg", l.loadable.barKg); putJsonArray("plates_per_side_kg") { l.loadable.platesPerSideKg.forEach { add(JsonPrimitive(it)) } }
        }
        is ResolvedLoad.Added -> { put("kind", "added"); put("pct", l.pct); put("added_kg", l.addedKg); l.totalKg?.let { put("total_kg", it) } }
        is ResolvedLoad.MaxRepsFraction -> { put("kind", "pct_max_reps"); put("pct", l.pct); put("reps", l.reps) }
        is ResolvedLoad.WorkUp -> { put("kind", "work_up_rm"); put("min_rm", l.minRm); put("max_rm", l.maxRm) }
        is ResolvedLoad.FixedKg -> { put("kind", "fixed"); put("kg", l.kg); put("loadable_kg", l.loadable.totalKg) }
        is ResolvedLoad.Rpe -> { put("kind", "rpe"); put("rpe", l.rpe) }
        ResolvedLoad.Bodyweight -> put("kind", "bodyweight")
        ResolvedLoad.NoLoad -> put("kind", "none")
        is ResolvedLoad.Unresolved -> { put("kind", "unresolved"); put("missing", l.missing.name) }
    }
}

fun GeneratedSession.prescriptionJson(): JsonArray = buildJsonArray {
    for (i in items) add(buildJsonObject {
        put("type", "lift"); put("slot", i.slot); put("exercise", i.exercise); put("role", i.role)
        i.sets?.let { put("sets", it.json()) }
        i.reps?.let { put("reps", it.json()) }
        put("load", i.load.toJson())
        i.technique?.let { put("technique", it) }
        if (i.optional) put("optional", true)
        i.note?.let { put("note", it) }
        if (i.projected) put("projected", true)
    })
    conditioning?.let { c ->
        add(buildJsonObject {
            put("type", "conditioning"); c.sessionKey?.let { put("session_key", it) }; put("label", c.label); c.category?.let { put("category", it) }
            c.minutes?.let { put("minutes", it.json()) }
            c.params?.let { put("params", it) }
        })
    }
    for (n in notes) add(buildJsonObject { put("type", "note"); put("text", n) })
}

fun GeneratedSession.hasProjectedLoad(): Boolean = items.any { it.projected }
