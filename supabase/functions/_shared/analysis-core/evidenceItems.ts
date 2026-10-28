// Analysis hub phase D (docs/analysis-console/PLAN.md): the decision layer's common view of evidence. Each engine
// publishes in its own shape; this turns their newest analysis_results rows into evidence items with one verdict scale,
// places each in the decision queue, and builds the snapshot a decision record keeps. Erasable TypeScript, no I/O.
//
// Sources (v1): catalogue questions (`relationship`) and registered questions (`registered_question`): association
// evidence; experiments (`experiment`): controlled comparisons; intervention reads (`intervention_its`): interrupted time
// series. Fueling and the observational intervention response are not decision items yet.
//
// Queue (no composite score: an item's place comes from its own verdict and its own decision history):
//   needs_data          not enough data for a verdict (blocked: only continue testing or inconclusive can be recorded)
//   under_observation   unclear and either not under the person's control or already being tested
//   ready_to_test       unclear, and the exposure is something the person can switch on and off: worth an experiment
//   decision_review     a settled verdict (clear or negligible) without a decision, a verdict that changed since the
//                       decision, or a review date that has come
//   decided             adopted or rejected, still standing
import type { DataRef } from './dataRef.ts'

export type EvidenceClass = 'association' | 'experiment' | 'interrupted_time_series'
export type ItemVerdict = 'clear' | 'negligible' | 'unclear' | 'not_yet'
export type QueueState = 'needs_data' | 'under_observation' | 'ready_to_test' | 'decision_review' | 'decided'
export type DecisionKind = 'adopt' | 'reject' | 'continue_testing' | 'inconclusive'

export interface ResultRow {
  kind: string
  key: string
  as_of: string
  engine_version: string
  inputs_hash: string | null
  state: string | null
  // deno-lint-ignore no-explicit-any
  payload: any
  calculated_at: string
}

export interface EvidenceItem {
  /** 'q:<id>' | 'reg:<id>' | 'exp:<id>' | 'its:<intervention id>' */
  id: string
  evidenceClass: EvidenceClass
  title: string
  /** The outcome group (hrv, rhr, sleep, energy) or the experiment's primary metric. */
  outcome: string
  verdict: ItemVerdict
  /** The engine's own word, for display (clear_benefit, collecting, ...). */
  engineState: string
  effect: { value: number; lo: number; hi: number; unit: string } | null
  noEffect: number | null
  n: number | null
  /** Readiness toward an answer: have / need. */
  readiness: { have: number; need: number; unit: string } | null
  /** Running experiments and intervention reads still collecting. */
  running: boolean
  /** The exposure is under the person's control (it can be tested with an experiment). */
  controllable: boolean
  summary: string
  asOf: string
  engineVersion: string
  inputsHash: string | null
  result: DataRef
}

// Exposures a person can switch on and off at will (so a self-experiment can test them). High stress and training days
// that follow a plan are not under day-to-day control in the same way.
export const CONTROLLABLE = new Set([
  'alcohol_any', 'caffeine_pm_any', 'late_meal', 'evening_training', 'screen_bed_any', 'screen_pm_social_any', 'daylight_day',
  'active_commute', 'recovery_any',
])

const ref = (r: ResultRow): DataRef => ({ kind: 'result', resultKind: r.kind, key: r.key, asOf: r.as_of })

/** The newest row per (kind, key). */
export function newestRows(rows: ResultRow[]): ResultRow[] {
  const m = new Map<string, ResultRow>()
  for (const r of rows) {
    const k = `${r.kind}|${r.key}`
    const old = m.get(k)
    if (!old || r.as_of > old.as_of || (r.as_of === old.as_of && r.calculated_at > old.calculated_at)) m.set(k, r)
  }
  return [...m.values()]
}

function fromRelationship(r: ResultRow, causeOf: (id: string) => string | null): EvidenceItem {
  const p = r.payload ?? {}
  const registered = r.kind === 'registered_question'
  const cause = causeOf(r.key)
  return {
    id: registered ? `reg:${String(r.key).replace(/^reg_/, '')}` : `q:${r.key}`,
    evidenceClass: 'association',
    title: p.label ?? r.key,
    outcome: p.outcome ?? '',
    verdict: (['clear', 'negligible', 'unclear', 'not_yet'].includes(p.state) ? p.state : 'not_yet') as ItemVerdict,
    engineState: p.state ?? r.state ?? '',
    effect: p.effect ?? null,
    noEffect: typeof p.noEffect === 'number' ? p.noEffect : null,
    n: typeof p.n === 'number' ? p.n : null,
    readiness: p.readiness ?? null,
    running: false,
    controllable: cause !== null && CONTROLLABLE.has(cause),
    summary: p.summary ?? '',
    asOf: r.as_of, engineVersion: r.engine_version, inputsHash: r.inputs_hash, result: ref(r),
  }
}

