// Run: node --test "supabase/functions/_shared/fuel-core/*.test.ts"
// Parity with the Android training-day builder: fixtures/planned-day-input.json holds scenarios (events, planned sessions, calendar entries,
// recordings, history) and fixtures/planned-day-expected.json the days the Kotlin PlannedDayParityTest resolves from them.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import type { PlannedDay } from './day.ts'
import { type CalendarOverride, type CalendarSessionInput, type DemandHistory, estimateRunTime, type EventInput, fmt as _f, intensityForTitle, parseCalendarWorkout, type PlannedSessionInput, type RecordedSessionInput, resolvePlannedDays, type StoredLink, zdtString } from './index.ts'

const dir = new URL('./fixtures/', import.meta.url)
interface Scenario {
  name: string; zone: string; from: string; days: number; now: string; history: DemandHistory; events: EventInput[]; planned: PlannedSessionInput[]
  calendar: CalendarSessionInput[]; recorded: RecordedSessionInput[]; overrides: Record<string, CalendarOverride>; links: Record<string, StoredLink>
}
const input = JSON.parse(readFileSync(new URL('planned-day-input.json', dir), 'utf8')) as Scenario[]
const expected = JSON.parse(readFileSync(new URL('planned-day-expected.json', dir), 'utf8')) as Record<string, PlannedDay[]>
void _f

function same(a: unknown, b: unknown, path = '$'): void {
  if (typeof a === 'number' && typeof b === 'number') { assert.ok(Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(b)), `${path}: ${a} vs ${b}`); return }
  if (Array.isArray(a) && Array.isArray(b)) { assert.equal(a.length, b.length, `${path}: length`); a.forEach((x, i) => same(x, b[i], `${path}[${i}]`)); return }
  if (a && b && typeof a === 'object' && typeof b === 'object') {
    assert.deepEqual(Object.keys(a).sort(), Object.keys(b).sort(), `${path}: keys`)
    for (const k of Object.keys(a)) same((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k], `${path}.${k}`)
    return
  }
  assert.equal(a, b, path)
}

for (const s of input) {
  test(`planned days: ${s.name}`, () => {
    const got = resolvePlannedDays(s.from, s.days, s.events, s.planned, s.calendar, s.recorded, s.history, s.now, s.zone, s.overrides, s.links)
    same(JSON.parse(JSON.stringify(got)), expected[s.name], s.name)
  })
}

test('the scenarios reach what they should', () => {
  const text = JSON.stringify(expected)
  for (const needle of [
    'reads as a hard session', 'reads as an easy session', 'from the calendar, about', 'intervals from the calendar', 'working sets', 'deload: light activity',
    'you set it as', 'RPE 8', 'of reserve', 'your usual run', 'no intensity known', 'B race', 'at a default pace', 'at your recent pace', 'at your easy pace',
    '"matchStored":true', '"recorded":true', 'planned as speed', 'Europe/Rome',
  ]) assert.ok(text.includes(needle), `no scenario reaches: ${needle}`)
  const days = Object.values(expected).flat()
  assert.ok(days.some((d) => d.reviews.length > 0), 'a calendar entry waits for review')
  assert.ok(days.some((d) => d.plans.length > d.items.length || d.plans.some((p) => !d.items.includes(p))), 'recordings replace plans')
})

test('zone strings follow java.time', () => {
  assert.equal(zdtString(Date.parse('2026-11-24T07:30:00+08:00'), 'Asia/Taipei'), '2026-11-24T07:30+08:00[Asia/Taipei]')
  assert.equal(zdtString(Date.parse('2027-03-28T07:30:15+02:00'), 'Europe/Rome'), '2027-03-28T07:30:15+02:00[Europe/Rome]')
  assert.equal(zdtString(Date.parse('2026-06-01T00:00:00Z'), 'UTC'), '2026-06-01T00:00Z[UTC]')
})

test('calendar text: units that must not be mistaken for each other', () => {
  const sets = parseCalendarWorkout('Lifts', 'Squat 5x5 @100kg\nRows 3x8 (6x800m is not sets)', 'strength')
  assert.equal(sets.sets, 8)
  assert.equal(sets.minReps, 5)
  assert.equal(parseCalendarWorkout('800m Repeats — 5-8 rounds', null, 'run').rounds, 6.5)
  assert.equal(parseCalendarWorkout('Long Run — 25,7km', null, 'run').distanceKm, 25.7)
  assert.equal(parseCalendarWorkout('Walk 0.2km', null, 'run').distanceKm, null)
  assert.equal(estimateRunTime(10, 0, []), null)
  assert.equal(intensityForTitle(null), null)
})
