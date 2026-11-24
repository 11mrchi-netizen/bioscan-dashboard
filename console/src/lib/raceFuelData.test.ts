// Run: node --test "src/lib/*.test.ts"
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { aidStationsOf, buildRaceFuel, courseOf, type EventRow, intensityOf } from './raceFuelPlan.ts'
import { NO_WEATHER } from '../../../supabase/functions/_shared/fuel-core/index.ts'

const event = (o: Partial<EventRow> = {}): EventRow => ({
  id: 1, name: 'Alpine 25', kind: 'race', sport: 'trail_run', priority: 'B', event_date: '2026-12-05', start_time: '2026-12-04T22:30:00+00:00', status: 'planned', target_time_min: 210,
  expected_duration_min: null, distance_km: 25, ascent_m: 800, start_lat: null, start_lon: null, course_profile: [[0, 100], [12500, 600], [25000, 120]], aid_stations: [{ name: 'CP1', km: 12, drop_bag: true, cutoff_min: 150 }], ...o,
})
const rows = (o: Partial<Parameters<typeof buildRaceFuel>[0]> = {}) => ({ event: event(), weightKg: 73.5, sweatTests: [], products: [], runs: [], weather: NO_WEATHER, zone: 'Asia/Taipei', history: null, ...o })

test('aid stations and course points read as the phone reads them', () => {
  assert.deepEqual(aidStationsOf([{ name: 'CP1', km: 12, drop_bag: true, cutoff_min: 150 }, { name: 'bad' }]), [{ name: 'CP1', km: 12, water: true, food: false, dropBag: true, cutoffMin: 150 }])
  assert.deepEqual(courseOf([[0, 100], [null, 5], [500, null]]), [{ distanceM: 0, elevationM: 100 }, { distanceM: 500, elevationM: null }])
  assert.equal(intensityOf('race', 'B', 200), 'RACE')
  assert.equal(intensityOf('race', 'C', 200), 'LONG')
  assert.equal(intensityOf('long_session', null, 60), 'HARD')
})

test('a race plan: the start is the event\'s instant in the zone, loading is planned for a long race', () => {
  const r = buildRaceFuel(rows())
  assert.ok(r)
  assert.equal(r.start, '2026-12-05T06:30+08:00[Asia/Taipei]')
  assert.equal(r.hasPlace, false)
  assert.equal(r.plan.durationMin, 210)
  assert.ok(r.plan.before.some((b) => b.whenLabel === '2 DAYS BEFORE'))
  assert.ok(r.plan.assumptions.some((a) => a.startsWith('Weather: unknown')))
  assert.ok(r.plan.steps.length > 5 && r.plan.steps[0].aidStation === null)
  assert.ok(r.plan.legs.some((l) => l.to === 'CP1'))
})

test('no duration and no runs: no plan; runs give one', () => {
  assert.equal(buildRaceFuel(rows({ event: event({ target_time_min: null }) })), null)
  const runs = [[10, 150, 62], [12.5, 300, 85], [8, 40, 46], [21, 520, 158], [15, 200, 96]].map(([d, a, m]) => ({ distanceKm: d, ascentM: a, durationMin: m }))
  const r = buildRaceFuel(rows({ event: event({ target_time_min: null }), runs }))
  assert.ok(r && r.plan.assumptions.some((a) => a.includes('your last 5 runs')))
})

test('an event with no start time starts at nine', () => {
  assert.equal(buildRaceFuel(rows({ event: event({ start_time: null, kind: 'long_session', priority: null }) }))?.start, '2026-12-05T09:00+08:00[Asia/Taipei]')
})
