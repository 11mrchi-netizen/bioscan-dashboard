package com.bioscan.fieldterminal.domain

// Intervention & Protocol Intelligence 08.3. Client-side mirror of the protocol lifecycle transition graph in
// supabase/functions/_shared/analysis-core/protocol.ts -- that file is canonical, and also owns schedule generation
// (generateSchedule), which is not mirrored here. A protocol's own lifecycle is coarser than an intervention's:
// there is no separate BASELINE state at this level -- baseline is just the first window of the generated schedule,
// not a stage the protocol as a whole moves through (individual interventions inside it still have their own
// InterventionLifecycleState).
enum class ProtocolLifecycleState(val key: String) {
    PROPOSED("proposed"),
    ACTIVE("active"),
    EVALUATING("evaluating"),
    COMPLETED("completed"),
    STOPPED("stopped");

    companion object {
        fun fromKey(key: String): ProtocolLifecycleState? = entries.find { it.key == key }
    }
}

private val ALLOWED_PROTOCOL_TRANSITIONS: Map<ProtocolLifecycleState, Set<ProtocolLifecycleState>> = mapOf(
    ProtocolLifecycleState.PROPOSED to setOf(ProtocolLifecycleState.ACTIVE, ProtocolLifecycleState.STOPPED),
    ProtocolLifecycleState.ACTIVE to setOf(ProtocolLifecycleState.EVALUATING, ProtocolLifecycleState.STOPPED),
    ProtocolLifecycleState.EVALUATING to setOf(ProtocolLifecycleState.COMPLETED, ProtocolLifecycleState.STOPPED),
    ProtocolLifecycleState.COMPLETED to setOf(ProtocolLifecycleState.STOPPED),
    ProtocolLifecycleState.STOPPED to emptySet(),
)

fun isValidProtocolTransition(from: ProtocolLifecycleState, to: ProtocolLifecycleState): Boolean =
    ALLOWED_PROTOCOL_TRANSITIONS.getValue(from).contains(to)
