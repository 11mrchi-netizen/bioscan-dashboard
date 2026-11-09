import assert from 'node:assert/strict'
import { test } from 'node:test'
import { stationarySeries, stepChangeSeries } from './fixtures.ts'
import { runSeriesBreaks, sustainedOriginChanges } from './runSeriesBreaks.ts'
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
        in(c: string, v: unknown[]) { filters.push(['in', c, v]); return q },
        order() { return q },
        range(a: number, b: number) { (q as { __range?: [number, number] }).__range = [a, b]; return q },
        delete() { (q as { __delete?: boolean }).__delete = true; return q },
        upsert(row: Row | Row[]) { for (const r of Array.isArray(row) ? row : [row]) upserts.push(r); return Promise.resolve({ error: null }) },
        then(resolve: (v: { data: Row[]; error: null }) => void) {
          const all = (tables[table] ?? []).filter((r) => filters.every(([op, c, v]) => (op === 'eq' ? r[c] === v : op === 'in' ? (v as unknown[]).includes(r[c]) : op === 'gte' ? String(r[c]) >= String(v) : String(r[c]) < String(v))))
          const range = (q as { __range?: [number, number] }).__range
          const rows = range ? all.slice(range[0], range[1] + 1) : all
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

  // One provenance row per day, as the sync writes them: one source before the break, another from the break on.
  const switchAt = ds.indexOf(breakDate)
  const provenance: Row[] = ds.map((d, i) => ({ user_id: USER, date: d, field: 'wearable_daily.hrv', origin: i < switchAt ? 'com.google.android.apps.fitness' : 'com.huami.watch.hmwatchmanager' }))
  const confirmed = await runSeriesBreaks(fakeAdmin({ wearable_daily: rows(ds, 'hrv', hrv), daily_field_provenance: provenance }), USER)
  assert.equal(confirmed.results.find((r) => r.metric === 'hrv')!.breaks![0].status, 'confirmed')

  const dismissed = await runSeriesBreaks(
    fakeAdmin({ wearable_daily: rows(ds, 'hrv', hrv), series_break_decisions: [{ user_id: USER, metric: 'hrv', break_date: breakDate, decision: 'real_change' }] }),
    USER,
  )
  assert.equal(dismissed.results.find((r) => r.metric === 'hrv')!.breaks![0].status, 'dismissed')
})

test('analysis review B10: only device-sensitive metrics are confirmed by a source change, and only a lasting one', async () => {
  // Alternating sources (phone one day, watch the next) never count as a switch; a switch that holds does.
  const alternating = Array.from({ length: 10 }, (_, i) => ({ date: dates(10)[i], origin: i % 2 ? 'watch' : 'phone' }))
  assert.deepEqual(sustainedOriginChanges(alternating), [])
  const lasting = dates(10).map((d, i) => ({ date: d, origin: i < 4 ? 'phone' : 'watch' }))
  assert.deepEqual(sustainedOriginChanges(lasting), [dates(10)[4]])

  // Resting heart rate (not device-sensitive) keeps a candidate break even when a source switch sits on it.
  const ds = dates(140)
  const rhr = stepChangeSeries(3, 140, 110, 52, 3, 0, 8)
  const first = await runSeriesBreaks(fakeAdmin({ wearable_daily: rows(ds, 'rhr', rhr) }), USER)
  const breakDate = first.results.find((r) => r.metric === 'rhr')!.breaks![0].date
  const switchAt = ds.indexOf(breakDate)
  const provenance: Row[] = ds.map((d, i) => ({ user_id: USER, date: d, field: 'wearable_daily.rhr', origin: i < switchAt ? 'phone' : 'watch' }))
  const res = await runSeriesBreaks(fakeAdmin({ wearable_daily: rows(ds, 'rhr', rhr), daily_field_provenance: provenance }), USER)
  assert.equal(res.results.find((r) => r.metric === 'rhr')!.breaks![0].status, 'candidate')
})

test('the provenance read pages past the row cap, so a recent source switch is seen', async () => {
  const ds = dates(140)
  const hrv = stepChangeSeries(1, 140, 110, 62, 6, 0, 3)
  const first = await runSeriesBreaks(fakeAdmin({ wearable_daily: rows(ds, 'hrv', hrv) }), USER)
  const breakDate = first.results.find((r) => r.metric === 'hrv')!.breaks![0].date
  const switchAt = ds.indexOf(breakDate)
  // Ten fields a day: the switch (day ~110) sits past the first 1,000 provenance rows.
  const fields = ['wearable_daily.hrv', ...Array.from({ length: 9 }, (_, k) => `other.f${k}`)]
  const provenance: Row[] = ds.flatMap((d, i) => fields.map((field) => ({ user_id: USER, date: d, field, origin: i < switchAt ? 'phone' : 'watch' })))
  assert.ok(switchAt * fields.length > 1000)
  const res = await runSeriesBreaks(fakeAdmin({ wearable_daily: rows(ds, 'hrv', hrv), daily_field_provenance: provenance }), USER)
  assert.equal(res.results.find((r) => r.metric === 'hrv')!.breaks![0].status, 'confirmed')
})

test('the timing hook reports the series-break steps and changes nothing', async () => {
  const ds = dates(140)
  const tables = () => ({ wearable_daily: rows(ds, 'hrv', stepChangeSeries(1, 140, 110, 62, 6, 0, 3)), sleep_daily: rows(ds, 'hours', stationarySeries(5, 140, 7, 0.5)) })
  const a = fakeAdmin(tables())
  const b = fakeAdmin(tables())
  const steps: string[] = []
  const traced = await runSeriesBreaks(a, USER, new Date('2026-09-18T12:00:00Z'), (step) => steps.push(step))
  const plain = await runSeriesBreaks(b, USER, new Date('2026-09-18T12:00:00Z'))
  assert.deepEqual(traced, plain)
  assert.deepEqual(a.upserts, b.upserts)
  assert.deepEqual(steps.slice(0, 2), ['provenance', 'reads'])
  assert.ok(steps.includes('origin.hrv') && steps.includes('result.hrv') && steps.includes('compute'))
  assert.equal(steps.at(-1), 'publish')
})

test('provenance of fields the engine does not use is not read and changes nothing', async () => {
  const ds = dates(140)
  const hrv = stepChangeSeries(1, 140, 110, 62, 6, 0, 3)
  const base = ds.map((d, i) => ({ user_id: USER, date: d, field: 'wearable_daily.hrv', origin: i < 110 ? 'phone' : 'watch' }))
  const noise = ds.flatMap((d) => [{ user_id: USER, date: d, field: 'meals.calories', origin: 'app' }, { user_id: USER, date: d, field: 'wearable_daily.vo2max', origin: 'watch' }])
  const withNoise = fakeAdmin({ wearable_daily: rows(ds, 'hrv', hrv), daily_field_provenance: [...base, ...noise] })
  const without = fakeAdmin({ wearable_daily: rows(ds, 'hrv', hrv), daily_field_provenance: base })
  const a = await runSeriesBreaks(withNoise, USER, new Date('2026-09-18T12:00:00Z'))
  const b = await runSeriesBreaks(without, USER, new Date('2026-09-18T12:00:00Z'))
  assert.deepEqual(a, b)
  assert.deepEqual(withNoise.upserts, without.upserts)
})
