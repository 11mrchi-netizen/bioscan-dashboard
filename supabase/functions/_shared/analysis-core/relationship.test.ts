// Backtest gates for the relationship engine (docs/analysis-review, phase D). Everything here is synthetic with known truth.
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

const breath: Hypothesis = { ...byId('recovery_hrv'), id: 'breath_hrv', label: 'Breathwork and next-morning HRV', cause: 'breath_any', causeText: 'A breathwork day', expectedSign: 0 }
// 240 days, no missing data, no planted illness effect leaking in (illness days are excluded by the frame).
const ds = plantedDataset(20261010, 240, '2026-01-05', 10_000, 10_000, [60, 150])
const frame = frameFrom(ds)

// The simulation behind the gates: log HRV = an AR(1) state (phi 0.6) + measurement noise + a weekday pattern + a slow
// wave, 20% of mornings missing; alcohol on half of Fridays and Saturdays and 8% of other days; a planted next-morning
// effect `beta` (log scale), or a planted effect of sleep duration.
const WEEKDAY = [0.0, 0.01, 0.02, 0.0, -0.01, -0.03, 0.01] // Sunday first
function simulated(seed: number, n: number, beta: number, sleepBeta = 0) {
  const rng = mulberry32(seed)
  const dates = dateAxis('2026-01-05', '2027-12-31').slice(0, n)
  let s = 0
  let sl = 0
  const alcohol: number[] = []
  const sleep: number[] = []
  const hrv: Num[] = []
  for (let t = 0; t < n; t++) {
    const dow = new Date(dates[t] + 'T00:00:00Z').getUTCDay()
    s = 0.6 * s + 0.07 * gaussian(rng)
    sl = 0.3 * sl + 0.8 * gaussian(rng)
    alcohol.push(rng() < (dow === 5 || dow === 6 ? 0.5 : 0.08) ? 1 : 0)
    sleep.push(7.3 + sl)
    const prev = t > 0 ? alcohol[t - 1] : 0
    hrv.push(Math.exp(4.1 + s + 0.05 * gaussian(rng) + WEEKDAY[dow] + 0.05 * Math.sin((2 * Math.PI * t) / 200) + beta * prev + sleepBeta * sl))
  }
  return { dates, alcohol, sleep, hrv: hrv.map((v) => (rng() < 0.2 ? null : v)) }
}

test('gate: with no true effect, at most 5% of questions read clear', () => {
  let clear = 0
  let tested = 0
  for (let i = 0; i < 150; i++) {
    for (const n of [120, 240]) {
      const d = simulated(31_000 + i * 11 + n, n, 0)
      const f: Frame = { dates: d.dates, series: { alcohol_any: d.alcohol, sleep_hours: d.sleep, hrv: d.hrv }, excluded: d.dates.map(() => false) }
      for (const o of runCatalogue([byId('alcohol_hrv'), byId('sleep_hrv')], f)) {
        if (o.state === 'not_yet') continue
        tested++
        if (o.state === 'clear') clear++
      }
    }
  }
  assert.ok(tested > 500, `${tested} questions tested`)
  assert.ok(clear / tested <= 0.05, `clear on null data: ${clear} of ${tested}`)
})

test('gate: planted effects are recovered with 95% intervals that cover the truth 93-97% of the time', () => {
  const scenarios: { label: string; hits: number; runs: number }[] = []
  const alcoholTruth = (Math.exp(-0.08) - 1) * 100
  const sleepTruth = (Math.exp(0.03 / 0.8) - 1) * 100 // per hour: the planted effect is 0.03 per 0.8 h of sleep
  for (const n of [120, 240]) {
    const a = { label: `alcohol, ${n} days`, hits: 0, runs: 0 }
    const sl = { label: `sleep, ${n} days`, hits: 0, runs: 0 }
    for (let i = 0; i < 250; i++) {
      const d = simulated(41_000 + i * 13 + n, n, -0.08)
      const o = analyseHypothesis(byId('alcohol_hrv'), { dates: d.dates, series: { alcohol_any: d.alcohol, hrv: d.hrv }, excluded: d.dates.map(() => false) })
      if (o.effect) {
        a.runs++
        if (o.effect.lo <= alcoholTruth && alcoholTruth <= o.effect.hi) a.hits++
      }
      const e = simulated(51_000 + i * 17 + n, n, 0, 0.03 / 0.8)
      const q = analyseHypothesis(byId('sleep_hrv'), { dates: e.dates, series: { sleep_hours: e.sleep, hrv: e.hrv }, excluded: e.dates.map(() => false) })
      if (q.effect) {
        sl.runs++
        if (q.effect.lo <= sleepTruth && sleepTruth <= q.effect.hi) sl.hits++
      }
    }
    scenarios.push(a, sl)
  }
  const hits = scenarios.reduce((s, x) => s + x.hits, 0)
  const runs = scenarios.reduce((s, x) => s + x.runs, 0)
  const pooled = hits / runs
  assert.ok(pooled >= 0.93 && pooled <= 0.97, `pooled coverage ${pooled.toFixed(3)}: ${JSON.stringify(scenarios)}`)
  // Each scenario alone (250 runs, so about +-0.03 of chance): no gross miscalibration in any of them.
  for (const s of scenarios) assert.ok(s.hits / s.runs >= 0.9, `${s.label}: ${(s.hits / s.runs).toFixed(3)}`)
})

