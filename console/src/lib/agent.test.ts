// Run: node --test console/src/lib/agent.test.ts
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { explainError, explainRequest, isoWithOffset } from './agent.ts'

const point = { seriesId: 'hrv', label: 'HRV (RMSSD)', unit: 'ms', grain: 'week' as const, start: '2026-10-19', end: '2026-10-25', value: '41.2 ms', aggregation: 'average of the days · 6 of 7 days observed' }

test('the request names the point and carries only what the Inspector shows', () => {
  const r = explainRequest(point, new Date(2026, 9, 28, 21, 5, 0), 'turn-0001')
  assert.equal(r.question, 'What does HRV (RMSSD) on 2026-10-19 to 2026-10-25 show, and what does it rest on?')
  assert.deepEqual(r.context, { card: 'INSPECTOR', series: 'hrv', date: '2026-10-25', shown: { series: 'HRV (RMSSD)', period: '2026-10-19 to 2026-10-25', value: '41.2 ms', aggregation: 'average of the days · 6 of 7 days observed' } })
  assert.equal(explainRequest({ ...point, grain: 'day', start: '2026-10-27', end: '2026-10-27' }, new Date(), 't-000001').question, 'What does HRV (RMSSD) on 2026-10-27 show, and what does it rest on?')
})

test('local time carries the browser offset in the form the function checks', () => {
  assert.match(isoWithOffset(new Date(2026, 9, 28, 21, 5, 7)), /^2026-10-28T21:05:07[+-]\d{2}:\d{2}$/)
})

test('error codes read as sentences', () => {
  assert.match(explainError('assistant_off', undefined), /Setup/)
  assert.equal(explainError('daily_cap', 'Answered 40 today.'), 'Answered 40 today.')
  assert.match(explainError(undefined, undefined), /Try again/)
})
