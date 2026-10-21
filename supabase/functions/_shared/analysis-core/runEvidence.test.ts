import assert from 'node:assert/strict'
import { test } from 'node:test'
import { plantedDataset } from './fixtures.ts'
import { loadFrame } from './runRelationships.ts'
import type { LoadedFrame } from './runRelationships.ts'
import { EVENT_CURVES, LAG_CURVES } from './evidence.ts'
import { runEvidence } from './runEvidence.ts'
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

test('runEvidence publishes event curves, case-crossover tables and lag curves, replacing older days', async () => {
  const ds = plantedDataset(20261010, 300, '2026-01-05', 10_000, 10_000, [])
  const today = ds.dates[ds.dates.length - 1]
  const admin = fakeAdmin({
    wearable_daily: ds.dates.map((d, i) => ({ user_id: USER, date: d, hrv: ds.hrv[i], rhr: ds.rhr[i], steps: 8000 })),
    sleep_daily: ds.dates.map((d, i) => ({ user_id: USER, date: d, hours: ds.sleepH[i] })),
    meals: ds.dates.map((d, i) => ({ user_id: USER, logged_at: `${d}T12:00:00Z`, meal_items: [{ alcohol_g: ds.alcoholG[i] > 0 ? ds.alcoholG[i] : null, caffeine_mg: null }] })),
    exercise_sessions: ds.dates.flatMap((d, i) => (ds.train[i] ? [{ user_id: USER, start_time: `${d}T07:00:00Z`, duration_min: 45, type: 'strength' }] : [])),
    recovery_sessions: [],
    illnesses: [],
  })
  const now = new Date(`${today}T20:30:00Z`)
  const loaded = await loadFrame(admin, USER, now)
  assert.ok(!('error' in loaded))
  const run = await runEvidence(admin, loaded as LoadedFrame, now)
  assert.equal(run.error, undefined)
  const kinds = (k: string) => admin.upserts.filter((u) => u.kind === k)
  assert.equal(kinds('event_curve').length, EVENT_CURVES.length)
  assert.equal(kinds('case_crossover').length, 3)
  assert.equal(kinds('lag_curve').length, LAG_CURVES.length)
  for (const u of admin.upserts) {
    assert.equal(u.engine_version, CORE_VERSION)
    assert.equal(u.as_of, today)
  }
  const alcohol = kinds('event_curve').find((u) => u.key === 'alcohol_hrv') as Row
  assert.equal(alcohol.state, 'ready')
  const curve = alcohol.payload as { peak: { k: number; value: number } | null; events: number }
  assert.ok(curve.events >= 8)
  assert.equal(curve.peak?.k, 1) // the planted model: -8 ms the morning after
  assert.ok((curve.peak?.value as number) < 0)
  assert.equal((kinds('event_curve').find((u) => u.key === 'late_meal_sleep') as Row).state, 'not_yet') // never logged
  assert.equal(admin.deletes.filter((d) => d.filters.some(([op, c]) => op === 'lt' && c === 'as_of')).length, admin.upserts.length)
})
