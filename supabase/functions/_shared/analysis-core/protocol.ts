// Intervention & Protocol Intelligence (08.3): protocol lifecycle and actionable schedule generation. Erasable
// TypeScript, no I/O.
//
// A protocol groups interventions around one objective. Its own lifecycle is coarser than an intervention's --
// there is no separate 'baseline' state here because baseline is just the first window of the schedule below, not
// a stage the protocol as a whole moves through on its own (individual interventions inside it still have their own
// `interventions.lifecycle_state`). Same division of labour as intervention.ts: the transition graph is enforced
// here, the database CHECK on `protocols.lifecycle_state` only restricts the set of values.
export type ProtocolLifecycleState = 'proposed' | 'active' | 'evaluating' | 'completed' | 'stopped'

export const PROTOCOL_LIFECYCLE_STATES: ProtocolLifecycleState[] = ['proposed', 'active', 'evaluating', 'completed', 'stopped']

export const ALLOWED_PROTOCOL_TRANSITIONS: Record<ProtocolLifecycleState, ProtocolLifecycleState[]> = {
  proposed: ['active', 'stopped'],
  active: ['evaluating', 'stopped'],
  evaluating: ['completed', 'stopped'],
  completed: ['stopped'],
  stopped: [],
}

export function isValidProtocolTransition(from: ProtocolLifecycleState, to: ProtocolLifecycleState): boolean {
  return ALLOWED_PROTOCOL_TRANSITIONS[from]?.includes(to) ?? false
}

// ---- Schedule generation: 08.3's acceptance is that an instantiated protocol "generate[s] an actionable schedule" ----

export interface PeriodSpec {
  days: number
}

export interface ProtocolWindows {
  baseline: PeriodSpec
  active: PeriodSpec
  /** A washout sits at the start of the active period (same convention as experiments.protocol.washoutDays). */
  washout?: PeriodSpec
}

export type StepRole = 'primary' | 'supporting' | 'concurrent'

export interface ProtocolStepSpec {
  interventionId: string
  role: StepRole
  /** Steps sharing a sequenceOrder run concurrently; a higher sequenceOrder's days start only after every lower one's. */
  sequenceOrder: number
}

export interface ScheduledStep {
  interventionId: string
  role: StepRole
  sequenceOrder: number
  start: string
  end: string
}

export interface ProtocolSchedule {
  baselineStart: string
  baselineEnd: string
  activeStart: string
  activeEnd: string
  washoutStart: string | null
  washoutEnd: string | null
  steps: ScheduledStep[]
}

const dayMs = 86400000
const addDays = (date: string, n: number) => new Date(Date.parse(date + 'T00:00:00Z') + n * dayMs).toISOString().slice(0, 10)

/**
 * An actionable schedule starting on `startDate`: the baseline window, then (if `windows.washout` is set) a washout
 * at the start of the active window, then the remaining active days sliced into sequential groups by
 * `sequenceOrder` -- steps that share a `sequenceOrder` run concurrently, each group getting an equal share of the
 * remaining active days (the last group absorbs any remainder so the schedule always ends exactly on `activeEnd`).
 * A protocol with no steps still returns valid baseline/active/washout dates with an empty `steps` array.
 */
export function generateSchedule(startDate: string, windows: ProtocolWindows, steps: ProtocolStepSpec[]): ProtocolSchedule {
  const baselineStart = startDate
  const baselineEnd = addDays(startDate, windows.baseline.days - 1)
  const activeStart = addDays(baselineEnd, 1)
  const activeEnd = addDays(activeStart, windows.active.days - 1)
  const washoutStart = windows.washout ? activeStart : null
  const washoutEnd = windows.washout ? addDays(activeStart, windows.washout.days - 1) : null

  const groupOrders = Array.from(new Set(steps.map((s) => s.sequenceOrder))).sort((a, b) => a - b)
  const groupsStart = washoutEnd ? addDays(washoutEnd, 1) : activeStart
  const remainingDays = windows.active.days - (windows.washout?.days ?? 0)
  const groupDays = groupOrders.length > 0 ? Math.max(1, Math.floor(remainingDays / groupOrders.length)) : 0

  const scheduled: ScheduledStep[] = []
  groupOrders.forEach((order, i) => {
    const groupStart = addDays(groupsStart, i * groupDays)
    const groupEnd = i === groupOrders.length - 1 ? activeEnd : addDays(groupStart, groupDays - 1)
    for (const step of steps.filter((s) => s.sequenceOrder === order)) {
      scheduled.push({ interventionId: step.interventionId, role: step.role, sequenceOrder: order, start: groupStart, end: groupEnd })
    }
  })

  return { baselineStart, baselineEnd, activeStart, activeEnd, washoutStart, washoutEnd, steps: scheduled }
}
