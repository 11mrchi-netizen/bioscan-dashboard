// Backtest gate for the typicality engine (docs/analysis-layer-3). Synthetic data with known truth only.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { invert, quad, shrunkCovariance } from './linalg.ts'
import { analyseTypicality, MIN_COMPLETE, quantile } from './typicality.ts'
import type { TypicalityInput } from './typicality.ts'
import { typicalitySeries } from './typicalityFixtures.ts'
import type { SignalFrame } from './typicalityFixtures.ts'

function inputFrom(f: SignalFrame, excluded?: boolean[], restartIndex = 0): TypicalityInput {
  return { dates: f.dates, hrv: f.hrv, rhr: f.rhr, sleepHours: f.sleepHours, deepRemMin: f.deepRemMin, steps: f.steps, excluded: excluded ?? f.dates.map(() => false), restartIndex }
}

test('linear algebra: inverse, quadratic form, shrinkage keeps collinear data invertible', () => {
  const inv = invert([[2, 0], [0, 4]])!
  assert.deepEqual(inv, [[0.5, 0], [0, 0.25]])
  assert.equal(quad(inv, [2, 4]), 2 + 4)
  assert.equal(invert([[1, 2], [2, 4]]), null)
  const rows = Array.from({ length: 12 }, (_, i) => [i, 2 * i, (i * i) % 5, 1]) // collinear columns and a constant one
  const { cov, delta } = shrunkCovariance(rows)
  assert.ok(delta >= 0.01 && delta <= 1)
  assert.ok(invert(cov) !== null, 'shrunk covariance is invertible')
  assert.equal(quantile([1, 2, 3, 4, 5], 0.5), 3)
  assert.equal(quantile([1, 2, 3, 4], 0.25), 1.75)
})

test('too little history is reported as building, not guessed', () => {
  const r = analyseTypicality(inputFrom(typicalitySeries(1, 40)))
  assert.equal(r.state, 'building')
  assert.ok(r.baselineDays < MIN_COMPLETE)
  assert.equal(r.today, null)
})

test('false alarms stay near the nominal share on stationary data', () => {
  let unusual = 0
  let elevatedOrWorse = 0
  let days = 0
  let prompts = 0
  const sims = 100
  for (let s = 0; s < sims; s++) {
    const r = analyseTypicality(inputFrom(typicalitySeries(200 + s, 200)))
    assert.notEqual(r.state, 'building')
    for (const d of r.recent) {
      if (d.state === 'insufficient') continue
      days++
      if (d.state === 'unusual') unusual++
      if (d.state !== 'typical') elevatedOrWorse++
    }
    if (r.suggestIllnessLog) prompts++
  }
  assert.ok(unusual / days <= 0.06, `unusual share ${unusual / days}`) // nominal 2.5 %
  assert.ok(elevatedOrWorse / days <= 0.16, `elevated-or-worse share ${elevatedOrWorse / days}`) // nominal 10 %
  assert.ok(prompts / sims <= 0.06, `illness prompts ${prompts / sims}`)
})

test('a planted illness is flagged, driven by resting heart rate and HRV, and prompts a log suggestion', () => {
  let flagged = 0
  let total = 0
  let prompted = 0
  const sims = 60
  for (let s = 0; s < sims; s++) {
    const f = typicalitySeries(900 + s, 200, '2026-01-05', [{ start: 192, length: 8 }]) // the last 8 days
    const r = analyseTypicality(inputFrom(f))
    for (const d of r.recent.filter((x) => f.ill[f.dates.indexOf(x.date)])) {
      total++
      if (d.state === 'elevated' || d.state === 'unusual') flagged++
    }
    if (r.suggestIllnessLog) prompted++
    if (s === 0) {
      assert.ok(r.today!.drivers.some((d) => d.signal === 'rhr' && d.direction === 'up'), JSON.stringify(r.today))
      assert.ok(r.today!.drivers.some((d) => d.signal === 'hrv' && d.direction === 'down'), JSON.stringify(r.today))
    }
  }
  assert.ok(flagged / total >= 0.9, `sensitivity ${flagged / total}`)
  assert.ok(prompted / sims >= 0.9, `prompts ${prompted / sims}`)
})

test('a logged illness is not re-suggested, stays out of the baseline, and the next one finds it as a lookalike', () => {
  const f = typicalitySeries(5, 200, '2026-01-05', [{ start: 100, length: 6 }, { start: 192, length: 8 }])
  const logged = analyseTypicality(inputFrom(f, f.ill.map((v) => v)))
  assert.equal(logged.suggestIllnessLog, false)
  assert.ok(logged.recent.slice(-3).every((d) => d.excluded))
  const earlierOnly = analyseTypicality(inputFrom(f, f.dates.map((_, i) => i >= 100 && i < 106)))
  assert.equal(earlierOnly.suggestIllnessLog, true)
  assert.ok(earlierOnly.baselineDays < 120 && earlierOnly.baselineDays >= 100, `baseline ${earlierOnly.baselineDays}`)
  assert.ok(earlierOnly.lookalikes.some((l) => l.illness), JSON.stringify(earlierOnly.lookalikes))
})

test('a baseline restarted at a confirmed series break does not mix regimes', () => {
  const f = typicalitySeries(7, 260)
  const shifted = { ...f, hrv: f.hrv.map((v, i) => (i >= 120 ? v * 0.55 : v)), deepRemMin: f.deepRemMin.map((v, i) => (i >= 120 ? v * 1.6 : v)) }
  const mixed = analyseTypicality(inputFrom(shifted))
  const restarted = analyseTypicality(inputFrom(shifted, undefined, 120))
  assert.ok(restarted.baselineStart! >= shifted.dates[120])
  const share = (x: ReturnType<typeof analyseTypicality>) => x.recent.filter((d) => d.state === 'unusual').length / x.recent.length
  assert.ok(share(restarted) <= 0.15, `restarted unusual share ${share(restarted)}`)
  assert.ok(share(mixed) >= share(restarted), 'mixing regimes can only make today look more unusual')
})

test("today's partial step count is ignored, and a day with fewer than 3 signals is not scored", () => {
  const f = typicalitySeries(11, 200)
  assert.equal(analyseTypicality(inputFrom(f)).today!.signalsUsed, 4)
  const input = inputFrom(f)
  const lastOnly = <T>(xs: (T | null)[]) => xs.map((v, i) => (i === 199 ? null : v))
  input.rhr = lastOnly(input.rhr)
  input.sleepHours = lastOnly(input.sleepHours)
  input.hrv = lastOnly(input.hrv)
  const r = analyseTypicality(input)
  const last = r.recent[r.recent.length - 1]
  assert.equal(last.state, 'insufficient') // steps (partial), hrv, rhr and sleep missing leave one signal
  assert.equal(r.today!.date, f.dates[198])
})

test('the engine is deterministic', () => {
  const f = typicalitySeries(3, 200, '2026-01-05', [{ start: 195, length: 5 }])
  assert.equal(JSON.stringify(analyseTypicality(inputFrom(f))), JSON.stringify(analyseTypicality(inputFrom(f))))
})
