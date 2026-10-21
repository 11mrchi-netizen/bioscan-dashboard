package com.bioscan.fieldterminal.domain

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

// Analysis Layer 3 / Phase 5. The stored protocol of an N-of-1 experiment (supabase/functions/_shared/analysis-core/
// experiment.ts does the analysis). It is stored once and the database refuses later changes, so what is compared and
// which outcome is primary cannot be re-chosen after seeing the data. Since analysis review phase F every new experiment
// is randomised (RandomisedExperiment.kt builds the protocol); the fixed A-then-B builder is gone.
// `pair` (analysis review phase F): which pair of a randomised design the period belongs to.
@Serializable
data class ExperimentPhase(val label: String, val start: String, val end: String, val pair: Int? = null)

// Analysis review phase F: the drawn daily schedule (B = with) and the behaviour a with-day asks for.
@Serializable
data class DailyAssignment(val date: String, val arm: String)

@Serializable
data class ExperimentBehaviour(val series: String, val withValue: Double, val label: String)

@Serializable
data class ExperimentRandomization(val method: String, val drawnAt: String)

// The client leaves a field out of the JSON when it equals its default, so the three registration settings are always
// written: the stored protocol is the pre-registration, and the analysis must not have to guess them.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExperimentProtocol(
    val design: String,
    val phases: List<ExperimentPhase>,
    @EncodeDefault val washoutDays: Int = 0,
    @EncodeDefault val minAdherence: Double = 0.7,
    @EncodeDefault val excludeAlcoholDays: Boolean = true,
    // ---- analysis review phase F: protocol version 2, the randomised designs ----
    val version: Int? = null,
    // No-effect zone of the primary outcome, in its display unit (% for HRV, minutes for sleep).
    val zone: Double? = null,
    val blinded: Boolean? = null,
    val pairs: Int? = null,
    val periodDays: Int? = null,
    val assignments: List<DailyAssignment>? = null,
    val lag: Int? = null,
    val behaviour: ExperimentBehaviour? = null,
    val randomization: ExperimentRandomization? = null,
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

// Plain reading of a preview, so a person picks an outcome the design can actually resolve.
fun mdeCaveat(p: MdePreviewRow): String? = when {
    p.unsettled -> "This outcome changed regime recently (a device or source change), so the estimate is less reliable."
    p.usableDays < 60 -> "Based on only ${p.usableDays} recent days; the estimate will firm up with more data."
    else -> null
}
