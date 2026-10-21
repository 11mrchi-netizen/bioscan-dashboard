// Phase C gates (docs/analysis-review/README.md): on synthetic data the 80% band holds 78-82% of mornings and flags
// without a real change stay under one a month; plus detection, regimes, log units, gaps, illness and building states.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { analyseExpectedBand, BAND, BAND_SPECS, fitWeights, horizonVariance, q80, q99, runFilter } from './expectedBand.ts'
import type { BandSpec } from './expectedBand.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'
import type { Num } from './stats.ts'

const N = 240
const DATES = dateAxis('2026-01-05', '2026-12-31').slice(0, N)
const NONE = DATES.map(() => false)
const SPEC_RAW: BandSpec = { metric: 'x', unit: 'u', transform: 'none', better: 'higher' }
const HRV = BAND_SPECS.find((s) => s.metric === 'hrv') as BandSpec

// Log-HRV-like series: level 4.0 (about 55 ms), 10% day-to-day spread, AR(1) noise, a weekly wave, 20% of days missing.
function synth(rng: () => number, rho = 0.3, missing = 0.2): Num[] {
  let e = 0
  const out: Num[] = []
  for (let t = 0; t < N; t++) {
    e = rho * e + gaussian(rng) * 0.1 * Math.sqrt(1 - rho * rho)
    out.push(4 + 0.03 * Math.sin((2 * Math.PI * (t % 7)) / 7) + e)
  }
  return out.map((v) => (rng() < missing ? null : v))
}

function filtered(y: Num[], excluded = NONE, restarts: number[] = []) {
  const w = fitWeights(DATES, y, excluded, restarts)
  return runFilter(DATES, y, excluded, restarts, w.alpha, w.gamma)
}

test('gate: the 80% band holds 78-82% of mornings and the 99% band about 99%', () => {
  for (const rho of [0, 0.3, 0.5]) {
    const rng = mulberry32(4100 + rho * 10)
    let scored = 0
    let in80 = 0
    let in99 = 0
    for (let s = 0; s < 60; s++) {
      for (const st of filtered(synth(rng, rho)).steps) {
        if (!st.scored || st.z === null) continue
        scored++
        if (Math.abs(st.z) <= q80(st.df)) in80++
        if (Math.abs(st.z) <= q99(st.df)) in99++
      }
    }
    assert.ok(scored > 8000, `rho ${rho}: ${scored} scored mornings`)
    const c80 = in80 / scored
    const c99 = in99 / scored
    assert.ok(c80 >= 0.78 && c80 <= 0.82, `rho ${rho}: 80% band held ${c80.toFixed(3)}`)
    assert.ok(c99 >= 0.98 && c99 <= 0.997, `rho ${rho}: 99% band held ${c99.toFixed(4)}`)
  }
})

test('gate: with no real change, flags stay under one a month', () => {
  for (const rho of [0, 0.3, 0.5]) {
    const rng = mulberry32(5200 + rho * 10)
    let scored = 0
    let lowDays = 0
    let episodes = 0
    for (let s = 0; s < 60; s++) {
      let prev = false
      for (const st of filtered(synth(rng, rho)).steps) {
        if (!st.scored) continue
        scored++
        if (st.flag !== null && st.side === 'low') lowDays++
        if (st.flag !== null && !prev) episodes++
        prev = st.flag !== null
      }
    }
    const lowPerMonth = (30 * lowDays) / scored
    const episodesPerMonth = (30 * episodes) / scored
    assert.ok(lowPerMonth < 1, `rho ${rho}: ${lowPerMonth.toFixed(2)} low-side flag days a month`)
    assert.ok(episodesPerMonth < 1, `rho ${rho}: ${episodesPerMonth.toFixed(2)} flag episodes (either side) a month`)
  }
})

// Measured over 1,000 series: 2.5 SD for five mornings is flagged 88% of the time, 1.5 SD for four mornings 44%.
test('a sustained drop is flagged: 2.5 SD for five mornings in most series, 1.5 SD for four in about four of ten', () => {
  const rate = (drop: number, days: number) => {
    const rng = mulberry32(7700 + drop * 10 + days)
    let hits = 0
    for (let s = 0; s < 200; s++) {
      const y = synth(rng)
      for (let t = N - days - 1; t < N - 1; t++) if (y[t] !== null) y[t] = (y[t] as number) - drop * 0.1
      const run = filtered(y)
      if (run.steps.slice(N - days - 1, N - 1).some((st) => st.flag !== null && st.side === 'low')) hits++
    }
    return hits / 200
  }
  const big = rate(2.5, 5)
  const small = rate(1.5, 4)
  assert.ok(big >= 0.8, `2.5 SD x 5: ${big}`)
  assert.ok(small >= 0.33, `1.5 SD x 4: ${small}`)
})

