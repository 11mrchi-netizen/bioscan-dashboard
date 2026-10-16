// Analysis review phase D: event curves, case-crossover and lag curves on synthetic data with known truth.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  analyseCaseCrossover, analyseEventCurve, analyseLagCurve, bandModel, EVENT_CURVES, LAG_CURVES, mantelHaenszel,
} from './evidence.ts'
import type { Frame } from './relationship.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'
import type { Num } from './stats.ts'

const WEEKDAY = [0.0, 0.01, 0.02, 0.0, -0.01, -0.03, 0.01] // Sunday first

interface WorldOptions {
  /** Effect of an evening with alcohol on log HRV k days later (index 0 = the next morning). */
  alcohol?: number[]
  /** Effect of an hour of training on log HRV l days later (index 0 = the same morning). */
  training?: number[]
  /** Effect of each drink on next-morning log HRV. */
  perDrink?: number
}

// Log HRV: an AR(1) state, measurement noise, a weekday pattern; resting HR and sleep around it; 10% of mornings missing.
function world(seed: number, n: number, o: WorldOptions = {}): Frame {
  const rng = mulberry32(seed)
  const dates = dateAxis('2026-01-05', '2027-12-31').slice(0, n)
  const alcohol: number[] = []
  const drinks: number[] = []
  const trainingMin: number[] = []
  const stress: number[] = []
  const hrv: Num[] = []
  const rhr: Num[] = []
  const sleep: Num[] = []
  let s = 0
  for (let t = 0; t < n; t++) {
    const dow = new Date(dates[t] + 'T00:00:00Z').getUTCDay()
    s = 0.6 * s + 0.07 * gaussian(rng)
    const drink = rng() < (dow === 5 || dow === 6 ? 0.5 : 0.08) ? 1 : 0
    alcohol.push(drink)
    drinks.push(drink ? 1 + Math.floor(rng() * 4) : 0)
    trainingMin.push(rng() < 0.5 ? 30 + Math.round(rng() * 60) : 0)
    stress.push(rng() < 0.2 ? 1 : 0)
    let eff = 0
    ;(o.alcohol ?? []).forEach((b, k) => { if (t - 1 - k >= 0) eff += b * alcohol[t - 1 - k] })
    ;(o.training ?? []).forEach((b, l) => { if (t - l >= 0) eff += (b * trainingMin[t - l]) / 60 })
    if (o.perDrink && t > 0) eff += o.perDrink * drinks[t - 1]
    hrv.push(Math.exp(4.1 + s + 0.05 * gaussian(rng) + WEEKDAY[dow] + eff))
    rhr.push(55 - 20 * s + 1.2 * gaussian(rng))
    sleep.push(7.3 + 0.6 * gaussian(rng))
  }
  const miss = (xs: Num[]) => xs.map((v) => (rng() < 0.1 ? null : v))
  return {
    dates,
    series: {
      hrv: miss(hrv), rhr: miss(rhr), sleep_hours: miss(sleep), alcohol_any: alcohol, alcohol_drinks: drinks, training_min: trainingMin,
      stress_high: stress, steps: dates.map(() => 6000 + 3000 * rng()),
    },
    excluded: dates.map(() => false),
  }
}

const alcoholHrv = EVENT_CURVES.find((c) => c.id === 'alcohol_hrv')!

test('event curve: a planted next-morning dip shows up, leaves the chance band, and recovers', () => {
  const f = world(3, 300, { alcohol: [-0.12, -0.05] })
  const r = analyseEventCurve(alcoholHrv, f, bandModel(f, 'hrv')!)
  assert.equal(r.state, 'ready')
  assert.ok(r.events >= 40, `${r.events} events`)
  assert.equal(r.days.length, 9)
  assert.deepEqual(r.days.map((d) => d.k), [-3, -2, -1, 0, 1, 2, 3, 4, 5])
  assert.equal(r.peak?.k, 1)
  assert.ok((r.peak?.value as number) < -7 && (r.peak?.value as number) > -16, `peak ${r.peak?.value} (planted -11%)`)
  assert.ok(r.backByDay !== null && r.backByDay <= 3, `back by day ${r.backByDay}`)
  assert.equal(r.differentBefore, false)
  assert.equal(r.unit, '%')
  assert.match(r.summary, /^After evenings with alcohol, HRV ran \d+% lower than after comparable days without the next morning, back within the chance band by day \d \(\d+ events\)\.$/)
})

test('event curve: with no effect the whole curve stays inside the simultaneous chance band most of the time', () => {
  let flagged = 0
  const runs = 60
  for (let i = 0; i < runs; i++) {
    const f = world(100 + i, 240)
    const r = analyseEventCurve(alcoholHrv, f, bandModel(f, 'hrv')!)
    if (r.peak !== null || r.differentBefore) flagged++
  }
  assert.ok(flagged / runs <= 0.1, `${flagged} of ${runs} null curves left the band`)
})

