// Gate for the N-of-1 fueling experiment eligibility layer (docs/endurance-fueling).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  buildDailyCarbRateSeries,
  evaluateEligibility,
  fuelingVerdict,
  isInterpretable,
  MIN_ELIGIBLE_ACTIVITIES_PER_PHASE,
} from './fuelingExperiment.ts'
import type { ActivityFuelingRecord, EligibilityCriteria } from './fuelingExperiment.ts'
import { summariseFueling } from './fuelingSummary.ts'
import type { FuelingEvent } from './fuelingSummary.ts'
import { analyseInterventionResponse } from './interventionResponse.ts'
import type { InterventionResponseWindows } from './interventionResponse.ts'
import type { Frame } from './relationship.ts'
import type { ProtocolRuleInputs } from './protocolRules.ts'

const CRITERIA: EligibilityCriteria = {
  activityTypes: ['run', 'hike'],
  minDurationSeconds: 3600,
  heldStable: { caffeineMgPerHour: 50, sodiumMgPerHour: 400, toleranceFraction: 0.2 },
}

function activity(date: string, overrides: Partial<{ activityType: string; durationSeconds: number; events: FuelingEvent[] }> = {}): ActivityFuelingRecord {
  const durationSeconds = overrides.durationSeconds ?? 7200
  const events = overrides.events ?? [
    { id: 1, status: 'consumed', elapsedSeconds: 0, carbsG: 60, fluidMl: 500, sodiumMg: 800, caffeineMg: 100 }, // 30g/h carbs, 50mg/h caffeine, 400mg/h sodium over 2h
  ]
  return { date, activityType: overrides.activityType ?? 'run', durationSeconds, summary: summariseFueling(events, durationSeconds) }
}

test('an activity meeting type/duration/held-stable criteria is eligible', () => {
  const r = evaluateEligibility(activity('2026-02-01'), CRITERIA)
  assert.equal(r.eligible, true)
  assert.equal(r.reason, 'eligible')
  assert.equal(r.carbsPerHour, 30)
})

test('wrong activity type is excluded with a named reason', () => {
  const r = evaluateEligibility(activity('2026-02-01', { activityType: 'ride' }), CRITERIA)
  assert.equal(r.eligible, false)
  assert.equal(r.reason, 'wrong_type')
})

test('too-short activity is excluded', () => {
  const r = evaluateEligibility(activity('2026-02-01', { durationSeconds: 1200 }), CRITERIA)
  assert.equal(r.eligible, false)
  assert.equal(r.reason, 'too_short')
})

test('unknown carb rate (no fueling logged) is excluded, not assumed zero', () => {
  const r = evaluateEligibility(activity('2026-02-01', { events: [] }), CRITERIA)
  assert.equal(r.eligible, false)
  assert.equal(r.reason, 'unknown_carb_rate')
})

test('a caffeine deviation beyond tolerance is excluded as a deviation, not silently averaged in', () => {
  const events: FuelingEvent[] = [{ id: 1, status: 'consumed', elapsedSeconds: 0, carbsG: 60, fluidMl: null, sodiumMg: 800, caffeineMg: 300 }] // 150mg/h caffeine, way outside 50±20%
  const r = evaluateEligibility(activity('2026-02-01', { events }), CRITERIA)
  assert.equal(r.eligible, false)
  assert.equal(r.reason, 'caffeine_deviation')
})

test('a sodium deviation beyond tolerance is excluded', () => {
  const events: FuelingEvent[] = [{ id: 1, status: 'consumed', elapsedSeconds: 0, carbsG: 60, fluidMl: null, sodiumMg: 50, caffeineMg: 100 }] // 25mg/h sodium, way under 400±20%
  const r = evaluateEligibility(activity('2026-02-01', { events }), CRITERIA)
  assert.equal(r.eligible, false)
  assert.equal(r.reason, 'sodium_deviation')
})

test('an unconstrained target (null) is never treated as a deviation', () => {
  const criteria: EligibilityCriteria = { ...CRITERIA, heldStable: { caffeineMgPerHour: null, sodiumMgPerHour: null, toleranceFraction: 0.2 } }
  const events: FuelingEvent[] = [{ id: 1, status: 'consumed', elapsedSeconds: 0, carbsG: 60, fluidMl: null, sodiumMg: 50, caffeineMg: 500 }]
  const r = evaluateEligibility(activity('2026-02-01', { events }), criteria)
  assert.equal(r.eligible, true)
})

test('buildDailyCarbRateSeries averages multiple eligible activities on the same day and leaves other days null', () => {
  const activities = [
    activity('2026-02-01'), // 30g/h
    { ...activity('2026-02-01'), summary: summariseFueling([{ id: 2, status: 'consumed', elapsedSeconds: 0, carbsG: 90, fluidMl: null, sodiumMg: 800, caffeineMg: 100 }], 7200) }, // 45g/h
    activity('2026-02-03', { activityType: 'ride' }), // ineligible: wrong type
  ]
  const dates = ['2026-02-01', '2026-02-02', '2026-02-03']
  const s = buildDailyCarbRateSeries(activities, CRITERIA, dates)
  assert.equal(s.values[0], 37.5) // (30+45)/2
  assert.equal(s.values[1], null)
  assert.equal(s.values[2], null) // excluded for wrong_type, not averaged in as a zero
  assert.equal(s.eligibility.filter((e) => e.eligible).length, 2)
})

