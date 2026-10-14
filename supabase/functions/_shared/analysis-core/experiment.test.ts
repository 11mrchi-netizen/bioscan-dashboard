// Backtest gate for the N-of-1 experiment engine (docs/analysis-layer-3). Synthetic data with known truth only.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { analyseExperiment, daysPerPhaseFor, dayLabels, previewMde } from './experiment.ts'
import type { ExperimentSpec, Protocol } from './experiment.ts'
import type { Frame } from './relationship.ts'
import { dateAxis, gaussian, mulberry32, pFromT, tCritical } from './stats.ts'
import type { Num } from './stats.ts'

const START = '2026-01-05'
const shiftDate = (d: string, n: number) => new Date(Date.parse(d + 'T00:00:00Z') + n * 86400000).toISOString().slice(0, 10)

// Daily HRV (ms) = exp(4.1 + AR(1) noise with sd ~0.1 + effect * inB), plus a few other series. Phases are given as
// [label, firstDayIndex, lastDayIndex] on the day axis starting at START.
function build(seed: number, days: number, phases: [('A' | 'B'), number, number][], effect: number, extra: { trendPerDay?: number; effectByPhase?: number[] } = {}) {
  const rng = mulberry32(seed)
  const dates = dateAxis(START, shiftDate(START, days - 1))
  let e = 0
  const hrv: Num[] = []
  const rhr: Num[] = []
  const sleep: Num[] = []
  const energy: Num[] = []
  let bIndex = -1
  const inB = dates.map((_, t) => {
    const k = phases.findIndex(([l, a, b]) => l === 'B' && t >= a && t <= b)
    return k
  })
  void bIndex
  for (let t = 0; t < days; t++) {
    e = 0.5 * e + 0.0866 * gaussian(rng)
    const k = inB[t]
    const eff = k >= 0 ? (extra.effectByPhase ? extra.effectByPhase[k] ?? effect : effect) : 0
    hrv.push(Math.exp(4.1 + e + eff + (extra.trendPerDay ?? 0) * t))
    rhr.push(55 + gaussian(rng))
    sleep.push(7.4 + 0.5 * gaussian(rng))
    energy.push(Math.max(0, Math.min(10, Math.round(6 + gaussian(rng)))))
  }
  const frame: Frame = {
    dates,
    series: { hrv, rhr, sleep_hours: sleep, energy, alcohol_any: dates.map(() => 0) },
    excluded: dates.map(() => false),
  }
  const protocol: Protocol = {
    design: phases.length > 2 ? 'ABAB' : 'AB',
    phases: phases.map(([label, a, b]) => ({ label, start: shiftDate(START, a), end: shiftDate(START, b) })),
    washoutDays: 0,
    minAdherence: 0.7,
    excludeAlcoholDays: true,
  }
  return { frame, protocol, dates }
}

const spec = (protocol: Protocol, secondary: ExperimentSpec['secondary'] = []): ExperimentSpec => ({ id: 'e1', name: 'Magnesium', primary: 'hrv', secondary, protocol })
const AB28: [('A' | 'B'), number, number][] = [['A', 0, 27], ['B', 28, 55]]
const AFTER = shiftDate(START, 70)

test('t helpers: critical value and p agree with textbook values', () => {
  assert.ok(Math.abs(tCritical(10) - 2.228) < 0.002)
  assert.ok(Math.abs(tCritical(30) - 2.042) < 0.002)
  assert.ok(Math.abs(pFromT(2.228, 10) - 0.05) < 0.002)
  assert.ok(Math.abs(pFromT(0, 12) - 1) < 1e-9)
})

test('dayLabels: phases, washout and gaps', () => {
  const { dates, protocol } = build(1, 60, AB28, 0)
  const withWash = { ...protocol, washoutDays: 5 }
  const labels = dayLabels(dates, withWash)
  assert.equal(labels.filter((l) => l === 'A').length, 28)
  assert.equal(labels.filter((l) => l === 'B').length, 23)
  assert.equal(labels[56], null)
})

