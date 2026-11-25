// The weekly and monthly report publisher against an in-memory client.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { backfillKeys, duePeriods, readsOf, runReports } from './runPeriodReport.ts'
import { addDays, type SeriesFile } from './seriesContract.ts'
import type { PeriodReport } from './periodReport.ts'

const file = JSON.parse(readFileSync(new URL('../metric-registry/metric-series.json', import.meta.url), 'utf8')) as SeriesFile

type Row = Record<string, unknown>
const cmp = (a: unknown, b: unknown) => (String(a) < String(b) ? -1 : String(a) > String(b) ? 1 : 0)

function fakeAdmin(tables: Record<string, Row[]>) {
  const upserts: Row[] = []
  const deletes: [string, string, unknown][][] = []
  return {
    upserts, deletes,
    from(table: string) {
      const preds: [string, string, unknown][] = []
      let order: [string, number][] = []
      let lim: number | null = null
      let range: [number, number] | null = null
      let isDelete = false
      const q = {
        select() { return q },
        eq(c: string, v: unknown) { preds.push(['eq', c, v]); return q },
        neq(c: string, v: unknown) { preds.push(['neq', c, v]); return q },
        gte(c: string, v: unknown) { preds.push(['gte', c, v]); return q },
        lte(c: string, v: unknown) { preds.push(['lte', c, v]); return q },
        in(c: string, vs: unknown[]) { preds.push(['in', c, vs]); return q },
        order(c: string, o?: { ascending?: boolean }) { order.push([c, o?.ascending === false ? -1 : 1]); return q },
        limit(n: number) { lim = n; return q },
        range(a: number, b: number) { range = [a, b]; return q },
        delete() { isDelete = true; return q },
        upsert(row: Row) { upserts.push(row); return Promise.resolve({ error: null }) },
        then(resolve: (v: { data: Row[] | null; error: null }) => void) {
          if (isDelete) { deletes.push(preds); return resolve({ data: null, error: null }) }
          let rows = (tables[table] ?? []).filter((r) => preds.every(([op, c, v]) =>
            op === 'eq' ? r[c] === v : op === 'neq' ? r[c] !== v : op === 'in' ? (v as unknown[]).includes(r[c])
              : r[c] !== null && r[c] !== undefined && (op === 'gte' ? cmp(r[c], v) >= 0 : cmp(r[c], v) <= 0)))
          rows = [...rows].sort((a, b) => { for (const [c, d] of order) { const x = cmp(a[c], b[c]); if (x) return x * d } return 0 })
          if (range) rows = rows.slice(range[0], range[1] + 1)
          if (lim !== null) rows = rows.slice(0, lim)
          resolve({ data: rows, error: null })
        },
      }
      return q
    },
  }
}

const U = 'u1'

function tables(): Record<string, Row[]> {
  const wearable: Row[] = []
  const sleep: Row[] = []
  const log: Row[] = []
  const meals: Row[] = []
  let id = 1
  // Ten weeks to Sunday 22 Nov 2026: HRV 50 for nine weeks, 60 in the last; sleep 7.5 h then 6.2 h.
  for (let i = 0; i < 70; i++) {
    const d = addDays('2026-09-14', i)
    const last = d >= '2026-11-16'
    wearable.push({ id: id++, user_id: U, date: d, hrv: last ? 60 : 50 + (i % 2), rhr: 50, steps: 9000 })
    sleep.push({ id: id++, user_id: U, date: d, hours: last ? 6.2 : 7.5, score: 80 })
    meals.push({ id: id++, user_id: U, logged_at: `${d}T12:00:00+00:00`, protein_g: 150, fiber_g: 35, calories: 2400 })
    // Creatine every day until the last week, then three days of seven.
    if (!last || ['2026-11-16', '2026-11-18', '2026-11-20'].includes(d)) log.push({ id: id++, user_id: U, supplement_id: 4, taken_at: `${d}T07:30:00+00:00` })
  }
  return {
    wearable_daily: wearable, sleep_daily: sleep, supplement_log: log, meals,
    daily_context: [{ user_id: U, date: '2026-11-24', utc_offset_min: 480 }],
    supplements: [
      { id: 4, user_id: U, name: 'Creatine', kind: 'supplement', status: 'active', time_of_day: 'morning', slots: ['morning'], every_n_days: null, schedule: { type: 'daily' }, start_date: '2026-06-01', end_date: null },
      { id: 1, user_id: U, name: 'Tart cherry', kind: 'supplement', status: 'active', time_of_day: 'as-needed', slots: [], every_n_days: null, schedule: { type: 'as_needed' }, start_date: '2026-08-27', end_date: null },
      { id: 9, user_id: U, name: 'Old one', kind: 'supplement', status: 'ended', time_of_day: 'night', slots: ['night'], every_n_days: null, schedule: { type: 'daily' }, start_date: '2026-06-01', end_date: '2026-09-01' },
    ],
    interventions: [{ id: 'iv1', user_id: U, name: 'Magnesium before bed', lifecycle_state: 'active', started_at: '2026-10-01', ended_at: null, supplement_id: null }],
    analysis_results: [{ user_id: U, kind: 'intervention_its', key: 'iv1', state: 'evaluated', calculated_at: '2026-11-22T20:00:00Z', payload: { metrics: [{ metric: 'sleep_hours', label: 'Sleep', verdict: 'negligible', gap: { value: 0.1, unit: 'h' } }] } }],
    experiments: [],
    nutrient_intake: [
      { id: 1, user_id: U, logged_at: '2026-11-17T12:00:00+00:00', nutrient: 'magnesium', amount: 150, unit: 'mg' },
      { id: 2, user_id: U, logged_at: '2026-11-17T12:00:00+00:00', nutrient: 'omega_3', amount: 400, unit: 'mg' },
      { id: 3, user_id: U, logged_at: '2026-11-18T12:00:00+00:00', nutrient: 'calories', amount: 2000, unit: 'kcal' },
    ],
    stool_log: [],
  }
}

