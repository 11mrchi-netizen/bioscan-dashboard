package com.bioscan.fieldterminal.domain

// One outcome claim from the evidence registry, reduced to what the roster view
// needs. Unreviewed claims (e.g. migrated from the legacy hard-coded map, with
// no citation) are always labelled, never shown as established fact.
data class EvidenceClaim(val subjectKey: String, val claimText: String, val reviewed: Boolean)

fun expectedOutcomeText(ingredientKeys: Set<String>, claims: List<EvidenceClaim>): String =
    claims
        .filter { it.subjectKey in ingredientKeys }
        .distinctBy { it.claimText }
        .joinToString("; ") { if (it.reviewed) it.claimText else "${it.claimText} (unreviewed)" }
