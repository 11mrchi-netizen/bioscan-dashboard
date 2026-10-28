// Analysis hub phase D: evidence items, the decision queue and decision records.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  allowedDecisions,
  decisionRow,
  evidenceItems,
  latestDecisions,
  newestRows,
  place,
  snapshotOf,
  type DecisionRecord,
  type EvidenceItem,
  type ResultRow,
} from './evidenceItems.ts'

const row = (kind: string, key: string, as_of: string, payload: unknown, state: string | null = null): ResultRow =>
  ({ kind, key, as_of, engine_version: '3.0.0', inputs_hash: 'h', state, payload, calculated_at: `${as_of}T04:30:00Z` })

const rel = (state: string, extra: object = {}) => ({
  label: 'Alcohol and next-morning HRV', outcome: 'hrv', state, n: 210, noEffect: 2,
  effect: state === 'not_yet' ? null : { value: -6.1, lo: -9.8, hi: -2.3, unit: '%' },
  readiness: { have: 41, need: 60, unit: 'days with it' }, summary: 'HRV about 6% lower', ...extra,
})

const CAUSES: Record<string, string> = { alcohol_hrv: 'alcohol_any', stress_hrv: 'stress_high', reg_7: 'caffeine_pm_any' }
const causeOf = (id: string) => CAUSES[id] ?? null

test('the newest row per key is used', () => {
  const rows = [row('relationship', 'alcohol_hrv', '2026-10-25', rel('unclear')), row('relationship', 'alcohol_hrv', '2026-10-26', rel('clear'))]
  assert.equal(newestRows(rows).length, 1)
  assert.equal(evidenceItems(rows, { causeOf })[0].verdict, 'clear')
})

test('each engine maps onto one verdict scale and its evidence class', () => {
  const items = evidenceItems([
    row('relationship', 'alcohol_hrv', '2026-10-26', rel('clear')),
    row('registered_question', 'reg_7', '2026-10-26', rel('not_yet', { label: 'Caffeine after 14:00 and sleep' })),
    row('experiment', '11', '2026-10-26', { name: 'Magnesium', stage: 'complete', metrics: [{ metric: 'hrv', verdict: 'clear_benefit', n0: 20, n1: 21, diff: { value: 4, lo: 1, hi: 7, unit: '%' }, zone: 2, summary: 's' }] }),
    row('experiment', '12', '2026-10-26', { name: 'No phone in bed', stage: 'running', metrics: [{ metric: 'sleep_hours', verdict: 'running', n0: 5, n1: 4 }] }),
    row('intervention_its', '3', '2026-10-26', { state: 'evaluated', metrics: [{ metric: 'rhr', label: 'Resting HR', verdict: 'negligible', gap: { value: -0.3, lo: -1, hi: 0.4, unit: 'bpm' }, zone: 1, activeDays: 30 }] }),
    row('typicality', 'user', '2026-10-26', {}),
  ], { causeOf, interventionName: () => 'Earlier bedtime' })
  const by = (id: string) => items.find((i) => i.id === id)!
  assert.equal(items.length, 5) // typicality is not a decision item
  assert.deepEqual([by('q:alcohol_hrv').evidenceClass, by('q:alcohol_hrv').verdict, by('q:alcohol_hrv').controllable], ['association', 'clear', true])
  assert.deepEqual([by('reg:7').verdict, by('reg:7').title], ['not_yet', 'Caffeine after 14:00 and sleep'])
  assert.deepEqual([by('exp:11').evidenceClass, by('exp:11').verdict, by('exp:11').engineState, by('exp:11').n], ['experiment', 'clear', 'clear_benefit', 41])
  assert.deepEqual([by('exp:12').verdict, by('exp:12').running], ['not_yet', true])
  assert.deepEqual([by('its:3').evidenceClass, by('its:3').verdict, by('its:3').title], ['interrupted_time_series', 'negligible', 'Earlier bedtime: Resting HR against its forecast'])
  assert.deepEqual(by('q:alcohol_hrv').result, { kind: 'result', resultKind: 'relationship', key: 'alcohol_hrv', asOf: '2026-10-26' })
})

function item(over: Partial<EvidenceItem>): EvidenceItem {
  return evidenceItems([row('relationship', 'alcohol_hrv', '2026-10-26', rel('unclear'))], { causeOf }).map((i) => ({ ...i, ...over }))[0]
}

