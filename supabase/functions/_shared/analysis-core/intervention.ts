// Intervention & Protocol Intelligence (08.2): lifecycle for a canonical intervention (any domain -- supplement,
// nutrition, training, sleep, lifestyle). Erasable TypeScript, no I/O.
//
// This is the single place that decides whether a lifecycle transition is legal. The database's `lifecycle_state`
// CHECK on public.interventions only constrains the set of values, not the graph between them, so the app calls
// `isValidTransition` before writing a change -- the same division of labour as `Experiment.kt` validating a
// protocol client-side ahead of the (weaker) database constraints.
//
// Chain: proposed -> baseline -> active -> evaluating -> one of validated / maintained / modified / stopped.
// `stopped` is reachable from every non-terminal state (abandoning a plan at any point is itself a disposition
// worth recording), but once an intervention is validated, maintained, modified or stopped there is no further
// transition here -- a later reassessment (08.7's adaptive rules) proposes a brand new intervention/protocol
// rather than reopening this one, which keeps `intervention_state_history` an honest audit trail.
export type LifecycleState = 'proposed' | 'baseline' | 'active' | 'evaluating' | 'validated' | 'maintained' | 'modified' | 'stopped'

export const LIFECYCLE_STATES: LifecycleState[] = ['proposed', 'baseline', 'active', 'evaluating', 'validated', 'maintained', 'modified', 'stopped']

export const ALLOWED_TRANSITIONS: Record<LifecycleState, LifecycleState[]> = {
  proposed: ['baseline', 'stopped'],
  baseline: ['active', 'stopped'],
  active: ['evaluating', 'stopped'],
  evaluating: ['validated', 'maintained', 'modified', 'stopped'],
  validated: ['stopped'],
  maintained: ['stopped'],
  modified: ['stopped'],
  stopped: [],
}

export function isValidTransition(from: LifecycleState, to: LifecycleState): boolean {
  return ALLOWED_TRANSITIONS[from]?.includes(to) ?? false
}
