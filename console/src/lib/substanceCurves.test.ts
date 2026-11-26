import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  alcoholSeries, caffeineAt, caffeineBelowAt, caffeinePeakMin, caffeineSeries, clockLabel, usualBedtimeMin, wallParts,
} from './substanceCurves.ts'

test('caffeine: nothing before the dose, peak about 50 min after it, half left after the half-life past the peak region', () => {
  const d = [{ at: 600, amount: 100 }]
  assert.equal(caffeineAt(d, 600), 0)
  const peak = caffeinePeakMin()
  assert.ok(peak > 40 && peak < 65, `peak ${peak}`)
  const top = caffeineAt(d, 600 + peak)
  assert.ok(top > 85 && top < 100, `top ${top}`)
  // Long after absorption the amount halves every 5 h.
  const a = caffeineAt(d, 600 + 6 * 60), b = caffeineAt(d, 600 + 11 * 60)
  assert.ok(Math.abs(b / a - 0.5) < 0.01)
})

test('caffeine: doses add up, a dose the evening before still counts', () => {
  const one = caffeineAt([{ at: -120, amount: 200 }], 0)
  assert.ok(one > 100 && one < 160, `${one}`)
  assert.ok(Math.abs(caffeineAt([{ at: 0, amount: 100 }, { at: 0, amount: 100 }], 120) - 2 * caffeineAt([{ at: 0, amount: 100 }], 120)) < 1e-9)
})

test('caffeine: when it falls below the bedtime line', () => {
  const t = caffeineBelowAt([{ at: 11 * 60, amount: 100 }], 50, 0)
  assert.ok(t != null && t > 11 * 60 + 5 * 60 && t < 11 * 60 + 7 * 60, `${t}`)
  assert.equal(caffeineBelowAt([], 50, 0), 0)
})

test('caffeine series', () => {
  const s = caffeineSeries([{ at: 60, amount: 100 }], 0, 120, 60)
  assert.deepEqual(s.map((p) => p.t), [0, 60, 120])
  assert.equal(s[0].v, 0)
})

test('alcohol: rises, then falls in a straight line at 7 g an hour, and is gone', () => {
  const s = alcoholSeries([{ at: 20 * 60, amount: 28 }], 19 * 60, 30 * 60, 5)
  const peak = Math.max(...s.map((p) => p.v))
  assert.ok(peak > 15 && peak < 28, `${peak}`)
  const at = (t: number) => s.find((p) => p.t === t)!.v
  assert.ok(Math.abs((at(22 * 60 + 30) - at(23 * 60 + 30)) - 7) < 0.3)
  assert.equal(at(30 * 60), 0)
  assert.equal(at(19 * 60), 0)
})

test('alcohol: a drink the evening before is cleared by morning', () => {
  const s = alcoholSeries([{ at: -180, amount: 14 }], 0, 360, 60)
  assert.equal(s[0].v, 0)
})

test('usual bedtime: median of recent nights in the zone of each night, after midnight counted past 24:00', () => {
  const night = (utc: string) => ({ bedtime: utc, tz_offset_min: 480 })
  // 23:57, 00:33, 23:00 local -> 1437, 1473, 1380 -> median 1437
  assert.equal(usualBedtimeMin([night('2026-11-25T15:57:00Z'), night('2026-11-24T16:33:00Z'), night('2026-11-23T15:00:00Z')]), 1437)
  assert.equal(usualBedtimeMin([night('2026-11-25T15:57:00Z')]), null)
})

test('wall clock helpers', () => {
  assert.deepEqual(wallParts('2026-11-24 10:36:22.508766+00'), { day: '2026-11-24', min: 636 })
  assert.equal(clockLabel(1437), '23:57')
  assert.equal(clockLabel(1500), '01:00')
})

