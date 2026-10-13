// Intervention & Protocol Intelligence 08.10: validation across the whole generalized layer (08.1-08.8), not any one
// engine's own unit tests. Covers DAV-340's exit criteria that are actually checkable from pure functions and
// synthetic data: determinism/reproducibility, no unsupported causal-claim language, evidence kept separate from
// personal observation, and that the engines actually compose across domains (supplement + sleep + training in one
// synthetic protocol) rather than only working in isolation. Real longitudinal backtesting and the "raw observations
// remain distinct from derived outputs" / "existing Evaluation/Comparison contracts are not duplicated" criteria are
// structural properties of the schema and the per-phase code review recorded in this milestone's docs, not something
// a synthetic-data test can usefully assert on its own -- see docs/intervention-protocol-intelligence/README.md.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { analyseInterventionResponse } from './interventionResponse.ts'
import type { InterventionResponseWindows } from './interventionResponse.ts'
import { proposeProtocolAction } from './protocolRules.ts'
import type { ProtocolRuleInputs } from './protocolRules.ts'
import { auditProtocol } from './protocolAudit.ts'
import type { AuditableIntervention } from './protocolAudit.ts'
import { evidenceForIntervention } from './evidenceLink.ts'
import type { EvidenceRow } from './evidenceLink.ts'
import type { Frame } from './relationship.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'
import type { Num } from './stats.ts'

const START = '2026-01-05'
const shiftDate = (d: string, n: number) => new Date(Date.parse(d + 'T00:00:00Z') + n * 86400000).toISOString().slice(0, 10)

// Forbidden phrases: language that asserts causation rather than an observed/tested association. Summaries may say
// a signal was "observed" or an effect was "detected" within a pre-registered design (08.5's stronger, but still
// explicitly phase-compared, vocabulary) -- they must never claim the intervention "caused", "proves", or is "due
// to" a change, per DAV-336/DAV-340.
const FORBIDDEN_CAUSAL_PHRASES = [/\bcause(s|d)?\b/i, /\bproves?\b/i, /\bdue to\b/i, /\bconfirms?\b/i]

function assertNoCausalLanguage(texts: string[]) {
  for (const t of texts) {
    for (const re of FORBIDDEN_CAUSAL_PHRASES) {
      assert.doesNotMatch(t, re, `forbidden causal language in: "${t}"`)
    }
  }
}

function buildFrame(seed: number, days: number, activeFromDay: number, hrvEffect: number): Frame {
  const rng = mulberry32(seed)
  const dates = dateAxis(START, shiftDate(START, days - 1))
  let e = 0
  const hrv: Num[] = []
  const sleep: Num[] = []
  for (let t = 0; t < days; t++) {
    e = 0.5 * e + 0.0866 * gaussian(rng)
    const eff = t >= activeFromDay ? hrvEffect : 0
    hrv.push(Math.exp(4.1 + e + eff))
    sleep.push(7.2 + 0.4 * gaussian(rng) + (t >= activeFromDay ? 0.3 : 0))
  }
  return { dates, series: { hrv, sleep_hours: sleep }, excluded: dates.map(() => false) }
}

// ---- Determinism / reproducibility ----

test('analyseInterventionResponse is deterministic: identical inputs produce identical output', () => {
  const frame = buildFrame(1, 90, 45, 0.2)
  const windows: InterventionResponseWindows = { baselineStart: frame.dates[0], baselineEnd: shiftDate(START, 44), activeStart: shiftDate(START, 45), activeEnd: null }
  const a = analyseInterventionResponse('iv-1', ['hrv'], windows, frame, shiftDate(START, 89))
  const b = analyseInterventionResponse('iv-1', ['hrv'], windows, frame, shiftDate(START, 89))
  assert.deepEqual(a, b)
})

test('proposeProtocolAction is deterministic', () => {
  const inputs: ProtocolRuleInputs = {
    verdict: 'signal_observed', observedDirection: 'increase', expectedDirection: 'increase',
    adherenceRate: 0.9, minAdherence: 0.7, evidenceStrength: 'moderate', burden: 'low', confidenceHave: 28, confidenceNeed: 14,
  }
  assert.deepEqual(proposeProtocolAction(inputs), proposeProtocolAction(inputs))
})

