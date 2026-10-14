// Backtest gate for the relationship engine (docs/analysis-layer-3). Everything here is synthetic with known truth.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { plantedDataset } from './fixtures.ts'
import type { PlantedDataset } from './fixtures.ts'
import { HYPOTHESES } from './hypotheses.ts'
import { analyseHypothesis, runCatalogue } from './relationship.ts'
import type { Frame, Hypothesis } from './relationship.ts'
import { blockPermutationP, dateAxis, gaussian, mulberry32, pearson } from './stats.ts'
import type { Num } from './stats.ts'

const byId = (id: string): Hypothesis => HYPOTHESES.find((h) => h.id === id)!

function frameFrom(ds: PlantedDataset): Frame {
  return {
    dates: ds.dates,
    series: {
      hrv: ds.hrv, rhr: ds.rhr, sleep_hours: ds.sleepH,
      alcohol_any: ds.alcoholG.map((g) => (g > 0 ? 1 : 0)),
      training_day: ds.train, recovery_any: ds.sauna, breath_any: ds.breath,
    },
    excluded: ds.ill.map((v) => v === 1),
  }
}

const breath: Hypothesis = { ...byId('recovery_hrv'), id: 'breath_hrv', cause: 'breath_any', causeText: 'A breathwork day', expectedSign: 0 }
// 240 days, no missing data, no planted illness effect leaking in (illness days are excluded by the frame).
const ds = plantedDataset(20261010, 240, '2026-01-05', 10_000, 10_000, [60, 150])
const frame = frameFrom(ds)

test('block permutation p is small for a real association and large for none', () => {
  const rng = mulberry32(3)
  const cause: Num[] = Array.from({ length: 140 }, () => (rng() < 0.3 ? 1 : 0))
  const real: Num[] = cause.map((c, i) => (i > 0 ? 3 * (cause[i - 1] as number) : 0) + gaussian(rng))
  const none: Num[] = cause.map(() => gaussian(rng))
  assert.ok(blockPermutationP(cause, real, 1, mulberry32(1), pearson, 1000) < 0.01)
  assert.ok(blockPermutationP(cause, none, 1, mulberry32(1), pearson, 1000) > 0.05)
})

test('false-positive rate stays near the nominal 5 % on autocorrelated nulls', () => {
  const h: Hypothesis = { ...byId('alcohol_hrv'), targetTransform: undefined }
  const dates = dateAxis('2026-01-05', '2026-05-04') // 120 days
  let hits = 0
  const sims = 150
  for (let s = 0; s < sims; s++) {
    const rng = mulberry32(1000 + s)
    let e = 0
    const target: Num[] = dates.map(() => { e = 0.5 * e + gaussian(rng); return 60 + 5 * e })
    const cause: Num[] = dates.map(() => (rng() < 0.3 ? 1 : 0))
    const o = analyseHypothesis(h, { dates, series: { alcohol_any: cause, hrv: target }, excluded: dates.map(() => false) }, 7 + s, 200)
    if (o.p !== null && o.p < 0.05) hits++
  }
  const rate = hits / sims
  assert.ok(rate <= 0.12, `false-positive rate ${rate}`)
})

test('planted effects: alcohol and sleep are supported with the right sign, size and interval', () => {
  const out = runCatalogue([byId('alcohol_hrv'), byId('alcohol_rhr'), byId('sleep_hrv'), breath], frame, 42)
  const get = (id: string) => out.find((o) => o.id === id)!
  const alcohol = get('alcohol_hrv')
  assert.equal(alcohol.state, 'supported')
  assert.equal(alcohol.direction, 'negative')
  // truth: -8 ms on a base of 62 ms is about -13 %
  assert.ok(alcohol.effect!.lo < -8 && alcohol.effect!.hi > -18, JSON.stringify(alcohol.effect))
  const sleep = get('sleep_hrv')
  assert.equal(sleep.state, 'supported')
  assert.equal(sleep.direction, 'positive')
  assert.ok(sleep.effect!.lo < 5 && sleep.effect!.hi > 4, JSON.stringify(sleep.effect)) // +3 ms/h is about +4.8 %/h
  assert.ok(get('alcohol_rhr').p! < 0.05)
})

test('null control (breathwork) is not distinguishable from noise and reports what it could have detected', () => {
  const [o] = runCatalogue([breath], frame, 42)
  assert.equal(o.state, 'not_distinguishable')
  assert.ok(o.mde! > 0)
  assert.match(o.summary, /^Not distinguishable from noise/)
  assert.doesNotMatch(o.summary, /Moderate|Strong/i)
})

