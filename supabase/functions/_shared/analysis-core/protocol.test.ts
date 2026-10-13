// Gate for the protocol lifecycle graph and schedule generation (docs/intervention-protocol-intelligence).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  ALLOWED_PROTOCOL_TRANSITIONS,
  generateSchedule,
  isValidProtocolTransition,
  PROTOCOL_LIFECYCLE_STATES,
} from './protocol.ts'
import type { ProtocolStepSpec, ProtocolWindows } from './protocol.ts'

test('every protocol state pair agrees with ALLOWED_PROTOCOL_TRANSITIONS', () => {
  for (const from of PROTOCOL_LIFECYCLE_STATES) {
    for (const to of PROTOCOL_LIFECYCLE_STATES) {
      assert.equal(isValidProtocolTransition(from, to), ALLOWED_PROTOCOL_TRANSITIONS[from].includes(to), `${from} -> ${to}`)
    }
  }
})

test('the full forward chain is reachable, and stopped is reachable from everywhere but itself', () => {
  assert.ok(isValidProtocolTransition('proposed', 'active'))
  assert.ok(isValidProtocolTransition('active', 'evaluating'))
  assert.ok(isValidProtocolTransition('evaluating', 'completed'))
  for (const from of PROTOCOL_LIFECYCLE_STATES) {
    if (from === 'stopped') assert.equal(ALLOWED_PROTOCOL_TRANSITIONS[from].length, 0)
    else assert.ok(isValidProtocolTransition(from, 'stopped'), `${from} -> stopped`)
  }
  assert.equal(isValidProtocolTransition('proposed', 'evaluating'), false, 'no skipping active')
  assert.equal(isValidProtocolTransition('active', 'proposed'), false, 'no going backwards')
})

test('generateSchedule: baseline, active and washout dates line up with no gaps', () => {
  const windows: ProtocolWindows = { baseline: { days: 14 }, active: { days: 28 }, washout: { days: 3 } }
  const s = generateSchedule('2026-01-01', windows, [])
  assert.equal(s.baselineStart, '2026-01-01')
  assert.equal(s.baselineEnd, '2026-01-14')
  assert.equal(s.activeStart, '2026-01-15')
  assert.equal(s.washoutStart, '2026-01-15')
  assert.equal(s.washoutEnd, '2026-01-17')
  assert.equal(s.activeEnd, '2026-02-11') // 2026-01-15 + 27 days
  assert.deepEqual(s.steps, [])
})

test('generateSchedule: no washout means steps start on activeStart', () => {
  const windows: ProtocolWindows = { baseline: { days: 7 }, active: { days: 14 } }
  const steps: ProtocolStepSpec[] = [{ interventionId: 'a', role: 'primary', sequenceOrder: 0 }]
  const s = generateSchedule('2026-01-01', windows, steps)
  assert.equal(s.washoutStart, null)
  assert.equal(s.steps[0].start, s.activeStart)
  assert.equal(s.steps[0].end, s.activeEnd)
})

test('generateSchedule: steps sharing a sequenceOrder run concurrently over the same dates', () => {
  const windows: ProtocolWindows = { baseline: { days: 7 }, active: { days: 20 } }
  const steps: ProtocolStepSpec[] = [
    { interventionId: 'a', role: 'primary', sequenceOrder: 0 },
    { interventionId: 'b', role: 'concurrent', sequenceOrder: 0 },
  ]
  const s = generateSchedule('2026-01-01', windows, steps)
  assert.equal(s.steps.length, 2)
  assert.equal(s.steps[0].start, s.steps[1].start)
  assert.equal(s.steps[0].end, s.steps[1].end)
  assert.equal(s.steps[0].end, s.activeEnd)
})

test('generateSchedule: a higher sequenceOrder starts the day after the lower one ends, and the last group reaches activeEnd exactly', () => {
  const windows: ProtocolWindows = { baseline: { days: 7 }, active: { days: 20 } }
  const steps: ProtocolStepSpec[] = [
    { interventionId: 'a', role: 'primary', sequenceOrder: 0 },
    { interventionId: 'b', role: 'supporting', sequenceOrder: 1 },
  ]
  const s = generateSchedule('2026-01-01', windows, steps)
  const first = s.steps.find((st) => st.interventionId === 'a')!
  const second = s.steps.find((st) => st.interventionId === 'b')!
  assert.equal(second.start, '2026-01-18') // the day after `first.end`
  const dayAfter = new Date(Date.parse(first.end + 'T00:00:00Z') + 86400000).toISOString().slice(0, 10)
  assert.equal(second.start, dayAfter)
  assert.equal(second.end, s.activeEnd)
})

test('generateSchedule: a protocol with no steps still returns a valid schedule', () => {
  const windows: ProtocolWindows = { baseline: { days: 5 }, active: { days: 10 } }
  const s = generateSchedule('2026-03-01', windows, [])
  assert.equal(s.steps.length, 0)
  assert.equal(s.baselineStart, '2026-03-01')
  assert.ok(s.activeEnd > s.activeStart)
})
