package com.bioscan.fieldterminal.domain

// Intervention & Protocol Intelligence 08.2. Client-side mirror of the lifecycle transition graph in
// supabase/functions/_shared/analysis-core/intervention.ts -- that file is canonical (and is what the database CHECK
// and lock trigger ultimately rely on); this one lets the app refuse an invalid lifecycle change before sending it,
// the same role Experiment.kt plays for experiment protocols (experiment.ts does the actual analysis).
//
// Chain: PROPOSED -> BASELINE -> ACTIVE -> EVALUATING -> one of VALIDATED / MAINTAINED / MODIFIED / STOPPED.
// STOPPED is reachable from every non-terminal state; VALIDATED/MAINTAINED/MODIFIED/STOPPED have no further
// transition here -- a later reassessment proposes a brand new intervention rather than reopening this one.
enum class InterventionLifecycleState(val key: String) {
    PROPOSED("proposed"),
    BASELINE("baseline"),
    ACTIVE("active"),
    EVALUATING("evaluating"),
    VALIDATED("validated"),
    MAINTAINED("maintained"),
    MODIFIED("modified"),
    STOPPED("stopped");

    companion object {
        fun fromKey(key: String): InterventionLifecycleState? = entries.find { it.key == key }
    }
}

private val ALLOWED_INTERVENTION_TRANSITIONS: Map<InterventionLifecycleState, Set<InterventionLifecycleState>> = mapOf(
    InterventionLifecycleState.PROPOSED to setOf(InterventionLifecycleState.BASELINE, InterventionLifecycleState.STOPPED),
    InterventionLifecycleState.BASELINE to setOf(InterventionLifecycleState.ACTIVE, InterventionLifecycleState.STOPPED),
    InterventionLifecycleState.ACTIVE to setOf(InterventionLifecycleState.EVALUATING, InterventionLifecycleState.STOPPED),
    InterventionLifecycleState.EVALUATING to setOf(
        InterventionLifecycleState.VALIDATED,
        InterventionLifecycleState.MAINTAINED,
        InterventionLifecycleState.MODIFIED,
        InterventionLifecycleState.STOPPED,
    ),
    InterventionLifecycleState.VALIDATED to setOf(InterventionLifecycleState.STOPPED),
    InterventionLifecycleState.MAINTAINED to setOf(InterventionLifecycleState.STOPPED),
    InterventionLifecycleState.MODIFIED to setOf(InterventionLifecycleState.STOPPED),
    InterventionLifecycleState.STOPPED to emptySet(),
)

fun isValidInterventionTransition(from: InterventionLifecycleState, to: InterventionLifecycleState): Boolean =
    ALLOWED_INTERVENTION_TRANSITIONS.getValue(from).contains(to)
