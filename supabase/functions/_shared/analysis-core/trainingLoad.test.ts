// Analysis review phase G: the frame's session load is the apps' load, and the averages are seeded as Android seeds them.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { sessionLoad, spanEma } from './trainingLoad.ts'
import { sessionLoad as consoleSessionLoad } from '../../../../console/src/lib/training.ts'

test('the session load matches the console (and through it Android): RPE x minutes, else TRIMP, else nothing', () => {
  const cases = [
    { duration_min: 60, rpe: 5 },
    { duration_min: 45, avg_hr: 150, max_hr: 185 },
    { duration_min: 30, avg_hr: 50, max_hr: 185 },
    { duration_min: 0, rpe: 6 },
    { duration_min: 40 },
  ]
  for (const c of cases) {
    const ours = sessionLoad(c, 55)
    const theirs = consoleSessionLoad({ duration_minutes: c.duration_min, rpe: c.rpe, avg_hr: c.avg_hr, max_hr: c.max_hr }, 55)
    assert.equal(ours, theirs, JSON.stringify(c))
  }
  assert.equal(sessionLoad({ duration_min: 60, rpe: 5 }, null), 300)
  assert.equal(sessionLoad({ duration_min: 45, avg_hr: 150, max_hr: 185 }, null), null, 'no resting heart rate, no TRIMP')
})

test('the span averages start at the first day with a load and treat later gaps as rest', () => {
  const ema = spanEma([null, null, 100, null, 0], 3) // alpha 0.5
  assert.deepEqual(ema, [null, null, 100, 50, 25])
  assert.deepEqual(spanEma([null, null], 7), [null, null])
})
