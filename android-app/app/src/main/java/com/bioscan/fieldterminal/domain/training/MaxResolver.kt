package com.bioscan.fieldterminal.domain.training

import com.bioscan.fieldterminal.domain.training.generate.MaxEntry
import java.time.LocalDate

// DAV-345. Which max a prescription is built from. A recorded max (test, manual entry or
// progression) and an estimate from logged sets compete on recency: the newer one wins, and a
// recorded max wins a tie. Every result says where it came from so the setup screen can show it.

data class RecordedMax(val movementKey: String, val name: String, val kind: String, val value: Double, val asOf: LocalDate, val source: String)

data class ResolvedMax(val name: String, val entry: MaxEntry, val source: String, val asOf: LocalDate?, val basedOn: String)

private val ONE_RM_KINDS = setOf("1rm", "e1rm", "rm_derived")

fun resolveMaxes(
    recorded: List<RecordedMax>,
    logged: Map<String, List<DatedSet>>,
    names: Map<String, String>,
    today: LocalDate,
): Map<String, ResolvedMax> {
    val keys = (recorded.map { it.movementKey } + logged.keys).toSet()
    return keys.associateWith { key ->
        val rows = recorded.filter { it.movementKey == key }
        val oneRm = rows.filter { it.kind in ONE_RM_KINDS }.maxByOrNull { it.asOf }
        val maxReps = rows.filter { it.kind == "max_reps" }.maxByOrNull { it.asOf }
        val training = rows.filter { it.kind == "training_max" }.maxByOrNull { it.asOf }
        val estimate = logged[key]?.let { estimateMaxFromHistory(it, today) }
        val useRecorded = oneRm != null && (estimate == null || !estimate.asOf.isAfter(oneRm.asOf))
        val kg = if (useRecorded) oneRm!!.value else estimate?.kg
        val source = when {
            useRecorded -> oneRm!!.source
            estimate != null -> estimate.source.db
            else -> "none"
        }
        val asOf = if (useRecorded) oneRm!!.asOf else estimate?.asOf
        val basedOn = when {
            useRecorded -> "recorded ${oneRm!!.source} ${oneRm.asOf}"
            estimate != null -> "${estimate.source.db.replace('_', ' ')}, ${estimate.basedOnSets} set(s) on ${estimate.asOf}"
            else -> "no data"
        }
        ResolvedMax(
            name = names[key] ?: rows.firstOrNull()?.name ?: key,
            entry = MaxEntry(oneRmKg = kg, trainingMaxKg = training?.value, maxReps = maxReps?.value?.toInt()),
            source = source, asOf = asOf, basedOn = basedOn,
        )
    }
}
