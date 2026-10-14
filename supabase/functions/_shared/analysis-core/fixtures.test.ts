import assert from 'node:assert/strict'
import { test } from 'node:test'
import { plantedDataset, stationarySeries, stepChangeSeries } from './fixtures.ts'
import { mean, pairedLag, pearson, pFromR, sd } from './stats.ts'

const lag = (c: (number | null)[], t: (number | null)[], l: number) => {
  const p = pairedLag(c, t, l)
  const r = pearson(p.x, p.y)
  return { r, n: p.x.length, p: pFromR(r, p.x.length) }
}

test('planted dataset is deterministic and well formed', () => {
  const a = plantedDataset()
  const b = plantedDataset()
  assert.deepEqual(a, b)
  assert.equal(a.dates.length, 61)
  assert.equal(a.dates[0], '2026-08-11')
  assert.equal(a.dates[60], '2026-10-10')
  assert.notDeepEqual(a.hrv, plantedDataset(1).hrv)
})

test('planted effects are recoverable and nulls stay weak (positive and negative controls)', () => {
  const d = plantedDataset()
  const alc = d.alcoholG.map((g) => (g > 0 ? 1 : 0))
  const next = lag(alc, d.hrv, 1)
  assert.ok(next.r < -0.35 && next.p < 0.01, `alcohol -> next-day HRV: ${JSON.stringify(next)}`)
  assert.ok(Math.abs(lag(alc, d.hrv, 0).r) < 0.25, 'same-day alcohol is a null')
  const sleep = lag(d.sleepH, d.hrv, 0)
  assert.ok(sleep.r > 0.15, `sleep -> HRV should be positive: ${JSON.stringify(sleep)}`)
  assert.ok(Math.abs(lag(d.breath, d.hrv, 1).r) < 0.3, 'breathwork is a null control')
  // Training is a calendar confounder (falls on drinking weekends): it must NOT look like a clean positive effect.
  assert.ok(lag(d.train, d.hrv, 1).r < 0.1)
})

test('illness days are planted as HRV drops with raised RHR', () => {
  const d = plantedDataset()
  const ill = d.ill.map((v, i) => (v ? i : -1)).filter((i) => i >= 0)
  assert.deepEqual(ill, [40, 52])
  const baseHrv = mean(d.hrv.filter((_, i) => !d.ill[i]))
  const baseRhr = mean(d.rhr.filter((_, i) => !d.ill[i]))
  ill.forEach((i) => {
    assert.ok(d.hrv[i] < baseHrv - 10)
    assert.ok(d.rhr[i] > baseRhr + 4)
  })
})

test('step-change and stationary fixtures have the intended shape', () => {
  const step = stepChangeSeries() as number[]
  const before = step.slice(0, 80)
  const after = step.slice(80)
  assert.ok(sd(after) > 2 * sd(before), 'noise grows after the break (like HRV after a sensor change)')
  const flat = stationarySeries() as number[]
  assert.ok(Math.abs(mean(flat.slice(0, 70)) - mean(flat.slice(70))) < 4)
})
