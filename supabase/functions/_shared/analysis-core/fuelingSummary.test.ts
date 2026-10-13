// Gate for the per-activity fueling summary (docs/endurance-fueling).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { summariseFueling } from './fuelingSummary.ts'
import type { FuelingEvent } from './fuelingSummary.ts'

const ev = (overrides: Partial<FuelingEvent> & { id: number }): FuelingEvent => ({
  status: 'consumed', elapsedSeconds: 0, carbsG: null, fluidMl: null, sodiumMg: null, caffeineMg: null,
  ...overrides,
})

test('totals reconcile exactly to a hand-added event list', () => {
  const events = [
    ev({ id: 1, elapsedSeconds: 900, carbsG: 24, sodiumMg: 20 }),
    ev({ id: 2, elapsedSeconds: 1800, carbsG: 24, fluidMl: 250, sodiumMg: 20 }),
    ev({ id: 3, elapsedSeconds: 2700, carbsG: 24, sodiumMg: 20, caffeineMg: 50 }),
  ]
  const s = summariseFueling(events, 3600)
  assert.equal(s.actualTotals.carbsG, 72)
  assert.equal(s.actualTotals.fluidMl, 250)
  assert.equal(s.actualTotals.sodiumMg, 60)
  assert.equal(s.actualTotals.caffeineMg, 50)
  assert.equal(s.eventCount, 3)
})

test('per-hour rates scale correctly for a non-hour-length duration', () => {
  const events = [ev({ id: 1, carbsG: 30 })]
  const s = summariseFueling(events, 1800) // 30 minutes
  assert.equal(s.carbsPerHour, 60) // 30g in 30 min = 60g/h
})

test('zero duration never produces a rate, and is reported as duration unknown', () => {
  const s = summariseFueling([ev({ id: 1, carbsG: 30 })], 0)
  assert.equal(s.durationKnown, false)
  assert.equal(s.carbsPerHour, null)
})

test('null (unknown) duration never produces a rate either', () => {
  const s = summariseFueling([ev({ id: 1, carbsG: 30 })], null)
  assert.equal(s.durationKnown, false)
  assert.equal(s.carbsPerHour, null)
  assert.equal(s.fluidPerHour, null)
})

test('planned intake is reported separately and never counted in actual totals', () => {
  const events = [
    ev({ id: 1, status: 'planned', carbsG: 25 }),
    ev({ id: 2, status: 'consumed', carbsG: 24 }),
  ]
  const s = summariseFueling(events, 3600)
  assert.equal(s.actualTotals.carbsG, 24)
  assert.equal(s.plannedTotals.carbsG, 25)
  assert.equal(s.plannedCount, 1)
  assert.equal(s.eventCount, 1)
})

test('skipped events are counted but excluded from both actual and planned totals', () => {
  const events = [ev({ id: 1, status: 'skipped', carbsG: 24 })]
  const s = summariseFueling(events, 3600)
  assert.equal(s.skippedCount, 1)
  assert.equal(s.eventCount, 0)
  assert.equal(s.actualTotals.carbsG, null)
  assert.equal(s.plannedTotals.carbsG, null)
})

test('an all-unknown nutrient across every event stays unknown, never zero', () => {
  const events = [ev({ id: 1, carbsG: 24 }), ev({ id: 2, carbsG: 24 })] // neither event has caffeine data
  const s = summariseFueling(events, 3600)
  assert.equal(s.actualTotals.caffeineMg, null)
  assert.equal(s.actualKnownCounts.caffeineMg, 0)
  assert.equal(s.actualKnownCounts.carbsG, 2)
})

test('an event at exactly the activity\'s end boundary still closes that bucket\'s gap', () => {
  const events = [ev({ id: 1, elapsedSeconds: 3600, carbsG: 24 })] // exactly at durationSeconds
  const s = summariseFueling(events, 3600, 1800)
  // buckets: [0,1800) and [1800,3600] -- the event at 3600 falls in the second, inclusive bucket
  assert.deepEqual(s.gaps, [{ startSeconds: 0, endSeconds: 1800 }])
})

test('an activity with no events at all reports a gap for every bucket', () => {
  const s = summariseFueling([], 3600, 1800)
  assert.deepEqual(s.gaps, [{ startSeconds: 0, endSeconds: 1800 }, { startSeconds: 1800, endSeconds: 3600 }])
})

test('a planned-only event does not close a gap (only consumed/partial count)', () => {
  const events = [ev({ id: 1, status: 'planned', elapsedSeconds: 900, carbsG: 24 })]
  const s = summariseFueling(events, 1800, 1800)
  assert.deepEqual(s.gaps, [{ startSeconds: 0, endSeconds: 1800 }])
})

test('caffeine doses are ordered by elapsed time regardless of input order, and zero/unknown doses are excluded', () => {
  const events = [
    ev({ id: 1, elapsedSeconds: 2700, caffeineMg: 50 }),
    ev({ id: 2, elapsedSeconds: 900, caffeineMg: 25 }),
    ev({ id: 3, elapsedSeconds: 1800, caffeineMg: 0 }),
    ev({ id: 4, elapsedSeconds: 1200, caffeineMg: null }),
  ]
  const s = summariseFueling(events, 3600)
  assert.deepEqual(s.caffeineDoses, [{ elapsedSeconds: 900, caffeineMg: 25 }, { elapsedSeconds: 2700, caffeineMg: 50 }])
})

test('a duplicate-looking event (same values twice) is simply summed twice -- de-duplication is the caller\'s job, not this function\'s', () => {
  const events = [ev({ id: 1, carbsG: 24 }), ev({ id: 2, carbsG: 24 })]
  assert.equal(summariseFueling(events, 3600).actualTotals.carbsG, 48)
})
