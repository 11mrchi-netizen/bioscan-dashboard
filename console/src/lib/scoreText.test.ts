// Run: node --test "console/src/lib/*.test.ts"
// The web says what the phone says: expectations taken from ScorePresentation.kt's rules.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { bandWord, chargeRankLine, domainSentence, driverSummary, formatInputValue, formatJudgedValue, referenceLine, type DomainScore } from './scoreText.ts'

test('input values in the phone formats', () => {
  assert.equal(formatInputValue('sleep.hours', 6.1), '6h06')
  assert.equal(formatInputValue('sleep.midpoint_min', 938), '03:38')
  assert.equal(formatInputValue('sleep.deep_rem_min', 171), '2h51')
  assert.equal(formatInputValue('sleep.deep_rem_min', 45), '45 min')
  assert.equal(formatInputValue('recovery.hrv', 76.9), '77 ms')
  assert.equal(formatInputValue('fuel.energy_ratio', 1.3446), '134 %')
  assert.equal(formatInputValue('gut.timing_min', 496), '08:16')
  assert.equal(formatInputValue('sleep.debt_7d', 0.01), 'none')
  assert.equal(formatInputValue('mind.mood', 7), '7/10')
})

test('a ratio just short of target never reads as the target', () => {
  assert.equal(formatJudgedValue({ spec: { key: 'fuel.protein_ratio', label: 'Protein', weight: 1 }, value: 0.996, score: 99, direction: 'SHORT' }), '99 %')
})

test('reference lines by kind', () => {
  const base = { spec: { key: 'recovery.hrv', label: 'HRV', weight: 1, kind: { type: 'x.InputKind.Baseline', log: false } }, value: 70, score: 90, reference: 60, spread: 5, direction: 'ABOVE' as const }
  assert.equal(referenceLine(base), 'your normal 55 ms – 65 ms')
  assert.equal(referenceLine({ ...base, spec: { key: 'fuel.protein_ratio', label: 'Protein', weight: 1, kind: { type: 'x.InputKind.AtLeast', target: 1 } } }), 'target at least 100 %')
  assert.equal(referenceLine({ ...base, spec: { key: 'fuel.energy_ratio', label: 'Energy', weight: 1, kind: { type: 'x.InputKind.Band', lo: 0.9, hi: 1.1 } } }), 'aim 90 % – 110 %')
})

test('sentences: ring, CHARGE rank and drivers', () => {
  const d: DomainScore = { key: 'SLEEP', score: 48, raw: { groups: [{ group: { key: 'main', label: 'Sleep', weight: 1 }, score: 76, coverage: 1, inputs: [
    { spec: { key: 'sleep.hours', label: 'Duration', weight: 30 }, value: 6.1, score: 37, direction: 'BELOW' },
    { spec: { key: 'sleep.regularity_min', label: 'Regularity', weight: 10 }, value: 40, score: 93, direction: 'BELOW' },
    { spec: { key: 'sleep.respiratory', label: 'Breathing', weight: 10 }, value: 13.5, score: 120, direction: 'ABOVE' },
  ] }] } }
  assert.equal(domainSentence(d), 'Lifted by breathing; held back by duration.')
  assert.equal(domainSentence({ ...d, key: 'FUEL', score: null }), 'Not enough logged to score: the food log or gut entries for the day are missing.')
  assert.equal(chargeRankLine({ score: 81, percentile: 0.5387, drivers: [] }), 'Better than 54 of your last 100 mornings.')
  assert.equal(driverSummary({ score: 81, drivers: [{ key: 'SLEEP', points: -6.3 }, { key: 'RECOVERY', points: 4.1 }, { key: 'LOAD', points: 3 }] }), 'Mostly RECOVERY (+4) and LOAD (+3). SLEEP holds it back (-6).')
  assert.equal(bandWord('LOAD', 100), 'ON TARGET')
  assert.equal(bandWord('SLEEP', 100), 'PRIMED')
})