test('auditProtocol is deterministic', () => {
  const ivs: AuditableIntervention[] = [{
    id: 'a', name: 'Magnesium', intendedOutcomes: [], requiredMeasurements: [], lifecycleState: 'active',
    baselineStartedAt: '2026-01-01', startedAt: '2026-01-20', adherenceRate: 0.9, minAdherence: 0.7, burden: 'low', evidenceStrength: 'moderate',
  }]
  assert.deepEqual(auditProtocol(ivs, [], '2026-02-01'), auditProtocol(ivs, [], '2026-02-01'))
})

// ---- No unsupported causal-claim language, generated from realistic synthetic runs (not just static string checks) ----

test('interventionResponse summaries never claim causation, signal observed or not', () => {
  const signalFrame = buildFrame(11, 90, 45, 0.22)
  const noSignalFrame = buildFrame(1, 90, 45, 0)
  const windows: InterventionResponseWindows = { baselineStart: START, baselineEnd: shiftDate(START, 44), activeStart: shiftDate(START, 45), activeEnd: null }
  const today = shiftDate(START, 89)
  const signalResult = analyseInterventionResponse('iv-1', ['hrv'], windows, signalFrame, today)
  const noSignalResult = analyseInterventionResponse('iv-1', ['hrv'], windows, noSignalFrame, today)
  assert.equal(signalResult.metrics[0].verdict, 'signal_observed')
  assert.equal(noSignalResult.metrics[0].verdict, 'no_signal')
  assertNoCausalLanguage([signalResult.metrics[0].summary, noSignalResult.metrics[0].summary])
  assert.ok(signalResult.metrics[0].summary.includes('observed signal during intervention'))
})

test('protocolRules rationale never claims causation across every action branch', () => {
  const base: ProtocolRuleInputs = {
    verdict: 'signal_observed', observedDirection: 'increase', expectedDirection: 'increase',
    adherenceRate: 0.9, minAdherence: 0.7, evidenceStrength: 'moderate', burden: 'low', confidenceHave: 28, confidenceNeed: 14,
  }
  const scenarios: ProtocolRuleInputs[] = [
    base,
    { ...base, confidenceHave: 2 },
    { ...base, adherenceRate: 0.3 },
    { ...base, verdict: 'insufficient' },
    { ...base, burden: 'high' },
    { ...base, observedDirection: 'decrease' },
    { ...base, verdict: 'no_signal', evidenceStrength: 'strong' },
    { ...base, verdict: 'no_signal', evidenceStrength: 'weak' },
    { ...base, verdict: 'no_signal', evidenceStrength: 'moderate' },
  ]
  const allRationale = scenarios.flatMap((s) => proposeProtocolAction(s).rationale)
  assert.ok(allRationale.length >= scenarios.length)
  assertNoCausalLanguage(allRationale)
})

test('protocolAudit findings never claim causation', () => {
  const ivs: AuditableIntervention[] = [
    { id: 'a', name: 'Magnesium', intendedOutcomes: ['sleep_duration'], requiredMeasurements: [], lifecycleState: 'active', baselineStartedAt: '2026-01-01', startedAt: '2026-01-05', adherenceRate: 0.3, minAdherence: 0.7, burden: 'high', evidenceStrength: 'weak' },
    { id: 'b', name: 'Caffeine cutoff', intendedOutcomes: ['sleep_duration'], requiredMeasurements: [], lifecycleState: 'active', baselineStartedAt: '2026-01-01', startedAt: '2026-01-08', adherenceRate: 0.9, minAdherence: 0.7, burden: 'low', evidenceStrength: 'moderate' },
  ]
  const findings = auditProtocol(ivs, [{ aId: 'a', bId: 'b', severity: 'moderate', effect: 'Timing overlap between magnesium and caffeine cutoff.' }], '2026-02-01')
  assert.ok(findings.length > 0)
  assertNoCausalLanguage(findings.map((f) => f.message))
})

// ---- Evidence stays separate from personal observation (DAV-340: "evidence claims remain separate from personal observations") ----

