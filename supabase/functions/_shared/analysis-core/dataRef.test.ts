// Analysis hub phase A: data references survive the trip through their stored string form.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseRefKey, refKey, type DataRef } from './dataRef.ts'

test('every kind of reference round-trips through its key', () => {
  const refs: DataRef[] = [
    { kind: 'record', table: 'exercise_sessions', id: '6683' },
    { kind: 'day', series: 'hrv', date: '2026-10-27' },
    { kind: 'period', series: 'run_distance', start: '2026-10-01', end: '2026-10-31' },
    { kind: 'result', resultKind: 'series_break', key: 'hrv', asOf: '2026-10-26' },
    { kind: 'question', questionId: 'reg_12' },
    { kind: 'result', resultKind: 'relationship', key: 'alcohol->hrv@1', asOf: '2026-10-26' },
    { kind: 'question', questionId: 'odd:id/with spaces' },
  ]
  for (const r of refs) assert.deepEqual(parseRefKey(refKey(r)), r, refKey(r))
  assert.equal(refKey({ kind: 'record', table: 'meals', id: 42 }), 'record:meals:42')
})

test('anything else parses to null', () => {
  for (const s of ['', 'record:meals', 'day:hrv:27-10-2026', 'period:hrv:2026-10-31:2026-10-01', 'nope:x', 'question:', 'record:meals:%E0%A4%A']) {
    assert.equal(parseRefKey(s), null, s)
  }
})
