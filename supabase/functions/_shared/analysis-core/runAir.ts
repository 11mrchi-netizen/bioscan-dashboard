// Risk profile R1: the nightly air exposure run (airExposure.ts does the arithmetic). Erasable TypeScript; the client is
// duck-typed (eq / gte / lt / in / order / range / upsert) like the other run* modules, and the outdoor feed is passed in
// so tests never touch the network.
//
// 1. Works out which local dates to (re)compute: the last RECOMPUTE_DAYS days (late phone uploads), or BACKFILL_DAYS on
//    the first run.
// 2. Reads place visits (with their place's category), location fixes, indoor PM2.5 / CO2, sessions and daily_context.
// 3. Fills the outdoor cache (outdoor_air_hourly) for the grid cells visited, from the Open-Meteo Air Quality API
//    (CAMS model, hourly, UTC), only for the hours it lacks.
// 4. Writes air_exposure_daily and publishes one `air_exposure` summary row (with the ways to improve) to analysis_results.
// Every table is read softly: before a table exists or has rows, the run reports what it lacked instead of failing.
import {
  AIR_ENGINE_VERSION, type AirDay, cellKey, cellOf, type Fix, hourKey, hourRows, hoursBetween, type IndoorReading,
  type OutdoorHour, type OutdoorTable, outdoorAtHome, type Session, summarize, summarizeDays, type Visit,
} from './airExposure.ts'
import { fetchAll } from './paging.ts'
import { publishResults } from './publish.ts'
import { type Admin, localClock, localDateAt } from './runRelationships.ts'

export const RECOMPUTE_DAYS = 8
export const BACKFILL_DAYS = 35
/** At most this many grid cells a run (the most visited first); the rest show as gaps. */
export const MAX_CELLS = 24
const OPEN_METEO = 'https://air-quality-api.open-meteo.com/v1/air-quality'
const COORDS_PER_REQUEST = 8

export type OutdoorFetcher = (cells: { lat: number; lon: number }[], fromDate: string, toDate: string) => Promise<{ cell: string; hours: Map<string, OutdoorHour> }[]>

/** The live feed: one request per few cells, hourly UTC values for the dates asked (both inclusive). */
export const openMeteoFetcher = (fetchFn: typeof fetch = fetch): OutdoorFetcher => async (cells, fromDate, toDate) => {
  const out: { cell: string; hours: Map<string, OutdoorHour> }[] = []
  for (let i = 0; i < cells.length; i += COORDS_PER_REQUEST) {
    const chunk = cells.slice(i, i + COORDS_PER_REQUEST)
    const url = `${OPEN_METEO}?latitude=${chunk.map((c) => c.lat.toFixed(1)).join(',')}&longitude=${chunk.map((c) => c.lon.toFixed(1)).join(',')}` +
      `&hourly=pm2_5,nitrogen_dioxide,ozone,uv_index&start_date=${fromDate}&end_date=${toDate}&timezone=GMT`
    const res = await fetchFn(url)
    if (!res.ok) throw new Error(`open-meteo ${res.status}`)
    const body = await res.json()
    const list = Array.isArray(body) ? body : [body]
    list.forEach((loc: { hourly?: Record<string, (number | null)[]> & { time: string[] } }, j: number) => {
      const h = loc.hourly
      const hours = new Map<string, OutdoorHour>()
      if (h) h.time.forEach((t, k) => hours.set(new Date(t + ':00Z').toISOString(), {
        pm25: h.pm2_5?.[k] ?? null, no2: h.nitrogen_dioxide?.[k] ?? null, o3: h.ozone?.[k] ?? null, uv: h.uv_index?.[k] ?? null,
      }))
      out.push({ cell: cellKey(chunk[j]), hours })
    })
  }
  return out
}

export interface AirRun {
  userId: string
  error?: string
  days?: number
  cells?: number
  fetchedCells?: number
  outdoorError?: string
  missing?: string[]
  published?: boolean
}

const shift = (date: string, days: number) => new Date(Date.parse(date + 'T00:00:00Z') + days * 86400000).toISOString().slice(0, 10)
const soft = <T>(r: { rows: T[]; error?: string }, name: string, missing: string[]): T[] => { if (r.error) missing.push(name); return r.rows }

