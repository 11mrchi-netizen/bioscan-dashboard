// Analysis hub phase C: the nightly integrity publisher against an in-memory client.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { buildIntegrity, localToday, runIntegrity } from './runIntegrity.ts'
import type { SeriesFile } from './seriesContract.ts'

const file = JSON.parse(readFileSync(new URL('../metric-registry/metric-series.json', import.meta.url), 'utf8')) as SeriesFile

type Row = Record<string, unknown>
const cmp = (a: unknown, b: unknown) => (String(a) < String(b) ? -1 : String(a) > String(b) ? 1 : 0)

function fakeAdmin(tables: Record<string, Row[]>) {
  const upserts: Row[] = []
  return {
    upserts,
    from(table: string) {
      const preds: ((r: Row) => boolean)[] = []
      let order: [string, number][] = []
      let lim: number | null = null
      let range: [number, number] | null = null
      const q = {
        select() { return q },
        eq(c: string, v: unknown) { preds.push((r) => r[c] === v); return q },
        gte(c: string, v: unknown) { preds.push((r) => r[c] !== null && cmp(r[c], v) >= 0); return q },
        lte(c: string, v: unknown) { preds.push((r) => r[c] !== null && cmp(r[c], v) <= 0); return q },
        in(c: string, vs: unknown[]) { preds.push((r) => vs.includes(r[c])); return q },
        not(c: string, op: string, v: unknown) { if (op === 'is' && v === null) preds.push((r) => r[c] !== null && r[c] !== undefined); return q },
        order(c: string, o?: { ascending?: boolean }) { order.push([c, o?.ascending === false ? -1 : 1]); return q },
        limit(n: number) { lim = n; return q },
        range(a: number, b: number) { range = [a, b]; return q },
        upsert(row: Row) { upserts.push(row); return Promise.resolve({ error: null }) },
        then(resolve: (v: { data: Row[]; error: null }) => void) {
          let rows = (tables[table] ?? []).filter((r) => preds.every((p) => p(r)))
          rows = [...rows].sort((a, b) => { for (const [c, d] of order) { const x = cmp(a[c], b[c]); if (x) return x * d } return 0 })
          if (range) rows = rows.slice(range[0], range[1] + 1)
          if (lim !== null) rows = rows.slice(0, lim)
          order = []
          resolve({ data: rows, error: null })
        },
      }
      return q
    },
  }
}

const U = 'u1'
const NOW = new Date('2026-10-27T03:00:00Z') // 11:00 in Taipei
const day = (n: number) => new Date(Date.parse('2026-10-27T00:00:00Z') - n * 864e5).toISOString().slice(0, 10)

test('local today uses the default offset', () => {
  assert.equal(localToday(new Date('2026-10-27T17:00:00Z'), 480), '2026-10-28')
  assert.equal(localToday(NOW, 480), '2026-10-27')
})

test('the nightly report covers 28 local days, finds first and newest readings, and reads the wear signal', async () => {
  let id = 1
  const wearable: Row[] = []
  for (let n = 60; n >= 0; n--) {
    if (n === 5 || n === 6) continue // two days without a row at all
    wearable.push({ id: id++, user_id: U, date: day(n), steps: n === 10 ? 0 : 8000, hrv: n > 3 ? 55 : null, hr_timeseries: n <= 40 ? [{ bpm: 60 }] : null, source: 'zepp' })
  }
  wearable.push({ id: id++, user_id: 'someone-else', date: day(1), steps: 1, hrv: 999, source: 'zepp' })
  const sessions: Row[] = [
    { id: 900, user_id: U, type: 'run', start_time: `${day(2)}T07:00:00+00:00`, duration_min: 40, distance_km: 8, source: 'zepp' },
    { id: 901, user_id: U, type: 'walk', start_time: `${day(2)}T09:00:00+00:00`, duration_min: 400, distance_km: 3, source: 'zepp' },
  ]
  const meals: Row[] = [{ id: 950, user_id: U, logged_at: '2026-09-09T17:00:00+00:00', calories: 500, source: 'manual' }]
  const admin = fakeAdmin({ wearable_daily: wearable, exercise_sessions: sessions, meals })
  const r = await buildIntegrity(admin, U, file, NOW)
  assert.deepEqual([r.start, r.end, r.wearSignal], [day(27), day(0), true])
  const steps = r.streams.find((s) => s.seriesId === 'steps')!
  assert.deepEqual([steps.daysDue, steps.daysObserved, steps.lastSeen, steps.freshness], [27, 25, day(0), 'fresh']) // today is not yet due
  assert.ok(r.issues.some((i) => i.kind === 'missing' && i.seriesId === 'steps' && i.firstDate === day(6) && i.lastDate === day(5)))
  assert.ok(r.issues.some((i) => i.kind === 'suspect_zero' && i.firstDate === day(10)))
  // hrv: worn every day, last value 4 days ago -> late, and the band was worn since: an ingestion gap
  const hrv = r.streams.find((s) => s.seriesId === 'hrv')!
  assert.deepEqual([hrv.lastSeen, hrv.freshness], [day(4), 'late'])
  assert.match(r.issues.find((i) => i.kind === 'stale' && i.seriesId === 'hrv')!.detail, /ingestion gap/)
  // another user's rows never leak in
  assert.ok(!r.issues.some((i) => i.kind === 'invalid' && i.seriesId === 'hrv'))
  // only runs count for run time; the walk does not
  const runs = r.streams.find((s) => s.seriesId === 'run_duration')!
  assert.deepEqual([runs.daysWithValue, runs.lastSeen], [1, day(2)])
  // the backfilled UTC meal lands on the next local day
  assert.equal(r.streams.find((s) => s.seriesId === 'meal_energy')!.lastSeen, '2026-09-10')
})

test('publishes one integrity row per night', async () => {
  const admin = fakeAdmin({ wearable_daily: [{ id: 1, user_id: U, date: day(0), steps: 9000, source: 'zepp' }] })
  const res = await runIntegrity(admin, U, file, NOW)
  assert.equal(res.error, undefined)
  assert.equal(admin.upserts.length, 1)
  const row = admin.upserts[0] as { kind: string; key: string; as_of: string; state: string; payload: { streams: unknown[] } }
  assert.deepEqual([row.kind, row.key, row.as_of], ['integrity', 'report', '2026-10-27'])
  assert.equal(row.payload.streams.length, file.series.length)
  assert.ok(['Critical', 'Warning', 'Clear'].includes(row.state))
})
