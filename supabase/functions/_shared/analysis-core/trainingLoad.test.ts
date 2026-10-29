// Analysis review phase G: the frame's session load is the apps' load, and the averages are seeded as Android seeds them.
// The console kept a copy of this model (console/src/lib/training.ts) until the analysis hub's phase 0 removed it; the
// expected values below are that copy's outputs, pinned when it was removed, so the agreement with Android stays tested.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { loadAnchorsByDate, SRPE_PER_TRIMP, sessionLoad, spanEma } from './trainingLoad.ts'

test('the session load: TRIMP when heart rate allows it, else RPE x minutes on the TRIMP scale, else nothing', () => {
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
  // expected[anchor set][case]
  const expected = [
    [300 / SRPE_PER_TRIMP, 75.61526752827001, null, 130.96240261038238, null, 52.27423222940505, null],
    [300 / SRPE_PER_TRIMP, null, null, null, null, null, null],
    [300 / SRPE_PER_TRIMP, null, null, null, null, null, null],
  ]
  anchorSets.forEach((a, i) => cases.forEach((c, j) => {
    const ours = sessionLoad(c, a)
    const want = expected[i][j]
    const label = JSON.stringify([c, a])
    if (want === null) assert.equal(ours, null, label)
    else assert.ok(ours !== null && Math.abs(ours - want) < 1e-9, `${label}: ${ours} vs ${want}`)
  }))
  assert.equal(sessionLoad({ duration_min: 60, rpe: 5 }, { restingHr: null, maxHr: null }), 300 / SRPE_PER_TRIMP)
  assert.equal(sessionLoad({ duration_min: 45, avg_hr: 150, max_hr: 185 }, { restingHr: null, maxHr: 185 }), null, 'no resting heart rate, no TRIMP')
})

test('TRIMP golden values, shared with Android (EnduranceLoadTest)', () => {
  const near = (x: number | null, y: number) => assert.ok(x !== null && Math.abs(x - y) < 1e-9, `${x} vs ${y}`)
  const anchors = { restingHr: 53, maxHr: 196 }
  near(sessionLoad({ duration_min: 60, avg_hr: 148, max_hr: 160 }, anchors), 91.34231903347344)
  near(sessionLoad({ duration_min: 60, avg_hr: 148 }, { restingHr: 53, maxHr: 160 }), 187.49991909716343)
  near(sessionLoad({ duration_min: 40, avg_hr: 175 }, anchors), 112.37154241030903)
  near(sessionLoad({ duration_min: 30, avg_hr: 210 }, anchors), 130.96240261038238)
})

test('the anchors by date match the former console model', () => {
  const maxima: [string, number][] = [['2025-01-03', 201], ['2026-05-12', 190], ['2026-05-22', 186], ['2026-06-06', 199], ['2026-06-07', 241]]
  const resting: [string, number][] = [['2026-04-22', 60], ['2026-05-31', 52], ['2026-06-11', 48], ['2026-06-12', 140]]
  const ours = loadAnchorsByDate(maxima, resting)
  const expected: Record<string, { maxHr: number | null; restingHr: number | null }> = {
    '2024-01-01': { maxHr: null, restingHr: 60 },
    '2026-02-21': { maxHr: 201, restingHr: 60 },
    '2026-06-01': { maxHr: 190, restingHr: 52 },
    '2026-06-07': { maxHr: 199, restingHr: 52 },
    '2026-06-11': { maxHr: 199, restingHr: 50 },
    '2026-06-12': { maxHr: 199, restingHr: 50 },
    '2027-03-01': { maxHr: 190, restingHr: 50 },
  }
  for (const [d, want] of Object.entries(expected)) assert.deepEqual(ours(d), want, d)
})

test('the span averages start at the first day with a load and treat later gaps as rest', () => {
  const ema = spanEma([null, null, 100, null, 0], 3) // alpha 0.5
  assert.deepEqual(ema, [null, null, 100, 50, 25])
  assert.deepEqual(spanEma([null, null], 7), [null, null])
})

test('an RPE does not change a session that has heart-rate inputs: one scale, not RPE x minutes next to TRIMP', () => {
  const anchors = { restingHr: 54, maxHr: 196 }
  const withRpe = sessionLoad({ duration_min: 50, rpe: 6, avg_hr: 150, max_hr: 185 }, anchors)
  const without = sessionLoad({ duration_min: 50, avg_hr: 150, max_hr: 185 }, anchors)
  assert.ok(withRpe !== null && without !== null)
  assert.equal(withRpe, without)
  assert.ok(Math.abs((withRpe as number) - 300) > 100, 'not the 300 that RPE x minutes would give')
})

test('an RPE-only session is brought onto the TRIMP scale by the fitted factor', () => {
  assert.equal(SRPE_PER_TRIMP, 3.8)
  const anchors = { restingHr: null, maxHr: null }
  assert.equal(sessionLoad({ duration_min: 60, rpe: 5 }, anchors), 300 / SRPE_PER_TRIMP)
  assert.equal(sessionLoad({ duration_min: 60, rpe: 0 }, anchors), null, 'RPE 0 is not an RPE')
  assert.equal(sessionLoad({ duration_min: 0, rpe: 5 }, anchors), null)
})
