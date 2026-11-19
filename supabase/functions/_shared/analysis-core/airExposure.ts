// Risk profile R1 (docs/risk-profile/01-plan.md): the air a person breathed, hour by hour and day by day. Pure and
// erasable TypeScript, no I/O (runAir.ts reads and writes).
//
// Each UTC hour is placed somewhere: the place visit covering most of it, else the nearest location fix within
// FIX_WINDOW_MIN, else nowhere (a gap). Then its PM2.5 comes from:
//   - `indoor`: at home, from the Home Terminal sensors (time-weighted over the hour);
//   - `session`: during an outdoor session, the outdoor level where it was (breathing is several times higher then);
//   - `outdoor_home`: at home without an indoor reading, the outdoor level at home (a stand-in, labelled as one);
//   - `outdoor_away`: anywhere else, the outdoor level at that place (indoors elsewhere is not measured).
// NO2, ozone and UV are always the outdoor (ambient) values where the person was.
//
// Thresholds: WHO Global Air Quality Guidelines 2021: PM2.5 15 ug/m3 24-h and 5 annual, NO2 25 24-h and 10 annual,
// ozone 100 ug/m3 highest 8-h mean. UV: WHO UV index bands (6-7 high, 8-10 very high, 11+ extreme). CO2 is a ventilation
// marker, not a pollutant limit: about 1,000 ppm is the usual "ventilation could be better" convention and 1,400 ppm is
// poor (EN 16798-1 category IV); both are conventions and labelled as such.

export const AIR_ENGINE_VERSION = 'air-1.0.0'

export const WHO_PM25_24H = 15
export const WHO_PM25_ANNUAL = 5
export const WHO_NO2_24H = 25
export const WHO_NO2_ANNUAL = 10
export const WHO_O3_8H = 100
export const UV_HIGH = 6
export const CO2_VENTILATE = 1000
export const CO2_POOR = 1400

/** Grid cell size for the outdoor cache, degrees. CAMS global is coarser; nearby cells may share model values. */
export const CELL_DEG = 0.1
/** A location fix this close in time places an hour with no visit. */
export const FIX_WINDOW_MIN = 90
/** Within this distance of home counts as home. */
export const HOME_RADIUS_M = 150
/** A sensor value is held this long after it was written (Home Terminal writes on change or every 15 minutes). */
export const INDOOR_HOLD_MIN = 20
/** Ticks per hour for the indoor time-weighting, and how many must have a value. */
const TICKS = 6
const MIN_TICKS = 3
/** Below this many covered hours a day's means are shown but marked partial. */
export const FULL_DAY_HOURS = 18
/** Local night for bedroom CO2: 22:00 to 07:00. */
const NIGHT_FROM = 22
const NIGHT_TO = 7
/** Session types read as outdoors. */
export const OUTDOOR_TYPES = ['run', 'running', 'trail', 'trail_run', 'ride', 'cycling', 'bike', 'hike', 'hiking', 'walk', 'walking', 'rowing', 'open_water', 'ski', 'outdoor']

export type Source = 'indoor' | 'session' | 'outdoor_home' | 'outdoor_away'

export interface Visit { start_at: string; end_at: string | null; lat: number; lon: number; home: boolean }
export interface Fix { ts: string; lat: number; lon: number }
export interface IndoorReading { measured_at: string; metric: string; value: number }
export interface Session { start_time: string; duration_min: number | null; type: string | null }
export interface OutdoorHour { pm25: number | null; no2: number | null; o3: number | null; uv: number | null }
/** Outdoor values by cell key, then by UTC hour (ISO, `YYYY-MM-DDTHH:00:00.000Z`). */
export type OutdoorTable = Map<string, Map<string, OutdoorHour>>
export type Clock = (stamp: string) => { date: string; hour: number }

export const cellOf = (lat: number, lon: number): { lat: number; lon: number } => ({
  lat: Math.round(lat / CELL_DEG) * CELL_DEG,
  lon: Math.round(lon / CELL_DEG) * CELL_DEG,
})
export const cellKey = (c: { lat: number; lon: number }): string => `${c.lat.toFixed(1)},${c.lon.toFixed(1)}`