const EXPERIMENT_VERDICT: Record<string, ItemVerdict> = {
  clear_benefit: 'clear', clear_harm: 'clear', effect_detected: 'clear', negligible: 'negligible',
  unclear: 'unclear', no_detectable_effect: 'unclear', insufficient: 'not_yet', running: 'not_yet',
}

function fromExperiment(r: ResultRow): EvidenceItem {
  const p = r.payload ?? {}
  const primary = (p.metrics ?? [])[0] ?? {}
  const running = p.stage !== 'complete'
  return {
    id: `exp:${r.key}`,
    evidenceClass: 'experiment',
    title: p.name ?? `Experiment ${r.key}`,
    outcome: primary.metric ?? '',
    verdict: running ? 'not_yet' : EXPERIMENT_VERDICT[primary.verdict] ?? 'not_yet',
    engineState: running ? p.stage ?? 'running' : primary.verdict ?? '',
    effect: primary.diff ?? null,
    noEffect: typeof primary.zone === 'number' ? primary.zone : null,
    n: typeof primary.n0 === 'number' && typeof primary.n1 === 'number' ? primary.n0 + primary.n1 : null,
    readiness: null,
    running,
    controllable: true,
    summary: primary.summary ?? '',
    asOf: r.as_of, engineVersion: r.engine_version, inputsHash: r.inputs_hash, result: ref(r),
  }
}

function fromIts(r: ResultRow, name: string | null): EvidenceItem {
  const p = r.payload ?? {}
  const primary = (p.metrics ?? [])[0] ?? {}
  const verdict: ItemVerdict = p.state === 'evaluated' ? (primary.verdict ?? 'unclear') : 'not_yet'
  return {
    id: `its:${r.key}`,
    evidenceClass: 'interrupted_time_series',
    title: `${name ?? `Intervention ${r.key}`}: ${primary.label ?? 'outcome'} against its forecast`,
    outcome: primary.metric ?? '',
    verdict,
    engineState: p.state ?? r.state ?? '',
    effect: primary.gap ?? null,
    noEffect: typeof primary.zone === 'number' ? primary.zone : null,
    n: typeof primary.activeDays === 'number' ? primary.activeDays : null,
    readiness: typeof primary.activeDays === 'number' ? { have: primary.activeDays, need: 14, unit: 'active days' } : null,
    running: p.state === 'collecting',
    controllable: true,
    summary: primary.summary ?? '',
    asOf: r.as_of, engineVersion: r.engine_version, inputsHash: r.inputs_hash, result: ref(r),
  }
}

/** Evidence items from analysis_results rows (any order, any history: the newest per key is used). */
export function evidenceItems(rows: ResultRow[], opts: { causeOf?: (id: string) => string | null; interventionName?: (id: string) => string | null } = {}): EvidenceItem[] {
  const causeOf = opts.causeOf ?? (() => null)
  const items: EvidenceItem[] = []
  for (const r of newestRows(rows)) {
    if (r.kind === 'relationship' || r.kind === 'registered_question') items.push(fromRelationship(r, causeOf))
    else if (r.kind === 'experiment') items.push(fromExperiment(r))
    else if (r.kind === 'intervention_its') items.push(fromIts(r, opts.interventionName?.(r.key) ?? null))
  }
  return items.sort((a, b) => a.title.localeCompare(b.title))
}

// --- Decisions ---

export interface EvidenceSnapshot {
  itemId: string
  title: string
  evidenceClass: EvidenceClass
  verdict: ItemVerdict
  engineState: string
  effect: EvidenceItem['effect']
  noEffect: number | null
  n: number | null
  asOf: string
  engineVersion: string
  inputsHash: string | null
  result: DataRef
}

export interface DecisionRecord {
  id: number
  item_id: string
  title: string
  decision: DecisionKind
  rationale: string
  threshold: string | null
  review_condition: string | null
  review_on: string | null
  evidence_class: EvidenceClass
  evidence: EvidenceSnapshot
  supersedes: number | null
  decided_at: string
}

