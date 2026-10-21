// Analysis review phase F: randomised experiments through the publisher -- four-word verdicts, frozen exclusions, soft
// deletion, daily schedules with adherence from the behaviour log. Synthetic data only.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { drawDailySchedule, drawPeriodSchedule, shiftDay } from './randomized.ts'
import { loadFrame } from './runRelationships.ts'
import type { LoadedFrame } from './runRelationships.ts'
import { runExperiments } from './runExperiments.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'

type Row = Record<string, unknown>

// In-memory query builder; upserts into snapshot tables are stored, so a second run reads them back.
function fakeAdmin(tables: Record<string, Row[]>) {
  const upserts: { table: string; row: Row }[] = []
  return {
    upserts,
    tables,
    from(table: string) {
      const filters: [string, string, unknown][] = []
      let window: [number, number] | null = null
      const q = {
        select() { return q },
        eq(c: string, v: unknown) { filters.push(['eq', c, v]); return q },
        gte(c: string, v: unknown) { filters.push(['gte', c, v]); return q },
        lt(c: string, v: unknown) { filters.push(['lt', c, v]); return q },
        order() { return q },
        range(a: number, b: number) { window = [a, b]; return q },
        delete() { return q },
        update() { return q },
        upsert(row: Row | Row[]) {
          for (const r of Array.isArray(row) ? row : [row]) {
            upserts.push({ table, row: r })
            if (table === 'experiment_exclusion_snapshots') (tables[table] ??= []).push(r)
          }
          return Promise.resolve({ error: null })
        },
        then(resolve: (v: { data: Row[]; error: null }) => void) {
          let rows = (tables[table] ?? []).filter((r) => filters.every(([op, c, v]) => (op === 'eq' ? r[c] === v : op === 'gte' ? String(r[c]) >= String(v) : String(r[c]) < String(v))))
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

function world(effect: number, isWith: (d: string) => boolean, days = 220, seed = 3) {
  const rng = mulberry32(seed)
  const dates = dateAxis(START, shiftDay(START, days - 1))
  let e = 0
  const wearable = dates.map((d) => {
    e = 0.5 * e + 0.07 * gaussian(rng)
    return { user_id: USER, date: d, hrv: Math.exp(4.1 + e + 0.04 * gaussian(rng) + (isWith(d) ? effect : 0)), rhr: 55 + gaussian(rng), steps: 9000 }
  })
  const sleep = dates.map((d) => ({ user_id: USER, date: d, hours: 7.3 + 0.5 * gaussian(rng) }))
  return { dates, wearable, sleep }
}

test('periods in random order: complete experiment reads CLEAR BENEFIT against its zone, with exclusions frozen per period', async () => {
  const first = shiftDay(START, 30)
  const phases = drawPeriodSchedule(mulberry32(9), first, 14, 6)
  const isWith = (d: string) => phases.some((p) => p.label === 'B' && d >= p.start && d <= p.end)
  const w = world(0.15, isWith)
  const today = shiftDay(phases[phases.length - 1].end, 5)
  const experiment: Row = {
    id: 'exp-2', user_id: USER, supplement_id: 7, intervention_id: null, name: 'Magnesium', primary_metric: 'hrv', secondary_metrics: [], status: 'running',
    preregistered_at: `${shiftDay(first, -1)}T10:00:00Z`, mde_preview: null, deleted_at: null,
    protocol: { version: 2, design: 'randomized_pairs', phases, washoutDays: 3, minAdherence: 0.7, excludeAlcoholDays: true, zone: 3, pairs: 6, periodDays: 14 },
  }
  const log = w.dates.filter(isWith).map((d) => ({ user_id: USER, supplement_id: 7, taken_at: `${d}T08:00:00Z` }))
  const tables: Record<string, Row[]> = { wearable_daily: w.wearable, sleep_daily: w.sleep, illnesses: [], experiments: [experiment], supplement_log: log, experiment_exclusion_snapshots: [] }
  const admin = fakeAdmin(tables)
  const now = new Date(`${today}T20:30:00Z`)
  const loaded = (await loadFrame(admin, USER, now)) as LoadedFrame
  const run = await runExperiments(admin, loaded, now)
  assert.equal(run.error, undefined)
  const published = admin.upserts.find((u) => u.table === 'analysis_results')!.row
  assert.equal(published.state, 'clear_benefit')
  const payload = published.payload as { metrics: { verdict: string; diff: { lo: number }; zone: number; randomizationP: number | null }[] }
  assert.equal(payload.metrics[0].zone, 3)
  assert.ok(payload.metrics[0].diff.lo > 3)
  assert.ok(payload.metrics[0].randomizationP !== null && payload.metrics[0].randomizationP < 0.05)
  const snapshots = tables.experiment_exclusion_snapshots
  assert.equal(snapshots.length, 12, 'one frozen set per ended period')

  // An illness logged afterwards inside a finished period changes nothing: that period's exclusions are frozen.
  tables.illnesses = [{ user_id: USER, start_date: phases[1].start, end_date: phases[1].end }]
  const again = fakeAdmin(tables)
  const loaded2 = (await loadFrame(again, USER, now)) as LoadedFrame
  await runExperiments(again, loaded2, now)
  const second = again.upserts.find((u) => u.table === 'analysis_results')!.row
  assert.deepEqual((second.payload as { metrics: unknown[] }).metrics, payload.metrics)
})

test('a deleted experiment is not analysed (deletion is soft)', async () => {
  const w = world(0, () => false)
  const experiment: Row = {
    id: 'exp-3', user_id: USER, supplement_id: null, intervention_id: null, name: 'Gone', primary_metric: 'hrv', secondary_metrics: [], status: 'running',
    preregistered_at: `${START}T10:00:00Z`, mde_preview: null, deleted_at: `${shiftDay(START, 40)}T10:00:00Z`,
    protocol: { version: 2, design: 'randomized_pairs', phases: drawPeriodSchedule(mulberry32(1), shiftDay(START, 1), 14, 2), washoutDays: 3, minAdherence: 0.7, excludeAlcoholDays: true, zone: 3 },
  }
  const admin = fakeAdmin({ wearable_daily: w.wearable, sleep_daily: w.sleep, illnesses: [], experiments: [experiment] })
  const now = new Date(`${shiftDay(START, 100)}T20:30:00Z`)
  const run = await runExperiments(admin, (await loadFrame(admin, USER, now)) as LoadedFrame, now)
  assert.deepEqual(run.results, [])
})

test('days in random order: a caffeine cut-off with no effect reads NO MEANINGFUL EFFECT or UNCLEAR, with adherence from the check-in', async () => {
  const first = shiftDay(START, 30)
  const assignments = drawDailySchedule(mulberry32(4), first, 10)
  const w = world(0, () => false, 120)
  const withDays = new Set(assignments.filter((a) => a.arm === 'B').map((a) => a.date))
  // The check-in says whether caffeine came after 14:00: followed on every with-day, and on half the others.
  const checkins = w.dates.map((d, i) => ({ user_id: USER, date: d, drinks: 0, caffeine_after_14: withDays.has(d) ? false : i % 2 === 0, late_meal: false, stress: null }))
  const experiment: Row = {
    id: 'exp-4', user_id: USER, supplement_id: null, intervention_id: null, name: 'Caffeine cut-off at 14:00', primary_metric: 'sleep_hours', secondary_metrics: [], status: 'running',
    preregistered_at: `${shiftDay(first, -1)}T10:00:00Z`, mde_preview: null, deleted_at: null,
    protocol: {
      version: 2, design: 'micro_randomized', phases: [], assignments, lag: 1, washoutDays: 0, minAdherence: 0.7, excludeAlcoholDays: false, zone: 10,
      behaviour: { series: 'caffeine_pm_any', withValue: 0, label: 'No caffeine after 14:00' },
    },
  }
  const today = shiftDay(assignments[assignments.length - 1].date, 3)
  const admin = fakeAdmin({ wearable_daily: w.wearable, sleep_daily: w.sleep, illnesses: [], experiments: [experiment], evening_checkins: checkins, experiment_exclusion_snapshots: [] })
  const now = new Date(`${today}T20:30:00Z`)
  const run = await runExperiments(admin, (await loadFrame(admin, USER, now)) as LoadedFrame, now)
  assert.equal(run.error, undefined)
  const row = admin.upserts.find((u) => u.table === 'analysis_results')!.row
  assert.ok(row.state === 'negligible' || row.state === 'unclear', String(row.state))
  const payload = row.payload as { adherence: { rate: number }; metrics: { diff: { unit: string } }[] }
  assert.equal(payload.adherence.rate, 1)
  assert.equal(payload.metrics[0].diff.unit, 'min')
})
