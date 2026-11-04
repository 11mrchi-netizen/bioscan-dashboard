@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class)
package com.bioscan.fieldterminal.domain.score

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate

// A FINAL morning's scores are kept (score_snapshots) and stand. They move only when the morning is built again with
// something it did not have before: an input that was missing (stress that synced late, a check-in logged after noon), or a
// cap (an illness logged for that day). A sync that rewrites a value the morning already had, the monthly re-fit of the
// CHARGE weights, or newer mornings joining the ranking history leave it as it was. A new SCORE_MODEL_VERSION replaces it.

@Serializable
data class KeptMorning(
    val day: LocalDate,
    @SerialName("model_version") val modelVersion: String,
    /** The input keys (and caps, as "cap:<reason>") the kept scores were built from. */
    val inputs: Set<String>,
    val scores: DayScores,
)

/** What a morning was scored from: every input that had a value, and its cap. */
fun inputsOf(s: DayScores): Set<String> =
    s.domains.values.flatMap { d -> d.raw.groups.flatMap { g -> g.inputs.map { it.spec.key } } }.toSet() +
        listOfNotNull(s.charge.cap?.let { "cap:${it.reason}" })

/** The scores to show for a FINAL morning, and the morning to keep when that changed (null: nothing to save). */
fun settle(fresh: DayScores, kept: KeptMorning?): Pair<DayScores, KeptMorning?> {
    val inputs = inputsOf(fresh)
    val stands = kept != null && kept.modelVersion == fresh.modelVersion && (inputs - kept.inputs).isEmpty()
    return if (stands) kept!!.scores to null else fresh to KeptMorning(fresh.day, fresh.modelVersion, inputs, fresh)
}