export function snapshotOf(item: EvidenceItem): EvidenceSnapshot {
  return {
    itemId: item.id, title: item.title, evidenceClass: item.evidenceClass, verdict: item.verdict, engineState: item.engineState,
    effect: item.effect, noEffect: item.noEffect, n: item.n, asOf: item.asOf, engineVersion: item.engineVersion,
    inputsHash: item.inputsHash, result: item.result,
  }
}

/** The latest decision per item (decisions in any order). */
export function latestDecisions(decisions: DecisionRecord[]): Map<string, DecisionRecord> {
  const m = new Map<string, DecisionRecord>()
  for (const d of decisions) {
    const old = m.get(d.item_id)
    if (!old || d.decided_at > old.decided_at) m.set(d.item_id, d)
  }
  return m
}

export interface Placement {
  state: QueueState
  reason: string
}

export function place(item: EvidenceItem, decision: DecisionRecord | undefined, today: string): Placement {
  if (decision && (decision.decision === 'adopt' || decision.decision === 'reject')) {
    if (decision.review_on && today >= decision.review_on) return { state: 'decision_review', reason: `review date ${decision.review_on} reached` }
    if (decision.evidence.verdict !== item.verdict) {
      return { state: 'decision_review', reason: `verdict changed from ${decision.evidence.verdict} to ${item.verdict} since the decision` }
    }
    return { state: 'decided', reason: `${decision.decision === 'adopt' ? 'adopted' : 'rejected'} on ${decision.decided_at.slice(0, 10)}` }
  }
  if (item.running) return { state: 'under_observation', reason: item.evidenceClass === 'experiment' ? 'experiment running' : 'collecting days after the start' }
  if (item.verdict === 'not_yet') {
    const r = item.readiness
    return { state: 'needs_data', reason: r ? `${r.have} of about ${r.need} ${r.unit}` : 'not enough data for a verdict' }
  }
  if (item.verdict === 'unclear') {
    if (item.evidenceClass === 'association' && item.controllable) return { state: 'ready_to_test', reason: 'unclear, and the exposure can be switched on and off: an experiment can settle it' }
    return { state: 'under_observation', reason: item.controllable ? 'unclear: more days will narrow it' : 'unclear, and not something to switch on and off: keep observing' }
  }
  if (decision) return { state: 'decision_review', reason: `settled (${item.verdict}); last recorded: ${decision.decision.replace('_', ' ')}` }
  return { state: 'decision_review', reason: `settled (${item.verdict}) with no decision recorded` }
}

/** Which decisions may be recorded now. Adopt and reject need a settled verdict (the table's trigger checks the same). */
export function allowedDecisions(item: EvidenceItem): DecisionKind[] {
  return item.verdict === 'clear' || item.verdict === 'negligible' ? ['adopt', 'reject', 'continue_testing', 'inconclusive'] : ['continue_testing', 'inconclusive']
}

/** What each class of evidence can and cannot say: shown next to every verdict and decision. */
export const CLASS_LIMITS: Record<EvidenceClass, string> = {
  association: 'Observational: days with and without the exposure, adjusted for weekday, trend and the outcome before it. It can say whether the two go together for you, not that one causes the other: an experiment can.',
  experiment: 'Controlled comparison of planned phases (days assigned at random when the design says so): the strongest evidence available here about your own response.',
  interrupted_time_series: 'One change read against a forecast from the weeks before it: anything else that changed at the same time cannot be ruled out.',
}

export interface DecisionInput {
  decision: DecisionKind
  rationale: string
  threshold?: string
  reviewCondition?: string
  reviewOn?: string
  supersedes?: number | null
}

/** The row to insert, or the reason it cannot be recorded. */
export function decisionRow(item: EvidenceItem, input: DecisionInput, today: string): { row: Record<string, unknown> } | { error: string } {
  if (!allowedDecisions(item).includes(input.decision)) return { error: `${input.decision} needs a settled verdict; this one is ${item.verdict}` }
  if (input.rationale.trim().length < 10) return { error: 'say why, in at least a sentence' }
  if (input.reviewOn && input.reviewOn < today) return { error: 'the review date cannot be in the past' }
  return {
    row: {
      item_id: item.id, title: item.title, decision: input.decision, rationale: input.rationale.trim(),
      threshold: input.threshold?.trim() || null, review_condition: input.reviewCondition?.trim() || null, review_on: input.reviewOn || null,
      evidence_class: item.evidenceClass, evidence: snapshotOf(item), supersedes: input.supersedes ?? null,
    },
  }
}
