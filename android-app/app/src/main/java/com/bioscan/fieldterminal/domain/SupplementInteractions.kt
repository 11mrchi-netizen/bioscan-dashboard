package com.bioscan.fieldterminal.domain

import kotlin.math.abs
import kotlin.math.min

// Analysis Layer 3 / Phase 6. Pairwise supplement/drug interactions from the
// shared, curated `interaction_records` registry, checked against the roster.
// Pure and offline, sibling of SupplementEvidence.kt: same rule that an
// unreviewed entry is always labelled, never shown as established fact.

data class InteractionRecord(
    val id: Long,
    val aKeys: Set<String> = emptySet(),
    val aAliases: Set<String> = emptySet(),
    val bKeys: Set<String> = emptySet(),
    val bAliases: Set<String> = emptySet(),
    val triggerMode: String = "both_present", // or "a_without_b"
    val preferTogether: Boolean = false,
    val effect: String,
    val advice: String,
    val spacingHours: Double? = null,
    val severity: String = "minor", // major | moderate | minor | info
    val source: String = "",
    val pmidDoi: String? = null,
    val caveats: String? = null,
    val reviewed: Boolean = false,
)

data class RosterItem(
    val id: Long,
    val name: String,
    val timeOfDay: String,
    val everyNDays: Int? = null,
    val ingredientKeys: Set<String> = emptySet(),
    // Extra names to alias-match against (ingredient names typed in the form).
    val ingredientNames: List<String> = emptyList(),
)

enum class InteractionStatus {
    SAME_SLOT, // spacing advised, both in the same time slot
    TOO_CLOSE, // spacing advised, slots closer than the advised gap
    TIMING_UNKNOWN, // spacing advised, but one item is as-needed
    ALWAYS, // not a timing matter: applies whenever both are taken
    MISSING_PARTNER, // a_without_b: the first is present, its partner is not
    SPACED, // spacing advised and the slots are far enough apart
    TOGETHER_OK, // helpful pairing, both in the same slot
}

data class DetectedInteraction(
    val record: InteractionRecord,
    val a: RosterItem,
    val b: RosterItem?, // null for MISSING_PARTNER
    val status: InteractionStatus,
    val gapHours: Double?,
    // True when either item is not taken daily, so the two only meet on some days.
    val intermittent: Boolean,
) {
    // Anything a person should read; SPACED and TOGETHER_OK are confirmations.
    val needsAttention: Boolean
        get() = status != InteractionStatus.SPACED && status != InteractionStatus.TOGETHER_OK
}

// Nominal clock time of each roster bucket; same as the notification defaults.
private val SLOT_HOUR = mapOf("morning" to 8.0, "afternoon" to 13.0, "night" to 21.0)
private val SEVERITY_RANK = mapOf("major" to 0, "moderate" to 1, "minor" to 2, "info" to 3)

// Smallest distance between two slots on a 24 h clock.
internal fun slotGapHours(a: String, b: String): Double? {
    val ha = SLOT_HOUR[a.lowercase()] ?: return null
    val hb = SLOT_HOUR[b.lowercase()] ?: return null
    val d = abs(ha - hb)
    return min(d, 24.0 - d)
}

private fun RosterItem.matches(keys: Set<String>, aliases: Set<String>): Boolean {
    if (ingredientKeys.any { it in keys }) return true
    val text = (listOf(name) + ingredientNames).joinToString(" | ").lowercase()
    return aliases.any { alias ->
        Regex("(?<![a-z0-9])" + Regex.escape(alias.lowercase()) + "(?![a-z0-9])").containsMatchIn(text)
    }
}

private fun RosterItem.intermittent(): Boolean = (everyNDays ?: 1) > 1

fun detectInteractions(roster: List<RosterItem>, records: List<InteractionRecord>): List<DetectedInteraction> {
    val found = mutableListOf<DetectedInteraction>()
    for (r in records) {
        val aItems = roster.filter { it.matches(r.aKeys, r.aAliases) }
        if (r.triggerMode == "a_without_b") {
            // The partner may be the same product (e.g. a zinc + copper formula).
            if (roster.any { it.matches(r.bKeys, r.bAliases) }) continue
            aItems.forEach { a ->
                found += DetectedInteraction(r, a, null, InteractionStatus.MISSING_PARTNER, null, false)
            }
            continue
        }
        val bItems = roster.filter { it.matches(r.bKeys, r.bAliases) }
        val seen = mutableSetOf<Pair<Long, Long>>()
        for (a in aItems) for (b in bItems) {
            // One product that holds both sides is a formulation, not a timing choice.
            if (a.id == b.id) continue
            if (!seen.add(minOf(a.id, b.id) to maxOf(a.id, b.id))) continue
            val gap = slotGapHours(a.timeOfDay, b.timeOfDay)
            val sameSlot = a.timeOfDay.equals(b.timeOfDay, ignoreCase = true)
            val asNeeded = a.timeOfDay.equals("as-needed", true) || b.timeOfDay.equals("as-needed", true)
            val status = when {
                r.preferTogether -> if (sameSlot && !asNeeded) InteractionStatus.TOGETHER_OK else null
                r.spacingHours == null -> InteractionStatus.ALWAYS
                asNeeded || gap == null -> InteractionStatus.TIMING_UNKNOWN
                sameSlot -> InteractionStatus.SAME_SLOT
                gap < r.spacingHours -> InteractionStatus.TOO_CLOSE
                else -> InteractionStatus.SPACED
            } ?: continue
            found += DetectedInteraction(r, a, b, status, gap.takeIf { !asNeeded }, a.intermittent() || b.intermittent())
        }
    }
    return found.sortedWith(
        compareBy<DetectedInteraction> { !it.needsAttention }
            .thenBy { SEVERITY_RANK[it.record.severity] ?: 9 }
            .thenBy { it.record.id },
    )
}

// Unreviewed entries carry the label wherever the effect is shown.
fun interactionEffectText(r: InteractionRecord): String =
    if (r.reviewed) r.effect else "${r.effect} (unreviewed)"

fun interactionStatusText(d: DetectedInteraction): String {
    val sp = d.record.spacingHours?.let { formatHours(it) }
    val gap = d.gapHours?.let { formatHours(it) }
    val base = when (d.status) {
        InteractionStatus.SAME_SLOT -> "Taken in the same slot; ${sp} apart is advised"
        InteractionStatus.TOO_CLOSE -> "About $gap apart; ${sp} is advised"
        InteractionStatus.TIMING_UNKNOWN -> "As-needed timing; keep ${sp} apart"
        InteractionStatus.ALWAYS -> "Applies whenever both are taken"
        InteractionStatus.MISSING_PARTNER -> "Nothing on your roster covers the partner"
        InteractionStatus.SPACED -> "About $gap apart; ${sp} is advised"
        InteractionStatus.TOGETHER_OK -> "Taken together, as suggested"
    }
    return if (d.intermittent && d.b != null) "$base (only on days you take both)" else base
}

private fun formatHours(h: Double): String =
    if (h % 1.0 == 0.0) "${h.toInt()} h" else "%.1f h".format(h)
