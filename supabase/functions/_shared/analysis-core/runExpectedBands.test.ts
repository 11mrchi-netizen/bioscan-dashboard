import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { ExpectedBandResult } from './expectedBand.ts'
import { loadFrame } from './runRelationships.ts'
import type { LoadedFrame } from './runRelationships.ts'
import { runExpectedBands } from './runExpectedBands.ts'
import { typicalitySeries } from './typicalityFixtures.ts'
import { CORE_VERSION } from './version.ts'

type Row = Record<string, unknown>

// In-memory stand-in for the Supabase query builder (select / eq / gte / lt / order / range / delete / upsert).
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
        in(c: string, v: unknown[]) { filters.push(['in', c, v]); return q },
        order() { return q },
        range(a: number, b: number) { window = [a, b]; return q },
        delete() { isDelete = true; return q },
        upsert(row: Row | Row[]) { for (const r of Array.isArray(row) ? row : [row]) upserts.push(r); return Promise.resolve({ error: null }) },
        then(resolve: (v: { data: Row[]; error: null }) => void) {
          let rows = (tables[table] ?? []).filter((r) => filters.every(([op, c, v]) => (op === 'eq' ? r[c] === v : op === 'in' ? (v as unknown[]).includes(r[c]) : op === 'gte' ? String(r[c]) >= String(v) : String(r[c]) < String(v))))
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

function tablesFor(f: ReturnType<typeof typicalitySeries>, extra: Record<string, Row[]> = {}): Record<string, Row[]> {
  return {
    wearable_daily: f.dates.map((d, i) => ({ user_id: USER, date: d, hrv: f.hrv[i], rhr: f.rhr[i], steps: f.steps[i] })),
    sleep_daily: f.dates.map((d, i) => ({ user_id: USER, date: d, hours: f.sleepHours[i], deep_min: f.deepRemMin[i] * 0.6, rem_min: f.deepRemMin[i] * 0.4 })),
    illnesses: [],
    ...extra,
  }
}

async function publish(f: ReturnType<typeof typicalitySeries>, extra: Record<string, Row[]> = {}) {
  const admin = fakeAdmin(tablesFor(f, extra))
  const now = new Date(`${f.dates[f.dates.length - 1]}T20:30:00Z`)
  const loaded = await loadFrame(admin, USER, now)
  assert.ok(!('error' in loaded))
  const run = await runExpectedBands(admin, loaded as LoadedFrame, now)
  assert.equal(run.error, undefined)
  const byKey = Object.fromEntries(admin.upserts.map((r) => [r.key as string, r]))
  return { admin, run, byKey, payload: (k: string) => byKey[k].payload as ExpectedBandResult }
}

test('one row per metric, in natural units, replacing older days of the same version', async () => {
  const f = typicalitySeries(41, 200)
  const { admin, run, byKey, payload } = await publish(f)
  assert.deepEqual(run.metrics?.map((m) => m.metric), ['hrv', 'rhr', 'sleep_hours'])
  assert.equal(admin.upserts.length, 3)
  for (const [key, unit] of [['hrv', 'ms'], ['rhr', 'bpm'], ['sleep_hours', 'h']]) {
    const row = byKey[key]
    assert.equal(row.kind, 'expected_band')
    assert.equal(row.engine_version, CORE_VERSION)
    assert.equal(row.as_of, f.dates[199])
    assert.equal(row.state, 'ready')
    assert.equal(row.confidence_need, 21)
    const p = payload(key)
    assert.equal(p.unit, unit)
    assert.equal(p.recent.length, 28)
    assert.equal(p.next?.date, '2026-07-24')
  }
  const hrv = payload('hrv').recent[27]
  assert.ok((hrv.expected as number) > 40 && (hrv.expected as number) < 90, `HRV expected ${hrv.expected} ms`)
  const rhr = payload('rhr').recent[27]
  assert.ok((rhr.expected as number) > 45 && (rhr.expected as number) < 65, `RHR expected ${rhr.expected} bpm`)
  const deletedKeys = admin.deletes.filter((d) => d.filters.some(([op, c]) => op === 'lt' && c === 'as_of'))
    .flatMap((d) => d.filters.filter(([op, c]) => op === 'in' && c === 'key').flatMap(([, , v]) => v as string[]))
  assert.equal(deletedKeys.length, 3, 'older days of the three metrics are removed, in one batched delete')
})

test('an unlogged illness is flagged on the unfavourable side; a logged one is shown but not flagged', async () => {
  const f = typicalitySeries(42, 200, '2026-01-05', [{ start: 192, length: 8 }])
  const plain = await publish(f)
  const ill = plain.payload('hrv').recent.slice(20)
  assert.ok(ill.some((p) => p.flag !== null && p.side === 'low'), 'low HRV flagged')
  assert.ok(plain.payload('rhr').recent.slice(20).some((p) => p.flag !== null && p.side === 'high'), 'high resting heart rate flagged')

  const logged = await publish(f, { illnesses: [{ user_id: USER, start_date: f.dates[192], end_date: f.dates[199] }] })
  const shown = logged.payload('hrv').recent.slice(20)
  assert.ok(shown.every((p) => p.excluded && p.flag === null))
  assert.ok(shown.some((p) => p.z !== null && p.z < -1), 'still placed against the band')
})

test('only a confirmed break of the metric itself starts a regime', async () => {
  const f = typicalitySeries(43, 220)
  const breakDate = f.dates[160]
  const { payload } = await publish(f, {
    analysis_results: [
      { user_id: USER, kind: 'series_break', key: 'hrv', payload: { breaks: [{ date: f.dates[100], status: 'candidate' }, { date: breakDate, status: 'confirmed' }], lastConfirmed: breakDate } },
      { user_id: USER, kind: 'series_break', key: 'steps', payload: { breaks: [{ date: f.dates[150], status: 'confirmed' }], lastConfirmed: f.dates[150] } },
    ],
  })
  assert.equal(payload('hrv').regimeStart, breakDate)
  assert.equal(payload('rhr').regimeStart, f.dates[0])
  assert.equal(payload('sleep_hours').regimeStart, f.dates[0])
})

test('a new account publishes building rows, not guesses', async () => {
  const f = typicalitySeries(44, 20)
  const { byKey, payload } = await publish(f)
  assert.equal(byKey.hrv.state, 'building')
  assert.equal(payload('hrv').next, null)
  assert.equal(payload('hrv').recent.filter((p) => p.lo80 !== null).length, 0)
})
