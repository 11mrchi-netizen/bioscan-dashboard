// Analysis review phase G gates for the personal fitness-fatigue model: with no load effect the personal spans are almost
// never adopted; with a true response far from 42 and 7 they usually are, and the fit finds the right corner of the grid.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { analyseFitnessFatigue, hacMean, STANDARD_SPANS } from './fitnessFatigue.ts'
import { spanEma } from './trainingLoad.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'
import type { Num } from './stats.ts'

// ln HRV: AR(0.2) around a level moved by fitness (+k1) and fatigue (-k2) at the true spans; sessions on 55% of days.
function world(seed: number, k1: number, k2: number, ctl: number, atl: number, days = 365) {
  const rng = mulberry32(seed)
  const dates = dateAxis('2025-10-01', new Date(Date.parse('2025-10-01T00:00:00Z') + (days - 1) * 86400000).toISOString().slice(0, 10))
  const load: Num[] = dates.map(() => (rng() < 0.55 ? 40 + 120 * rng() : 0))
  const fit = spanEma(load, ctl)
  const fat = spanEma(load, atl)
  let y = 4
  const hrv: Num[] = dates.map((_, t) => {
    const level = 4 + k1 * ((fit[t] as number) - 50) - k2 * ((fat[t] as number) - 50)
    y = level + 0.2 * (y - level) + 0.04 * gaussian(rng)
    return rng() < 0.08 ? null : Math.exp(y)
  })
  return { dates, hrv, load }
}

test('gate: with no load effect at all, the personal spans are adopted at most once in 20 histories', () => {
  let adopted = 0
  for (let s = 0; s < 20; s++) {
    const w = world(300 + s, 0, 0, 42, 7)
    const r = analyseFitnessFatigue(w.dates, w.hrv, w.load)
    assert.notEqual(r.state, 'not_yet')
    if (r.adopted) adopted++
  }
  assert.ok(adopted <= 1, `${adopted} of 20`)
})

test('a slow true response (fitness 90 days, fatigue 21) is found and usually beats 42 and 7 on held-out weeks', () => {
  let predictive = 0
  for (let s = 0; s < 20; s++) {
    const w = world(200 + s, 0.002, 0.003, 90, 21)
    const r = analyseFitnessFatigue(w.dates, w.hrv, w.load)
    assert.deepEqual(r.personal, { ctl: 70, atl: 14 }, 'the grid corner nearest the truth')
    if (r.state === 'predictive') {
      predictive++
      assert.equal(r.adopted, true)
      assert.match(r.summary, /forecast tomorrow's HRV better than the standard 42 and 7/)
    }
  }
  assert.ok(predictive >= 12, `${predictive} of 20`)
})

test('not yet: too little history keeps the standard spans', () => {
  const w = world(1, 0.002, 0.003, 90, 21, 150)
  const r = analyseFitnessFatigue(w.dates, w.hrv, w.load)
  assert.equal(r.state, 'not_yet')
  assert.equal(r.adopted, false)
  assert.deepEqual(r.standard, STANDARD_SPANS)
  assert.match(r.summary, /until then the standard 42 and 7 days stay/)
})

test('the Newey-West mean of a constant is exact', () => {
  const h = hacMean(new Array(50).fill(0.25))
  assert.equal(h.mean, 0.25)
  assert.equal(h.lo, 0.25)
})
