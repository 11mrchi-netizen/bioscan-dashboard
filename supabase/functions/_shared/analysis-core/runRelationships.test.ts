import assert from 'node:assert/strict'
import { test } from 'node:test'
import { plantedDataset } from './fixtures.ts'
import { HYPOTHESES } from './hypotheses.ts'
import { buildFrame, runRelationships } from './runRelationships.ts'
import type { RawInputs } from './runRelationships.ts'
import { CORE_VERSION } from './version.ts'

type Row = Record<string, unknown>

// In-memory stand-in for the Supabase query builder: select / eq / gte / lt / order / range / delete / upsert.
function fakeAdmin(tables: Record<string, Row[]>) {
  const upserts: Row[] = []
  const deletes: { table: string; filters: [string, string, unknown][] }[] = []
  return {
    upserts,
    deletes,
    from(table: string) {
      const filters: [string, string, unknown][] = []
      let window: [number, number] | null = null
      let isDelete = false
      const q = {
        select() { return q },
        eq(c: string, v: unknown) { filters.push(['eq', c, v]); return q },
        gte(c: string, v: unknown) { filters.push(['gte', c, v]); return q },
        lt(c: string, v: unknown) { filters.push(['lt', c, v]); return q },
        order() { return q },
        range(a: number, b: number) { window = [a, b]; return q },
        delete() { isDelete = true; return q },
        upsert(row: Row) { upserts.push(row); return Promise.resolve({ error: null }) },
        then(resolve: (v: { data: Row[]; error: null }) => void) {
          let rows = (tables[table] ?? []).filter((r) => filters.every(([op, c, v]) => (op === 'eq' ? r[c] === v : op === 'gte' ? String(r[c]) >= String(v) : String(r[c]) < String(v))))
          if (isDelete) deletes.push({ table, filters })
          if (window) rows = rows.slice(window[0], window[1] + 1)
          resolve({ data: rows, error: null })
        },
      }
      return q
    },
  }
}

const USER = 'u1'

test('buildFrame: exposures are zero only where the logging model says so, illness days are excluded', () => {
  const raw: RawInputs = {
    wearable: [
      { date: '2026-03-02', hrv: 60, rhr: 55, steps: 9000 },
      { date: '2026-03-03', hrv: 58, rhr: 56, steps: 8000 },
      { date: '2026-03-04', hrv: 0, rhr: null, steps: null }, // HRV 0 is not a reading
    ],
    sleep: [{ date: '2026-03-02', hours: 7 }, { date: '2026-03-03', hours: 0.5 }], // 0.5 h is a fault
    wellbeing: [{ date: '2026-03-02', energy: 6, mood: null, stress: null, soreness: null }],
    meals: [
      { logged_at: '2026-03-02T12:00:00Z', meal_items: [{ alcohol_g: null, caffeine_mg: 80 }] }, // morning-free, no drink
      { logged_at: '2026-03-03T19:00:00Z', meal_items: [{ alcohol_g: 19.7, caffeine_mg: 95 }] },
      { logged_at: '2026-03-03T08:00:00Z', meal_items: null },
    ],
    sessions: [
      { start_time: '2026-03-03T07:00:00Z', duration_min: 45, type: 'strength' },
      { start_time: '2026-03-02T07:00:00Z', duration_min: 10, type: 'run' }, // too short
      { start_time: '2026-03-02T18:00:00Z', duration_min: 40, type: 'walk' }, // a walk is not training
    ],
    recovery: [{ date: '2026-03-03' }],
    illnesses: [{ start_date: '2026-03-04', end_date: '2026-03-05' }],
  }
  const f = buildFrame(raw, '2026-03-06', 6) // 03-01 .. 03-06
  assert.deepEqual(f.dates, ['2026-03-01', '2026-03-02', '2026-03-03', '2026-03-04', '2026-03-05', '2026-03-06'])
  assert.deepEqual(f.series.hrv, [null, 60, 58, null, null, null])
  assert.deepEqual(f.series.sleep_hours, [null, 7, null, null, null, null])
  assert.deepEqual(f.series.alcohol_any, [null, 0, 1, null, null, null]) // no food log on 03-01 and after: unknown, not zero
  assert.deepEqual(f.series.caffeine_pm_any, [null, 0, 1, null, null, null]) // 80 mg at 12:00 is before 14:00; 95 mg at 19:00 counts
  assert.deepEqual(f.series.training_day, [null, 0, 1, 0, null, null]) // a 10 min session is not training; 03-04 has a watch record
  assert.deepEqual(f.series.recovery_any, [null, null, 1, 0, 0, 0]) // zero only from the first logged session
  assert.deepEqual(f.excluded, [false, false, false, true, true, true]) // illness through the day after it ended
})

test('runRelationships publishes one row per hypothesis from raw tables and replaces older rows', async () => {
  const ds = plantedDataset(20261010, 200, '2026-01-05', 10_000, 10_000, [])
  const today = ds.dates[ds.dates.length - 1]
  const tables: Record<string, Row[]> = {
    wearable_daily: ds.dates.map((d, i) => ({ user_id: USER, date: d, hrv: ds.hrv[i], rhr: ds.rhr[i], steps: null })),
    sleep_daily: ds.dates.map((d, i) => ({ user_id: USER, date: d, hours: ds.sleepH[i] })),
    meals: ds.dates.map((d, i) => ({ user_id: USER, logged_at: `${d}T12:00:00Z`, meal_items: [{ alcohol_g: ds.alcoholG[i] > 0 ? ds.alcoholG[i] : null, caffeine_mg: null }] })),
    exercise_sessions: ds.dates.flatMap((d, i) => (ds.train[i] ? [{ user_id: USER, start_time: `${d}T07:00:00Z`, duration_min: 45, type: 'strength' }] : [])),
    recovery_sessions: ds.dates.flatMap((d, i) => (ds.sauna[i] ? [{ user_id: USER, date: d }] : [])),
    illnesses: [],
  }
  const admin = fakeAdmin(tables)
  const run = await runRelationships(admin, USER, new Date(`${today}T20:00:00Z`))
  assert.equal(run.error, undefined)
  assert.equal(run.results!.length, HYPOTHESES.length)
  assert.equal(admin.upserts.length, HYPOTHESES.length)

  const row = (k: string) => admin.upserts.find((u) => u.key === k) as Row
  assert.equal(row('alcohol_hrv').kind, 'relationship')
  assert.equal(row('alcohol_hrv').state, 'supported')
  assert.equal(row('alcohol_hrv').engine_version, CORE_VERSION)
  assert.equal(row('alcohol_hrv').as_of, today)
  assert.equal(row('training_hrv').state, 'insufficient') // fixed weekly schedule
  assert.equal(row('steps_hrv').state, 'insufficient') // no steps recorded
  assert.equal(row('caffeine_pm_sleep').state, 'insufficient') // no caffeine logged
  assert.equal((row('alcohol_hrv').payload as Row).catalogueVersion, '1.0.0')
  assert.ok(admin.deletes.some((d) => d.table === 'analysis_results' && d.filters.some(([op, c]) => op === 'lt' && c === 'as_of')))
})
