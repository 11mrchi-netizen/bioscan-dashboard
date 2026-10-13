// Exhaustive gate for the intervention lifecycle graph (docs/intervention-protocol-intelligence).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ALLOWED_TRANSITIONS, isValidTransition, LIFECYCLE_STATES } from './intervention.ts'
import type { LifecycleState } from './intervention.ts'

test('every state pair agrees with ALLOWED_TRANSITIONS', () => {
  for (const from of LIFECYCLE_STATES) {
    for (const to of LIFECYCLE_STATES) {
      assert.equal(isValidTransition(from, to), ALLOWED_TRANSITIONS[from].includes(to), `${from} -> ${to}`)
    }
  }
})

test('the full forward chain is reachable one step at a time', () => {
  const chain: LifecycleState[] = ['proposed', 'baseline', 'active', 'evaluating']
  for (let i = 0; i + 1 < chain.length; i++) assert.ok(isValidTransition(chain[i], chain[i + 1]), `${chain[i]} -> ${chain[i + 1]}`)
  for (const outcome of ['validated', 'maintained', 'modified', 'stopped'] as const) {
    assert.ok(isValidTransition('evaluating', outcome), `evaluating -> ${outcome}`)
  }
})

test('stopped is reachable from every non-terminal state and has no transitions out', () => {
  for (const from of LIFECYCLE_STATES) {
    if (from === 'stopped') {
      assert.equal(ALLOWED_TRANSITIONS[from].length, 0)
    } else {
      assert.ok(isValidTransition(from, 'stopped'), `${from} -> stopped`)
    }
  }
})

test('validated, maintained and modified are terminal here (no further transition until a new intervention is proposed)', () => {
  for (const from of ['validated', 'maintained', 'modified'] as const) {
    assert.deepEqual(ALLOWED_TRANSITIONS[from], ['stopped'])
  }
})

test('no skipping a step and no going backwards', () => {
  assert.equal(isValidTransition('proposed', 'active'), false)
  assert.equal(isValidTransition('proposed', 'evaluating'), false)
  assert.equal(isValidTransition('active', 'proposed'), false)
  assert.equal(isValidTransition('evaluating', 'baseline'), false)
  assert.equal(isValidTransition('active', 'baseline'), false)
})

test('a state is never its own transition', () => {
  for (const s of LIFECYCLE_STATES) assert.equal(isValidTransition(s, s), false, s)
})