export async function runAir(admin: Admin, userId: string, now: Date = new Date(), fetcher: OutdoorFetcher = openMeteoFetcher()): Promise<AirRun> {
  const missing: string[] = []
  // All of it (one small row a day): the offset for a date is the latest row on or before it, which can be older than
  // the window when the phone has not written one for a while. A lower bound here made every hour UTC in that case.
  const ctxRes = await fetchAll<{ date: string; utc_offset_min: number | null }>((a, b) =>
    admin.from('daily_context').select('date, utc_offset_min').eq('user_id', userId).order('date').range(a, b))
  const context = soft(ctxRes, 'daily_context', missing).filter((c) => c.utc_offset_min !== null) as { date: string; utc_offset_min: number }[]
  const clock = localClock(context)
  const today = localDateAt(now, context)
  const offsetOn = (localDate: string): number => {
    const sorted = [...context].sort((a, b) => a.date.localeCompare(b.date))
    const hit = sorted.filter((c) => c.date <= localDate).at(-1) ?? sorted[0]
    return hit ? Number(hit.utc_offset_min) || 0 : 0
  }
  // A logged time's digits are local; its instant is those digits minus that day's offset.
  const wallToUtc = (stamp: string) => Date.parse(String(stamp).slice(0, 19) + 'Z') - offsetOn(String(stamp).slice(0, 10)) * 60000

  const existing = await fetchAll<AirDay>((a, b) =>
    admin.from('air_exposure_daily').select('*').eq('user_id', userId).gte('date', shift(today, -40)).order('date').range(a, b))
  if (existing.error) return { userId, error: `air_exposure_daily: ${existing.error}` }
  const latest = existing.rows.map((r) => r.date).sort().at(-1)
  const fromDate = latest && latest >= shift(today, -BACKFILL_DAYS) ? shift(today, -(RECOMPUTE_DAYS - 1)) : shift(today, -(BACKFILL_DAYS - 1))
  const fromMs = Date.parse(fromDate + 'T00:00:00Z') - offsetOn(fromDate) * 60000
  const toMs = Math.min(now.getTime(), Date.parse(shift(today, 1) + 'T00:00:00Z') - offsetOn(today) * 60000)
  const fromIso = new Date(fromMs - 86400000).toISOString()
  const toIso = new Date(toMs).toISOString()

  const [visitsRes, placesRes, fixesRes, indoorRes, sessionsRes] = await Promise.all([
    fetchAll<{ start_at: string; end_at: string | null; lat: number; lon: number; place_id: string | null }>((a, b) =>
      admin.from('place_visits').select('start_at, end_at, lat, lon, place_id').eq('user_id', userId).gte('start_at', fromIso).lt('start_at', toIso).order('start_at').range(a, b)),
    fetchAll<{ id: string; lat: number; lon: number; category: string | null; source: string }>((a, b) =>
      admin.from('places').select('id, lat, lon, category, source').eq('user_id', userId).order('id').range(a, b)),
    fetchAll<Fix>((a, b) => admin.from('location_fixes').select('ts, lat, lon').eq('user_id', userId).gte('ts', fromIso).lt('ts', toIso).order('ts').range(a, b)),
    fetchAll<IndoorReading>((a, b) =>
      admin.from('home_env_readings').select('measured_at, metric, value').eq('user_id', userId).in('metric', ['pm25_ugm3', 'co2_ppm'])
        .gte('measured_at', fromIso).lt('measured_at', toIso).order('measured_at').range(a, b)),
    // logged (wall-clock) start times: one extra day each side covers any offset
    fetchAll<Session>((a, b) =>
      admin.from('exercise_sessions').select('start_time, duration_min, type').eq('user_id', userId).gte('start_time', `${shift(fromDate, -1)}T00:00:00Z`).order('start_time').range(a, b)),
  ])
  const places = soft(placesRes, 'places', missing)
  const homes = places.filter((p) => p.category === 'home').sort((a, b) => (a.source === 'home_setting' ? -1 : 0) - (b.source === 'home_setting' ? -1 : 0))
  const home = homes[0] ? { lat: Number(homes[0].lat), lon: Number(homes[0].lon) } : null
  const homeIds = new Set(homes.map((p) => p.id))
  const visits: Visit[] = soft(visitsRes, 'place_visits', missing).map((v) => ({ start_at: v.start_at, end_at: v.end_at, lat: Number(v.lat), lon: Number(v.lon), home: v.place_id !== null && homeIds.has(v.place_id) }))
  const fixes = soft(fixesRes, 'location_fixes', missing).map((f) => ({ ts: f.ts, lat: Number(f.lat), lon: Number(f.lon) }))
  const indoor = soft(indoorRes, 'home_env_readings', missing)
  const sessions = soft(sessionsRes, 'exercise_sessions', missing)

  // Cells: home first, then by how often they were visited.
  const counts = new Map<string, { c: { lat: number; lon: number }; n: number }>()
  const add = (p: { lat: number; lon: number }, n: number) => { const c = cellOf(p.lat, p.lon); const k = cellKey(c); counts.set(k, { c, n: (counts.get(k)?.n ?? 0) + n }) }
  if (home) add(home, 1e9)
  visits.forEach((v) => add(v, 10))
  fixes.forEach((f) => add(f, 1))
  const cells = [...counts.values()].sort((a, b) => b.n - a.n).slice(0, MAX_CELLS).map((x) => x.c)
  if (cells.length === 0) return { userId, days: 0, cells: 0, missing: [...missing, 'location'], published: false }

  // The cache: what is there, then what is missing (hours before the last full hour).
  const hours = hoursBetween(fromMs, toMs)
  const outdoor: OutdoorTable = new Map()
  const cached = await fetchAll<{ cell_lat: number; cell_lon: number; hour: string; pm25_ugm3: number | null; no2_ugm3: number | null; o3_ugm3: number | null; uv_index: number | null }>((a, b) =>
    admin.from('outdoor_air_hourly').select('cell_lat, cell_lon, hour, pm25_ugm3, no2_ugm3, o3_ugm3, uv_index')
      .in('cell_lat', [...new Set(cells.map((c) => Number(c.lat.toFixed(1))))]).gte('hour', new Date(fromMs).toISOString()).lt('hour', toIso).order('hour').range(a, b))
  if (cached.error) return { userId, error: `outdoor_air_hourly: ${cached.error}` }
  for (const r of cached.rows) {
    const k = cellKey({ lat: Number(r.cell_lat), lon: Number(r.cell_lon) })
    const m = outdoor.get(k) ?? new Map()
    m.set(new Date(r.hour).toISOString(), { pm25: r.pm25_ugm3, no2: r.no2_ugm3, o3: r.o3_ugm3, uv: r.uv_index })
    outdoor.set(k, m)
  }
  const lastFull = Math.floor(now.getTime() / 3600000) * 3600000 - 3600000
  const lacking = cells.filter((c) => {
    const m = outdoor.get(cellKey(c))
    return hours.some((h) => h <= lastFull && !m?.get(hourKey(h)))
  })
  let outdoorError: string | undefined
  if (lacking.length) {
    try {
      const got = await fetcher(lacking, new Date(fromMs).toISOString().slice(0, 10), new Date(toMs).toISOString().slice(0, 10))
      const rows: Record<string, unknown>[] = []
      for (const g of got) {
        const m = outdoor.get(g.cell) ?? new Map()
        const [lat, lon] = g.cell.split(',').map(Number)
        for (const [hour, v] of g.hours) {
          if (Date.parse(hour) > lastFull || (v.pm25 === null && v.no2 === null && v.o3 === null && v.uv === null)) continue
          m.set(hour, v)
          rows.push({ cell_lat: lat, cell_lon: lon, hour, pm25_ugm3: v.pm25, no2_ugm3: v.no2, o3_ugm3: v.o3, uv_index: v.uv, source: 'open-meteo-cams', fetched_at: now.toISOString() })
        }
        outdoor.set(g.cell, m)
      }
      for (let i = 0; i < rows.length; i += 500) {
        const { error } = await admin.from('outdoor_air_hourly').upsert(rows.slice(i, i + 500), { onConflict: 'cell_lat,cell_lon,hour' })
        if (error) { outdoorError = `cache: ${error.message}`; break }
      }
    } catch (e) {
      outdoorError = String(e)
    }
  }

  const inputs = { visits, fixes, home, indoor, sessions, outdoor, clock, wallToUtc }
  const rows = hourRows(hours, inputs, now.getTime())
  const days = summarizeDays(rows, outdoorAtHome(hours, home, outdoor), inputs).filter((d) => d.date >= fromDate && d.date <= today)

  const upserts = days.map((d) => ({ user_id: userId, ...d, engine_version: AIR_ENGINE_VERSION, calculated_at: now.toISOString() }))
  for (let i = 0; i < upserts.length; i += 20) {
    const { error } = await admin.from('air_exposure_daily').upsert(upserts.slice(i, i + 20), { onConflict: 'user_id,date' })
    if (error) return { userId, error: `air_exposure_daily: ${error.message}`, outdoorError }
  }

  const byDate = new Map(existing.rows.map((r) => [r.date, r]))
  for (const d of days) byDate.set(d.date, d)
  const summary = summarize([...byDate.values()], indoor.some((r) => r.metric === 'pm25_ugm3'))
  const state = summary.days_with_data === 0 ? 'NO_DATA' : summary.full_days < 7 ? 'PARTIAL' : 'READY'
  const pub = await publishResults(admin, userId, today, [{
    kind: 'air_exposure', key: 'summary', state, have: summary.full_days, need: 7, payload: { ...summary, missing, outdoorError: outdoorError ?? null }, hash: `${AIR_ENGINE_VERSION}:${today}:${summary.days_with_data}`,
  }], now, AIR_ENGINE_VERSION)

  return { userId, days: days.length, cells: cells.length, fetchedCells: lacking.length, outdoorError, missing, published: !pub.error, ...(pub.error ? { error: pub.error } : {}) }
}
