// Analysis review phase G: the typicality strain flag is scored against logged illness on a replay of the history.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { typicalityScorecard } from './typicalityScorecard.ts'
import { typicalitySeries } from './typicalityFixtures.ts'

const STARTS = [150, 185, 215, 245, 275, 305, 335]

// The body changes from `phys` days on; the person logs the illness from two days after each start.
function world(seed: number, physOffset: number, starts = STARTS) {
  const f = typicalitySeries(seed, 365, '2025-10-01', starts.map((s) => ({ start: s + physOffset, length: 6 })))
  const excluded = f.dates.map((_, i) => starts.some((s) => i >= s + 2 && i < s + 7))
  return { dates: f.dates, hrv: f.hrv, rhr: f.rhr, sleepHours: f.sleepHours, deepRemMin: f.deepRemMin, steps: f.steps, excluded }
}

test('when the body changes two days before an illness is logged, the strain flag earns its place', () => {
  for (let s = 0; s < 4; s++) {
    const r = typicalityScorecard(world(40 + s, 0))
    assert.equal(r.verdict, 'earns_its_place', r.sentence)
    assert.ok(r.precisionLo! > r.baseRate!)
    assert.equal(r.onsets, 7)
  }
})

test('when the flagged days have nothing to do with the logged illnesses, it never earns its place', () => {
  for (let s = 0; s < 4; s++) {
    const r = typicalityScorecard(world(60 + s, 12))
    assert.notEqual(r.verdict, 'earns_its_place', r.sentence)
  }
})

test('with fewer than five logged illnesses it is not scored yet', () => {
  const r = typicalityScorecard(world(70, 0, STARTS.slice(0, 3)))
  assert.equal(r.verdict, 'not_yet')
  assert.match(r.sentence, /^Scored once there are 5 logged illnesses/)
})
