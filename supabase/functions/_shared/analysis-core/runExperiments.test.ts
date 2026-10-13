import assert from 'node:assert/strict'
import { test } from 'node:test'
import { buildPreview, runExperiments } from './runExperiments.ts'
import { loadFrame } from './runRelationships.ts'
import type { LoadedFrame } from './runRelationships.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'
import { CORE_VERSION } from './version.ts'

type Row = Record<string, unknown>

// In-memory stand-in for the Supabase query builder: select / eq / gte / lt / order / range / delete / update / upsert.
function fakeAdmin(tables: Record<string, Row[]>) {
  const upserts: Row[] = []
  const updates: { table: string; patch: Row; filters: [string, string, unknown][] }[] = []
  const deletes: { table: string; filters: [string, string, unknown][] }[] = []
  return {
    upserts,
    updates,
    deletes,
    from(table: string) {
      const filters: [string, string, unknown][] = []
      let window: [number, number] | null = null
      let isDelete = false
      let patch: Row | null = null
      const q = {
        select() { return q },
        eq(c: string, v: unknown) { filters.push(['eq', c, v]); return q },
        gte(c: string, v: unknown) { filters.push(['gte', c, v]); return q },
        lt(c: string, v: unknown) { filters.push(['lt', c, v]); return q },
        order() { return q },
        range(a: number, b: number) { window = [a, b]; return q },
        delete() { isDelete = true; return q },
        update(p: Row) { patch = p; return q },
        upsert(row: Row) { upserts.push(row); return Promise.resolve({ error: null }) },
        then(resolve: (v: { data: Row[]; error: null }) => void) {
          let rows = (tables[table] ?? []).filter((r) => filters.every(([op, c, v]) => (op === 'eq' ? r[c] === v : op === 'gte' ? String(r[c]) >= String(v) : String(r[c]) < String(v))))
          if (isDelete) deletes.push({ table, filters })
          if (patch) updates.push({ table, patch, filters })
          if (window) rows = rows.slice(window[0], window[1] + 1)
          resolve({ data: rows, error: null })
        },
      }
      return q
    },
  }
}

const USER = 'u1'
const START = '2026-01-05'
const shift = (d: string, n: number) => new Date(Date.parse(d + 'T00:00:00Z') + n * 86400000).toISOString().slice(0, 10)

// 120 days of data; A = days 40..67, B = days 68..95 (with a planted +22 % HRV effect in B).
function tables(extra: Record<string, Row[]> = {}): Record<string, Row[]> {
  const rng = mulberry32(5)
  const dates = dateAxis(START, shift(START, 119))
  let e = 0
  const wearable = dates.map((d, t) => {
    e = 0.5 * e + 0.0866 * gaussian(rng)
    return { user_id: USER, date: d, hrv: Math.exp(4.1 + e + (t >= 68 && t <= 95 ? 0.22 : 0)), rhr: 55 + gaussian(rng), steps: 9000 }
  })
  const sleep = dates.map((d) => ({ user_id: USER, date: d, hours: 7.4 + 0.5 * gaussian(rng), deep_min: 80, rem_min: 90 }))
  const experiment: Row = {
    id: 'exp-1', user_id: USER, supplement_id: 7, name: 'Magnesium', primary_metric: 'hrv', secondary_metrics: ['rhr'], status: 'planned',
    preregistered_at: `${shift(START, 38)}T10:00:00Z`, mde_preview: [{ metric: 'hrv', mde: 14 }],
    protocol: { design: 'AB', phases: [{ label: 'A', start: shift(START, 40), end: shift(START, 67) }, { label: 'B', start: shift(START, 68), end: shift(START, 95) }], washoutDays: 0, minAdherence: 0.7, excludeAlcoholDays: true },
  }
  const log = dates.slice(68, 96).map((d) => ({ user_id: USER, supplement_id: 7, taken_at: `${d}T08:00:00Z` }))
  return { wearable_daily: wearable, sleep_daily: sleep, illnesses: [], experiments: [experiment], supplement_log: log, ...extra }
}

