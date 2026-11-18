// Run: node --test "console/src/lib/*.test.ts"
// The web block planner's state over the shared engine, with the parity fixtures' placeholder programs.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { DefinitionIndex, parseDefinition } from '../../../supabase/functions/_shared/training-core/index.ts'
import {
  buildToward, changeWeeks, choicesJson, startAfterBlocks, DEFAULT_SETTINGS, definitionsUsed, generate, missingMaxes, newDraft, pickProtocol, pickStrength, type PlannerCatalog,
} from './blockPlanner.ts'

const sc = JSON.parse(readFileSync(new URL('../../../supabase/functions/_shared/training-core/fixtures/scenarios.json', import.meta.url), 'utf8'))
const index = new DefinitionIndex(sc.definitions.map(parseDefinition))
const cat: PlannerCatalog = {
  index, raw: {}, unreadable: [], settings: DEFAULT_SETTINGS, events: [{ id: 1, name: 'Spring half', priority: 'A', eventDate: '2026-03-14', plannedDurationMin: 95 }], bodyweightKg: 78,
  maxes: { press: { key: 'press', name: 'Press', entry: { oneRmKg: 80 }, source: 'test', asOf: '2026-01-01', basedOn: 'recorded' } },
}

test('a new draft starts on the next Monday with the settings, and picking a program sizes it to two blocks', () => {
  let d = newDraft(DEFAULT_SETTINGS, '2026-01-07', 78)
  assert.equal(d.startDate, '2026-01-12')
  assert.equal(d.slotTimes[1].start, '07:00')
  d = pickStrength(d, index, 'example.three_day')
  d = pickProtocol(d, index, 'example.black')
  assert.equal(d.weeks, 8) // two blocks of three weeks, each with a deload
  assert.deepEqual(d.timeline, ['normal', 'normal', 'normal', 'deload', 'normal', 'normal', 'normal', 'deload'])
  assert.equal(d.layout.filter((x) => x.strengthSession != null).length, 3)
  const { block } = generate(d, cat)
  assert.ok(block && block.sessions.length > 0)
  assert.deepEqual(missingMaxes(d, cat).sort(), ['Deadlift', 'Push-up', 'Squat', 'Weighted Pull-up'])
  const used = definitionsUsed(d, index, block!).map((x) => x.key).sort()
  assert.ok(used.includes('example.three_day') && used.includes('example.black') && used.includes('example.run'))
  const ch = choicesJson(d, index)
  assert.equal(ch.path, 'build')
  assert.equal((ch.timeline as string[]).length, 8)
})

test('building toward an event sets the length to its week and tapers the last weeks', () => {
  let d = pickStrength(newDraft(DEFAULT_SETTINGS, '2026-01-07', null), index, 'example.three_day')
  d = buildToward(d, index, cat.events[0], cat.events)
  assert.equal(d.weeks, 9)
  assert.deepEqual(d.timeline.slice(-2), ['deload', 'deload'])
  assert.equal(d.name, 'Toward Spring half')
  d = changeWeeks(d, index, 4)
  assert.equal(d.timeline.length, 4)
})

test('toward a race the weeks start with early weeks and the last cycle ends right before the taper', () => {
  let d = pickStrength(newDraft(DEFAULT_SETTINGS, '2026-01-07', null), index, 'example.three_day')
  const race = { id: 2, name: 'Race', priority: 'B', eventDate: '2026-03-16', plannedDurationMin: 60 }
  d = buildToward(d, index, race, [race])
  assert.deepEqual(d.timeline, ['lead', 'lead', 'normal', 'normal', 'normal', 'deload', 'normal', 'normal', 'normal', 'deload'])
})

test('a new block starts the day after the latest block still running', () => {
  assert.equal(startAfterBlocks([{ end_date: '2026-11-29', status: 'completed' }, { end_date: '2026-10-01', status: 'completed' }], '2026-11-18'), '2026-11-30')
  assert.equal(startAfterBlocks([{ end_date: '2026-10-01', status: 'completed' }], '2026-11-18'), undefined)
  assert.equal(startAfterBlocks([{ end_date: '2026-12-31', status: 'abandoned' }], '2026-11-18'), undefined)
})
