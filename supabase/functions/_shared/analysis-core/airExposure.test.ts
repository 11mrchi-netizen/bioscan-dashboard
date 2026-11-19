import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  cellKey, cellOf, hourKey, hourRows, hoursBetween, improvements, indoorHour, locate, mda8, type OutdoorTable, outdoorAtHome,
  summarize, summarizeDays, WHO_PM25_24H,
} from './airExposure.ts'
import { localClock } from './runRelationships.ts'
import { runAir } from './runAir.ts'
import { fakeAdmin } from './healthTestKit.ts'

// One person in UTC+8: home at (25.03, 121.56), work 5 km away. Local 2026-11-10 is UTC 2026-11-09T16:00 .. 11-10T16:00.
const HOME = { lat: 25.03, lon: 121.56 }
const WORK = { lat: 25.07, lon: 121.52 }
const context = [{ date: '2026-11-01', utc_offset_min: 480 }]
const clock = localClock(context)
const wallToUtc = (s: string) => Date.parse(s.slice(0, 19) + 'Z') - 480 * 60000
const DAY_START = Date.parse('2026-11-09T16:00:00Z')
const hours = hoursBetween(DAY_START, DAY_START + 24 * 3600000)

function outdoorTable(pm: (cell: string, h: number) => number, o3 = 60): OutdoorTable {
  const t: OutdoorTable = new Map()
  for (const p of [HOME, WORK]) {
    const k = cellKey(cellOf(p.lat, p.lon))
    t.set(k, new Map(hoursBetween(DAY_START - 86400000, DAY_START + 2 * 86400000).map((h) => [hourKey(h), { pm25: pm(k, h), no2: 20, o3, uv: 5 }])))
  }
  return t
}

// Local 00:00-08:00 and 18:00-24:00 at home, 09:00-17:00 at work.
const at = (localHour: number) => new Date(DAY_START + localHour * 3600000).toISOString()
const visits = [
  { start_at: at(-2), end_at: at(8.5), ...HOME, home: true },
  { start_at: at(9), end_at: at(17), ...WORK, home: false },
  { start_at: at(18), end_at: at(26), ...HOME, home: true },
]

test('cells round to 0.1 degree and keys are stable', () => {
  assert.equal(cellKey(cellOf(25.03, 121.56)), '25.0,121.6')
  assert.equal(cellKey(cellOf(-0.04, -0.06)), '0.0,-0.1')
})

test('an hour goes to the visit covering most of it, else a near fix, else nowhere', () => {
  const h = DAY_START + 10 * 3600000
  assert.deepEqual(locate(h, visits, [], HOME, h), { ...WORK, home: false })
  const lone = DAY_START + 17.5 * 3600000
  assert.equal(locate(lone, [], [], HOME, lone), null)
  assert.deepEqual(locate(lone, [], [{ ts: new Date(lone + 3600000).toISOString(), ...HOME }], HOME, lone), { ...HOME, home: true })
})

test('indoor values are time-weighted and held at most 20 minutes', () => {
  const h = DAY_START
  const series = [{ t: h - 5 * 60000, value: 10 }, { t: h + 30 * 60000, value: 30 }]
  // ticks at :05 and :15 hold 10 (10 and 20 min old), :25 has nothing (30 min old), :35 and :45 hold 30, :55 is 25 min old
  assert.equal(indoorHour(h, series), (10 + 10 + 30 + 30) / 4)
  assert.equal(indoorHour(h, [{ t: h - 60 * 60000, value: 10 }]), null)
})

test('highest 8-hour mean needs 6 of 8 hours', () => {
  assert.equal(mda8([10, 10, 10, 10, 10, 10, 10, 10, 50, 50, 50, 50, 50, 50, 50, 50]), 50)
  assert.equal(mda8([10, null, null, null, 10, 10, 10, 10]), null)
})

test('a day: indoor at home with a sensor, outdoor at work, the dose split and the reference', () => {
  const outdoor = outdoorTable((k) => (k === cellKey(cellOf(WORK.lat, WORK.lon)) ? 30 : 20))
  const indoor = hours.flatMap((h) => [0, 15, 30, 45].map((m) => ({ measured_at: new Date(h + m * 60000).toISOString(), metric: 'pm25_ugm3', value: 8 })))
  const inp = { visits, fixes: [], home: HOME, indoor, sessions: [], outdoor, clock, wallToUtc }
  const rows = hourRows(hours, inp, DAY_START + 30 * 3600000)
  const [day] = summarizeDays(rows, outdoorAtHome(hours, HOME, outdoor), inp, 0)
  assert.equal(day.date, '2026-11-10')
  assert.equal(day.coverage_h, 23, '17:00-18:00 has no visit or fix')
  assert.equal(day.sources.indoor, 15)
  assert.equal(day.sources.outdoor_away, 8)
  assert.equal(day.pm25_indoor_ugm3, 8)
  assert.equal(day.pm25_ugm3, Math.round(((15 * 8 + 8 * 30) / 23) * 10) / 10)
  assert.equal(day.pm25_outdoor_home_ugm3, 20)
  assert.equal(day.dose_home_share, Math.round((120 / 360) * 100) / 100)
  assert.equal(day.hours.length, 24)
})

test('without an indoor sensor home hours use the outdoor level there, labelled as such', () => {
  const outdoor = outdoorTable(() => 12)
  const inp = { visits, fixes: [], home: HOME, indoor: [], sessions: [], outdoor, clock, wallToUtc }
  const [day] = summarizeDays(hourRows(hours, inp, 0), new Map(), inp, 0)
  assert.equal(day.sources.indoor, 0)
  assert.equal(day.sources.outdoor_home, 15)
  assert.equal(day.pm25_ugm3, 12)
})

