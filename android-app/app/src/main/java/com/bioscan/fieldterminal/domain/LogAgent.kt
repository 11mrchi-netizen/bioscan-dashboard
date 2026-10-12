package com.bioscan.fieldterminal.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Analysis Layer 3 / R2 (docs/analysis-layer-3). Shapes returned by the `log-agent` Edge Function and the pure rules the app
// applies before anything is written. The function only PROPOSES (supabase/functions/_shared/log-core/proposal.ts decides
// every number); the person confirms or fills in what is missing, and the app writes through the same repositories as the forms.

@Serializable
data class RosterCandidate(val id: Long, val name: String)

@Serializable
data class UnparsedEntry(val quote: String = "", val reason: String = "")

// One flat shape for all kinds; only the fields of `kind` are filled. `needs` is what the server could not ground in the
// message ("volume_ml", "abv_percent", "energy", "supplement_match" ...); `missing()` is recomputed from the fields so an edit
// in the review sheet clears it.
@Serializable
data class LogProposal(
    val id: String,
    val kind: String,
    val quote: String = "",
    val summary: String = "",
    val warnings: List<String> = emptyList(),
    val needs: List<String> = emptyList(),
    @SerialName("beverage_class") val beverageClass: String? = null,
    @SerialName("volume_ml") val volumeMl: Double? = null,
    @SerialName("abv_percent") val abvPercent: Double? = null,
    @SerialName("alcohol_g") val alcoholG: Double? = null,
    @SerialName("logged_at") val loggedAt: String? = null,
    val text: String? = null,
    val date: String? = null,
    val energy: Int? = null,
    val mood: Int? = null,
    val stress: Int? = null,
    val soreness: Int? = null,
    @SerialName("taken_at") val takenAt: String? = null,
    @SerialName("supplement_id") val supplementId: Long? = null,
    @SerialName("supplement_name") val supplementName: String? = null,
    val quantity: Int = 1,
    val candidates: List<RosterCandidate> = emptyList(),
    val name: String? = null,
    val category: String? = null,
    @SerialName("duration_min") val durationMin: Int? = null,
)

private val SCORE_RANGE = 0..10

fun LogProposal.wellbeingValue(dim: String): Int? = when (dim) {
    "energy" -> energy
    "mood" -> mood
    "stress" -> stress
    "soreness" -> soreness
    else -> null
}

// What still has to be supplied before this proposal can be written. Empty = ready.
fun LogProposal.missing(): List<String> = when (kind) {
    "drink" -> buildList {
        if ((volumeMl ?: 0.0) <= 0.0) add("volume")
        // Alcohol is stored as grams of ethanol, so it cannot be written without a strength (see Alcohol.kt).
        if (beverageClass == "alcohol" && ethanolGrams(volumeMl ?: 1.0, abvPercent ?: 0.0) == null) add("strength")
    }
    "wellbeing" -> buildList {
        needs.filter { wellbeingValue(it) == null }.forEach { add(it) }
        val values = listOf(energy, mood, stress, soreness)
        if (values.all { it == null }) add("score") else if (values.any { it != null && it !in SCORE_RANGE }) add("score 0-10")
    }.distinct()
    "supplement_taken" -> if (supplementId == null) listOf("supplement") else emptyList()
    "meal" -> if (text.isNullOrBlank()) listOf("food") else emptyList()
    "recovery" -> if (name.isNullOrBlank()) listOf("activity") else emptyList()
    else -> listOf("unsupported")
}

fun LogProposal.isReady(): Boolean = missing().isEmpty()

// The server resolves times on the person's own clock and labels them with the device's offset. The app stores local
// wall-clock time labeled UTC everywhere (see AddEntrySheet's toIsoWithOffset), so only the digits are kept.
fun LogProposal.whenLocal(): LocalDateTime? =
    (loggedAt ?: takenAt)?.let { runCatching { OffsetDateTime.parse(it).toLocalDateTime() }.getOrNull() }

fun LogProposal.localDate(): String? = date ?: whenLocal()?.toLocalDate()?.toString()

fun LocalDateTime.toAppIso(): String = atOffset(ZoneOffset.UTC).toString()
