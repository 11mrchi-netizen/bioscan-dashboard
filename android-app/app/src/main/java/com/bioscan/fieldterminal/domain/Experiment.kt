package com.bioscan.fieldterminal.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate

// Analysis Layer 3 / Phase 5. Pure rules for registering a supplement N-of-1 experiment (supabase/functions/_shared/
// analysis-core/experiment.ts does the analysis). The protocol built here is stored once and the database refuses later
// changes, so what is compared and which outcome is primary cannot be re-chosen after seeing the data.
@Serializable
data class ExperimentPhase(val label: String, val start: String, val end: String)

@Serializable
data class ExperimentProtocol(
    val design: String,
    val phases: List<ExperimentPhase>,
    val washoutDays: Int = 0,
    val minAdherence: Double = 0.7,
    val excludeAlcoholDays: Boolean = true,
)

@Serializable
data class MdePreviewRow(
    val metric: String = "",
    val label: String = "",
    val unit: String = "",
    val sigma: Double = 0.0,
    val rho: Double = 0.0,
    val usableDays: Int = 0,
    val mde: Double = 0.0,
    val unsettled: Boolean = false,
)

val EXPERIMENT_METRICS = listOf(
    "hrv" to "HRV",
    "rhr" to "Resting heart rate",
    "sleep_hours" to "Sleep duration",
    "deep_rem_min" to "Deep + REM sleep",
    "energy" to "Energy",
    "mood" to "Mood",
    "stress" to "Stress",
    "soreness" to "Soreness",
)

const val MIN_EXPERIMENT_PHASE_DAYS = 14
const val MAX_EXPERIMENT_PHASE_DAYS = 60

// A (without) first, then B (with); ABAB repeats the pair. Phases are back to back, `start` is the first day of A.
fun buildProtocol(start: LocalDate, design: String, daysPerPhase: Int, washoutDays: Int): ExperimentProtocol {
    val labels = if (design == "ABAB") listOf("A", "B", "A", "B") else listOf("A", "B")
    val phases = labels.mapIndexed { i, label ->
        val from = start.plusDays((i * daysPerPhase).toLong())
        ExperimentPhase(label, from.toString(), from.plusDays((daysPerPhase - 1).toLong()).toString())
    }
    return ExperimentProtocol(design, phases, washoutDays)
}

// What would stop the experiment from being registered. Empty = valid.
fun experimentProblems(primary: String?, daysPerPhase: Int, design: String, washoutDays: Int): List<String> = buildList {
    if (primary == null || EXPERIMENT_METRICS.none { it.first == primary }) add("Choose the one outcome this experiment is about.")
    if (design != "AB" && design != "ABAB") add("Choose a design.")
    if (daysPerPhase !in MIN_EXPERIMENT_PHASE_DAYS..MAX_EXPERIMENT_PHASE_DAYS) add("Each phase must be $MIN_EXPERIMENT_PHASE_DAYS to $MAX_EXPERIMENT_PHASE_DAYS days.")
    if (washoutDays < 0 || washoutDays >= daysPerPhase / 2) add("The washout must be shorter than half a phase.")
}

fun mdeText(p: MdePreviewRow): String {
    val d = if (p.unit == "%" || p.unit == "min") "%.1f".format(p.mde) else "%.2f".format(p.mde)
    return "Smallest difference this design could detect: $d${if (p.unit == "%") "%" else " " + p.unit}"
}

// Plain reading of a preview, so a person picks an outcome the design can actually resolve.
fun mdeCaveat(p: MdePreviewRow): String? = when {
    p.unsettled -> "This outcome changed regime recently (a device or source change), so the estimate is less reliable."
    p.usableDays < 60 -> "Based on only ${p.usableDays} recent days; the estimate will firm up with more data."
    else -> null
}