const decision = (over: Partial<DecisionRecord>): DecisionRecord => ({
  id: 1, item_id: 'q:alcohol_hrv', title: 't', decision: 'adopt', rationale: 'long enough reason', threshold: null, review_condition: null,
  review_on: null, evidence_class: 'association', evidence: snapshotOf(item({ verdict: 'clear' })), supersedes: null, decided_at: '2026-10-20T10:00:00Z', ...over,
})

test('queue: not enough data is blocked; unclear goes to testing or observation; settled goes to review', () => {
  const T = '2026-10-27'
  assert.deepEqual(place(item({ verdict: 'not_yet' }), undefined, T), { state: 'needs_data', reason: '41 of about 60 days with it' })
  assert.equal(place(item({ verdict: 'unclear', controllable: true }), undefined, T).state, 'ready_to_test')
  assert.equal(place(item({ verdict: 'unclear', controllable: false }), undefined, T).state, 'under_observation')
  assert.equal(place(item({ verdict: 'unclear', evidenceClass: 'experiment', controllable: true }), undefined, T).state, 'under_observation')
  assert.equal(place(item({ running: true, verdict: 'not_yet', evidenceClass: 'experiment' }), undefined, T).reason, 'experiment running')
  assert.equal(place(item({ verdict: 'clear' }), undefined, T).state, 'decision_review')
  assert.equal(place(item({ verdict: 'negligible' }), undefined, T).state, 'decision_review')
})

test('queue: a decision stands until its verdict changes or its review date comes', () => {
  const T = '2026-10-27'
  assert.deepEqual(place(item({ verdict: 'clear' }), decision({}), T), { state: 'decided', reason: 'adopted on 2026-10-20' })
  assert.match(place(item({ verdict: 'unclear' }), decision({}), T).reason, /verdict changed from clear to unclear/)
  assert.equal(place(item({ verdict: 'clear' }), decision({ review_on: '2026-10-27' }), T).state, 'decision_review')
  assert.equal(place(item({ verdict: 'clear' }), decision({ review_on: '2026-11-27' }), T).state, 'decided')
  // continue testing keeps the item in the flow
  assert.equal(place(item({ verdict: 'unclear', controllable: true }), decision({ decision: 'continue_testing' }), T).state, 'ready_to_test')
})

test('the latest decision per question wins', () => {
  const m = latestDecisions([decision({ id: 1 }), decision({ id: 2, decided_at: '2026-10-25T10:00:00Z', decision: 'reject' })])
  assert.equal(m.get('q:alcohol_hrv')!.id, 2)
})

test('adopt and reject need a settled verdict; a decision keeps a snapshot of its evidence', () => {
  assert.deepEqual(allowedDecisions(item({ verdict: 'unclear' })), ['continue_testing', 'inconclusive'])
  assert.deepEqual(allowedDecisions(item({ verdict: 'clear' })), ['adopt', 'reject', 'continue_testing', 'inconclusive'])
  const T = '2026-10-27'
  assert.match((decisionRow(item({ verdict: 'unclear' }), { decision: 'adopt', rationale: 'because I want to' }, T) as { error: string }).error, /settled verdict/)
  assert.match((decisionRow(item({ verdict: 'clear' }), { decision: 'adopt', rationale: 'short' }, T) as { error: string }).error, /say why/)
  assert.match((decisionRow(item({ verdict: 'clear' }), { decision: 'adopt', rationale: 'long enough reason', reviewOn: '2026-10-01' }, T) as { error: string }).error, /past/)
  const ok = decisionRow(item({ verdict: 'clear' }), { decision: 'adopt', rationale: '  No alcohol on weekdays from now on.  ', threshold: '3% HRV', reviewOn: '2027-01-27' }, T) as { row: Record<string, unknown> }
  assert.equal(ok.row.rationale, 'No alcohol on weekdays from now on.')
  assert.equal(ok.row.evidence_class, 'association')
  const snap = ok.row.evidence as ReturnType<typeof snapshotOf>
  assert.deepEqual([snap.verdict, snap.effect?.value, snap.engineVersion, snap.inputsHash, snap.asOf], ['clear', -6.1, '3.0.0', 'h', '2026-10-26'])
})
