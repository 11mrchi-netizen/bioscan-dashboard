package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.TypicalityResultRow

// Analysis Layer 3 / Phase 4. Words for the published "is today typical for you" result (supabase/functions/_shared/
// analysis-core/typicality.ts). A state, never a score. Shown as a quiet drill-down: on the real history the engine flags more
// ordinary days than its nominal share, so it does not feed Today's Recovery (docs/analysis-layer-3/README.md, Phase 4).
data class TypicalityView(
    val headline: String,
    val lines: List<String>,
    val promptIllness: Boolean,
)

private val SIGNAL_NAMES = mapOf(
    "hrv" to "HRV",
    "rhr" to "resting heart rate",
    "sleep" to "sleep",
    "deep_rem" to "deep + REM sleep",
    "steps" to "steps",
)

fun typicalityView(row: TypicalityResultRow?): TypicalityView {
    if (row == null) return TypicalityView("Not analysed yet", listOf("The nightly analysis publishes this."), false)
    val p = row.payload
    val today = p.today
    return when {
        row.state == "building" -> TypicalityView("Building your baseline", listOf("${p.baselineDays} of ${p.baselineNeed} complete days"), false)
        today == null -> TypicalityView("Not enough signals to judge", listOf("Needs at least three of today's signals."), false)
        today.excluded -> TypicalityView("Illness logged: not judged", listOf("Days inside a logged illness are left out of the baseline and of the judgement."), false)
        else -> {
            val headline = when (today.state) {
                "typical" -> "Typical for you"
                "elevated" -> "Different from your usual"
                "unusual" -> "Unusual for you"
                else -> "Not enough signals to judge"
            } + " (${today.date})"
            val lines = buildList {
                if (today.drivers.isNotEmpty()) {
                    add(today.drivers.joinToString(", ") { "${SIGNAL_NAMES[it.signal] ?: it.signal} ${it.direction}" })
                }
                if (today.state != "typical" && p.lookalikes.isNotEmpty()) {
                    add("Looks like: " + p.lookalikes.joinToString(", ") { it.date + if (it.illness) " (illness)" else "" })
                }
                if (p.skipped.isNotEmpty()) {
                    add("Left out: ${p.skipped.joinToString(", ") { SIGNAL_NAMES[it] ?: it }} (its source changed recently).")
                }
                if (p.suggestIllnessLog) add("Two strained days in a row. If you feel unwell, consider logging an illness.")
            }
            TypicalityView(headline, lines, p.suggestIllnessLog)
        }
    }
}