export function distanceM(a: { lat: number; lon: number }, b: { lat: number; lon: number }): number {
  const r = 6371000
  const toRad = (d: number) => (d * Math.PI) / 180
  const dLat = toRad(b.lat - a.lat)
  const dLon = toRad(b.lon - a.lon)
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLon / 2) ** 2
  return 2 * r * Math.asin(Math.sqrt(h))
}

export const hourKey = (ms: number): string => new Date(Math.floor(ms / 3600000) * 3600000).toISOString()

/** The UTC hours from [fromMs] (inclusive) to [toMs] (exclusive). */
export function hoursBetween(fromMs: number, toMs: number): number[] {
  const out: number[] = []
  for (let t = Math.floor(fromMs / 3600000) * 3600000; t < toMs; t += 3600000) out.push(t)
  return out
}

export interface Located { lat: number; lon: number; home: boolean }

/** Where the person was during the hour starting at [h] (ms), or null. */
export function locate(h: number, visits: Visit[], fixes: Fix[], home: { lat: number; lon: number } | null, nowMs: number): Located | null {
  const end = h + 3600000
  let best: Visit | null = null
  let bestOverlap = 0
  for (const v of visits) {
    const s = Date.parse(v.start_at)
    const e = v.end_at ? Date.parse(v.end_at) : nowMs
    const overlap = Math.min(e, end) - Math.max(s, h)
    if (overlap > bestOverlap) { best = v; bestOverlap = overlap }
  }
  if (best && bestOverlap >= 30 * 60000) return { lat: best.lat, lon: best.lon, home: best.home || nearHome(best, home) }
  const mid = h + 1800000
  let fix: Fix | null = null
  let gap = Infinity
  for (const f of fixes) {
    const d = Math.abs(Date.parse(f.ts) - mid)
    if (d < gap) { gap = d; fix = f }
  }
  if (fix && gap <= FIX_WINDOW_MIN * 60000) return { lat: fix.lat, lon: fix.lon, home: nearHome(fix, home) }
  if (best) return { lat: best.lat, lon: best.lon, home: best.home || nearHome(best, home) }
  return null
}

const nearHome = (p: { lat: number; lon: number }, home: { lat: number; lon: number } | null) => home !== null && distanceM(p, home) <= HOME_RADIUS_M

/**
 * Time-weighted indoor value over the hour starting at [h]: the latest reading at or before each of [TICKS] ticks,
 * held at most [INDOOR_HOLD_MIN] minutes; null when fewer than [MIN_TICKS] ticks have one. [sorted] is ascending by time.
 */
export function indoorHour(h: number, sorted: { t: number; value: number }[]): number | null {
  const vals: number[] = []
  for (let i = 0; i < TICKS; i++) {
    const tick = h + ((i + 0.5) * 3600000) / TICKS
    let lo = 0
    let hi = sorted.length - 1
    let found = -1
    while (lo <= hi) {
      const m = (lo + hi) >> 1
      if (sorted[m].t <= tick) { found = m; lo = m + 1 } else hi = m - 1
    }
    if (found >= 0 && tick - sorted[found].t <= INDOOR_HOLD_MIN * 60000) vals.push(sorted[found].value)
  }
  return vals.length >= MIN_TICKS ? mean(vals) : null
}

export interface HourRow {
  utc: number
  date: string
  hour: number
  source: Source | null
  pm25: number | null
  no2: number | null
  o3: number | null
  uv: number | null
  co2: number | null
  home: boolean
  session: boolean
}

export interface AirInputs {
  visits: Visit[]
  fixes: Fix[]
  home: { lat: number; lon: number } | null
  indoor: IndoorReading[]
  sessions: Session[]
  outdoor: OutdoorTable
  /** Local date and hour of a real instant (localClock over daily_context). */
  clock: Clock
  /** Local date-time of a logged (wall-clock) time to a real instant: the inverse of the local offset on that date. */
  wallToUtc: (stamp: string) => number
}

