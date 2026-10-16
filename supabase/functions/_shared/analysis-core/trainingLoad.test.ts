// Analysis review phase G: the frame's session load is the apps' load, and the averages are seeded as Android seeds them.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { loadAnchorsByDate, sessionLoad, spanEma } from './trainingLoad.ts'
import { loadAnchorsByDate as consoleAnchorsByDate, sessionLoad as consoleSessionLoad } from '../../../../console/src/lib/training.ts'

test('the session load matches the console (and through it Android): RPE x minutes, else TRIMP, else nothing', () => {
  const cases = [
    { duration_min: 60, rpe: 5 },
    { duration_min: 45, avg_hr: 150, max_hr: 185 },
    { duration_min: 30, avg_hr: 50, max_hr: 185 },
    { duration_min: 30, avg_hr: 210 },
    { duration_min: 0, rpe: 6 },
    { duration_min: 40, rpe: 0, avg_hr: 140 },
    { duration_min: 40 },
  ]
  const anchorSets = [{ restingHr: 55, maxHr: 192 }, { restingHr: null, maxHr: 192 }, { restingHr: 55, maxHr: null }]
  for (const a of anchorSets) {
    for (const c of cases) {
      const ours = sessionLoad(c, a)
      const theirs = consoleSessionLoad({ duration_minutes: c.duration_min, rpe: c.rpe, avg_hr: c.avg_hr, max_hr: c.max_hr }, a)
      assert.equal(ours, theirs, JSON.stringify([c, a]))
    }
  }
  assert.equal(sessionLoad({ duration_min: 60, rpe: 5 }, { restingHr: null, maxHr: null }), 300)
  assert.equal(sessionLoad({ duration_min: 45, avg_hr: 150, max_hr: 185 }, { restingHr: null, maxHr: 185 }), null, 'no resting heart rate, no TRIMP')
})

test('TRIMP golden values, shared with Android (EnduranceLoadTest) and the console', () => {
  const near = (x: number | null, y: number) => assert.ok(x !== null && Math.abs(x - y) < 1e-9, `${x} vs ${y}`)
  const anchors = { restingHr: 53, maxHr: 196 }
  near(sessionLoad({ duration_min: 60, avg_hr: 148, max_hr: 160 }, anchors), 91.34231903347344)
  near(sessionLoad({ duration_min: 60, avg_hr: 148 }, { restingHr: 53, maxHr: 160 }), 187.49991909716343)
  near(sessionLoad({ duration_min: 40, avg_hr: 175 }, anchors), 112.37154241030903)
  near(sessionLoad({ duration_min: 30, avg_hr: 210 }, anchors), 130.96240261038238)
})

test('the anchors by date match the console', () => {
  const maxima: [string, number][] = [['2025-01-03', 201], ['2026-05-12', 190], ['2026-05-22', 186], ['2026-06-06', 199], ['2026-06-07', 241]]
  const resting: [string, number][] = [['2026-04-22', 60], ['2026-05-31', 52], ['2026-06-11', 48], ['2026-06-12', 140]]
  const ours = loadAnchorsByDate(maxima, resting)
  const theirs = consoleAnchorsByDate(maxima, resting)
  for (const d of ['2024-01-01', '2026-02-21', '2026-06-01', '2026-06-07', '2026-06-11', '2026-06-12', '2027-03-01']) {
    assert.deepEqual(ours(d), theirs(d), d)
  }
  assert.deepEqual(ours('2026-06-07'), { maxHr: 199, restingHr: 52 })
})

test('the span averages start at the first day with a load and treat later gaps as rest', () => {
  const ema = spanEma([null, null, 100, null, 0], 3) // alpha 0.5
  assert.deepEqual(ema, [null, null, 100, 50, 25])
  assert.deepEqual(spanEma([null, null], 7), [null, null])
})