test('gate (phase E): re-checked every night with no true effect, a question ever reads clear at most 5% of the time', () => {
  // 50 simulated histories; every 10th night from day 45 to day 405, on the last 365 days each time. Without the
  // sequential boundary 10% of such histories read clear at some point (most of them in the first weeks).
  let ever = 0
  const runs = 50
  for (let r = 0; r < runs; r++) {
    const d = simulated(70_000 + r * 7, 405, 0)
    for (let end = 45; end <= 405; end += 10) {
      const start = Math.max(0, end - 365)
      const f: Frame = { dates: d.dates.slice(start, end), series: { alcohol_any: d.alcohol.slice(start, end), hrv: d.hrv.slice(start, end) }, excluded: d.dates.slice(start, end).map(() => false) }
      if (analyseHypothesis(byId('alcohol_hrv'), f).state === 'clear') { ever++; break }
    }
  }
  assert.ok(ever / runs <= 0.05, `${ever} of ${runs} null histories read clear at some point`)
})

test('the verdict uses a wider, sequential interval while information is short; the 95% interval stays the estimate', () => {
  const short = analyseHypothesis(byId('alcohol_hrv'), { ...frame, dates: frame.dates.slice(0, 120), excluded: frame.excluded.slice(0, 120), series: Object.fromEntries(Object.entries(frame.series).map(([k, v]) => [k, v.slice(0, 120)])) })
  const v = short.verdictInterval!
  assert.ok(v.information < 1 && v.boundary > 2.24, JSON.stringify(v))
  assert.ok(v.lo < short.effect!.lo && v.hi > short.effect!.hi)
  const long = analyseHypothesis(byId('alcohol_hrv'), frame)
  assert.ok(long.verdictInterval!.boundary < v.boundary)
})

test('related questions are pooled: same exposure, favourable direction, probability beyond the zone', () => {
  const out = runCatalogue([byId('alcohol_hrv'), byId('alcohol_rhr'), byId('sleep_hrv')], frame)
  const hrv = out.find((o) => o.id === 'alcohol_hrv')!
  const rhr = out.find((o) => o.id === 'alcohol_rhr')!
  assert.equal(hrv.pooled?.family, 'alcohol_any')
  assert.equal(hrv.pooled?.members, 2)
  assert.equal(hrv.pooled?.unit, '%')
  assert.equal(rhr.pooled?.unit, 'bpm')
  assert.ok((hrv.pooled?.value as number) < 0 && (rhr.pooled?.value as number) > 0, 'signs stay in each outcome\'s own direction')
  assert.ok((hrv.pooled?.probabilityBeyondZone as number) > 0.9)
  assert.equal(out.find((o) => o.id === 'sleep_hrv')!.pooled, null) // alone in its family
})

test('planted effects read clear, with the right sign, size and interval', () => {
  const out = runCatalogue([byId('alcohol_hrv'), byId('alcohol_rhr'), byId('sleep_hrv'), breath], frame)
  const get = (id: string) => out.find((o) => o.id === id)!
  const alcohol = get('alcohol_hrv')
  assert.equal(alcohol.state, 'clear')
  assert.equal(alcohol.direction, 'negative')
  assert.equal(alcohol.rung, 'associated')
  // truth: -8 ms on a base of 62 ms is about -13 %
  assert.ok(alcohol.effect!.lo < -12.9 && alcohol.effect!.hi > -12.9, JSON.stringify(alcohol.effect))
  const sleep = get('sleep_hrv')
  assert.equal(sleep.state, 'clear')
  assert.ok(sleep.effect!.lo < 4.8 && sleep.effect!.hi > 4.8, JSON.stringify(sleep.effect)) // +3 ms/h is about +4.8 %/h
  const rhr = get('alcohol_rhr')
  assert.equal(rhr.state, 'clear')
  assert.equal(rhr.effect!.unit, 'bpm')
  assert.ok(rhr.effect!.lo < 3.2 && rhr.effect!.hi > 3.2, JSON.stringify(rhr.effect)) // +2 bpm plus 0.15 x 8 ms
  assert.ok(alcohol.eValue!.point > 1 && alcohol.eValue!.ci > 1)
  assert.equal(alcohol.negativeControl!.clear, false)
})

test('a null exposure is never clear, says what it could have detected, and shows the zone', () => {
  const [o] = runCatalogue([breath], frame)
  assert.ok(o.state === 'negligible' || o.state === 'unclear', o.state)
  assert.ok(o.mde! > 0)
  assert.equal(o.noEffect, 2)
  if (o.state === 'unclear') assert.ok(o.readiness && o.readiness.need > o.readiness.have)
})

