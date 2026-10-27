import assert from 'node:assert/strict'
import { test } from 'node:test'
import { plantedDataset } from './fixtures.ts'
import { HYPOTHESES } from './hypotheses.ts'
import { buildFrame, localClock, runRelationships } from './runRelationships.ts'
import type { RawInputs } from './runRelationships.ts'
import { CORE_VERSION } from './version.ts'

type Row = Record<string, unknown>

// In-memory stand-in for the Supabase query builder: select / eq / gte / lt / order / range / delete / upsert.
function fakeAdmin(tables: Record<string, Row[]>) {
  const upserts: Row[] = []
  const ledger: Row[] = []
  const deletes: { table: string; filters: [string, string, unknown][] }[] = []
  return {
    upserts,
    ledger,
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
        upsert(row: Row | Row[]) {
          if (table === 'evidence_ledger') ledger.push(...(row as Row[]))
          else upserts.push(row as Row)
          return Promise.resolve({ error: null })
        },
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

test('buildFrame: exposures are zero only where the logging model says so, illness days are excluded', () => {
  const raw: RawInputs = {
    wearable: [
      { date: '2026-03-02', hrv: 60, rhr: 55, steps: 9000 },
      { date: '2026-03-03', hrv: 58, rhr: 56, steps: 8000 },
      { date: '2026-03-04', hrv: 0, rhr: null, steps: null }, // HRV 0 is not a reading
    ],
    sleep: [{ date: '2026-03-02', hours: 7 }, { date: '2026-03-03', hours: 0.5 }], // 0.5 h is a fault
    wellbeing: [{ date: '2026-03-02', energy: 6, mood: null, stress: null, soreness: null }],
    meals: [
      { logged_at: '2026-03-02T12:00:00Z', meal_items: [{ alcohol_g: null, caffeine_mg: 80 }] }, // morning-free, no drink
      { logged_at: '2026-03-03T19:00:00Z', meal_items: [{ alcohol_g: 19.7, caffeine_mg: 95 }] },
      { logged_at: '2026-03-03T08:00:00Z', meal_items: null },
    ],
    sessions: [
      { start_time: '2026-03-03T07:00:00Z', duration_min: 45, type: 'strength' },
      { start_time: '2026-03-02T07:00:00Z', duration_min: 10, type: 'run' }, // too short
      { start_time: '2026-03-02T18:00:00Z', duration_min: 40, type: 'walk' }, // a walk is not training
    ],
    recovery: [{ date: '2026-03-03' }],
    illnesses: [{ start_date: '2026-03-04', end_date: '2026-03-05' }],
  }
  const f = buildFrame(raw, '2026-03-06', 6) // 03-01 .. 03-06
  assert.deepEqual(f.dates, ['2026-03-01', '2026-03-02', '2026-03-03', '2026-03-04', '2026-03-05', '2026-03-06'])
  assert.deepEqual(f.series.hrv, [null, 60, 58, null, null, null])
  assert.deepEqual(f.series.sleep_hours, [null, 7, null, null, null, null])
  assert.deepEqual(f.series.alcohol_any, [null, 0, 1, null, null, null]) // no food log on 03-01 and after: unknown, not zero
  assert.deepEqual(f.series.caffeine_pm_any, [null, 0, 1, null, null, null]) // 80 mg at 12:00 is before 14:00; 95 mg at 19:00 counts
  assert.deepEqual(f.series.training_day, [null, 0, 1, 0, null, null]) // a 10 min session is not training; 03-04 has a watch record
  assert.deepEqual(f.series.recovery_any, [null, null, 1, 0, 0, 0]) // zero only from the first logged session
  assert.deepEqual(f.excluded, [false, false, false, true, true, true]) // illness through the day after it ended
})

test('runRelationships publishes one row per hypothesis from raw tables and replaces older rows', async () => {
  const ds = plantedDataset(20261010, 200, '2026-01-05', 10_000, 10_000, [])
  const today = ds.dates[ds.dates.length - 1]
  const tables: Record<string, Row[]> = {
    wearable_daily: ds.dates.map((d, i) => ({ user_id: USER, date: d, hrv: ds.hrv[i], rhr: ds.rhr[i], steps: null })),
    sleep_daily: ds.dates.map((d, i) => ({ user_id: USER, date: d, hours: ds.sleepH[i] })),
    meals: ds.dates.map((d, i) => ({ user_id: USER, logged_at: `${d}T12:00:00Z`, meal_items: [{ alcohol_g: ds.alcoholG[i] > 0 ? ds.alcoholG[i] : null, caffeine_mg: null }] })),
    exercise_sessions: ds.dates.flatMap((d, i) => (ds.train[i] ? [{ user_id: USER, start_time: `${d}T07:00:00Z`, duration_min: 45, type: 'strength' }] : [])),
    recovery_sessions: ds.dates.flatMap((d, i) => (ds.sauna[i] ? [{ user_id: USER, date: d }] : [])),
    illnesses: [],
  }
  const admin = fakeAdmin(tables)
  const run = await runRelationships(admin, USER, new Date(`${today}T20:00:00Z`))
  assert.equal(run.error, undefined)
  assert.equal(run.results!.length, HYPOTHESES.length)
  assert.equal(admin.upserts.length, HYPOTHESES.length)

  const row = (k: string) => admin.upserts.find((u) => u.key === k) as Row
  assert.equal(row('alcohol_hrv').kind, 'relationship')
  assert.equal(row('alcohol_hrv').state, 'clear')
  assert.equal(row('alcohol_hrv').engine_version, CORE_VERSION)
  assert.equal(row('alcohol_hrv').as_of, today)
  assert.equal(row('training_hrv').state, 'not_yet') // fixed weekly schedule
  assert.equal(row('steps_hrv').state, 'not_yet') // no steps recorded
  assert.equal(row('caffeine_pm_sleep').state, 'not_yet') // no caffeine logged
  const payload = row('alcohol_hrv').payload as Row
  assert.equal(payload.catalogueVersion, '2.1.0')
  assert.equal(payload.rung, 'associated')
  assert.equal(payload.noEffect, 2)
  assert.ok('capture' in payload)
  // NOT YET rows carry their progress in the confidence columns.
  assert.equal(row('caffeine_pm_sleep').confidence_have, 0)
  assert.ok((row('caffeine_pm_sleep').confidence_need as number) >= 6)
  assert.ok(admin.deletes.some((d) => d.table === 'analysis_results' && d.filters.some(([op, c]) => op === 'lt' && c === 'as_of')))
  // Analysis review phase E: every verdict goes to the ledger too.
  assert.equal(admin.ledger.length, HYPOTHESES.length)
  const entry = admin.ledger.find((l) => l.question_id === 'alcohol_hrv') as Row
  assert.equal(entry.source, 'catalogue')
  assert.equal(entry.verdict, 'clear')
  assert.equal(entry.as_of, today)
  assert.ok((entry.information as number) > 0 && (entry.information as number) <= 1)
})

test('a registered question is tested only on days after its registration, and a withdrawn one not at all', async () => {
  const ds = plantedDataset(20261011, 300, '2026-01-05', 10_000, 10_000, [])
  const today = ds.dates[ds.dates.length - 1]
  const tables: Record<string, Row[]> = {
    wearable_daily: ds.dates.map((d, i) => ({ user_id: USER, date: d, hrv: ds.hrv[i], rhr: ds.rhr[i], steps: null })),
    sleep_daily: ds.dates.map((d, i) => ({ user_id: USER, date: d, hours: ds.sleepH[i] })),
    meals: ds.dates.map((d, i) => ({ user_id: USER, logged_at: `${d}T12:00:00Z`, meal_items: [{ alcohol_g: ds.alcoholG[i] > 0 ? ds.alcoholG[i] : null, caffeine_mg: null }] })),
    exercise_sessions: [],
    recovery_sessions: [],
    illnesses: [],
    registered_questions: [
      { user_id: USER, id: 1, exposure: 'alcohol_any', outcome: 'rhr', lag: 1, no_effect: 1, registered_on: ds.dates[119], source: 'case_crossover', withdrawn_at: null },
      { user_id: USER, id: 2, exposure: 'alcohol_any', outcome: 'sleep_hours', lag: 1, no_effect: 10, registered_on: ds.dates[119], source: 'case_crossover', withdrawn_at: '2026-06-01T00:00:00Z' },
    ],
  }
  const admin = fakeAdmin(tables)
  const run = await runRelationships(admin, USER, new Date(`${today}T20:00:00Z`))
  assert.equal(run.error, undefined)
  assert.deepEqual(run.registered?.map((r) => r.id), ['reg_1'])
  const row = admin.upserts.find((u) => u.kind === 'registered_question') as Row
  const payload = row.payload as { n: number; registeredOn: string; daysSince: number; label: string }
  assert.equal(payload.registeredOn, ds.dates[119])
  assert.equal(payload.daysSince, 180)
  assert.ok(payload.n <= 180, `only post-registration days: ${payload.n}`)
  assert.equal(payload.label, 'Alcohol and next-morning resting heart rate')
  assert.ok(admin.ledger.some((l) => l.question_id === 'reg_1' && l.source === 'registered'))
})

// Analysis review phase B: local time, the small hours belong to the evening before, and check-ins make real zeros.
test('localClock: timestamps become local dates and hours with the recorded offset, nearest earlier day first', () => {
  const clock = localClock([{ date: '2026-03-01', utc_offset_min: 480 }, { date: '2026-03-10', utc_offset_min: 60 }])
  assert.deepEqual(clock('2026-03-02T07:00:00Z'), { date: '2026-03-02', hour: 15 }) // 15:00 in UTC+8, not 07:00
  assert.deepEqual(clock('2026-03-02T23:30:00Z'), { date: '2026-03-03', hour: 7 }) // breakfast the next local day
  assert.deepEqual(clock('2026-03-12T07:00:00Z'), { date: '2026-03-12', hour: 8 }) // after the move to UTC+1
  assert.deepEqual(clock('2026-02-20T07:00:00Z'), { date: '2026-02-20', hour: 15 }) // before the first row: its offset
  assert.deepEqual(localClock([])('2026-03-02T07:00:00Z'), { date: '2026-03-02', hour: 7 }) // nothing recorded: UTC
})

test('buildFrame phase B: local caffeine hours, late-night drinks on the evening before, explicit zeros from check-ins', () => {
  const raw: RawInputs = {
    wearable: ['2026-03-01', '2026-03-02', '2026-03-03', '2026-03-04', '2026-03-05', '2026-03-06'].map((date) => ({ date, hrv: 60, rhr: 55, steps: 8000 })),
    sleep: [],
    wellbeing: [],
    meals: [
      { logged_at: '2026-03-01T07:00:00Z', meal_items: [{ alcohol_g: null, caffeine_mg: 90 }] }, // 15:00 local: afternoon caffeine
      { logged_at: '2026-03-02T16:40:00Z', meal_items: [{ alcohol_g: 14, caffeine_mg: null }] }, // 00:40 on 03-03: evening of 03-02
      { logged_at: '2026-03-03T13:30:00Z', meal_items: [{ alcohol_g: null, caffeine_mg: null }] }, // 21:30: a late meal on 03-03
      { logged_at: '2026-03-05T01:00:00Z', meal_items: [{ alcohol_g: null, caffeine_mg: null }] }, // breakfast on 03-05, after check-ins began
    ],
    sessions: [{ start_time: '2026-03-04T10:30:00Z', duration_min: 60, type: 'run' }], // ends 19:30 local
    recovery: [],
    illnesses: [],
    checkins: [
      { date: '2026-03-04', drinks: 0, caffeine_after_14: false, late_meal: false, stress: 4 },
      { date: '2026-03-06', drinks: 2, caffeine_after_14: null, late_meal: null, stress: 2 },
    ],
    context: [{ date: '2026-03-01', utc_offset_min: 480 }],
  }
  const f = buildFrame(raw, '2026-03-06', 6)
  // 03-01 legacy zero (food log, before check-ins), 03-02 a late-night drink, 03-03 legacy zero, 03-04 answered none,
  // 03-05 unknown (after check-ins began, no answer and no positive log), 03-06 answered two drinks
  assert.deepEqual(f.series.alcohol_any, [0, 1, 0, 0, null, 1])
  assert.deepEqual(f.series.caffeine_pm_any, [1, 0, 0, 0, null, null])
  assert.deepEqual(f.series.late_meal, [0, 1, 1, 0, null, null])
  assert.deepEqual(f.series.stress_high, [null, null, null, 1, null, 0])
  assert.deepEqual(f.series.evening_training, [0, 0, 0, 1, 0, 0])
  assert.equal(f.capture!.firstCheckin, '2026-03-04')
  assert.equal(f.capture!.localTime, true)
  assert.equal(f.capture!.knownShare28.alcohol, 0.83) // 5 of the 6 days
})