/** The session spans (real instants) that are outdoors. */
export function outdoorSessionSpans(sessions: Session[], wallToUtc: (s: string) => number): { from: number; to: number; s: Session }[] {
  return sessions
    .filter((s) => s.type !== null && OUTDOOR_TYPES.includes(String(s.type).toLowerCase()) && (s.duration_min ?? 0) > 0)
    .map((s) => { const from = wallToUtc(s.start_time); return { from, to: from + Number(s.duration_min) * 60000, s } })
    .filter((x) => Number.isFinite(x.from))
}

export function hourRows(hours: number[], inp: AirInputs, nowMs: number): HourRow[] {
  const series = (metric: string) => inp.indoor.filter((r) => r.metric === metric && Number.isFinite(Number(r.value)))
    .map((r) => ({ t: Date.parse(r.measured_at), value: Number(r.value) })).sort((a, b) => a.t - b.t)
  const pm = series('pm25_ugm3')
  const co2 = series('co2_ppm')
  const spans = outdoorSessionSpans(inp.sessions, inp.wallToUtc)
  return hours.map((h) => {
    const at = locate(h, inp.visits, inp.fixes, inp.home, nowMs)
    const { date, hour } = inp.clock(new Date(h).toISOString())
    const inSession = spans.some((x) => Math.min(x.to, h + 3600000) - Math.max(x.from, h) >= 15 * 60000)
    const out = at ? inp.outdoor.get(cellKey(cellOf(at.lat, at.lon)))?.get(hourKey(h)) ?? null : null
    const home = at?.home ?? false
    const indoorPm = home && !inSession ? indoorHour(h, pm) : null
    const indoorCo2 = home && !inSession ? indoorHour(h, co2) : null
    let source: Source | null = null
    let pm25: number | null = null
    if (inSession && out?.pm25 != null) { source = 'session'; pm25 = out.pm25 }
    else if (indoorPm !== null) { source = 'indoor'; pm25 = indoorPm }
    else if (out?.pm25 != null) { source = home ? 'outdoor_home' : 'outdoor_away'; pm25 = out.pm25 }
    return { utc: h, date, hour, source, pm25, no2: out?.no2 ?? null, o3: out?.o3 ?? null, uv: out?.uv ?? null, co2: indoorCo2, home, session: inSession }
  })
}

export interface DaySession { start: string; type: string; minutes: number; pm25: number | null; o3: number | null; uv: number | null }

export interface AirDay {
  date: string
  coverage_h: number
  pm25_ugm3: number | null
  pm25_indoor_ugm3: number | null
  pm25_outdoor_home_ugm3: number | null
  no2_ugm3: number | null
  o3_mda8_ugm3: number | null
  uv_max: number | null
  uv_session_max: number | null
  co2_night_ppm: number | null
  co2_night_poor_h: number | null
  dose_home_share: number | null
  dose_session_share: number | null
  sources: Record<Source, number>
  hours: (string | number | null)[][]
  sessions: DaySession[]
}

const mean = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length
const meanOrNull = (xs: (number | null)[]) => { const v = xs.filter((x): x is number => x !== null && Number.isFinite(x)); return v.length ? mean(v) : null }
const round = (x: number | null, d = 1) => (x === null ? null : Math.round(x * 10 ** d) / 10 ** d)

/** Highest 8-hour running mean, each window needing at least 6 of its 8 hours (WHO / EU practice). */
export function mda8(values: (number | null)[]): number | null {
  let best: number | null = null
  for (let i = 0; i + 8 <= values.length; i++) {
    const w = values.slice(i, i + 8).filter((x): x is number => x !== null)
    if (w.length >= 6) { const m = mean(w); if (best === null || m > best) best = m }
  }
  return best
}

/** The outdoor PM2.5 at home's cell for each hour, for the reference column. */
export function outdoorAtHome(hours: number[], home: { lat: number; lon: number } | null, outdoor: OutdoorTable): Map<number, number | null> {
  const m = new Map<number, number | null>()
  if (!home) return m
  const cell = outdoor.get(cellKey(cellOf(home.lat, home.lon)))
  for (const h of hours) m.set(h, cell?.get(hourKey(h))?.pm25 ?? null)
  return m
}