test('a long null history settles as negligible, not as "no significant effect"', () => {
  const d = simulated(777, 720, 0)
  const f: Frame = { dates: d.dates, series: { alcohol_any: d.alcohol, hrv: d.hrv }, excluded: d.dates.map(() => false) }
  // A planted effect of zero with two years of mornings and a +-5% zone.
  const o = analyseHypothesis({ ...byId('alcohol_hrv'), noEffect: 5 }, f)
  assert.equal(o.state, 'negligible')
  assert.match(o.summary, /^No meaningful difference/)
})

test('the negative-control lag warns when the outcome already differed before the exposure', () => {
  // Drinking follows good mornings: the exposure depends on the state, and the state carries over.
  const rng = mulberry32(5)
  const dates = dateAxis('2026-01-05', '2026-12-31')
  let s = 0
  const hrv: number[] = []
  const alcohol: number[] = []
  for (let t = 0; t < dates.length; t++) {
    s = 0.8 * s + 0.07 * gaussian(rng)
    hrv.push(Math.exp(4.1 + s + 0.06 * gaussian(rng)))
    alcohol.push(rng() < (s > 0.05 ? 0.6 : 0.05) ? 1 : 0)
  }
  const o = analyseHypothesis(byId('alcohol_hrv'), { dates, series: { alcohol_any: alcohol, hrv }, excluded: dates.map(() => false) })
  assert.equal(o.negativeControl!.clear, true)
  assert.ok(o.notes.some((n) => n.startsWith('Warning: the same model finds a difference before the exposure')))
})

test('a fixed weekly schedule is not testable, instead of looking like an effect (the confounded training card)', () => {
  const [o] = runCatalogue([byId('training_hrv')], frame)
  assert.equal(o.state, 'not_yet')
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
  const [o] = runCatalogue([byId('alcohol_hrv')], f)
  assert.notEqual(o.state, 'clear')
})

test('too little data and one-sided exposure read NOT YET, with progress toward an answer', () => {
  const short: Frame = { dates: frame.dates.slice(0, 20), series: { alcohol_any: frame.series.alcohol_any.slice(0, 20), hrv: frame.series.hrv.slice(0, 20) }, excluded: new Array(20).fill(false) }
  const s = analyseHypothesis(byId('alcohol_hrv'), short)
  assert.equal(s.state, 'not_yet')
  assert.ok(s.readiness !== null)
  const never: Frame = { ...frame, series: { ...frame.series, alcohol_any: frame.dates.map(() => 0) } }
  const o = analyseHypothesis(byId('alcohol_hrv'), never)
  assert.equal(o.state, 'not_yet')
  assert.match(o.reason!, /0 days with it/)
  assert.equal(o.readiness!.unit, 'days with it')
  assert.equal(o.readiness!.have, 0)
  assert.ok(o.readiness!.need >= 6)
  assert.match(o.summary, /^Not yet: .* 0 of about \d+ days with it\.$/)
})

test('excluded (illness) days drop out of the analysis', () => {
  const withMask = analyseHypothesis(byId('sleep_hrv'), frame)
  const noMask = analyseHypothesis(byId('sleep_hrv'), { ...frame, excluded: frame.excluded.map(() => false) })
  assert.ok(withMask.n < noMask.n)
})

test('summaries follow the language contract: no cause verbs, no arrows', () => {
  const out = runCatalogue(HYPOTHESES, { ...frame, series: { ...frame.series, energy: frame.dates.map((_, i) => (i % 3 === 0 ? 6 : null)) } })
  for (const o of out) {
    assert.doesNotMatch(o.summary, /->|→|\blowers?\b|\braises?\b|\bcauses?\b|\bimproves?\b/i, o.summary)
    assert.doesNotMatch(o.label, /\blower\b|\braise\b|\bshorten\b/i, o.label)
  }
})

test('results are deterministic for the same inputs', () => {
  const a = runCatalogue(HYPOTHESES, frame)
  const b = runCatalogue(HYPOTHESES, frame)
  assert.equal(JSON.stringify(a), JSON.stringify(b))
  assert.equal(a.length, HYPOTHESES.length)
  assert.ok(a.every((o) => o.inputsHash.length === 8))
})

test('block permutation p is small for a real association and large for none (stats.ts, still used elsewhere)', () => {
  const rng = mulberry32(3)
  const cause: Num[] = Array.from({ length: 140 }, () => (rng() < 0.3 ? 1 : 0))
  const real: Num[] = cause.map((c, i) => (i > 0 ? 3 * (cause[i - 1] as number) : 0) + gaussian(rng))
  const none: Num[] = cause.map(() => gaussian(rng))
  assert.ok(blockPermutationP(cause, real, 1, mulberry32(1), pearson, 1000) < 0.01)
  assert.ok(blockPermutationP(cause, none, 1, mulberry32(1), pearson, 1000) > 0.05)
})
