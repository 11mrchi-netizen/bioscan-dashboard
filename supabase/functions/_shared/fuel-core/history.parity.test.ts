// Run: node --test "supabase/functions/_shared/fuel-core/*.test.ts"
// Parity with the Android demand history and meals-by-day: fixtures/history-input.json, history-expected.json (DemandHistoryParityTest).
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { demandHistory, type RecordedSessionInput } from './plannedDay.ts'
import { aggregateMealsByDay, type MealRow } from './weekEnergy.ts'

const dir = new URL('./fixtures/', import.meta.url)
const input = JSON.parse(readFileSync(new URL('history-input.json', dir), 'utf8')) as { recordings: RecordedSessionInput[]; anchors: (number | null)[][]; meals: MealRow[] }
const expected = JSON.parse(readFileSync(new URL('history-expected.json', dir), 'utf8')) as { histories: unknown[]; days: unknown[] }

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

test('the demand history is the phone\'s', () => {
  input.anchors.forEach(([rest, max, kg], i) => same(JSON.parse(JSON.stringify(demandHistory(input.recordings, rest, max, kg, 'Asia/Taipei'))), expected.histories[i], `history ${i}`))
})
test('meals are summed by day as the phone does', () => {
  same(aggregateMealsByDay(input.meals), expected.days, 'days')
})
