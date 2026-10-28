// Analysis hub phase C2: the corrected discovery screen. Its gate: on data with no true link it flags something in at
// most about the false-discovery rate of histories (the retired raw scan flagged several bars on every view), and a
// planted link is found at its own lag.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { DISCOVERY, detrend, discover, prewhiten } from './discovery.ts'
import type { Frame } from './relationship.ts'
import { dateAxis, gaussian, mulberry32, pairedLag, pearson, pFromR, type Num } from './stats.ts'

const EXPOSURES = ['alcohol_any', 'caffeine_pm_any', 'late_meal', 'stress_high', 'evening_training', 'training_day']

/**
 * A synthetic history with the confounds that fooled the old scan: weekend-heavy exposures, a weekday pattern and a slow
 * trend in every outcome, AR(1) carry-over, and missing days. `effect`: alcohol lowers next-morning HRV by this
 * fraction, from day `effectFrom` on.
 */
function history(seed: number, days = 450, effect = 0, effectFrom = 0): Frame {
  const rng = mulberry32(seed)
  const dates = dateAxis('2025-01-01', new Date(Date.UTC(2025, 0, 1) + (days - 1) * 864e5).toISOString().slice(0, 10))
  const series: Record<string, Num[]> = Object.fromEntries([...EXPOSURES, 'hrv', 'rhr', 'sleep_hours'].map((k) => [k, [] as Num[]]))
  let ar = 0
  let arR = 0
  let arS = 0
  for (let t = 0; t < days; t++) {
    const dow = new Date(Date.parse(`${dates[t]}T00:00:00Z`)).getUTCDay()
    const weekend = dow === 5 || dow === 6
    const trend = Math.sin((2 * Math.PI * t) / 180)
    for (const [i, e] of EXPOSURES.entries()) series[e].push(rng() < (weekend ? 0.45 : 0.12 + 0.03 * i) ? 1 : 0)
    ar = 0.6 * ar + 0.06 * gaussian(rng)
    arR = 0.6 * arR + 1.2 * gaussian(rng)
    arS = 0.4 * arS + 0.5 * gaussian(rng)
    const prevAlc = t > 0 ? (series.alcohol_any[t - 1] as number) : 0
    const hit = t >= effectFrom ? effect * prevAlc : 0
    const miss = rng() < 0.08
    series.hrv.push(miss ? null : 60 * Math.exp(0.08 * trend + (weekend ? 0.04 : 0) + ar - hit))
    series.rhr.push(miss ? null : 54 + 2 * trend + (dow === 1 ? 1 : 0) + arR)
    series.sleep_hours.push(miss ? null : 7.2 + 0.3 * trend + (weekend ? 0.5 : 0) + arS)
  }
  return { dates, series, excluded: dates.map(() => false) }
}

test('detrend and prewhiten keep gaps as gaps', () => {
  const v: Num[] = [1, 2, null, 4, 5, 6, 7, 8, 9, 10]
  const d = detrend(v, 5)
  assert.equal(d[2], null)
  assert.ok(Math.abs((d[5] as number) - 0) < 1.5)
  const w = prewhiten([1, null, 3, 4])
  assert.deepEqual([w[0], w[1], w[2]], [null, null, null])
  assert.equal(typeof w[3], 'number')
})

test('scans the older two-thirds only and holds the newest third out', () => {
  const r = discover(history(1))
  assert.deepEqual(r.window, { start: '2025-01-01', end: '2025-10-27' }) // 300 of 450 days
  assert.deepEqual(r.heldOut, { start: '2025-10-28', end: '2026-03-26' })
  assert.equal(r.cells.length, EXPOSURES.length * 3 * (DISCOVERY.maxLag + 1))
  assert.equal(r.tests, r.cells.filter((c) => c.p !== null).length)
  for (const c of r.cells.filter((x) => x.band !== null)) assert.ok(Math.abs((c.band as number) - 1.96 / Math.sqrt(c.nEff)) < 1e-12)
})

test('on histories with no true link, at most about the false-discovery rate of them flag anything', () => {
  const sims = 200
  let anyFlag = 0
  let rawHits = 0
  let rawTests = 0
  for (let s = 0; s < sims; s++) {
    const f = history(1000 + s)
    const r = discover(f)
    if (r.flagged > 0) anyFlag++
    // the retired scan: raw correlations of the untouched series, p < 0.05, no correction
    for (const e of EXPOSURES) for (const o of ['hrv', 'rhr', 'sleep_hours']) for (let lag = 0; lag <= 3; lag++) {
      const pr = pairedLag(f.series[e].slice(0, 300), f.series[o].slice(0, 300), lag)
      rawTests++
      if (pFromR(pearson(pr.x, pr.y), pr.x.length) < 0.05) rawHits++
    }
  }
  assert.ok(anyFlag / sims <= 0.13, `flagged on ${anyFlag} of ${sims} null histories`)
  // the confounds make the raw scan far worse than its nominal 5%
  assert.ok(rawHits / rawTests > 0.1, `raw scan false-positive share ${rawHits / rawTests}`)
})

test('a planted link is found at its own lag and direction', () => {
  let found = 0
  let wrongLag = 0
  for (let s = 0; s < 20; s++) {
    const r = discover(history(5000 + s, 450, 0.08))
    const cell = r.cells.find((c) => c.exposure === 'alcohol_any' && c.outcome === 'hrv' && c.lag === 1)!
    if (cell.status === 'flagged' && (cell.r as number) < 0) found++
    wrongLag += r.cells.filter((c) => c.exposure === 'alcohol_any' && c.outcome === 'hrv' && c.lag !== 1 && c.status === 'flagged').length
  }
  assert.ok(found >= 16, `found in ${found} of 20`)
  assert.ok(wrongLag <= 4, `flagged at the wrong lag ${wrongLag} times`)
})

test('a link that exists only in the held-out third is not looked at', () => {
  let found = 0
  for (let s = 0; s < 10; s++) {
    const r = discover(history(7000 + s, 450, 0.15, 320))
    if (r.cells.some((c) => c.exposure === 'alcohol_any' && c.outcome === 'hrv' && c.status === 'flagged')) found++
  }
  assert.ok(found <= 2, `found ${found} times`)
})

test('short histories and rare exposures read too few; catalogued questions are marked', () => {
  const short = discover(history(3, 60))
  assert.ok(short.cells.every((c) => c.status === 'too_few'))
  assert.equal(short.tests, 0)
  const r = discover(history(4))
  const alc = r.cells.find((c) => c.exposure === 'alcohol_any' && c.outcome === 'hrv' && c.lag === 1)!
  assert.equal(alc.catalogued, true)
  assert.ok(r.cells.some((c) => !c.catalogued))
})