test('the derived series feeds directly into the existing interventionResponse engine, unmodified', () => {
  // Build 90 days of activities: baseline (days 0-44) at a planted lower carb rate, active (45-89) at a higher one.
  const dates: string[] = []
  const activities: ActivityFuelingRecord[] = []
  const start = new Date('2026-01-05T00:00:00Z').getTime()
  for (let t = 0; t < 90; t++) {
    const d = new Date(start + t * 86400000).toISOString().slice(0, 10)
    dates.push(d)
    const carbsG = t >= 45 ? 120 : 60 // 60g/h baseline, 120g/h intervention, over 2h
    activities.push(activity(d, { events: [{ id: t, status: 'consumed', elapsedSeconds: 0, carbsG, fluidMl: null, sodiumMg: 400 * 2, caffeineMg: 50 * 2 }] }))
  }
  const series = buildDailyCarbRateSeries(activities, CRITERIA, dates)
  const frame: Frame = { dates, series: { fueling_carb_rate: series.values }, excluded: dates.map(() => false) }
  const windows: InterventionResponseWindows = { baselineStart: dates[0], baselineEnd: dates[44], activeStart: dates[45], activeEnd: null }
  const result = analyseInterventionResponse('iv-fueling', ['fueling_carb_rate'], windows, frame, dates[89])
  assert.equal(result.metrics[0].verdict, 'signal_observed')
  assert.ok(result.metrics[0].diff!.value > 0, 'the higher intervention-phase carb rate shows as a positive signal')

  const interp = isInterpretable(series.eligibility, new Set(dates.slice(0, 45)), new Set(dates.slice(45)))
  assert.equal(interp.interpretable, true)
  assert.equal(interp.baselineEligibleCount, 45)
  assert.equal(interp.interventionEligibleCount, 45)
})

test('isInterpretable requires enough eligible activities per phase, not just enough calendar days', () => {
  const eligibility = [
    { date: '2026-01-01', eligible: true, reason: 'eligible' as const, carbsPerHour: 60 },
    { date: '2026-01-02', eligible: false, reason: 'too_short' as const, carbsPerHour: null },
    { date: '2026-02-01', eligible: true, reason: 'eligible' as const, carbsPerHour: 120 },
  ]
  const baseline = new Set(['2026-01-01', '2026-01-02'])
  const intervention = new Set(['2026-02-01'])
  const interp = isInterpretable(eligibility, baseline, intervention, 2)
  assert.equal(interp.interpretable, false, 'only one eligible day in each phase, below the minimum of 2')
  assert.equal(interp.baselineEligibleCount, 1)
})

test('fuelingVerdict is a label over proposeProtocolAction, never a second decision engine', () => {
  const inputs: ProtocolRuleInputs = {
    verdict: 'signal_observed', observedDirection: 'increase', expectedDirection: 'increase',
    adherenceRate: 0.9, minAdherence: 0.7, evidenceStrength: 'moderate', burden: 'low', confidenceHave: 28, confidenceNeed: 14,
  }
  const { verdict, action } = fuelingVerdict(inputs)
  assert.equal(action.action, 'maintain')
  assert.equal(verdict, 'keep')
  assert.ok(action.rationale.length > 0, 'the full reviewable proposal is still returned alongside the label')
})

test('every ProtocolAction the rules engine can return maps to one of the four required fueling verdicts', () => {
  const scenarios: ProtocolRuleInputs[] = [
    { verdict: 'signal_observed', observedDirection: 'increase', expectedDirection: 'increase', adherenceRate: 0.9, minAdherence: 0.7, evidenceStrength: 'moderate', burden: 'low', confidenceHave: 28, confidenceNeed: 14 },
    { verdict: 'signal_observed', observedDirection: 'increase', expectedDirection: 'increase', adherenceRate: 0.9, minAdherence: 0.7, evidenceStrength: 'moderate', burden: 'high', confidenceHave: 28, confidenceNeed: 14 },
    { verdict: 'signal_observed', observedDirection: 'decrease', expectedDirection: 'increase', adherenceRate: 0.9, minAdherence: 0.7, evidenceStrength: 'moderate', burden: 'low', confidenceHave: 28, confidenceNeed: 14 },
    { verdict: 'insufficient', observedDirection: null, expectedDirection: null, adherenceRate: 0.9, minAdherence: 0.7, evidenceStrength: 'moderate', burden: 'low', confidenceHave: 2, confidenceNeed: 14 },
    { verdict: 'no_signal', observedDirection: null, expectedDirection: null, adherenceRate: 0.3, minAdherence: 0.7, evidenceStrength: 'moderate', burden: 'low', confidenceHave: 28, confidenceNeed: 14 },
  ]
  for (const s of scenarios) {
    const { verdict } = fuelingVerdict(s)
    assert.ok(['keep', 'modify', 'stop', 'inconclusive'].includes(verdict), verdict)
  }
})
