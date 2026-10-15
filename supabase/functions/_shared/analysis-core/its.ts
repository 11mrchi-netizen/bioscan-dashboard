// Analysis review phase F (P8, last part): an intervention read as an interrupted time series. Erasable TypeScript, no I/O.
//
// The before-and-after comparison (interventionResponse.ts) compares two windows chosen as the data came in and is read
// every night. Here the counterfactual is fixed in advance: a level and weekday pattern fitted on a fixed pre-period
// (HRV on a log scale, as in the expected band) forecasts each day after the start; the estimate is the average gap
// between what happened and that forecast, read once, on a date fixed when the intervention started. Its interval uses
// the long-run variance of the pre-period's day-to-day noise (AR(1), bias-corrected), both for
// the forecast's level and for the gaps, so a few weeks of neighbouring days are not counted as independent.
//
// The expected band's moving level was tried as the counterfactual and rejected: fitted for next-morning forecasts it
// treats the level as a random walk, so four weeks ahead its interval was too wide to read anything (a planted +16% step
// came out as -5% to +54%). The fixed level assumes the pre-period would have stayed level; when the pre-period was
// already drifting, the result says so.
//
// It stays on the OBSERVED rung -- what happened against what was expected -- and never drives a protocol change on its
// own (protocolRules.ts).
import { mean, sd, tCritical, weekdayOf } from './stats.ts'
import type { Num } from './stats.ts'

export type ItsState = 'collecting' | 'evaluated' | 'insufficient'
export type ItsVerdict = 'clear' | 'negligible' | 'unclear'

export interface ItsInput {
  metric: string
  label: string
  /** Natural units. */
  dates: string[]
  values: Num[]
  excluded: boolean[]
  ln: boolean
  /** Display unit of the gap: % for log metrics, else the metric's unit (minutes for sleep hours, via scale). */
  unit: string
  scale?: number
  preStart: string
  activeStart: string
  evaluateOn: string
  today: string
  /** No-effect zone in the display unit. */
  zone: number
  /** Confirmed series breaks of this metric (a new device or source), as the first day of the new regime. */
  breaks?: string[]
}

export interface ItsResult {
  metric: string
  label: string
  state: ItsState
  rung: 'observed'
  evaluateOn: string
  preDays: number
  activeDays: number
  gap: { value: number; lo: number; hi: number; unit: string } | null
  verdict: ItsVerdict | null
  zone: number
  /** Each active day with its forecast, for the chart (natural units). */
  days: { date: string; value: number | null; forecast: number; lo80: number; hi80: number }[]
  summary: string
}

export const ITS = { minPreDays: 28, minActiveDays: 14 }
const r = (v: number, d = 2) => Math.round(v * 10 ** d) / 10 ** d