test('a confirmed device change starts a new regime: no flags from the offset, the band relearns and starts wide', () => {
  const rng = mulberry32(31)
  const step = 180
  const y = synth(rng, 0.3, 0.1).map((v, t) => (v !== null && t >= step ? v + 0.35 : v)) // +3.5 SD with the new strap
  const plain = filtered(y)
  const restarted = filtered(y, NONE, [step])
  const flagsAfter = (run: ReturnType<typeof filtered>) => run.steps.slice(step, step + 30).filter((st) => st.flag !== null).length
  assert.ok(flagsAfter(plain) >= 2, 'without the restart the offset is flagged')
  assert.equal(flagsAfter(restarted), 0, 'with the restart it is not')
  // No band until seven new-regime errors exist, then a wide one that narrows.
  assert.equal(restarted.steps[step].sigma, null)
  assert.equal(restarted.steps[step + 3].sigma, null)
  const firstBand = restarted.steps.findIndex((st, t) => t >= step && st.sigma !== null)
  assert.ok(firstBand > step && firstBand < step + 14, `first band on day ${firstBand - step}`)
  const settled = restarted.steps[N - 1]
  assert.equal(restarted.steps[firstBand].df, BAND.minBandErrors)
  assert.ok(q99(BAND.minBandErrors) > 1.2 * q99(settled.df), 'the first band rests on seven errors, so its 99% band is a fifth wider')
  assert.ok(Math.abs((settled.mu as number) - 4.35) < 0.1, `relearned level ${settled.mu}`)

  const result = analyseExpectedBand({ spec: SPEC_RAW, dates: DATES, values: y, excluded: NONE, restartDates: [DATES[step]] })
  assert.equal(result.regimeStart, DATES[step])
  assert.equal(result.state, 'ready')
})

test('HRV is modelled in logs and published in milliseconds', () => {
  const rng = mulberry32(8)
  const ms = synth(rng).map((v) => (v === null ? null : Math.round(Math.exp(v) * 10) / 10))
  const result = analyseExpectedBand({ spec: HRV, dates: DATES, values: ms, excluded: NONE, restartDates: [] })
  assert.equal(result.state, 'ready')
  assert.equal(result.unit, 'ms')
  const p = result.recent[result.recent.length - 1]
  assert.ok(p.expected !== null && p.lo80 !== null && p.hi80 !== null && p.lo99 !== null && p.hi99 !== null && p.mu !== null && p.sigma !== null)
  assert.ok(p.expected > 40 && p.expected < 75, `expected ${p.expected} ms`)
  assert.ok(Math.abs(p.expected - Math.exp(p.mu)) < 0.02)
  assert.ok(p.lo99 < p.lo80 && p.lo80 < p.expected && p.expected < p.hi80 && p.hi80 < p.hi99)
  assert.ok(p.hi80 - p.expected > p.expected - p.lo80, 'a log band is wider above its centre than below')
  assert.ok(Math.abs(p.hi80 - Math.exp(p.mu + q80(result.params.df) * p.sigma)) < 0.05)
  if (p.value !== null) assert.equal(p.value, ms[N - 1])
})

test('the published calibration and the next morning band', () => {
  const rng = mulberry32(12)
  const result = analyseExpectedBand({ spec: SPEC_RAW, dates: DATES, values: synth(rng), excluded: NONE, restartDates: [] })
  assert.ok(result.calibration.days > 60)
  assert.ok((result.calibration.inside80 as number) > 0.65 && (result.calibration.inside80 as number) < 0.95)
  assert.equal(result.recent.length, BAND.recentDays)
  assert.equal(result.recent[BAND.recentDays - 1].date, DATES[N - 1])
  assert.ok(result.next !== null)
  assert.equal(result.next.date, '2026-09-02')
  assert.equal(result.next.value, null)
  assert.equal(result.next.z, null)
  assert.ok(Math.abs(result.ewma.limit - 2.4 * Math.sqrt(0.3 / 1.7)) < 1e-3)
  assert.equal(result.ewma.runsActive, true)
  assert.ok(result.ewma.date !== null && result.ewma.date <= DATES[N - 1])
})