test('a large planted effect is detected, with the right size, sign and a sensible interval', () => {
  const { frame, protocol } = build(11, 70, AB28, 0.22) // +22 %
  const res = analyseExperiment(spec(protocol), frame, new Set(), AFTER)
  const m = res.metrics[0]
  assert.equal(res.stage, 'complete')
  assert.equal(m.verdict, 'effect_detected')
  assert.ok(m.diff!.value > 0 && m.diff!.lo < 24.6 && m.diff!.hi > 24.6, JSON.stringify(m.diff)) // exp(0.22) - 1 = 24.6 %
  assert.equal(m.n0, 28)
  assert.equal(m.n1, 28)
  assert.match(m.summary, /with the supplement/)
})

test('a null control (vitamin D) is called "no detectable effect" and says what the design could see', () => {
  const { frame, protocol } = build(12, 70, AB28, 0)
  const m = analyseExperiment(spec(protocol), frame, new Set(), AFTER).metrics[0]
  assert.equal(m.verdict, 'no_detectable_effect')
  assert.match(m.summary, /^No detectable effect/)
  assert.ok(m.mde! > 0)
  assert.doesNotMatch(m.summary, /improv|boost|work/i)
})

test('false positives stay near 5 % on nulls and the interval covers the truth', () => {
  let fp = 0
  let covered = 0
  const sims = 200
  for (let s = 0; s < sims; s++) {
    const a = analyseExperiment(spec(build(2000 + s, 70, AB28, 0).protocol), build(2000 + s, 70, AB28, 0).frame, new Set(), AFTER).metrics[0]
    if (a.verdict === 'effect_detected') fp++
    const b = build(3000 + s, 70, AB28, 0.15)
    const m = analyseExperiment(spec(b.protocol), b.frame, new Set(), AFTER).metrics[0]
    if (m.diff!.lo <= 16.2 && 16.2 <= m.diff!.hi) covered++ // exp(0.15) - 1 = 16.2 %
  }
  assert.ok(fp / sims <= 0.1, `false-positive rate ${fp / sims}`)
  assert.ok(covered / sims >= 0.88, `interval coverage ${covered / sims}`)
})

test('the reported smallest detectable effect matches simulated power (about 80 %)', () => {
  const mdes: number[] = []
  for (let s = 0; s < 40; s++) {
    const b = build(4000 + s, 70, AB28, 0)
    mdes.push(analyseExperiment(spec(b.protocol), b.frame, new Set(), AFTER).metrics[0].mde!)
  }
  mdes.sort((x, y) => x - y)
  const medianMde = mdes[20] // percent
  const effect = Math.log(1 + medianMde / 100)
  let detected = 0
  const sims = 200
  for (let s = 0; s < sims; s++) {
    const b = build(5000 + s, 70, AB28, effect)
    if (analyseExperiment(spec(b.protocol), b.frame, new Set(), AFTER).metrics[0].verdict === 'effect_detected') detected++
  }
  const power = detected / sims
  assert.ok(power >= 0.65 && power <= 0.95, `power at the stated MDE ${power}`)
})

test('too few usable days: not enough data; mid-experiment: interim, no verdict', () => {
  const short = build(21, 30, [['A', 0, 6], ['B', 7, 13]], 0.3)
  assert.equal(analyseExperiment(spec(short.protocol), short.frame, new Set(), shiftDate(START, 29)).metrics[0].verdict, 'insufficient')
  const { frame, protocol } = build(22, 70, AB28, 0.3)
  const mid = analyseExperiment(spec(protocol), frame, new Set(), shiftDate(START, 45))
  assert.equal(mid.stage, 'running')
  assert.equal(mid.metrics[0].verdict, 'running')
  assert.match(mid.metrics[0].summary, /^Interim, not a result/)
})

test('adherence comes from the real log; low adherence and with-phase contamination are noted, not assumed away', () => {
  const { frame, protocol, dates } = build(31, 70, AB28, 0.2)
  const taken = new Set<string>()
  for (let t = 28; t <= 55; t += 2) taken.add(dates[t]) // half of the B days
  taken.add(dates[3]) // one slip in the A phase
  const res = analyseExperiment(spec(protocol), frame, taken, AFTER)
  assert.equal(res.adherence.interventionDays, 28)
  assert.equal(res.adherence.interventionLogged, 14)
  assert.equal(res.adherence.rate, 0.5)
  assert.equal(res.adherence.ok, false)
  assert.equal(res.adherence.baselineLogged, 1)
  assert.ok(res.metrics[0].notes.some((n) => n.includes('50%')))
  assert.ok(res.metrics[0].notes.some((n) => n.includes('without-phase')))
})

