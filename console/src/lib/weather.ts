// The hourly weather a race or long-session fuel plan is built for: the forecast (Open-Meteo, 16 days) or, further out, the typical weather of the
// same dates over the last ten years (its archive), as the phone's WeatherRepository.fetchHourly does. No key; read only.
import { atTime, climatology, type HourWeather, localDate, NO_WEATHER, zdtString, type WeatherSeries } from '../../../supabase/functions/_shared/fuel-core/index.ts'

export const FORECAST_DAYS = 16
const FIELDS = 'temperature_2m,relative_humidity_2m,dew_point_2m,apparent_temperature,shortwave_radiation,windspeed_10m,precipitation'
type Fetcher = (url: string) => Promise<{ ok: boolean; status: number; json: () => Promise<unknown> }>

interface Hourly { time: string[]; temperature_2m?: (number | null)[]; relative_humidity_2m?: (number | null)[]; dew_point_2m?: (number | null)[]; apparent_temperature?: (number | null)[]; shortwave_radiation?: (number | null)[]; windspeed_10m?: (number | null)[]; precipitation?: (number | null)[] }

const plusDays = (d: string, n: number) => new Date(Date.UTC(Number(d.slice(0, 4)), Number(d.slice(5, 7)) - 1, Number(d.slice(8, 10)) + n)).toISOString().slice(0, 10)
/** LocalDate.minusYears: the 29th of February becomes the 28th. */
export function minusYears(d: string, y: number): string {
  const year = Number(d.slice(0, 4)) - y
  const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0
  const md = d.slice(5) === '02-29' && !leap ? '02-28' : d.slice(5)
  return `${String(year).padStart(4, '0')}-${md}`
}

/** One response of Open-Meteo as the hours it holds (times are naive local strings in the asked zone). */
export function hoursOf(h: Hourly | undefined, zone: string): HourWeather[] {
  if (!h) return []
  const out: HourWeather[] = []
  h.time.forEach((t, i) => {
    const temp = h.temperature_2m?.[i]
    if (temp == null || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(t)) return
    out.push({
      time: zdtString(atTime(t.slice(0, 10), `${t.slice(11, 16)}:00`, zone), zone), tempC: temp, humidityPct: h.relative_humidity_2m?.[i] ?? null, dewPointC: h.dew_point_2m?.[i] ?? null,
      apparentC: h.apparent_temperature?.[i] ?? null, solarWm2: h.shortwave_radiation?.[i] ?? null, windKmh: h.windspeed_10m?.[i] ?? null, precipMm: h.precipitation?.[i] ?? null,
    })
  })
  return out
}

async function hourly(fetcher: Fetcher, base: string, lat: number, lon: number, start: string, end: string, zone: string): Promise<HourWeather[]> {
  const q = new URLSearchParams({ latitude: String(lat), longitude: String(lon), hourly: FIELDS, start_date: start, end_date: end, timezone: zone })
  const r = await fetcher(`${base}?${q}`)
  if (!r.ok) throw new Error(`Weather request failed (${r.status})`)
  return hoursOf(((await r.json()) as { hourly?: Hourly }).hourly, zone)
}

/** Every hour from fromMs to toMs at a place: the forecast within 16 days of now, else the typical weather of those dates (labelled TYPICAL). */
export async function fetchHourly(lat: number, lon: number, fromMs: number, toMs: number, zone: string, nowMs: number = Date.now(), years = 10, fetcher: Fetcher = (u) => fetch(u)): Promise<WeatherSeries> {
  const fromDate = localDate(fromMs, zone)
  const toDate = localDate(toMs, zone)
  const targets: number[] = []
  for (let t = atTime(fromDate, `${zdtString(fromMs, zone).slice(11, 13)}:00:00`, zone); t <= toMs; t += 3600000) targets.push(t)
  if (toDate <= plusDays(localDate(nowMs, zone), FORECAST_DAYS - 1)) {
    const hours = await hourly(fetcher, 'https://api.open-meteo.com/v1/forecast', lat, lon, fromDate, toDate, zone)
    const byMs = new Map(hours.map((h) => [Date.parse(h.time.replace(/\[.*\]$/, '')), h]))
    return { source: 'FORECAST', hours: targets.map((t) => byMs.get(t)).filter((h): h is HourWeather => h != null) }
  }
  const samples: HourWeather[] = []
  for (let y = 1; y <= years; y++) {
    try { samples.push(...(await hourly(fetcher, 'https://archive-api.open-meteo.com/v1/archive', lat, lon, plusDays(minusYears(fromDate, y), -3), plusDays(minusYears(toDate, y), 3), zone))) } catch { /* a year that fails is left out */ }
  }
  return samples.length === 0 ? NO_WEATHER : climatology(samples, targets.map((t) => zdtString(t, zone)), years)
}
