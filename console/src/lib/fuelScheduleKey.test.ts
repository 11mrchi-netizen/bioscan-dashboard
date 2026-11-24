// Run: node --test "src/lib/*.test.ts"
import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { PlannedItem } from '../../../supabase/functions/_shared/fuel-core/index.ts'
import { isLongNonEvent, planKey, scheduleKey } from './fuelScheduleKey.ts'

const item = (o: Partial<PlannedItem>): PlannedItem => ({
  source: 'PLANNED_SESSION', sourceId: 'planned:5', title: 'Long run', type: 'run', start: null, durationMin: 120, intensity: 'LONG', intensityReason: '', kcal: null, recorded: false,
  eventId: null, eventKind: null, eventPriority: null, distanceKm: null, planRef: null, planTitle: null, matchConfidence: null, matchStored: false, matchManual: false, ...o,
})

test('a schedule lives under its plan, or on the recording', () => {
  assert.deepEqual(scheduleKey(item({})), ['planned', 'planned:5'])
  assert.deepEqual(scheduleKey(item({ source: 'CALENDAR', sourceId: 'calendar:abc' })), ['planned', 'calendar:abc'])
  assert.deepEqual(scheduleKey(item({ recorded: true, source: 'RECORDED', sourceId: 'session:77', planRef: 'planned:5' })), ['planned', 'planned:5'])
  assert.deepEqual(scheduleKey(item({ recorded: true, source: 'RECORDED', sourceId: 'session:77', planRef: null })), ['session', '77'])
  assert.deepEqual(scheduleKey(item({ recorded: true, source: 'RECORDED', sourceId: 'session:77', planRef: 'event:3' })), ['session', '77'])
  assert.equal(scheduleKey(item({ eventId: 3 })), null)
})
test('a key is at most 64 characters, and long means 90 minutes', () => {
  assert.equal(planKey('calendar:' + 'x'.repeat(100)).length, 64)
  assert.equal(isLongNonEvent(item({ durationMin: 90 })), true)
  assert.equal(isLongNonEvent(item({ durationMin: 89 })), false)
  assert.equal(isLongNonEvent(item({ eventId: 1 })), false)
})
