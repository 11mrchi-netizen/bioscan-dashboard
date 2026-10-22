// Analysis review phase G: the nightly fitness-fatigue row, and the frame's daily training load it is fitted on.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { FITNESS_FATIGUE_KIND, runFitnessFatigue } from './runFitnessFatigue.ts'
import type { FitnessFatigueResult } from './fitnessFatigue.ts'
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
const START = '2025-10-20'
const shift = (d: string, n: number) => new Date(Date.parse(d + 'T00:00:00Z') + n * 86400000).toISOString().slice(0, 10)

function tables(): Record<string, Row[]> {
  const rng = mulberry32(5)
  const dates = dateAxis(START, shift(START, 364))
  const wearable = dates.map((d) => ({ user_id: USER, date: d, hrv: Math.exp(4.1 + 0.08 * gaussian(rng)), rhr: 52 + gaussian(rng), steps: 9000 }))
  const sessions = dates.filter((_, i) => i % 2 === 0).map((d, i) => ({
    user_id: USER, start_time: `${d}T07:00:00Z`, duration_min: 50, type: 'run', rpe: i % 4 === 0 ? 6 : null, avg_hr: 150, max_hr: 185,
  }))
  return { wearable_daily: wearable, sleep_daily: [], wellbeing_daily: [], meals: [], exercise_sessions: sessions, recovery_sessions: [], illnesses: [] }
}

test('the frame carries the day\'s session load, and the run publishes one fitness_fatigue row', async () => {
  const admin = fakeAdmin(tables())
  const now = new Date(`${shift(START, 364)}T20:00:00Z`)
  const loaded = (await loadFrame(admin, USER, now)) as LoadedFrame
  const load = loaded.frame.series.training_load
  const day = loaded.frame.dates.indexOf(START)
  assert.equal(load[day], 300, 'RPE 6 x 50 minutes')
  assert.ok((load[day + 2] as number) > 0 && (load[day + 2] as number) !== 300, 'TRIMP when no RPE was logged')
  assert.equal(load[day + 1], 0, 'a watch day without a session is rest')
  const run = await runFitnessFatigue(admin, loaded, now)
  assert.equal(run.error, undefined)
  const row = admin.upserts.find((r) => r.kind === FITNESS_FATIGUE_KIND)!
  assert.equal(row.key, 'hrv')
  assert.equal(row.engine_version, CORE_VERSION)
  const payload = row.payload as FitnessFatigueResult
  assert.ok(['no_better', 'unclear', 'predictive', 'not_yet'].includes(payload.state))
  assert.equal(payload.adopted, payload.state === 'predictive')
  assert.deepEqual(payload.standard, { ctl: 42, atl: 7 })
})
