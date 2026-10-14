import test from 'node:test'
import assert from 'node:assert/strict'
import { fromSaved, toSaved } from './rangeStore.ts'
import { localDaysAgo, localToday } from './dates.ts'

const now = new Date(2026, 9, 13, 1, 30) // 2026-10-13 01:30 local time

test('local dates follow the local calendar, not UTC', () => {
  assert.equal(localToday(now), '2026-10-13')
  assert.equal(localDaysAgo(7, now), '2026-10-06')
  assert.equal(localDaysAgo(90, now), '2026-07-15')
})

test('a preset range is saved as days and restored relative to the new today', () => {
  const saved = toSaved({ start: localDaysAgo(28, now), end: localToday(now) }, now)
  assert.deepEqual(saved, { days: 28 })
  const later = new Date(2026, 10, 2, 9, 0)
  assert.deepEqual(fromSaved(saved, later), { start: localDaysAgo(28, later), end: '2026-11-02' })
})

test('a custom range is saved and restored as dates', () => {
  const r = { start: '2026-03-01', end: '2026-04-15' }
  assert.deepEqual(toSaved(r, now), r)
  assert.deepEqual(fromSaved(r, now), r)
})

test('garbage in storage is ignored', () => {
  for (const bad of [null, 5, 'x', {}, { days: 13 }, { start: '2026-04-15', end: '2026-03-01' }, { start: 'a', end: 'b' }]) {
    assert.equal(fromSaved(bad, now), null)
  }
})