test('an outdoor session takes its hours from the outdoor level, even at home', () => {
  const outdoor = outdoorTable(() => 40)
  const indoor = hours.flatMap((h) => [0, 15, 30, 45].map((m) => ({ measured_at: new Date(h + m * 60000).toISOString(), metric: 'pm25_ugm3', value: 5 })))
  // a 60-minute run at 06:00 local (logged wall clock)
  const sessions = [{ start_time: '2026-11-10T06:00:00+00:00', duration_min: 60, type: 'run' }]
  const inp = { visits, fixes: [], home: HOME, indoor, sessions, outdoor, clock, wallToUtc }
  const [day] = summarizeDays(hourRows(hours, inp, 0), new Map(), inp, 0)
  assert.equal(day.sources.session, 1)
  assert.equal(day.sessions.length, 1)
  assert.equal(day.sessions[0].pm25, 40)
  assert.ok((day.dose_session_share ?? 0) > 0)
})

test('the summary flags guideline days and the ways to improve fire on the numbers', () => {
  const day = (date: string, pm: number, extra: Record<string, unknown> = {}) => ({
    date, coverage_h: 24, pm25_ugm3: pm, pm25_indoor_ugm3: null, pm25_outdoor_home_ugm3: pm, no2_ugm3: 15, o3_mda8_ugm3: 80, uv_max: 7, uv_session_max: null,
    co2_night_ppm: 1250, co2_night_poor_h: 3, dose_home_share: 0.8, dose_session_share: 0.05,
    sources: { indoor: 0, session: 1, outdoor_home: 18, outdoor_away: 5 }, hours: [],
    sessions: [{ start: `${date}T06:00:00+00:00`, type: 'run', minutes: 60, pm25: pm, o3: 50, uv: 7 }], ...extra,
  })
  const days = Array.from({ length: 10 }, (_, i) => day(`2026-11-${String(i + 1).padStart(2, '0')}`, i < 4 ? 25 : 10))
  const s = summarize(days, false)
  assert.equal(s.pm25_days_over_24h, 4)
  assert.equal(s.pm25_mean, 16)
  assert.equal(s.states.pm25, 'bad')
  assert.equal(s.sessions_poor_air, 4)
  const ids = s.improvements.map((i) => i.id)
  // Measuring comes first: without a sensor the home level is the outdoor stand-in.
  assert.deepEqual(ids, ['measure_indoor', 'purifier', 'ventilate_bedroom', 'session_timing', 'uv'])
  // clean air, a sensor, a ventilated room: nothing to suggest
  const clean = summarize(days.map((d) => ({ ...d, pm25_ugm3: 3, pm25_indoor_ugm3: 3, pm25_outdoor_home_ugm3: 3, co2_night_ppm: 700, co2_night_poor_h: 0, sessions: [] })), true)
  assert.deepEqual(improvements(clean), [])
  assert.ok(WHO_PM25_24H === 15)
})

test('runAir: reads softly, fetches only missing cells, writes days and the summary', async () => {
  const now = new Date('2026-11-10T20:30:00Z') // 04:30 on 11-11 local
  const placeRows = [{ id: 'p-home', lat: HOME.lat, lon: HOME.lon, category: 'home', source: 'home_setting', user_id: 'u' }]
  const visitRows = [{ start_at: '2026-11-01T00:00:00.000Z', end_at: null, lat: HOME.lat, lon: HOME.lon, place_id: 'p-home', user_id: 'u' }]
  const admin = fakeAdmin({
    daily_context: context.map((c) => ({ ...c, user_id: 'u' })),
    places: placeRows,
    place_visits: visitRows,
  }, ['home_env_readings'])
  const asked: string[][] = []
  const fetcher = async (cells: { lat: number; lon: number }[], from: string, to: string) => {
    asked.push([cells.map(cellKey).join('|'), from, to])
    return cells.map((c) => ({
      cell: cellKey(c),
      hours: new Map(hoursBetween(Date.parse(from + 'T00:00:00Z'), Date.parse(to + 'T00:00:00Z') + 86400000).map((h) => [hourKey(h), { pm25: 18, no2: 12, o3: 70, uv: 3 }])),
    }))
  }
  const out = await runAir(admin, 'u', now, fetcher)
  assert.equal(out.error, undefined, String(out.error))
  assert.deepEqual(out.missing, ['home_env_readings'])
  assert.equal(asked.length, 1)
  assert.equal(asked[0][0], '25.0,121.6')
  const days = admin.upserts.filter((u) => u.table === 'air_exposure_daily').flatMap((u) => (Array.isArray(u.row) ? u.row : [u.row]))
  assert.equal(days.length, 35, 'first run backfills 35 local days')
  const full = days.find((d) => d.date === '2026-11-10')!
  assert.equal(full.pm25_ugm3, 18)
  assert.equal(full.coverage_h, 24)
  const cache = admin.upserts.filter((u) => u.table === 'outdoor_air_hourly')
  assert.ok(cache.length > 0)
  const summary = admin.upserts.find((u) => u.table === 'analysis_results')!
  const row = (Array.isArray(summary.row) ? summary.row[0] : summary.row) as Record<string, unknown>
  assert.equal(row.kind, 'air_exposure')
  assert.equal(row.as_of, '2026-11-11')
  assert.equal((row.payload as { pm25_mean: number }).pm25_mean, 18)
})