export function analyseIts(input: ItsInput): ItsResult {
  const { dates, values, excluded, ln, unit, activeStart, evaluateOn, today, zone } = input
  const scale = input.scale ?? 1
  const toModel = (v: Num): Num => (v === null || !Number.isFinite(v) ? null : ln ? (v > 0 ? Math.log(v) : null) : v)
  const base = { metric: input.metric, label: input.label, rung: 'observed' as const, evaluateOn, zone, days: [] as ItsResult['days'] }
  const activeEnd = evaluateOn < today ? evaluateOn : today
  // A new device or source before the start: forecast only from the days after it. On or after the start: the change
  // and the intervention can't be told apart, so there is nothing to read.
  const breaks = [...(input.breaks ?? [])].sort()
  const before = breaks.filter((b) => b > input.preStart && b < activeStart)
  const preStart = before.length > 0 ? before[before.length - 1] : input.preStart
  const during = breaks.find((b) => b >= activeStart && b <= activeEnd)
  const pre = dates.map((d, i) => (d >= preStart && d < activeStart && !excluded[i] ? toModel(values[i]) : null))
  const preDays = pre.filter((v) => v !== null).length
  // Read the night after the evaluation date, so its own morning has had time to arrive.
  if (today <= evaluateOn) {
    return { ...base, state: 'collecting', preDays, activeDays: 0, gap: null, verdict: null, summary: `Collecting through ${evaluateOn}, then read once against the forecast from the days before the start.` }
  }
  if (during) {
    return { ...base, state: 'insufficient', preDays, activeDays: 0, gap: null, verdict: null, summary: `The ${input.label} source changed on ${during}, after the start: the change and the intervention can't be told apart.` }
  }
  if (preDays < ITS.minPreDays) {
    return { ...base, state: 'insufficient', preDays, activeDays: 0, gap: null, verdict: null, summary: `Not enough days before the start to forecast from: ${preDays} of ${ITS.minPreDays}.` }
  }
  // The pre-period's weekday pattern and level.
  const sums = [0, 0, 0, 0, 0, 0, 0]
  const counts = [0, 0, 0, 0, 0, 0, 0]
  pre.forEach((v, i) => {
    if (v === null) return
    sums[weekdayOf(dates[i])] += v
    counts[weekdayOf(dates[i])]++
  })
  const level = mean(pre.filter((v): v is number => v !== null))
  const pattern = sums.map((x, w) => (counts[w] > 0 ? x / counts[w] - level : 0))
  const centre = pattern.reduce((a, b) => a + b, 0) / 7
  const forecastAt = (i: number) => level + pattern[weekdayOf(dates[i])] - centre
  const resid = pre.map((v, i) => (v === null ? null : v - forecastAt(i)))
  const present = resid.filter((v): v is number => v !== null)
  const sigma = sd(present)
  // Long-run variance factor of the residuals, from their lag-one autocorrelation as an AR(1): (1 + rho) / (1 - rho).
  // rho is measured on the day pairs that exist (missing days thin them) and corrected for its small-sample bias
  // (about (1 + 3 rho) / n low). Summing sample autocorrelations over one or two weeks was tried and left intervals 10-15%
  // too narrow on a few weeks of data, because long-lag sample autocorrelations are biased low.
  let num = 0
  let pairs = 0
  for (let i = 1; i < resid.length; i++) {
    if (resid[i] !== null && resid[i - 1] !== null) {
      num += (resid[i] as number) * (resid[i - 1] as number)
      pairs++
    }
  }
  const variance = present.reduce((a, v) => a + v * v, 0) / present.length
  const r1 = pairs > 0 && variance > 0 ? num / pairs / variance : 0
  const rho = Math.max(0, Math.min(0.9, r1 + (1 + 3 * r1) / present.length))
  const lrv = (1 + rho) / (1 - rho)

  const active = dates.map((d, i) => (d >= activeStart && d <= activeEnd ? i : -1)).filter((i) => i >= 0)
  const back = (v: number) => (ln ? Math.exp(v) : v)
  const dayHalf = 1.2816 * sigma * Math.sqrt(1 + lrv / preDays)
  const days: ItsResult['days'] = active.map((i) => {
    const f = forecastAt(i)
    return { date: dates[i], value: values[i] === null ? null : r(values[i] as number), forecast: r(back(f)), lo80: r(back(f - dayHalf)), hi80: r(back(f + dayHalf)) }
  })
  const gaps = active.filter((i) => !excluded[i] && toModel(values[i]) !== null).map((i) => (toModel(values[i]) as number) - forecastAt(i))
  const activeDays = gaps.length
  if (activeDays < ITS.minActiveDays) {
    return { ...base, days, state: 'insufficient', preDays, activeDays, gap: null, verdict: null, summary: `Too few days since the start with a reading: ${activeDays} of ${ITS.minActiveDays}.` }
  }
  const est = mean(gaps)
  const half = tCritical(Math.max(2, Math.round(Math.min(activeDays, preDays) / lrv))) * sigma * Math.sqrt(lrv / activeDays + lrv / preDays)
  const toDisplay = (v: number) => (ln ? (Math.exp(v) - 1) * 100 : v * scale)
  const gap = { value: r(toDisplay(est)), lo: r(toDisplay(est - half)), hi: r(toDisplay(est + half)), unit }
  const verdict: ItsVerdict = gap.lo > zone || gap.hi < -zone ? 'clear' : gap.lo >= -zone && gap.hi <= zone ? 'negligible' : 'unclear'
  // Was the pre-period drifting? Compare its two halves.
  const preIdx = pre.map((v, i) => (v === null ? -1 : i)).filter((i) => i >= 0)
  const halfN = Math.floor(preIdx.length / 2)
  const drift = mean(preIdx.slice(halfN).map((i) => resid[i] as number)) - mean(preIdx.slice(0, halfN).map((i) => resid[i] as number))
  const drifting = Math.abs(drift) > 2.5 * sigma * Math.sqrt((2 * lrv) / Math.max(1, halfN))
  const fmt = (v: number) => (unit === '%' ? `${v >= 0 ? '+' : '-'}${Math.abs(v).toFixed(1)}%` : `${v >= 0 ? '+' : '-'}${Math.abs(v).toFixed(unit === 'min' ? 0 : 1)} ${unit}`)
  const zoneText = `±${zone}${unit === '%' ? '%' : ' ' + unit}`
  const what = `${input.label} ran ${fmt(gap.value)} against the forecast from before the start (95% interval ${fmt(gap.lo)} to ${fmt(gap.hi)}; ${activeDays} days)`
  let summary = verdict === 'clear'
    ? `Observed: ${what}, outside the ${zoneText} zone. What happened, not why: other changes at the same time count too.`
    : verdict === 'negligible'
      ? `Observed: ${what}, inside the ${zoneText} zone: no meaningful difference from the forecast.`
      : `Observed: ${what}; unclear against the ${zoneText} zone.`
  if (drifting) summary += ' The days before the start were already drifting, and the forecast assumes they would have stayed level.'
  return { ...base, days, state: 'evaluated', preDays, activeDays, gap, verdict, summary }
}
