// Routine intelligence R6 (docs/routine-intelligence/01-research-and-plan.md, section 10): self-experiments on routines,
// run end to end through loadFrame and runExperiments, with adherence measured passively from what the phone published.
// The plan's gate: one experiment run end to end with passively measured adherence. Synthetic data only.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { drawDailySchedule, shiftDay } from './randomized.ts'
import { loadFrame } from './runRelationships.ts'
import type { LoadedFrame } from './runRelationships.ts'
import { runExperiments } from './runExperiments.ts'
import { routineSeries } from './routineExposures.ts'
import { localClock } from './runRelationships.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'

type Row = Record<string, unknown>

// In-memory query builder (as in runExperimentsV2.test.ts).
function fakeAdmin(tables: Record<string, Row[]>) {
  const upserts: { table: string; row: Row }[] = []
  return {
    upserts,
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
const DAYS = 220

function base(seed: number) {
  const rng = mulberry32(seed)
  const dates = dateAxis(START, shiftDay(START, DAYS - 1))
  const wearable = dates.map((d) => ({ user_id: USER, date: d, hrv: Math.exp(4.1 + 0.05 * gaussian(rng)), rhr: 55 + gaussian(rng), steps: 9000 }))
  return { rng, dates, wearable }
}

test('gate: "phone out of the hour before sleep", days in random order, adherence from the phone itself', async () => {
  const { rng, dates, wearable } = base(11)
  const first = shiftDay(START, 30)
  const assignments = drawDailySchedule(mulberry32(5), first, 12)
  const withDays = new Set(assignments.filter((a) => a.arm === 'B').map((a) => a.date))
  // The rule is followed on 9 with-days in 10 (2 minutes on the phone instead of about 25).
  const followed = new Set([...withDays].filter((_, i) => i % 10 !== 9))
  const phone = dates.map((d) => ({
    user_id: USER, date: d, bed_window_min: followed.has(d) ? 2 : 22 + 6 * Math.abs(gaussian(rng)), night_screen_min: 0, night_unlocks: 0,
  }))
  // Planted: a phone-free bedtime adds 30 minutes to that night's sleep (sleep_daily is dated by the morning it ends).
  const sleep = dates.map((d) => ({ user_id: USER, date: d, hours: 7.2 + 0.45 * gaussian(rng) + (followed.has(shiftDay(d, -1)) ? 0.5 : 0) }))
  const experiment: Row = {
    id: 'exp-phone', user_id: USER, supplement_id: null, intervention_id: null, name: 'Phone out of the hour before sleep',
    primary_metric: 'sleep_hours', secondary_metrics: [], status: 'running', preregistered_at: `${shiftDay(first, -1)}T10:00:00Z`,
    mde_preview: null, deleted_at: null,
    protocol: {
      version: 2, design: 'micro_randomized', phases: [], assignments, lag: 1, washoutDays: 0, minAdherence: 0.7, excludeAlcoholDays: false, zone: 10,
      behaviour: { series: 'screen_bed_any', withValue: 0, label: 'No phone in the hour before sleep' },
    },
  }
  const today = shiftDay(assignments[assignments.length - 1].date, 3)
  const admin = fakeAdmin({ wearable_daily: wearable, sleep_daily: sleep, illnesses: [], experiments: [experiment], phone_usage_daily: phone, experiment_exclusion_snapshots: [] })
  const now = new Date(`${today}T20:30:00Z`)
  const run = await runExperiments(admin, (await loadFrame(admin, USER, now)) as LoadedFrame, now)
  assert.equal(run.error, undefined)
  const row = admin.upserts.find((u) => u.table === 'analysis_results')!.row
  const payload = row.payload as { stage: string; adherence: { rate: number; interventionDays: number; interventionLogged: number }; metrics: { diff: { value: number; unit: string } }[] }
  assert.equal(payload.stage, 'complete')
  // Adherence came from phone_usage_daily: no log, no check-in.
  assert.equal(payload.adherence.interventionDays, withDays.size)
  assert.equal(payload.adherence.interventionLogged, followed.size)
  assert.ok(Math.abs(payload.adherence.rate - followed.size / withDays.size) < 0.01, String(payload.adherence.rate))
  // Analysed as assigned, the planted 30 minutes is diluted by the missed days but stays clearly favourable.
  assert.equal(payload.metrics[0].diff.unit, 'min')
  assert.ok(payload.metrics[0].diff.value > 15, String(payload.metrics[0].diff.value))
  assert.ok(!['unclear', 'negligible', 'running'].includes(String(row.state)), String(row.state))
})

test('a habit can be the outcome: no social apps after 21:00 against phone use in the hour before sleep', async () => {
  const { rng, dates, wearable } = base(12)
  const sleep = dates.map((d) => ({ user_id: USER, date: d, hours: 7.2 + 0.4 * gaussian(rng) }))
  const first = shiftDay(START, 30)
  const assignments = drawDailySchedule(mulberry32(6), first, 12)
  const withDays = new Set(assignments.filter((a) => a.arm === 'B').map((a) => a.date))
  // On with-days the social and video apps stay closed after 21:00, and bedtime phone use falls by about 12 minutes.
  const phone = dates.map((d) => ({ user_id: USER, date: d, bed_window_min: Math.max(0, 25 + 5 * gaussian(rng) - (withDays.has(d) ? 12 : 0)), night_screen_min: 0, night_unlocks: 0 }))
  const apps = dates.map((d) => ({ user_id: USER, date: d, category: 'social', after_21_min: withDays.has(d) ? 0 : 35 }))
  const experiment: Row = {
    id: 'exp-social', user_id: USER, supplement_id: null, intervention_id: null, name: 'No social apps after 21:00',
    primary_metric: 'screen_bed_min', secondary_metrics: [], status: 'running', preregistered_at: `${shiftDay(first, -1)}T10:00:00Z`,
    mde_preview: null, deleted_at: null,
    protocol: {
      version: 2, design: 'micro_randomized', phases: [], assignments, lag: 0, washoutDays: 0, minAdherence: 0.7, excludeAlcoholDays: false, zone: 5,
      behaviour: { series: 'screen_pm_social_any', withValue: 0, label: 'No social or video apps after 21:00' },
    },
  }
  const today = shiftDay(assignments[assignments.length - 1].date, 3)
  const admin = fakeAdmin({ wearable_daily: wearable, sleep_daily: sleep, illnesses: [], experiments: [experiment], phone_usage_daily: phone, phone_app_usage_daily: apps, experiment_exclusion_snapshots: [] })
  const now = new Date(`${today}T20:30:00Z`)
  const run = await runExperiments(admin, (await loadFrame(admin, USER, now)) as LoadedFrame, now)
  assert.equal(run.error, undefined)
  const row = admin.upserts.find((u) => u.table === 'analysis_results')!.row
  const payload = row.payload as { adherence: { rate: number }; metrics: { diff: { value: number; unit: string } }[] }
  assert.equal(payload.adherence.rate, 1)
  assert.equal(payload.metrics[0].diff.unit, 'min')
  assert.ok(payload.metrics[0].diff.value < -6, String(payload.metrics[0].diff.value)) // less phone: the favourable side
  assert.ok(!['unclear', 'negligible', 'running'].includes(String(row.state)), String(row.state))
})

test('the adherence series: none up to 5 minutes, a daylight day from 30 minutes, unknown when not measured', () => {
  const axis = dateAxis('2026-10-01', '2026-10-03')
  const clock = localClock([{ date: '2026-01-01', utc_offset_min: 480 }])
  const s = routineSeries(axis, {
    phone: [
      { date: '2026-10-01', bed_window_min: 4, night_screen_min: 0, night_unlocks: 0 },
      { date: '2026-10-02', bed_window_min: 12, night_screen_min: 0, night_unlocks: 0 },
    ],
    phoneApps: [{ date: '2026-10-02', category: 'video', after_21_min: 6 }],
    mobility: [
      { date: '2026-10-01', coverage_pct: 90, day_type: 'workday', outdoor_daylight_min: 35 },
      { date: '2026-10-02', coverage_pct: 90, day_type: 'workday', outdoor_daylight_min: 10 },
    ],
  }, clock)
  assert.deepEqual(s.screen_bed_any, [0, 1, null])
  assert.deepEqual(s.screen_pm_social_any, [0, 1, null])
  assert.deepEqual(s.daylight_day, [1, 0, null])
})