import { amountIn, compoundFor, compoundSeries, oneCompartmentAt } from './substanceCurves.ts'
test('supplements: compounds by key, units, a short half-life empties in hours', () => {
  assert.equal(compoundFor('l-theanine')?.key, 'l-theanine')
  assert.equal(compoundFor('Theanine')?.key, 'l-theanine')
  assert.equal(compoundFor('magnesium'), null)
  const gly = compoundFor('glycine')!
  assert.equal(amountIn(gly, 3, 'Gram(s)'), 3)
  assert.equal(amountIn(gly, 3000, 'mg'), 3)
  const mel = compoundFor('melatonin')!
  assert.equal(amountIn(mel, 500, 'mcg'), 0.5)
  assert.equal(amountIn(mel, 1, 'IU'), null)
  const s = compoundSeries(mel, [{ at: 0, amount: 1 }], 0, 480, 60)
  assert.ok(s[1].v > 0.4 && s[1].v < 0.8, `${s[1].v}`)
  assert.ok(s[7].v < 0.01, `${s[7].v}`)
  assert.equal(oneCompartmentAt([{ at: 0, amount: 100 }], 90, 5, 4), caffeineAt([{ at: 0, amount: 100 }], 90))
})

import { DEFAULT_TMAX_H, doseQty, kaFromTmax, lookbackDays, massMg, qtyText, quantity } from './substanceCurves.ts'
test('regimen items: amounts in a base unit, doses from the log or from servings', () => {
  assert.equal(massMg(2.5, 'mg'), 2.5)
  assert.equal(massMg(500, 'mcg'), 0.5)
  assert.equal(massMg(1, 'tablet'), null)
  assert.deepEqual(quantity(250, 'IU'), { v: 250, base: 'IU' })
  assert.deepEqual(quantity(10, 'units'), { v: 10, base: 'units' })
  const mg5 = { v: 5, base: 'mg' as const }
  assert.deepEqual(doseQty({ dose_value: 0.5, dose_unit: 'tablet', serving_count: 0.5 }, mg5), { v: 2.5, base: 'mg' })
  assert.deepEqual(doseQty({ dose_value: 2.5, dose_unit: 'mg', serving_count: 1 }, null), { v: 2.5, base: 'mg' })
  assert.deepEqual(doseQty({ dose_value: 250, dose_unit: 'mcg', serving_count: 1 }, null), { v: 0.25, base: 'mg' })
  assert.equal(doseQty({ dose_value: 0.5, dose_unit: 'tablet', serving_count: 0.5 }, null), null)
  assert.equal(qtyText(0.25, 'mg'), '250 mcg')
  assert.equal(qtyText(2.5, 'mg'), '2.5 mg')
  assert.equal(qtyText(40, 'IU'), '40 IU')
  assert.equal(lookbackDays(17.5), 4)
  assert.equal(lookbackDays(0.75), 1)
  assert.equal(lookbackDays(8 * 24), 40)
})

function peakOf(halfLifeH: number, tmaxH: number): number {
  const ka = kaFromTmax(halfLifeH, tmaxH)
  const d = [{ at: 0, amount: 1 }]
  let best = 0, bestT = 0
  for (let t = 1; t <= 60 * 24 * 20; t += 1) { const v = oneCompartmentAt(d, t, halfLifeH, ka); if (v > best) { best = v; bestT = t } }
  return bestT / 60
}
test('peak time: the absorption rate is solved so the curve peaks where entered', () => {
  assert.ok(Math.abs(peakOf(17.5, DEFAULT_TMAX_H) - 2) < 0.05)
  assert.ok(Math.abs(peakOf(4.5, 1) - 1) < 0.05)
  assert.ok(Math.abs(peakOf(0.75, 0.5) - 0.5) < 0.05)
  // a depot: half-life 8 days, peak 2.5 days (slower to absorb than to clear is not needed: 1/ke = 11.5 days)
  assert.ok(Math.abs(peakOf(8 * 24, 2.5 * 24) - 60) < 1)
  // flip-flop: a peak later than 1/ke (a slow-release product with a short half-life) still peaks where entered
  assert.ok(Math.abs(peakOf(4, 8) - 8) < 0.1, `${peakOf(4, 8)}`)
  // the 17.5 h tablet carries into the next day: about 40 % of the dose is left 24 h later
  const ka = kaFromTmax(17.5, 2)
  assert.ok(oneCompartmentAt([{ at: 0, amount: 1 }], 26 * 60, 17.5, ka) > 0.35)
})