test('a fixed weekly schedule is not testable, instead of looking like an effect (the confounded training card)', () => {
  const [o] = runCatalogue([byId('training_hrv')], frame, 42)
  assert.equal(o.state, 'insufficient')
  assert.match(o.reason!, /weekly schedule/)
})

test('a weekday pattern shared by cause and target does not become a finding', () => {
  const dates = dateAxis('2026-01-05', '2026-06-27')
  const rng = mulberry32(99)
  const dow = (d: string) => new Date(d + 'T00:00:00Z').getUTCDay()
  // drinking on Fri/Sat with chance, HRV lower every Sunday regardless: a calendar link only
  const cause: Num[] = dates.map((d) => (dow(d) === 5 || dow(d) === 6 ? (rng() < 0.7 ? 1 : 0) : rng() < 0.05 ? 1 : 0))
  const target: Num[] = dates.map((d) => 60 + (dow(d) === 0 ? -9 : 0) + 3 * gaussian(rng))
  const f: Frame = { dates, series: { alcohol_any: cause, hrv: target }, excluded: dates.map(() => false) }
  const naive = pearson(cause.slice(0, -1) as number[], target.slice(1) as number[])
  assert.ok(Math.abs(naive) > 0.1, `the unadjusted correlation is the trap: ${naive}`)
  const [o] = runCatalogue([{ ...byId('alcohol_hrv'), targetTransform: undefined }], f, 5)
  assert.notEqual(o.state, 'supported')
})

test('too little data and one-sided exposure are reported as not testable', () => {
  const short: Frame = { dates: frame.dates.slice(0, 20), series: { alcohol_any: frame.series.alcohol_any.slice(0, 20), hrv: frame.series.hrv.slice(0, 20) }, excluded: new Array(20).fill(false) }
  assert.equal(analyseHypothesis(byId('alcohol_hrv'), short).state, 'insufficient')
  const never: Frame = { ...frame, series: { ...frame.series, alcohol_any: frame.dates.map(() => 0) } }
  const o = analyseHypothesis(byId('alcohol_hrv'), never)
  assert.equal(o.state, 'insufficient')
  assert.match(o.reason!, /0 days with it/)
})

test('excluded (illness) days drop out of the analysis', () => {
  const withMask = analyseHypothesis(byId('sleep_hrv'), frame, 1, 200)
  const noMask = analyseHypothesis(byId('sleep_hrv'), { ...frame, excluded: frame.excluded.map(() => false) }, 1, 200)
  assert.ok(withMask.n < noMask.n)
})

test('results are deterministic for the same inputs and seed', () => {
  const a = runCatalogue(HYPOTHESES, frame, 7, 300)
  const b = runCatalogue(HYPOTHESES, frame, 7, 300)
  assert.equal(JSON.stringify(a), JSON.stringify(b))
  assert.equal(a.length, HYPOTHESES.length)
  assert.ok(a.every((o) => o.inputsHash.length === 8))
})

test('backtest gate over 40 planted datasets (120 days): power, false-positive rate and interval coverage', () => {
  const ids = ['alcohol_hrv', 'alcohol_sleep', 'sleep_hrv']
  let alcoholFound = 0
  let nullFalse = 0
  let covered = 0
  let sleepCovered = 0
  const runs = 40
  for (let s = 1; s <= runs; s++) {
    const d = plantedDataset(500 + s, 120, '2026-01-05', 10_000, 10_000, [])
    const f: Frame = {
      dates: d.dates,
      series: { hrv: d.hrv, sleep_hours: d.sleepH, alcohol_any: d.alcoholG.map((g) => (g > 0 ? 1 : 0)) },
      excluded: d.dates.map(() => false),
    }
    const out = runCatalogue(HYPOTHESES.filter((h) => ids.includes(h.id)), f, 11, 300)
    const get = (id: string) => out.find((o) => o.id === id)!
    if (get('alcohol_hrv').state === 'supported') alcoholFound++
    if (get('alcohol_sleep').state === 'supported') nullFalse++ // alcohol does not touch sleep in the planted model
    const a = get('alcohol_hrv').effect!
    if (a.lo <= -12.9 && -12.9 <= a.hi) covered++ // truth: -8 ms on 62 ms
    const sl = get('sleep_hrv').effect!
    if (sl.lo <= 4.96 && 4.96 <= sl.hi) sleepCovered++ // truth: +3 ms/h on 62 ms
  }
  assert.ok(alcoholFound / runs >= 0.9, `power ${alcoholFound / runs}`)
  assert.ok(nullFalse / runs <= 0.1, `false positives ${nullFalse / runs}`)
  assert.ok(covered / runs >= 0.8, `alcohol interval coverage ${covered / runs}`)
  assert.ok(sleepCovered / runs >= 0.75, `sleep interval coverage ${sleepCovered / runs}`)
})