test('building: no band from a few weeks of readings, then a provisional one', () => {
  const rng = mulberry32(5)
  const y = synth(rng, 0.3, 0).map((v, t) => (t < N - 20 ? null : v))
  const short = analyseExpectedBand({ spec: SPEC_RAW, dates: DATES, values: y, excluded: NONE, restartDates: [] })
  assert.equal(short.state, 'building')
  assert.equal(short.have, 0)
  assert.equal(short.next, null)
  assert.ok(short.recent.every((p) => p.lo80 === null && p.flag === null))

  const y2 = synth(rng, 0.3, 0).map((v, t) => (t < N - 40 ? null : v))
  const later = analyseExpectedBand({ spec: SPEC_RAW, dates: DATES, values: y2, excluded: NONE, restartDates: [] })
  assert.equal(later.state, 'provisional')
  assert.ok(later.have >= BAND.minBandErrors && later.have < BAND.minResiduals)
  assert.equal(later.ewma.runsActive, false)
  assert.ok(later.recent.every((p) => p.flag !== 'run'), 'no run flags while provisional')
})

test('illness days are shown against the band, never flagged, and do not move the model', () => {
  const rng = mulberry32(9)
  const y = synth(rng, 0.3, 0)
  const ill = DATES.map((_, t) => t >= 200 && t < 205)
  const sick = y.map((v, t) => (ill[t] ? (v as number) - 0.5 : v))
  const blanked = y.map((v, t) => (ill[t] ? null : v))
  const a = runFilter(DATES, sick, ill, [], 0.2, 0.05)
  const b = runFilter(DATES, blanked, NONE, [], 0.2, 0.05)
  for (let t = 200; t < 205; t++) {
    assert.ok(a.steps[t].z !== null && (a.steps[t].z as number) < -2, 'the low reading is placed against the band')
    assert.equal(a.steps[t].flag, null)
    assert.equal(a.steps[t].scored, false)
  }
  assert.equal(a.steps[205].mu, b.steps[205].mu)
  assert.equal(a.steps[205].sigma, b.steps[205].sigma)
})

test('a gap widens the band by the h-step forecast variance', () => {
  assert.equal(horizonVariance(1, 0.2, 0.1), 1)
  assert.ok(Math.abs(horizonVariance(7, 0.2, 0) - 1.24) < 1e-12)
  assert.ok(Math.abs(horizonVariance(8, 0.2, 0.1) - (1 + 7 * 0.04 + 0.1 * 0.5)) < 1e-12)
  const rng = mulberry32(14)
  const y = synth(rng, 0.3, 0).map((v, t) => (t >= 210 && t < 216 ? null : v))
  const run = runFilter(DATES, y, NONE, [], 0.2, 0)
  const before = run.steps[209].sigma as number
  const after = run.steps[216].sigma as number
  assert.ok(after / before > Math.sqrt(1 + 6 * 0.04) - 0.03, `ratio ${after / before}`)
  const far = synth(rng, 0.3, 0).map((v, t) => (t >= 220 ? null : v))
  const stale = runFilter(DATES, far, NONE, [], 0.2, 0)
  assert.ok(stale.steps[220 + BAND.maxHorizon - 1].sigma !== null)
  assert.equal(stale.steps[220 + BAND.maxHorizon].sigma, null, 'no band two weeks after the last reading')
})

test('one faulty reading is flagged but moves the level by at most the clipped amount', () => {
  const rng = mulberry32(21)
  const y = synth(rng, 0.3, 0)
  const spiked = y.map((v, t) => (t === 220 ? (v as number) + 1.5 : v)) // +15 SD
  const a = runFilter(DATES, y, NONE, [], 0.2, 0)
  const b = runFilter(DATES, spiked, NONE, [], 0.2, 0)
  assert.equal(b.steps[220].flag, 'extreme')
  assert.equal(b.steps[220].side, 'high')
  const moved = (b.steps[221].mu as number) - (a.steps[221].mu as number)
  const unclipped = 0.2 * 1.5
  assert.ok(moved > 0 && moved < unclipped / 2, `level moved ${moved.toFixed(3)}, unclipped it would move about ${unclipped}`)
})
