// Synthetic daily signals with known truth for the typicality engine. SYNTHETIC ONLY (this repository is public).
import { dateAxis, gaussian, mulberry32 } from './stats.ts'

export interface SignalFrame {
  dates: string[]
  hrv: number[]
  rhr: number[]
  sleepHours: number[]
  deepRemMin: number[]
  steps: number[]
  /** True on planted illness days. */
  ill: boolean[]
}

// Five correlated daily signals: a shared slowly varying "recovery" factor drives HRV up and resting heart rate down, sleep
// and deep + REM up; steps are independent. Planted illness days raise resting heart rate by ~8 bpm, cut HRV by ~30 %, add
// sleep, cut deep + REM and halve steps.
export function typicalitySeries(seed = 31, days = 200, start = '2026-01-05', illness: { start: number; length: number }[] = []): SignalFrame {
  const rng = mulberry32(seed)
  const dates = dateAxis(start, new Date(new Date(start + 'T00:00:00Z').getTime() + (days - 1) * 86400000).toISOString().slice(0, 10))
  const out: SignalFrame = { dates, hrv: [], rhr: [], sleepHours: [], deepRemMin: [], steps: [], ill: [] }
  let f = 0
  for (let t = 0; t < days; t++) {
    f = 0.6 * f + 0.8 * gaussian(rng)
    const ill = illness.some((w) => t >= w.start && t < w.start + w.length)
    let hrv = Math.exp(4.1 + 0.1 * f + 0.08 * gaussian(rng))
    let rhr = 55 - 1.8 * f + 1.3 * gaussian(rng)
    let sleep = 7.4 + 0.45 * f + 0.6 * gaussian(rng)
    let deepRem = 160 + 18 * f + 22 * gaussian(rng)
    let steps = Math.max(500, 9000 + 2500 * gaussian(rng))
    if (ill) {
      hrv *= 0.7
      rhr += 8
      sleep += 1
      deepRem -= 30
      steps *= 0.5
    }
    out.hrv.push(hrv)
    out.rhr.push(rhr)
    out.sleepHours.push(sleep)
    out.deepRemMin.push(deepRem)
    out.steps.push(steps)
    out.ill.push(ill)
  }
  return out
}
