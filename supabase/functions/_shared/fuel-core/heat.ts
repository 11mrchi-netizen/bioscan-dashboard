// Port of the Android domain/fuel/HeatStress.kt: the weather a fuel plan is built for, and how hot it is. A forecast covers 16 days; further
// out the plan uses the TYPICAL weather of those dates (median and 90th percentile over past years). Heat is a heat-index band (NWS Rothfusz
// regression), the temperature falls 6.5 °C per 1,000 m of climb. Parity by fixture (RaceFuelParityTest).

import { instant } from './plannedDay.ts'
import { roundToInt } from './plates.ts'

export type WeatherSource = 'FORECAST' | 'TYPICAL' | 'NONE'
/** time is a Kotlin ZonedDateTime.toString(). */
export interface HourWeather {
  time: string; tempC: number; humidityPct?: number | null; dewPointC?: number | null; apparentC?: number | null; solarWm2?: number | null
  windKmh?: number | null; precipMm?: number | null; tempP90C?: number | null
}
export interface WeatherSeries { source: WeatherSource; hours: HourWeather[]; note?: string | null }
export const NO_WEATHER: WeatherSeries = { source: 'NONE', hours: [] }

export const FORECAST_DAYS = 16
export const LAPSE_RATE_C_PER_KM = 6.5

/** The hour closest to t (ms) within 90 minutes, else null. */
export function seriesAt(w: WeatherSeries, t: number): HourWeather | null {
  let best: HourWeather | null = null
  let bestGap = Infinity
  for (const h of w.hours) { const g = Math.abs(Math.trunc((t - instant(h.time)) / 60000)); if (g < bestGap) { best = h; bestGap = g } }
  return best && bestGap <= 90 ? best : null
}

export const tempAtElevation = (tempC: number, climbM: number) => tempC - (LAPSE_RATE_C_PER_KM * climbM) / 1000

const medianSorted = (s: number[]) => (s.length % 2 === 1 ? s[Math.trunc(s.length / 2)] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2)
function percentile(sorted: number[], p: number): number {
  if (sorted.length === 1) return sorted[0]
  const pos = p * (sorted.length - 1)
  const lo = Math.trunc(pos)
  const hi = Math.min(lo + 1, sorted.length - 1)
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
}
const hourOf = (zdt: string) => Number(zdt.slice(11, 13))

/** The TYPICAL weather for targetHours (zoned strings) from archive samples of the same dates in past years. */
export function climatology(samples: HourWeather[], targetHours: string[], years: number): WeatherSeries {
  const byHour = new Map<number, HourWeather[]>()
  for (const s of samples) { const h = hourOf(s.time); const l = byHour.get(h); if (l) l.push(s); else byHour.set(h, [s]) }
  const hours: HourWeather[] = []
  for (const t of targetHours) {
    const s = byHour.get(hourOf(t))
    if (!s || s.length === 0) continue
    const temps = s.map((x) => x.tempC).sort((a, b) => a - b)
    const med = (f: (x: HourWeather) => number | null | undefined) => {
      const v = s.map(f).filter((x): x is number => x != null).sort((a, b) => a - b)
      return v.length ? medianSorted(v) : null
    }
    hours.push({
      time: t, tempC: medianSorted(temps), humidityPct: med((x) => x.humidityPct), dewPointC: med((x) => x.dewPointC), apparentC: med((x) => x.apparentC),
      solarWm2: med((x) => x.solarWm2), windKmh: med((x) => x.windKmh), precipMm: med((x) => x.precipMm), tempP90C: percentile(temps, 0.9),
    })
  }
  return { source: 'TYPICAL', hours, note: `Typical weather for these dates over the last ${years} years, not a forecast.` }
}

/** NWS heat index, °C, from air temperature (°C) and relative humidity (%). Equal to the temperature below about 27 °C. */
export function heatIndexC(tempC: number, humidityPct: number): number {
  const t = (tempC * 9) / 5 + 32
  const rh = Math.min(Math.max(humidityPct, 0), 100)
  const simple = 0.5 * (t + 61 + (t - 68) * 1.2 + rh * 0.094)
  let hiF: number
  if ((simple + t) / 2 < 80) hiF = simple
  else {
    let hi = -42.379 + 2.04901523 * t + 10.14333127 * rh - 0.22475541 * t * rh - 0.00683783 * t * t - 0.05481717 * rh * rh + 0.00122874 * t * t * rh + 0.00085282 * t * rh * rh - 0.00000199 * t * t * rh * rh
    if (rh < 13 && t >= 80 && t <= 112) hi -= ((13 - rh) / 4) * Math.sqrt((17 - Math.abs(t - 95)) / 17)
    if (rh > 85 && t >= 80 && t <= 87) hi += ((rh - 85) / 10) * ((87 - t) / 5)
    hiF = hi
  }
  return ((hiF - 32) * 5) / 9
}

export type HeatBand = 'COLD' | 'COOL' | 'MILD' | 'WARM' | 'HOT' | 'VERY_HOT'
export const raisesFluid = (b: HeatBand) => b === 'WARM' || b === 'HOT' || b === 'VERY_HOT'

export function heatBand(tempC: number, humidityPct: number | null | undefined): HeatBand {
  const hi = humidityPct != null ? heatIndexC(tempC, humidityPct) : tempC
  if (tempC < 5) return 'COLD'
  if (tempC < 12) return 'COOL'
  if (hi < 22) return 'MILD'
  if (hi < 27) return 'WARM'
  if (hi < 32) return 'HOT'
  return 'VERY_HOT'
}

export interface HeatSummary { band: HeatBand; maxTempC: number; maxHeatIndexC: number; strongSun: boolean; lines: string[] }

export function summariseHeat(hours: HourWeather[], source: WeatherSource): HeatSummary | null {
  if (hours.length === 0) return null
  const index = (h: HourWeather) => (h.humidityPct != null ? heatIndexC(h.tempC, h.humidityPct) : h.tempC)
  let hottest = hours[0]
  for (const h of hours) if (index(h) > index(hottest)) hottest = h
  const hi = index(hottest)
  const band = heatBand(hottest.tempC, hottest.humidityPct)
  const sun = hours.some((h) => (h.solarWm2 ?? 0) >= 600)
  const lines: string[] = []
  let l = `Up to ${roundToInt(hottest.tempC)} °C`
  if (hottest.humidityPct != null) l += `, ${roundToInt(hottest.humidityPct)} % humidity`
  if (hottest.dewPointC != null) l += `, dew point ${roundToInt(hottest.dewPointC)} °C`
  if (hi - hottest.tempC >= 1) l += ` (feels like ${roundToInt(hi)} °C)`
  if (source === 'TYPICAL' && hottest.tempP90C != null) l += `; 1 year in 10 above ${roundToInt(hottest.tempP90C)} °C`
  lines.push(`${l}.`)
  if (band === 'HOT' || band === 'VERY_HOT') {
    lines.push('Hot: sweat losses rise and the gut tolerates less. Drink to the plan from the start, add sodium, and pre-cool with a cold drink or ice slurry.')
    lines.push('Heat acclimatisation (10-14 days of training in the heat) is the biggest single help.')
  } else if (band === 'WARM') lines.push('Warm: sweat losses rise; fluid and sodium are raised.')
  else if (band === 'COLD') lines.push('Cold: you sweat less and feel less thirsty; still drink on a schedule in long sessions.')
  if (sun) lines.push('Strong sun: shade and a cap help; it adds to heat strain beyond the air temperature.')
  return { band, maxTempC: hottest.tempC, maxHeatIndexC: hi, strongSun: sun, lines }
}