test('runExperiments analyses a registered experiment, advances its status and publishes one row', async () => {
  const admin = fakeAdmin(tables())
  const now = new Date(`${shift(START, 119)}T20:00:00Z`)
  const loaded = (await loadFrame(admin, USER, now)) as LoadedFrame
  const run = await runExperiments(admin, loaded, now)
  assert.equal(run.error, undefined)
  assert.deepEqual(run.results, [{ id: 'exp-1', stage: 'complete', verdict: 'effect_detected' }])

  assert.equal(admin.upserts.length, 1)
  const row = admin.upserts[0]
  assert.equal(row.kind, 'experiment')
  assert.equal(row.key, 'exp-1')
  assert.equal(row.engine_version, CORE_VERSION)
  assert.equal(row.state, 'effect_detected')
  const payload = row.payload as { status: string; adherence: { rate: number }; protocol: unknown; mdePreview: unknown; metrics: { diff: { value: number } }[] }
  assert.equal(payload.status, 'completed')
  assert.equal(payload.adherence.rate, 1)
  assert.ok(payload.metrics[0].diff.value > 10)
  assert.ok(payload.protocol && payload.mdePreview, 'the registered protocol and preview travel with the result')
  assert.deepEqual(admin.updates.map((u) => u.patch), [{ status: 'completed' }])
  assert.ok(admin.deletes.some((d) => d.table === 'analysis_results'))
})

test('an intervention-linked experiment (no supplement_id) is analysed the same way and carries interventionId in the payload', async () => {
  const t = tables()
  ;(t.experiments[0] as Row).supplement_id = null
  ;(t.experiments[0] as Row).intervention_id = 'iv-1'
  delete (t as Row).supplement_log // nothing to adjust on: no domain-owned adherence log is wired in for a bare intervention yet
  t.supplement_log = []
  const admin = fakeAdmin(t)
  const now = new Date(`${shift(START, 119)}T20:00:00Z`)
  const run = await runExperiments(admin, (await loadFrame(admin, USER, now)) as LoadedFrame, now)
  assert.deepEqual(run.results, [{ id: 'exp-1', stage: 'complete', verdict: 'effect_detected' }])
  const payload = admin.upserts[0].payload as { interventionId: string | null; supplementId: number | null; adherence: { rate: number | null } }
  assert.equal(payload.interventionId, 'iv-1')
  assert.equal(payload.supplementId, null)
  assert.equal(payload.adherence.rate, 0, 'no adherence log source for a bare intervention yet, so zero logged days rather than a guess')
})

test('an experiment that has not started publishes its stage only; abandoned ones are skipped', async () => {
  const t = tables()
  ;(t.experiments[0] as Row).protocol = { design: 'AB', phases: [{ label: 'A', start: shift(START, 130), end: shift(START, 157) }, { label: 'B', start: shift(START, 158), end: shift(START, 185) }], washoutDays: 0, minAdherence: 0.7, excludeAlcoholDays: true }
  t.experiments.push({ ...(t.experiments[0] as Row), id: 'exp-2', status: 'abandoned' })
  const admin = fakeAdmin(t)
  const now = new Date(`${shift(START, 119)}T20:00:00Z`)
  const run = await runExperiments(admin, (await loadFrame(admin, USER, now)) as LoadedFrame, now)
  assert.deepEqual(run.results, [{ id: 'exp-1', stage: 'before', verdict: 'before' }])
  assert.equal(admin.updates.length, 0, 'planned stays planned')
})

test('the preview reports the smallest detectable effect per outcome and flags an unsettled series', async () => {
  const admin = fakeAdmin(tables({ analysis_results: [{ user_id: USER, kind: 'series_break', key: 'hrv', payload: { breaks: [{ date: shift(START, 100) }] } }] }))
  const now = new Date(`${shift(START, 119)}T20:00:00Z`)
  const res = await buildPreview(admin, (await loadFrame(admin, USER, now)) as LoadedFrame, 28, 28, ['hrv', 'rhr', 'sleep_hours'])
  assert.equal(res.error, undefined)
  const by = Object.fromEntries(res.previews!.map((p) => [p.metric, p]))
  assert.ok(by.hrv.mde > 0 && by.hrv.unit === '%')
  assert.equal(by.hrv.unsettled, true)
  assert.equal(by.rhr.unsettled, false)
  assert.equal(res.plannedA, 28)
})