test('illness, alcohol (and the next morning) and washout days are left out', () => {
  const { frame, protocol } = build(41, 70, AB28, 0.2)
  const plain = analyseExperiment(spec(protocol), frame, new Set(), AFTER).metrics[0]
  const ill = { ...frame, excluded: frame.dates.map((_, i) => i >= 10 && i < 13) }
  assert.equal(analyseExperiment(spec(protocol), ill, new Set(), AFTER).metrics[0].n0, plain.n0 - 3)
  const drink = { ...frame, series: { ...frame.series, alcohol_any: frame.dates.map((_, i) => (i === 40 ? 1 : 0)) } }
  assert.equal(analyseExperiment(spec(protocol), drink, new Set(), AFTER).metrics[0].n1, plain.n1 - 2) // the day and the morning after
  const noRule = { ...protocol, excludeAlcoholDays: false }
  assert.equal(analyseExperiment(spec(noRule), drink, new Set(), AFTER).metrics[0].n1, plain.n1)
  const wash = { ...protocol, washoutDays: 7 }
  assert.equal(analyseExperiment(spec(wash), frame, new Set(), AFTER).metrics[0].n1, plain.n1 - 7)
})

test('a drifting baseline is flagged, because a step in a drifting series looks like an effect', () => {
  const { frame, protocol } = build(51, 70, AB28, 0, { trendPerDay: 0.006 }) // +16 % over the 28 A days, no real effect
  const m = analyseExperiment(spec(protocol), frame, new Set(), AFTER).metrics[0]
  assert.ok(m.diff!.value > 0)
  assert.equal(m.trendWarning, true)
  assert.ok(m.notes.some((n) => n.includes('drifting')))
})

test('ABAB: a replicated effect is marked replicated, a one-cycle effect is not', () => {
  const phases: [('A' | 'B'), number, number][] = [['A', 0, 20], ['B', 21, 41], ['A', 42, 62], ['B', 63, 83]]
  const today = shiftDate(START, 100)
  const both = build(61, 90, phases, 0.25)
  assert.equal(analyseExperiment(spec(both.protocol), both.frame, new Set(), today).metrics[0].replicated, true)
  const once = build(62, 90, phases, 0.25, { effectByPhase: [0.3, -0.3] })
  const m = analyseExperiment(spec(once.protocol), once.frame, new Set(), today).metrics[0]
  assert.equal(m.replicated, false)
  assert.ok(m.notes.some((n) => n.includes('disagree')))
})

test('secondary outcomes are corrected together; the registered primary is not', () => {
  const { frame, protocol } = build(71, 70, AB28, 0.22)
  const res = analyseExperiment(spec(protocol, ['rhr', 'sleep_hours', 'energy']), frame, new Set(), AFTER)
  assert.equal(res.metrics[0].verdict, 'effect_detected')
  for (const m of res.metrics.slice(1)) {
    assert.notEqual(m.verdict, 'effect_detected')
    assert.ok(m.pAdjusted !== null && m.pAdjusted >= (m.p as number))
  }
})

test('the pre-registration preview states a detectable size and the days needed are consistent with it', () => {
  const { frame } = build(81, 140, AB28, 0)
  const [hrv] = previewMde(frame, ['hrv'], 28, 28)
  assert.equal(hrv.metric, 'hrv')
  assert.ok(hrv.mde > 5 && hrv.mde < 30, `HRV mde ${hrv.mde}`)
  assert.ok(Math.abs(daysPerPhaseFor(hrv, hrv.mde) - 28) <= 1)
  assert.ok(daysPerPhaseFor(hrv, hrv.mde / 2) > 80) // half the effect needs about four times the days
  assert.equal(previewMde(frame, ['hrv'], 28, 28, ['hrv'])[0].unsettled, true)
  assert.equal(previewMde(frame, ['hrv'], 28, 28, ['rhr'])[0].unsettled, false)
})