test('a user-observed row never outranks a literature-backed tier, regardless of input order', () => {
  const row = (tier: EvidenceRow['evidenceTier']): EvidenceRow => ({
    subjectType: 'ingredient', subjectKey: 'magnesium_glycinate', outcomeKey: 'sleep_duration',
    claimText: 'placeholder', effectDirection: 'increase', evidenceTier: tier, reviewStatus: 'reviewed',
  })
  const ordersToTry = [
    [row('user_observed'), row('controlled_human'), row('observational')],
    [row('observational'), row('user_observed'), row('controlled_human')],
    [row('controlled_human'), row('observational'), row('user_observed')],
  ]
  for (const evidence of ordersToTry) {
    const [link] = evidenceForIntervention('ingredient', 'magnesium_glycinate', ['sleep_duration'], evidence)
    assert.equal(link.evidence[link.evidence.length - 1].evidenceTier, 'user_observed', 'the person\'s own result always sorts last, never mixed above literature')
  }
})

// ---- Cross-engine composition: one synthetic protocol spanning three domains (supplement + sleep + training),
// exercising interventionResponse -> protocolRules -> protocolAudit together rather than each in isolation. ----

test('a multi-domain protocol (supplement + sleep + training) composes sensibly end to end', () => {
  const today = shiftDate(START, 89)
  const windows: InterventionResponseWindows = { baselineStart: START, baselineEnd: shiftDate(START, 44), activeStart: shiftDate(START, 45), activeEnd: null }

  // Supplement: magnesium, planted +22% HRV signal.
  const magFrame = buildFrame(11, 90, 45, 0.22)
  const magResponse = analyseInterventionResponse('iv-magnesium', ['hrv'], windows, magFrame, today)
  assert.equal(magResponse.metrics[0].verdict, 'signal_observed')

  // Sleep: wind-down routine, started only 4 days after the magnesium intervention -- same outcome (hrv is a stand-in
  // "sleep quality" proxy here), close enough together that attribution should be flagged by the audit.
  // Training: a deload week, unrelated outcome, no clustering expected with the other two.
  const interventions: AuditableIntervention[] = [
    { id: 'iv-magnesium', name: 'Magnesium glycinate', intendedOutcomes: ['hrv'], requiredMeasurements: ['hrv'], lifecycleState: 'evaluating', baselineStartedAt: windows.baselineStart, startedAt: windows.activeStart, adherenceRate: 0.9, minAdherence: 0.7, burden: 'low', evidenceStrength: 'moderate' },
    { id: 'iv-winddown', name: 'Wind-down routine', intendedOutcomes: ['hrv'], requiredMeasurements: ['hrv'], lifecycleState: 'evaluating', baselineStartedAt: windows.baselineStart, startedAt: shiftDate(windows.activeStart, 4), adherenceRate: 0.8, minAdherence: 0.7, burden: 'low', evidenceStrength: 'weak' },
    { id: 'iv-deload', name: 'Deload week', intendedOutcomes: ['training_load'], requiredMeasurements: ['training_load'], lifecycleState: 'active', baselineStartedAt: windows.baselineStart, startedAt: shiftDate(windows.activeStart, 30), adherenceRate: 1, minAdherence: 0.7, burden: 'low', evidenceStrength: 'none' },
  ]
  const findings = auditProtocol(interventions, [], today)
  const cluster = findings.find((f) => f.code === 'same_outcome_overlap')
  assert.ok(cluster, 'magnesium and wind-down started close together on the same outcome should be flagged')
  assert.deepEqual(cluster!.interventionIds.sort(), ['iv-magnesium', 'iv-winddown'])
  assert.ok(!cluster!.interventionIds.includes('iv-deload'), 'the unrelated-outcome deload week is never pulled into the cluster')

  // The adaptive-rules engine, fed the magnesium intervention's own response, still gives a sensible proposal
  // independent of what the audit found (the two engines are decoupled by design).
  const proposal = proposeProtocolAction({
    verdict: magResponse.metrics[0].verdict, observedDirection: 'increase', expectedDirection: 'increase',
    adherenceRate: 0.9, minAdherence: 0.7, evidenceStrength: 'moderate', burden: 'low', confidenceHave: magResponse.metrics[0].n1, confidenceNeed: 14,
  })
  assert.equal(proposal.action, 'maintain')
  assertNoCausalLanguage([...proposal.rationale, magResponse.metrics[0].summary, ...findings.map((f) => f.message)])
})
