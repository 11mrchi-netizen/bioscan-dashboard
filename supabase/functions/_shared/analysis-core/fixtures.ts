// Deterministic synthetic fixtures with known truth, used as positive/negative controls for every engine.
// SYNTHETIC ONLY: this repo is public, so real health data never goes in fixtures (real-history replays stay private).
import { dateAxis, gaussian, mulberry32 } from './stats.ts'
import type { Num } from './stats.ts'

export interface PlantedDataset {
  dates: string[]
  sleepH: number[]
  hrv: number[]
  rhr: number[]
  train: number[] // 1 on training days (Mon/Wed/Fri/Sat) - a calendar confounder by design
  alcoholG: number[]
  sauna: number[]
  breath: number[] // null-effect recovery control
  mag: number[] // 1 from magStart (planted +3 ms HRV)
  vitd: number[] // 1 from vitdStart (null control)
  ill: number[]
  truth: { alcoholPrevEveningHrv: number; sleepHrvPerHour: number; saunaPrevDayHrv: number; magHrv: number; illnessHrv: number }
}

// Planted model (same structure as the competitor-teardown seed): alcohol the previous evening -8 ms HRV,
// +3 ms per extra sleep hour, +4 ms the day after sauna, +3 ms from magnesium start, -22 ms on illness days;
// training, vitamin D and breathwork have no effect. Training falls on drinking weekends, so it is confounded.
export function plantedDataset(seed = 20261010, days = 61, start = '2026-08-11', magStart = 30, vitdStart = 44, illDays = [40, 52]): PlantedDataset {
  const rng = mulberry32(seed)
  const dates = dateAxis(start, new Date(new Date(start + 'T00:00:00Z').getTime() + (days - 1) * 86400000).toISOString().slice(0, 10))
  const truth = { alcoholPrevEveningHrv: -8, sleepHrvPerHour: 3, saunaPrevDayHrv: 4, magHrv: 3, illnessHrv: -22 }
  const out: PlantedDataset = { dates, sleepH: [], hrv: [], rhr: [], train: [], alcoholG: [], sauna: [], breath: [], mag: [], vitd: [], ill: [], truth }
  let ar = 0
  for (let t = 0; t < days; t++) {
    const dow = new Date(dates[t] + 'T00:00:00Z').getUTCDay() // 0 Sun
    const trainDay = dow === 1 || dow === 3 || dow === 5 || dow === 6 ? 1 : 0
    const weekend = dow === 5 || dow === 6
    const drink = rng() < (weekend ? 0.65 : 0.07) ? 1 : 0
    const alcoholG = drink ? Math.round(10 + rng() * 30) : 0
    const saunaDay = (dow === 2 || dow === 0) && rng() < 0.7 ? 1 : 0
    const breath = t % 5 === 1 ? 1 : 0
    const mag = t >= magStart ? 1 : 0
    const vitd = t >= vitdStart ? 1 : 0
    const ill = illDays.includes(t) ? 1 : 0
    const sleepH = Math.max(4.5, 7.4 + 0.8 * gaussian(rng))
    ar = 0.5 * ar + 3.5 * gaussian(rng)
    const prevAlc = t > 0 && out.alcoholG[t - 1] > 0 ? 1 : 0
    const prevSauna = t > 0 ? out.sauna[t - 1] : 0
    const hrv = 62 + truth.sleepHrvPerHour * (sleepH - 7.4) + truth.alcoholPrevEveningHrv * prevAlc + truth.saunaPrevDayHrv * prevSauna + truth.magHrv * mag + ar + truth.illnessHrv * ill
    const rhr = 55 - 0.15 * (hrv - 62) + 2 * prevAlc + gaussian(rng) * 1.2 + 9 * ill
    out.sleepH.push(sleepH); out.hrv.push(hrv); out.rhr.push(rhr); out.train.push(trainDay)
    out.alcoholG.push(alcoholG); out.sauna.push(saunaDay); out.breath.push(breath)
    out.mag.push(mag); out.vitd.push(vitd); out.ill.push(ill)
  }
  return out
}

// A series with a level and noise shift at `breakAt` (a device/source change). `shift` in units of the base sd.
export function stepChangeSeries(seed = 7, n = 140, breakAt = 80, base = 62, sdBase = 6, shift = 0, sdAfterMultiplier = 3): Num[] {
  const rng = mulberry32(seed)
  const out: number[] = []
  for (let t = 0; t < n; t++) {
    const after = t >= breakAt
    out.push(base + (after ? shift * sdBase : 0) + gaussian(rng) * sdBase * (after ? sdAfterMultiplier : 1))
  }
  return out
}

// Stationary AR(1) noise around a constant mean: a break detector must stay quiet on it.
export function stationarySeries(seed = 11, n = 140, base = 62, sdBase = 6, phi = 0.4): Num[] {
  const rng = mulberry32(seed)
  const out: number[] = []
  let e = 0
  for (let t = 0; t < n; t++) {
    e = phi * e + sdBase * Math.sqrt(1 - phi * phi) * gaussian(rng)
    out.push(base + e)
  }
  return out
}