test('event curve: fewer than eight events is not drawn', () => {
  const f = world(4, 300)
  const few = { ...f, series: { ...f.series, alcohol_any: f.series.alcohol_any.map((v, i) => (i < 260 ? 0 : v)) } }
  const r = analyseEventCurve(alcoholHrv, few, bandModel(few, 'hrv')!)
  assert.equal(r.state, 'not_yet')
  assert.ok(r.events < 8)
  assert.equal(r.days.length, 0)
  assert.match(r.summary, /^Not yet: \d of 8 evenings with alcohol/)
})

test('Mantel-Haenszel over matched sets matches a hand calculation', () => {
  // (1 x 2 / 4 + 1 x 3 / 4 + 0 + 1 x 1 / 4) / (0 + 0 + 1 x 1 / 4 + 0) = 1.5 / 0.25 = 6
  const r = mantelHaenszel([
    { caseExposed: 1, refs: [0, 0, 1] },
    { caseExposed: 1, refs: [0, 0, 0] },
    { caseExposed: 0, refs: [1, 0, 0] },
    { caseExposed: 1, refs: [1, 1, 0] },
  ])!
  assert.ok(Math.abs(r.or - 6) < 1e-12)
  assert.ok(r.lo < 6 && r.hi > 6)
  assert.equal(mantelHaenszel([{ caseExposed: 1, refs: [0, 0] }]), null)
})

test('case-crossover: alcohol comes before low mornings more often; an unrelated exposure does not', () => {
  const f = world(7, 365, { alcohol: [-0.15] })
  const r = analyseCaseCrossover(f, bandModel(f, 'hrv')!, 'low', 'Mornings with HRV below its expected range')
  assert.ok(r.caseDays >= 30, `${r.caseDays} low mornings`)
  assert.equal(r.exploratory, true)
  const row = (e: string) => r.rows.find((x) => x.exposure === e)!
  assert.equal(row('alcohol_any').verdict, 'more_often')
  assert.ok((row('alcohol_any').or as number) > 2)
  assert.notEqual(row('stress_high').verdict, 'more_often')
  assert.equal(row('late_meal').verdict, 'not_yet') // never recorded in this world
})

test('lag curve: a planted training effect is located at the right lag and fades', () => {
  const f = world(11, 365, { training: [0, -0.06, -0.03] })
  const spec = LAG_CURVES.find((c) => c.id === 'training_hrv')!
  const r = analyseLagCurve(spec, f, bandModel(f, 'hrv')!)
  assert.equal(r.state, 'ready')
  assert.equal(r.lags.length, 7)
  const lag1 = r.lags[1]
  assert.ok(lag1.hi < 0, `lag 1 ${JSON.stringify(lag1)}`)
  assert.ok(lag1.lo < -5.82 && lag1.hi > -5.82, `lag 1 should cover the planted -5.8%: ${JSON.stringify(lag1)}`)
  assert.ok(r.lastClearLag !== null && r.lastClearLag >= 1 && r.lastClearLag <= 2, `last clear lag ${r.lastClearLag}`)
  // A lone chance hit later in the week is listed, but not read as the effect lasting that long.
  assert.ok(r.clearLags.includes(1))
  assert.ok(r.cumulative !== null)
  assert.match(r.summary, /^The next morning HRV differed by -\d+\.\d% per hour of training/)
})

test('lag curve: not yet without a full week of exposure behind enough days; dose curve orders by drinks', () => {
  const short = world(12, 100)
  const spec = LAG_CURVES.find((c) => c.id === 'training_hrv')!
  assert.equal(analyseLagCurve(spec, short, bandModel(short, 'hrv')!).state, 'not_yet')
  const f = world(13, 365, { perDrink: -0.04 })
  const alcohol = LAG_CURVES.find((c) => c.id === 'alcohol_hrv')!
  const r = analyseLagCurve(alcohol, f, bandModel(f, 'hrv')!)
  assert.ok(r.dose !== null)
  const g = (name: string) => r.dose!.find((d) => d.group === name)!
  assert.ok(g('3+').mean < g('1-2').mean && g('1-2').mean < g('0').mean, JSON.stringify(r.dose))
})

test('evidence engines are deterministic', () => {
  const f = world(21, 240, { alcohol: [-0.1] })
  const m = bandModel(f, 'hrv')!
  assert.equal(JSON.stringify(analyseEventCurve(alcoholHrv, f, m)), JSON.stringify(analyseEventCurve(alcoholHrv, f, bandModel(f, 'hrv')!)))
})
