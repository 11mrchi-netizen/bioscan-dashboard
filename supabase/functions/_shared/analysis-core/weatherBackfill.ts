// Daily weather for the days daily_context has none of (it starts on 15 Oct 2026; sleep, sessions and resting heart rate go
// back to 2023). The phone fills temperature, humidity and daylight for the days it was running; this fills the earlier ones
// from Open-Meteo's historical archive, the keyless provider the apps already use (android WeatherRepository.kt), for the
// person's Home place. The coordinates are rounded to 0.1 degree (about 11 km): weather does not need more, and the request
// carries no more than that. Rows are written with source 'open-meteo-archive' and never overwrite a day the phone filled.

export interface WeatherDay { date: string; temp_max_c: number | null; humidity_mean_pct: number | null; daylight_hours: number | null }

export const roundCoord = (v: number) => Math.round(v * 10) / 10

/** The archive's daily answer as rows. Never throws. */
export function weatherDaysOf(body: unknown): WeatherDay[] {
  const d = (body as { daily?: Record<string, unknown> } | null)?.daily
  const time = d?.time
  if (!Array.isArray(time)) return []
  const col = (k: string): unknown[] => (Array.isArray(d?.[k]) ? (d?.[k] as unknown[]) : [])
  const tmax = col('temperature_2m_max'), hum = col('relative_humidity_2m_mean'), day = col('daylight_duration')
  const n = (v: unknown) => (v === null || v === undefined || !Number.isFinite(Number(v)) ? null : Number(v))
  return time.map((t, i) => ({
    date: String(t),
    temp_max_c: n(tmax[i]),
    humidity_mean_pct: n(hum[i]),
    daylight_hours: n(day[i]) === null ? null : Math.round((n(day[i]) as number) / 36) / 100, // seconds to hours, 2 decimals
  })).filter((w) => /^\d{4}-\d{2}-\d{2}$/.test(w.date) && (w.temp_max_c !== null || w.humidity_mean_pct !== null))
}

/** One archive request for [from]..[to] at a rounded location. [get] fetches JSON (injected for tests). */
export async function fetchWeather(lat: number, lon: number, from: string, to: string, get: (url: string) => Promise<unknown>): Promise<WeatherDay[]> {
  const url = `https://archive-api.open-meteo.com/v1/archive?latitude=${roundCoord(lat)}&longitude=${roundCoord(lon)}&start_date=${from}&end_date=${to}` +
    '&daily=temperature_2m_max,relative_humidity_2m_mean,daylight_duration&timezone=auto'
  return weatherDaysOf(await get(url))
}