test('the nightly run writes the last week and month on their first night, then leaves them after the rewrite days', () => {
  assert.deepEqual(duePeriods('2026-11-23', new Set()).map((p) => p.start), ['2026-11-16', '2026-10-01'])
  const stored = new Set(['week:2026-11-16', 'month:2026-10'])
  assert.deepEqual(duePeriods('2026-11-24', stored).map((p) => p.start), ['2026-11-16'], 'the week is rewritten while late data lands')
  assert.deepEqual(duePeriods('2026-11-26', stored), [])
  assert.deepEqual(backfillKeys('2026-11-25', 2, 2), ['week:2026-11-16', 'week:2026-11-09', 'month:2026-10', 'month:2026-09'])
})

test('a week report from the database: metrics, adherence, Lab and suggestions', async () => {
  const admin = fakeAdmin(tables())
  // 2026-11-23 01:00 UTC is 09:00 on Monday 23 Nov in UTC+8.
  const run = await runReports(admin, U, file, new Date('2026-11-23T01:00:00Z'), { keys: ['week:2026-11-16', 'week:2026-11-23'] })
  assert.equal(run.error, undefined)
  assert.equal(run.today, '2026-11-23')
  assert.deepEqual(run.written, ['week:2026-11-16'])
  assert.deepEqual(run.skipped, ['week:2026-11-23'], 'a week that has not ended is not written')
  const row = admin.upserts[0]
  assert.equal(row.kind, 'period_report')
  assert.equal(row.as_of, '2026-11-22')
  const r = row.payload as PeriodReport
  const m = new Map(r.metrics.map((l) => [l.id, l]))
  assert.equal(m.get('hrv')!.status, 'better')
  assert.equal(m.get('hrv')!.value, 60)
  assert.equal(m.get('sleep_hours')!.status, 'worse')
  assert.equal(m.get('rhr')!.status, 'steady')
  assert.equal(m.get('meal_protein')!.value, 150)
  assert.equal(m.get('energy')!.status, 'no_data')
  const creatine = r.regimen.find((x) => x.id === 4)!
  assert.equal(creatine.rate, 0.429)
  assert.equal(creatine.status, 'not_followed')
  assert.equal(r.regimen.find((x) => x.id === 1)!.status, 'as_needed')
  assert.ok(!r.regimen.some((x) => x.id === 9), 'an item that ended before the week is left out')
  assert.equal(r.lab[0].verdict, 'no_effect')
  assert.deepEqual(r.summary.wasted, ['Magnesium before bed'])
  assert.ok(r.summary.notFollowed.includes('Creatine'))
  assert.equal(r.micro.foodDays, 2)
  assert.equal(r.micro.lines.find((l) => l.key === 'magnesium')!.value, 75)
  assert.equal(r.micro.lines.find((l) => l.key === 'omega_3')!.value, 0.2, 'mg are converted to the reference unit')
  assert.equal(r.suggestions[0].id, 'sleep_short')
  assert.ok(r.suggestions.some((s) => s.id === 'regimen_4'))
  // Older engine versions of the same report go.
  assert.ok(admin.deletes.some((d) => d.some(([op, c]) => op === 'neq' && c === 'engine_version')))
})

test('engine reads in one vocabulary; secondary outcomes are left out', () => {
  assert.deepEqual(readsOf([
    { metric: 'hrv', label: 'HRV', verdict: 'clear', gap: { value: 4, unit: 'ms' } },
    { metric: 'rhr', label: 'RHR', verdict: 'no_signal', diff: { value: 0.2, unit: 'bpm' } },
    { metric: 'mood', label: 'Mood', verdict: 'running', role: 'primary' },
    { metric: 'stress', label: 'Stress', verdict: 'effect_detected', role: 'secondary' },
  ]).map((r) => r.outcome), ['change', 'none', 'undecided'])
})
