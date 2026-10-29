import assert from 'node:assert/strict'
import { test } from 'node:test'
import { runEventEvidence, EVENT_KIND } from './runEventEvidence.ts'
import type { LoadedFrame } from './runRelationships.ts'
import type { SeriesFile } from './seriesContract.ts'
import seriesFile from '../metric-registry/metric-series.json' with { type: 'json' }

type Row = Record<string, unknown>

// In-memory stand-in for the Supabase query builder: select / eq / gte / lt / in / order / range / delete / upsert.
function fakeAdmin(tables: Record<string, Row[]>) {
  const upserts: Row[] = []
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
        in(c: string, v: unknown[]) { filters.push(['in', c, v]); return q },
        order() { return q },
        range(a: number, b: number) { window = [a, b]; return q },
        delete() { return q },
        upsert(row: Row) { upserts.push(row); return Promise.resolve({ error: null }) },
        then(resolve: (v: { data: Row[]; error: null }) => void) {
          let rows = (tables[table] ?? []).filter((r) => filters.every(([op, c, v]) => (op === 'eq' ? r[c] === v : op === 'gte' ? String(r[c]) >= String(v) : op === 'lt' ? String(r[c]) < String(v) : (v as unknown[]).includes(r[c]))))
          if (window) rows = rows.slice(window[0], window[1] + 1)
          resolve({ data: rows, error: null })
        },
      }
      return q
    },
  }
}

const USER = 'u1'
const TODAY = '2026-10-31'
const day = (offset: number) => new Date(Date.parse(TODAY + 'T00:00:00Z') + offset * 86400000).toISOString().slice(0, 10)
const loaded = { userId: USER, today: TODAY, frame: {} } as unknown as LoadedFrame

test('publishes one row per episodic intervention, reading a night and a delayed tracked outcome', async () => {
  const stripNights = [-14, -12, -10, -8, -6, -4, -2]
  const wob = (o: number) => ((o * 7919) % 11) / 10 - 0.5
  const sleep = Array.from({ length: 60 }, (_, i) => {
    const o = -59 + i
    return { user_id: USER, date: day(o), respiratory_rate: 15 + wob(o) - (stripNights.some((n) => n + 1 === o) ? 1.5 : 0) }
  })
  const lactoseDays = [-40, -36, -32, -28, -24, -20, -16, -12]
  const stool = Array.from({ length: 60 }, (_, i) => {
    const o = -59 + i
    return { user_id: USER, outcome_id: 'stool', local_date: day(o), local_minute: 9 * 60, value: lactoseDays.some((n) => n + 1 === o) ? 6 : 3 + (Math.abs(o) % 3 === 0 ? 1 : 0) }
  })
  const admin = fakeAdmin({
    interventions: [
      { id: 'strips', user_id: USER, mode: 'episodic', name: 'Nasal strips', version: 1 },
      { id: 'lactose', user_id: USER, mode: 'episodic', name: 'Lactose meal', version: 1 },
      { id: 'cont', user_id: USER, mode: 'continuous', name: 'Magnesium', version: 1 },
    ],
    intervention_expectations: [
      { id: 'x1', user_id: USER, intervention_id: 'strips', outcome_kind: 'series', outcome_key: 'sleep_respiratory_rate', role: 'primary', direction: 'down', window_start_h: 0, window_end_h: 12, aggregation: 'mean', exploratory: false },
      { id: 'x2', user_id: USER, intervention_id: 'lactose', outcome_kind: 'tracked', outcome_key: 'stool', role: 'primary', direction: 'up', window_start_h: 6, window_end_h: 36, aggregation: 'max', exploratory: false },
    ],
    intervention_events: [
      ...stripNights.map((o) => ({ id: `s${o}`, user_id: USER, intervention_id: 'strips', local_date: day(o), local_minute: 22 * 60, adhered: 'yes', confounders: [], feeling: 7, assigned_by: 'self' })),
      ...lactoseDays.map((o) => ({ id: `l${o}`, user_id: USER, intervention_id: 'lactose', local_date: day(o), local_minute: 13 * 60, adhered: 'yes', confounders: [], feeling: null, assigned_by: 'self' })),
    ],
    tracked_outcomes: [{ id: 'stool', user_id: USER, name: 'Stool consistency', unit: null }],
    tracked_outcome_values: stool,
    sleep_daily: sleep,
    evening_checkins: [],
  })
  const run = await runEventEvidence(admin, loaded, seriesFile as unknown as SeriesFile, new Date(TODAY + 'T06:00:00Z'))
  assert.equal(run.error, undefined)
  assert.equal(run.results?.length, 2)
  const rows = admin.upserts.filter((r) => r.kind === EVENT_KIND)
  assert.equal(rows.length, 2)
  const strips = rows.find((r) => r.key === 'strips')!
  const sp = strips.payload as { expectations: { observed: string; diff: number }[]; ladder: string; name: string }
  assert.equal(sp.ladder, 'emerging')
  assert.equal(sp.expectations[0].observed, 'as_expected')
  assert.ok(sp.expectations[0].diff < -1)
  assert.equal(sp.name, 'Nasal strips')
  const lac = rows.find((r) => r.key === 'lactose')!
  const lp = lac.payload as { expectations: { observed: string; diff: number }[]; ladder: string }
  assert.equal(lp.ladder, 'emerging')
  assert.equal(lp.expectations[0].observed, 'as_expected')
  assert.ok(lp.expectations[0].diff >= 2)
})

test('alcohol on the day from the evening check-in leaves the event out', async () => {
  const admin = fakeAdmin({
    interventions: [{ id: 'strips', user_id: USER, mode: 'episodic', name: 'Nasal strips', version: 1 }],
    intervention_expectations: [{ id: 'x1', user_id: USER, intervention_id: 'strips', outcome_kind: 'series', outcome_key: 'sleep_respiratory_rate', role: 'primary', direction: 'down', window_start_h: 0, window_end_h: 12, aggregation: 'mean', exploratory: false }],
    intervention_events: [{ id: 'e1', user_id: USER, intervention_id: 'strips', local_date: day(-5), local_minute: 22 * 60, adhered: 'yes', confounders: [], feeling: null, assigned_by: 'self' }],
    tracked_outcomes: [], tracked_outcome_values: [], sleep_daily: [], evening_checkins: [{ user_id: USER, date: day(-5), drinks: 2 }],
  })
  const run = await runEventEvidence(admin, loaded, seriesFile as unknown as SeriesFile)
  assert.equal(run.results?.[0].nUsed, 0)
})

test('no episodic interventions means nothing is published', async () => {
  const admin = fakeAdmin({ interventions: [{ id: 'c', user_id: USER, mode: 'continuous', name: 'x', version: 1 }] })
  const run = await runEventEvidence(admin, loaded, seriesFile as unknown as SeriesFile)
  assert.deepEqual(run.results, [])
  assert.equal(admin.upserts.length, 0)
})
