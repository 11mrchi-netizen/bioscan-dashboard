import assert from 'node:assert/strict'
import { test } from 'node:test'
import { stationarySeries, stepChangeSeries } from './fixtures.ts'
import { runSeriesBreaks } from './runSeriesBreaks.ts'
import { dateAxis } from './stats.ts'
import { CORE_VERSION } from './version.ts'

type Row = Record<string, unknown>

// In-memory stand-in for the Supabase query builder: just enough chaining for runSeriesBreaks.
function fakeAdmin(tables: Record<string, Row[]>) {
  const upserts: Row[] = []
  const deletes: { table: string; filters: [string, string, unknown][] }[] = []
  const admin = {
    upserts,
    deletes,
    from(table: string) {
      const filters: [string, string, unknown][] = []
      const q = {
        select() { return q },
        eq(c: string, v: unknown) { filters.push(['eq', c, v]); return q },
        gte(c: string, v: unknown) { filters.push(['gte', c, v]); return q },
        lt(c: string, v: unknown) { filters.push(['lt', c, v]); return q },
        order() { return q },
        delete() { (q as { __delete?: boolean }).__delete = true; return q },
        upsert(row: Row) { upserts.push(row); return Promise.resolve({ error: null }) },
        then(resolve: (v: { data: Row[]; error: null }) => void) {
          const rows = (tables[table] ?? []).filter((r) => filters.every(([op, c, v]) => (op === 'eq' ? r[c] === v : op === 'gte' ? String(r[c]) >= String(v) : String(r[c]) < String(v))))
          if ((q as { __delete?: boolean }).__delete) deletes.push({ table, filters })
          resolve({ data: rows, error: null })
        },
      }
      return q
    },
  }
  return admin
}

const USER = 'u1'
const dates = (n: number, start = '2026-05-01') => dateAxis(start, new Date(Date.parse(start + 'T00:00:00Z') + (n - 1) * 86400000).toISOString().slice(0, 10))
const rows = (ds: string[], col: string, vs: (number | null)[]): Row[] => ds.map((d, i) => ({ user_id: USER, date: d, [col]: vs[i] }))

test('publishes one series_break row per metric with data, skips empty ones, honours the sleep floor', async () => {
  const ds = dates(140)
  const hrv = stepChangeSeries(1, 140, 110, 62, 6, 0, 3)
  const hours = stationarySeries(5, 140, 7, 0.5).map((v, i) => (i === 3 ? 0.08 : v)) // one implausible night
  const admin = fakeAdmin({ wearable_daily: rows(ds, 'hrv', hrv), sleep_daily: rows(ds, 'hours', hours) })
  const res = await runSeriesBreaks(admin, USER, new Date('2026-09-18T12:00:00Z'))

  const byMetric = Object.fromEntries(res.results.map((r) => [r.metric, r]))
  assert.equal(byMetric.hrv.state, 'Unstable')
  assert.equal(byMetric.hrv.breaks?.length, 1)
  assert.equal(byMetric.hrv.breaks?.[0].status, 'candidate')
  assert.equal(byMetric.sleep_hours.n, 139) // the 0.08 h night is excluded, not zero-filled
  assert.equal(byMetric.rhr.skipped, 'no data')

  const hrvRow = admin.upserts.find((u) => u.key === 'hrv') as Row
  assert.equal(hrvRow.kind, 'series_break')
  assert.equal(hrvRow.engine_version, CORE_VERSION)
  assert.equal(hrvRow.as_of, ds[139])
  assert.equal(hrvRow.confidence_need, 42)
  assert.ok(typeof hrvRow.inputs_hash === 'string' && (hrvRow.inputs_hash as string).length === 8)
  assert.ok(admin.deletes.some((d) => d.table === 'analysis_results' && d.filters.some(([op, c]) => op === 'lt' && c === 'as_of')))
})

test('provenance origin changes confirm a break; user decisions are applied', async () => {
  const ds = dates(140)
  const hrv = stepChangeSeries(1, 140, 110, 62, 6, 0, 3)
  const first = await runSeriesBreaks(fakeAdmin({ wearable_daily: rows(ds, 'hrv', hrv) }), USER)
  const breakDate = first.results.find((r) => r.metric === 'hrv')!.breaks![0].date

  const provenance: Row[] = [
    { user_id: USER, date: ds[100], field: 'wearable_daily.hrv', origin: 'com.google.android.apps.fitness' },
    { user_id: USER, date: breakDate, field: 'wearable_daily.hrv', origin: 'com.huami.watch.hmwatchmanager' },
  ]
  const confirmed = await runSeriesBreaks(fakeAdmin({ wearable_daily: rows(ds, 'hrv', hrv), daily_field_provenance: provenance }), USER)
  assert.equal(confirmed.results.find((r) => r.metric === 'hrv')!.breaks![0].status, 'confirmed')

  const dismissed = await runSeriesBreaks(
    fakeAdmin({ wearable_daily: rows(ds, 'hrv', hrv), series_break_decisions: [{ user_id: USER, metric: 'hrv', break_date: breakDate, decision: 'real_change' }] }),
    USER,
  )
  assert.equal(dismissed.results.find((r) => r.metric === 'hrv')!.breaks![0].status, 'dismissed')
})
