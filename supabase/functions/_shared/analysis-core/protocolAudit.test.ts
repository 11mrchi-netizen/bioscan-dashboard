// Gate for the protocol audit engine (docs/intervention-protocol-intelligence).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { auditProtocol } from './protocolAudit.ts'
import type { AuditableIntervention, PrecomputedInteraction } from './protocolAudit.ts'

const iv = (overrides: Partial<AuditableIntervention> = {}): AuditableIntervention => ({
  id: 'iv-1',
  name: 'Magnesium glycinate',
  intendedOutcomes: ['sleep_duration'],
  requiredMeasurements: ['sleep_duration'],
  lifecycleState: 'active',
  baselineStartedAt: '2026-01-01',
  startedAt: '2026-01-20',
  adherenceRate: 0.9,
  minAdherence: 0.7,
  burden: 'low',
  evidenceStrength: 'moderate',
  ...overrides,
})

test('a well-formed solo intervention produces no findings', () => {
  assert.deepEqual(auditProtocol([iv()], [], '2026-02-01'), [])
})

test('no measurable outcome', () => {
  const findings = auditProtocol([iv({ intendedOutcomes: [] })], [], '2026-02-01')
  assert.ok(findings.some((f) => f.code === 'no_measurable_outcome'))
})

test('insufficient baseline', () => {
  const findings = auditProtocol([iv({ baselineStartedAt: '2026-01-15', startedAt: '2026-01-20' })], [], '2026-02-01')
  assert.ok(findings.some((f) => f.code === 'insufficient_baseline'))
})

test('a proposed intervention (not yet started) is not flagged for insufficient baseline', () => {
  const findings = auditProtocol([iv({ lifecycleState: 'proposed', baselineStartedAt: '2026-01-15', startedAt: '2026-01-20' })], [], '2026-02-01')
  assert.ok(!findings.some((f) => f.code === 'insufficient_baseline'))
})

test('insufficient adherence', () => {
  const findings = auditProtocol([iv({ adherenceRate: 0.4 })], [], '2026-02-01')
  assert.ok(findings.some((f) => f.code === 'insufficient_adherence'))
})

test('high burden with weak evidence', () => {
  const findings = auditProtocol([iv({ burden: 'high', evidenceStrength: 'weak' })], [], '2026-02-01')
  assert.ok(findings.some((f) => f.code === 'high_burden_weak_evidence'))
})

test('high burden with strong evidence is not flagged', () => {
  const findings = auditProtocol([iv({ burden: 'high', evidenceStrength: 'strong' })], [], '2026-02-01')
  assert.ok(!findings.some((f) => f.code === 'high_burden_weak_evidence'))
})

test('stale evaluation window', () => {
  const findings = auditProtocol([iv({ lifecycleState: 'evaluating', startedAt: '2026-01-01' })], [], '2026-04-01')
  assert.ok(findings.some((f) => f.code === 'stale_evaluation'))
})

test('same-outcome clustering uses the Linear issue\'s own wording and names every intervention in the cluster', () => {
  const interventions = [
    iv({ id: 'a', name: 'Magnesium', intendedOutcomes: ['sleep_duration'], startedAt: '2026-01-01' }),
    iv({ id: 'b', name: 'Caffeine cutoff', intendedOutcomes: ['sleep_duration'], startedAt: '2026-01-05' }),
    iv({ id: 'c', name: 'Wind-down routine', intendedOutcomes: ['sleep_duration'], startedAt: '2026-01-08' }),
    iv({ id: 'd', name: 'Blackout curtains', intendedOutcomes: ['sleep_duration'], startedAt: '2026-01-10' }),
  ]
  const findings = auditProtocol(interventions, [], '2026-02-01')
  const f = findings.find((x) => x.code === 'same_outcome_overlap')
  assert.ok(f)
  assert.equal(f!.message, 'Attribution quality is low because 4 interventions affecting sleep_duration were started within the same 10-day window.')
  assert.deepEqual(f!.interventionIds.sort(), ['a', 'b', 'c', 'd'])
})

test('two interventions on the same outcome started far apart are not clustered', () => {
  const interventions = [
    iv({ id: 'a', intendedOutcomes: ['hrv'], startedAt: '2026-01-01' }),
    iv({ id: 'b', intendedOutcomes: ['hrv'], startedAt: '2026-03-01' }),
  ]
  assert.ok(!auditProtocol(interventions, [], '2026-04-01').some((f) => f.code === 'same_outcome_overlap'))
})

test('only one intervention naming an outcome never clusters', () => {
  assert.ok(!auditProtocol([iv()], [], '2026-02-01').some((f) => f.code === 'same_outcome_overlap'))
})

test('a precomputed interaction pair is included only when both sides are in this protocol', () => {
  const interventions = [iv({ id: 'a' }), iv({ id: 'b' })]
  const interactions: PrecomputedInteraction[] = [
    { aId: 'a', bId: 'b', severity: 'major', effect: 'Zinc and iron reduce absorption of each other.' },
    { aId: 'a', bId: 'not-in-protocol', severity: 'moderate', effect: 'Should not appear.' },
  ]
  const findings = auditProtocol(interventions, interactions, '2026-02-01')
  const included = findings.filter((f) => f.code === 'interaction')
  assert.equal(included.length, 1)
  assert.equal(included[0].severity, 'high')
  assert.equal(included[0].message, 'Zinc and iron reduce absorption of each other.')
})

test('auditProtocol never emits a numeric score, only findings', () => {
  const findings = auditProtocol([iv({ intendedOutcomes: [] })], [], '2026-02-01')
  for (const f of findings) assert.equal(typeof (f as unknown as { score?: unknown }).score, 'undefined')
})
