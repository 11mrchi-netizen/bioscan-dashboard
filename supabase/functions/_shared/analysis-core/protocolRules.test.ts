// Gate for the adaptive protocol rules (docs/intervention-protocol-intelligence): every branch produces a
// rationale and echoes its inputs, and the first matching rule wins regardless of what comes after it.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { proposeProtocolAction } from './protocolRules.ts'
import type { ProtocolRuleInputs } from './protocolRules.ts'

const base = (overrides: Partial<ProtocolRuleInputs> = {}): ProtocolRuleInputs => ({
  verdict: 'signal_observed',
  observedDirection: 'increase',
  expectedDirection: 'increase',
  adherenceRate: 0.9,
  minAdherence: 0.7,
  evidenceStrength: 'moderate',
  burden: 'low',
  confidenceHave: 28,
  confidenceNeed: 14,
  ...overrides,
})

test('every proposal carries at least one rationale sentence and echoes its exact inputs', () => {
  const inputs = base()
  const p = proposeProtocolAction(inputs)
  assert.ok(p.rationale.length > 0)
  assert.deepEqual(p.sourceInputs, inputs)
})

test('not enough data yet wins over everything else', () => {
  const p = proposeProtocolAction(base({ confidenceHave: 5, confidenceNeed: 14, verdict: 'no_signal', evidenceStrength: 'none' }))
  assert.equal(p.action, 'extend_evaluation')
  assert.match(p.rationale[0], /5 of 14/)
})

test('low adherence wins over a positive verdict', () => {
  const p = proposeProtocolAction(base({ adherenceRate: 0.4, verdict: 'effect_detected' }))
  assert.equal(p.action, 'reassess')
  assert.match(p.rationale[0], /40%/)
})

test('insufficient or null verdict (with enough confidence and adherence) extends evaluation', () => {
  assert.equal(proposeProtocolAction(base({ verdict: 'insufficient' })).action, 'extend_evaluation')
  assert.equal(proposeProtocolAction(base({ verdict: null })).action, 'extend_evaluation')
})

test('a signal in the intended direction with acceptable burden: maintain', () => {
  const p = proposeProtocolAction(base({ verdict: 'signal_observed', observedDirection: 'increase', expectedDirection: 'increase', burden: 'low' }))
  assert.equal(p.action, 'maintain')
})

test('a signal in the intended direction with high burden: decrease, not terminate', () => {
  const p = proposeProtocolAction(base({ verdict: 'effect_detected', observedDirection: 'increase', expectedDirection: 'increase', burden: 'high' }))
  assert.equal(p.action, 'decrease')
})

test('a signal in the wrong direction: terminate, even with low burden', () => {
  const p = proposeProtocolAction(base({ verdict: 'signal_observed', observedDirection: 'decrease', expectedDirection: 'increase', burden: 'low' }))
  assert.equal(p.action, 'terminate')
})

test('expectedDirection "stabilize" is never treated as a direction mismatch', () => {
  const p = proposeProtocolAction(base({ verdict: 'signal_observed', observedDirection: 'decrease', expectedDirection: 'stabilize', burden: 'low' }))
  assert.equal(p.action, 'maintain')
})

test('no signal with strong evidence and acceptable burden: extend_evaluation, not reassess', () => {
  const p = proposeProtocolAction(base({ verdict: 'no_signal', evidenceStrength: 'strong', burden: 'medium' }))
  assert.equal(p.action, 'extend_evaluation')
})

test('no signal with strong evidence but high burden: not extend_evaluation', () => {
  const p = proposeProtocolAction(base({ verdict: 'no_detectable_effect', evidenceStrength: 'strong', burden: 'high' }))
  assert.notEqual(p.action, 'extend_evaluation')
})

test('no signal with weak or no evidence: reassess', () => {
  assert.equal(proposeProtocolAction(base({ verdict: 'no_signal', evidenceStrength: 'weak' })).action, 'reassess')
  assert.equal(proposeProtocolAction(base({ verdict: 'no_signal', evidenceStrength: 'none' })).action, 'reassess')
})

test('no signal with moderate evidence: pause', () => {
  const p = proposeProtocolAction(base({ verdict: 'no_signal', evidenceStrength: 'moderate', burden: 'medium' }))
  assert.equal(p.action, 'pause')
})
