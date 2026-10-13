import assert from 'node:assert/strict'
import { test } from 'node:test'
import { buildFrame, loadFrame } from './runRelationships.ts'
import type { LoadedFrame } from './runRelationships.ts'
import { runTypicality } from './runTypicality.ts'
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

function tablesFor(f: ReturnType<typeof typicalitySeries>, extra: Record<string, Row[]> = {}): Record<string, Row[]> {
  return {
    wearable_daily: f.dates.map((d, i) => ({ user_id: USER, date: d, hrv: f.hrv[i], rhr: f.rhr[i], steps: f.steps[i] })),
    sleep_daily: f.dates.map((d, i) => ({ user_id: USER, date: d, hours: f.sleepHours[i], deep_min: f.deepRemMin[i] * 0.6, rem_min: f.deepRemMin[i] * 0.4 })),
    illnesses: [],
    ...extra,
  }
}

test('buildFrame adds deep + REM only where both stages exist', () => {
  const frame = buildFrame(
    {
      wearable: [], wellbeing: [], meals: [], sessions: [], recovery: [], illnesses: [],
      sleep: [{ date: '2026-03-02', hours: 7, deep_min: 80, rem_min: 90 }, { date: '2026-03-03', hours: 7, deep_min: 80, rem_min: null }],
    },
    '2026-03-03',
    3,
  )
  assert.deepEqual(frame.series.deep_rem_min, [null, 170, null])
})

test('runTypicality publishes one row, flags a planted illness and suggests logging it', async () => {
  const f = typicalitySeries(21, 200, '2026-01-05', [{ start: 192, length: 8 }])
  const admin = fakeAdmin(tablesFor(f))
  const now = new Date(`${f.dates[199]}T20:00:00Z`)
  const loaded = await loadFrame(admin, USER, now)
  assert.ok(!('error' in loaded))
  const run = await runTypicality(admin, loaded as LoadedFrame, now)
  assert.equal(run.error, undefined)
  assert.ok(run.baselineDays! >= 100)

  assert.equal(admin.upserts.length, 1)
  const row = admin.upserts[0]
  assert.equal(row.kind, 'typicality')
  assert.equal(row.key, 'personal_normal')
  assert.equal(row.engine_version, CORE_VERSION)
  assert.equal(row.as_of, f.dates[199])
  assert.ok(row.state === 'elevated' || row.state === 'unusual', String(row.state))
  const payload = row.payload as { suggestIllnessLog: boolean; recent: unknown[]; thresholds: { elevated: number } }
  assert.equal(payload.suggestIllnessLog, true)
  assert.equal(payload.recent.length, 14)
  assert.ok(admin.deletes.some((d) => d.table === 'analysis_results' && d.filters.some(([op, c]) => op === 'lt' && c === 'as_of')))
})

test('a confirmed series break published earlier restarts the baseline there', async () => {
  const f = typicalitySeries(22, 260)
  const breakDate = f.dates[150]
  const admin = fakeAdmin(
    tablesFor(f, { analysis_results: [{ user_id: USER, kind: 'series_break', key: 'hrv', payload: { lastConfirmed: breakDate } }, { user_id: USER, kind: 'series_break', key: 'steps', payload: { lastConfirmed: '2026-01-06' } }] }),
  )
  const now = new Date(`${f.dates[259]}T20:00:00Z`)
  const loaded = await loadFrame(admin, USER, now)
  await runTypicality(admin, loaded as LoadedFrame, now)
  const payload = admin.upserts[0].payload as { baselineStart: string }
  assert.ok(payload.baselineStart >= breakDate, `${payload.baselineStart} should not precede ${breakDate} (a break in steps is not one of the signals' series)`)
})

test('too little history publishes a building state, not a guess', async () => {
  const f = typicalitySeries(23, 30)
  const admin = fakeAdmin(tablesFor(f))
  const now = new Date(`${f.dates[29]}T20:00:00Z`)
  await runTypicality(admin, (await loadFrame(admin, USER, now)) as LoadedFrame, now)
  assert.equal(admin.upserts[0].state, 'building')
})

test('a device-sensitive signal with a detected break in the window is left out', async () => {
  const f = typicalitySeries(24, 200)
  const breakDate = f.dates[150]
  const admin = fakeAdmin(
    tablesFor(f, { analysis_results: [{ user_id: USER, kind: 'series_break', key: 'deep_min', payload: { lastConfirmed: null, breaks: [{ date: breakDate, status: 'dismissed' }] } }] }),
  )
  const now = new Date(`${f.dates[199]}T20:00:00Z`)
  await runTypicality(admin, (await loadFrame(admin, USER, now)) as LoadedFrame, now)
  const payload = admin.upserts[0].payload as { skipped: string[]; today: { signalsUsed: number } }
  assert.deepEqual(payload.skipped, ['deep_rem'])
  assert.equal(payload.today.signalsUsed, 3) // hrv, rhr, sleep; steps are partial today and deep + REM is out
})