export function summarizeDays(rows: HourRow[], homeOutdoor: Map<number, number | null>, inp: Pick<AirInputs, 'sessions' | 'wallToUtc' | 'clock' | 'outdoor' | 'visits' | 'fixes' | 'home'>, nowMs: number): AirDay[] {
  const byDate = new Map<string, HourRow[]>()
  for (const r of rows) { const l = byDate.get(r.date) ?? []; l.push(r); byDate.set(r.date, l) }
  const spans = outdoorSessionSpans(inp.sessions, inp.wallToUtc)
  const out: AirDay[] = []
  for (const [date, hs] of [...byDate.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    hs.sort((a, b) => a.utc - b.utc)
    const covered = hs.filter((h) => h.pm25 !== null)
    const sources: Record<Source, number> = { indoor: 0, session: 0, outdoor_home: 0, outdoor_away: 0 }
    for (const h of covered) sources[h.source!]++
    const dose = covered.reduce((a, h) => a + h.pm25!, 0)
    const doseHome = covered.filter((h) => h.home && !h.session).reduce((a, h) => a + h.pm25!, 0)
    const doseSession = covered.filter((h) => h.session).reduce((a, h) => a + h.pm25!, 0)
    const night = hs.filter((h) => h.hour >= NIGHT_FROM || h.hour < NIGHT_TO)
    const nightCo2 = night.map((h) => h.co2).filter((x): x is number => x !== null)
    const sessionRows = hs.filter((h) => h.session)
    const sessions: DaySession[] = spans
      .filter((x) => inp.clock(new Date(x.from).toISOString()).date === date)
      .map((x) => {
        const within = hs.filter((h) => Math.min(x.to, h.utc + 3600000) - Math.max(x.from, h.utc) > 0)
        return {
          start: x.s.start_time, type: String(x.s.type), minutes: Math.round(Number(x.s.duration_min)),
          pm25: round(meanOrNull(within.map((h) => h.pm25))), o3: round(meanOrNull(within.map((h) => h.o3))), uv: round(maxOrNull(within.map((h) => h.uv))),
        }
      })
    const homeRef = hs.map((h) => homeOutdoor.get(h.utc) ?? null)
    out.push({
      date,
      coverage_h: covered.length,
      pm25_ugm3: round(covered.length ? dose / covered.length : null),
      pm25_indoor_ugm3: round(meanOrNull(hs.filter((h) => h.source === 'indoor').map((h) => h.pm25))),
      pm25_outdoor_home_ugm3: round(homeRef.filter((x) => x !== null).length >= FULL_DAY_HOURS ? meanOrNull(homeRef) : null),
      no2_ugm3: round(meanOrNull(hs.map((h) => h.no2))),
      o3_mda8_ugm3: round(mda8(hs.map((h) => h.o3))),
      uv_max: round(maxOrNull(hs.filter((h) => h.hour >= 6 && h.hour < 19).map((h) => h.uv))),
      uv_session_max: round(maxOrNull(sessionRows.map((h) => h.uv))),
      co2_night_ppm: nightCo2.length >= 3 ? Math.round(mean(nightCo2)) : null,
      co2_night_poor_h: nightCo2.length >= 3 ? nightCo2.filter((x) => x > CO2_POOR).length : null,
      dose_home_share: dose > 0 ? round(doseHome / dose, 2) : null,
      dose_session_share: dose > 0 ? round(doseSession / dose, 2) : null,
      sources,
      hours: hs.map((h) => [h.hour, h.source, round(h.pm25), round(h.no2), round(h.o3), round(h.uv), h.co2 === null ? null : Math.round(h.co2)]),
      sessions,
    })
  }
  return out
}

const maxOrNull = (xs: (number | null)[]) => { const v = xs.filter((x): x is number => x !== null); return v.length ? Math.max(...v) : null }

// ---------- The summary and the ways to improve ----------

export interface Improvement {
  id: string
  title: string
  why: string
  evidence: string
  effort: 'low' | 'medium' | 'high'
  /** What it lowers: pm25, no2, o3, uv, co2. */
  lowers: string[]
}

export interface AirSummary {
  window_days: number
  days_with_data: number
  full_days: number
  pm25_mean: number | null
  pm25_days_over_24h: number
  no2_mean: number | null
  no2_days_over_24h: number
  o3_days_over_8h: number
  dose_home_share: number | null
  indoor_sensor: boolean
  indoor_pm25_mean: number | null
  outdoor_home_pm25_mean: number | null
  co2_night_mean: number | null
  co2_poor_nights: number
  sessions_outdoor: number
  sessions_poor_air: number
  sessions_high_uv: number
  /** Which side of each guideline the window's mean sits on: ok | warn | bad. */
  states: Record<string, 'ok' | 'warn' | 'bad' | null>
  improvements: Improvement[]
  thresholds: Record<string, number>
}

export const SUMMARY_WINDOW_DAYS = 30

const state = (v: number | null, ok: number, bad: number): 'ok' | 'warn' | 'bad' | null => (v === null ? null : v <= ok ? 'ok' : v <= bad ? 'warn' : 'bad')

export function summarize(days: AirDay[], indoorSensor: boolean): AirSummary {
  const recent = [...days].sort((a, b) => a.date.localeCompare(b.date)).slice(-SUMMARY_WINDOW_DAYS).filter((d) => d.coverage_h > 0)
  const full = recent.filter((d) => d.coverage_h >= FULL_DAY_HOURS)
  const avg = (f: (d: AirDay) => number | null, from = recent) => round(meanOrNull(from.map(f)))
  const pm25 = avg((d) => d.pm25_ugm3, full.length ? full : recent)
  const no2 = avg((d) => d.no2_ugm3)
  const homeShare = round(meanOrNull(recent.map((d) => d.dose_home_share)), 2)
  const indoor = avg((d) => d.pm25_indoor_ugm3)
  const outdoorHome = avg((d) => d.pm25_outdoor_home_ugm3)
  const co2 = avg((d) => d.co2_night_ppm, recent.filter((d) => d.co2_night_ppm !== null))
  const sessions = recent.flatMap((d) => d.sessions)
  const poorAir = sessions.filter((s) => (s.pm25 ?? 0) > WHO_PM25_24H || (s.o3 ?? 0) > WHO_O3_8H).length
  const highUv = sessions.filter((s) => (s.uv ?? 0) >= UV_HIGH).length
  const s: AirSummary = {
    window_days: SUMMARY_WINDOW_DAYS,
    days_with_data: recent.length,
    full_days: full.length,
    pm25_mean: pm25,
    pm25_days_over_24h: full.filter((d) => (d.pm25_ugm3 ?? 0) > WHO_PM25_24H).length,
    no2_mean: no2,
    no2_days_over_24h: recent.filter((d) => (d.no2_ugm3 ?? 0) > WHO_NO2_24H).length,
    o3_days_over_8h: recent.filter((d) => (d.o3_mda8_ugm3 ?? 0) > WHO_O3_8H).length,
    dose_home_share: homeShare,
    indoor_sensor: indoorSensor,
    indoor_pm25_mean: indoor,
    outdoor_home_pm25_mean: outdoorHome,
    co2_night_mean: co2 === null ? null : Math.round(co2),
    co2_poor_nights: recent.filter((d) => (d.co2_night_poor_h ?? 0) >= 2).length,
    sessions_outdoor: sessions.length,
    sessions_poor_air: poorAir,
    sessions_high_uv: highUv,
    states: {
      pm25: state(pm25, WHO_PM25_ANNUAL, WHO_PM25_24H),
      no2: state(no2, WHO_NO2_ANNUAL, WHO_NO2_24H),
      co2: state(co2, CO2_VENTILATE, CO2_POOR),
    },
    improvements: [],
    thresholds: { WHO_PM25_24H, WHO_PM25_ANNUAL, WHO_NO2_24H, WHO_NO2_ANNUAL, WHO_O3_8H, UV_HIGH, CO2_VENTILATE, CO2_POOR },
  }
  s.improvements = improvements(s)
  return s
}

/** Ranked suggestions: each fires only on the person's own numbers, most dose first. */
export function improvements(s: AirSummary): Improvement[] {
  const out: (Improvement & { rank: number })[] = []
  const homeHeavy = (s.dose_home_share ?? 0) >= 0.5
  if (!s.indoor_sensor && homeHeavy && s.days_with_data > 0) {
    out.push({
      id: 'measure_indoor', rank: 90, effort: 'medium', lowers: ['pm25', 'co2'],
      title: 'Measure the air at home',
      why: `About ${Math.round((s.dose_home_share ?? 0) * 100)} % of your PM2.5 dose is breathed at home, and it is estimated from the outdoor level there. An indoor sensor (PM2.5 and CO2) would replace the estimate.`,
      evidence: 'Indoor PM2.5 differs from outdoor by cooking, candles, smoking and filtration; it cannot be inferred reliably from outdoor levels (WHO indoor air quality guidelines).',
    })
  }
  const homePm = s.indoor_sensor ? s.indoor_pm25_mean : s.outdoor_home_pm25_mean
  if (homeHeavy && homePm !== null && homePm > WHO_PM25_ANNUAL) {
    out.push({
      id: 'purifier', rank: 80 + Math.min(10, homePm), effort: 'medium', lowers: ['pm25'],
      title: 'Run a HEPA air purifier where you spend the most hours',
      why: `PM2.5 at home averages ${homePm} ug/m3 (${s.indoor_sensor ? 'measured indoors' : 'outdoor level at home'}), above the WHO annual guideline of ${WHO_PM25_ANNUAL}. Most of your dose is breathed there.`,
      evidence: 'Portable HEPA cleaners lowered indoor PM2.5 substantially in field studies (Cheek et al., Sci Total Environ 2021, review). Size it to the room: clean-air delivery rate about 2/3 of the floor area in ft2.',
    })
  }
  if (s.co2_night_mean !== null && (s.co2_night_mean > CO2_VENTILATE || s.co2_poor_nights >= 3)) {
    out.push({
      id: 'ventilate_bedroom', rank: 70, effort: 'low', lowers: ['co2'],
      title: 'Ventilate the bedroom at night',
      why: `Night-time CO2 at home averages ${s.co2_night_mean} ppm${s.co2_poor_nights ? `, with ${s.co2_poor_nights} nights above ${CO2_POOR} ppm for 2 hours or more` : ''}.`,
      evidence: 'Lower bedroom CO2 (an open window or a fan) improved sleep and next-day performance (Strom-Tejsen et al., Indoor Air 2016). 1,000 ppm is a ventilation convention, not a health limit.',
    })
  }
  if (s.sessions_poor_air >= 2) {
    out.push({
      id: 'session_timing', rank: 60 + s.sessions_poor_air, effort: 'low', lowers: ['pm25', 'o3'],
      title: 'Move hard outdoor sessions to cleaner hours',
      why: `${s.sessions_poor_air} of your ${s.sessions_outdoor} outdoor sessions in the last ${s.window_days} days were in air above the WHO 24-hour PM2.5 level or the 8-hour ozone level. The forecast is available a few days ahead.`,
      evidence: 'Breathing rises several-fold during exercise, so the dose of a polluted hour is higher (Giles & Koehle, Sports Med 2014). Ozone peaks in the afternoon; PM2.5 often in the evening and early morning.',
    })
  }
  if ((s.no2_mean ?? 0) > WHO_NO2_ANNUAL && !homeHeavy) {
    out.push({
      id: 'routes', rank: 50, effort: 'medium', lowers: ['no2', 'pm25'],
      title: 'Prefer routes away from busy roads',
      why: `NO2 where you were averages ${s.no2_mean} ug/m3 (WHO annual guideline ${WHO_NO2_ANNUAL}) and much of your exposure is away from home.`,
      evidence: 'NO2 is mainly from traffic and falls off within a few hundred metres of a main road (WHO Air Quality Guidelines 2021).',
    })
  }
  if (s.sessions_high_uv >= 2) {
    out.push({
      id: 'uv', rank: 40, effort: 'low', lowers: ['uv'],
      title: 'Sun protection, or earlier sessions on high UV days',
      why: `${s.sessions_high_uv} outdoor sessions were at a UV index of ${UV_HIGH} or more.`,
      evidence: 'WHO: protection is needed from UV index 3, and extra protection from 6 (high).',
    })
  }
  return out.sort((a, b) => b.rank - a.rank).map(({ rank: _rank, ...i }) => i)
}
