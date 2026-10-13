import assert from 'node:assert/strict'
import { test } from 'node:test'
import { runInterventionResponses } from './runInterventionResponse.ts'
import { loadFrame } from './runRelationships.ts'
import type { LoadedFrame } from './runRelationships.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'
import { CORE_VERSION } from './version.ts'

type Row = Record<string, unknown>

// Same in-memory stand-in as runExperiments.test.ts (select / eq / gte / lt / order / range / delete / update / upsert).
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
const START = '2026-01-05'
const shift = (d: string, n: number) => new Date(Date.parse(d + 'T00:00:00Z') + n * 86400000).toISOString().slice(0, 10)

function tables(extra: Record<string, Row[]> = {}): Record<string, Row[]> {
  const rng = mulberry32(11)
  const dates = dateAxis(START, shift(START, 89))
  let e = 0
  const wearable = dates.map((d, t) => {
    e = 0.5 * e + 0.0866 * gaussian(rng)
    return { user_id: USER, date: d, hrv: Math.exp(4.1 + e + (t >= 45 ? 0.22 : 0)), rhr: 55 + gaussian(rng), steps: 9000 }
  })
  const sleep = dates.map((d) => ({ user_id: USER, date: d, hours: 7.4 + 0.5 * gaussian(rng), deep_min: 80, rem_min: 90 }))
  const intervention: Row = {
    id: 'iv-1', user_id: USER, lifecycle_state: 'active', baseline_started_at: dates[0], started_at: shift(START, 45), ended_at: null,
    intended_outcomes: ['hrv', 'not_a_real_metric'],
  }
  return { wearable_daily: wearable, sleep_daily: sleep, illnesses: [], interventions: [intervention], ...extra }
}

test('runInterventionResponses analyses an active intervention and publishes one row', async () => {
  const admin = fakeAdmin(tables())
  const now = new Date(`${shift(START, 89)}T20:00:00Z`)
  const loaded = (await loadFrame(admin, USER, now)) as LoadedFrame
  const run = await runInterventionResponses(admin, loaded, now)
  assert.equal(run.error, undefined)
  assert.deepEqual(run.results, [{ id: 'iv-1', metrics: 1 }], 'the unknown outcome key is silently skipped, only hrv is analysed')

  assert.equal(admin.upserts.length, 1)
  const row = admin.upserts[0]
  assert.equal(row.kind, 'intervention_response')
  assert.equal(row.key, 'iv-1')
  assert.equal(row.engine_version, CORE_VERSION)
  assert.equal(row.state, 'signal_observed')
  const payload = row.payload as { interventionId: string; metrics: { metric: string; diff: { value: number } }[] }
  assert.equal(payload.interventionId, 'iv-1')
  assert.equal(payload.metrics.length, 1)
  assert.ok(payload.metrics[0].diff.value > 10)
  assert.ok(admin.deletes.some((d) => d.table === 'analysis_results'))
})

test('an intervention still in baseline (no started_at) is skipped', async () => {
  const t = tables()
  ;(t.interventions[0] as Row).started_at = null
  ;(t.interventions[0] as Row).lifecycle_state = 'baseline'
  const admin = fakeAdmin(t)
  const now = new Date(`${shift(START, 89)}T20:00:00Z`)
  const run = await runInterventionResponses(admin, (await loadFrame(admin, USER, now)) as LoadedFrame, now)
  assert.deepEqual(run.results, [])
  assert.equal(admin.upserts.length, 0)
})

test('an intervention with no outcome the frame can measure is skipped', async () => {
  const t = tables()
  ;(t.interventions[0] as Row).intended_outcomes = ['not_a_real_metric']
  const admin = fakeAdmin(t)
  const now = new Date(`${shift(START, 89)}T20:00:00Z`)
  const run = await runInterventionResponses(admin, (await loadFrame(admin, USER, now)) as LoadedFrame, now)
  assert.deepEqual(run.results, [])
})
