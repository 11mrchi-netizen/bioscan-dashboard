package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.RelationshipResultRow

// Analysis Layer 3 / Phase 3. What the Patterns card shows: only relationships that survived the multiple-comparison correction
// are listed (strongest first); the rest are counted, never ranked, so a non-finding cannot read as a weak finding.
data class PatternsView(
    val supported: List<RelationshipResultRow>,
    val notDistinguishable: Int,
    val insufficient: Int,
) {
    val total: Int get() = supported.size + notDistinguishable + insufficient
}

fun patternsView(rows: List<RelationshipResultRow>): PatternsView = PatternsView(
    supported = rows.filter { it.state == "supported" }.sortedBy { it.payload.pAdjusted ?: 1.0 },
    notDistinguishable = rows.count { it.state == "not_distinguishable" },
    insufficient = rows.count { it.state == "insufficient" },
)
