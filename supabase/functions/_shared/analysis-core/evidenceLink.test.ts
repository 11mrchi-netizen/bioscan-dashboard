// Gate for intervention-to-evidence matching (docs/intervention-protocol-intelligence).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { evidenceForIntervention } from './evidenceLink.ts'
import type { EvidenceRow } from './evidenceLink.ts'

const row = (overrides: Partial<EvidenceRow> = {}): EvidenceRow => ({
  subjectType: 'ingredient',
  subjectKey: 'magnesium_glycinate',
  outcomeKey: 'sleep_duration',
  claimText: 'placeholder claim',
  effectDirection: 'increase',
  evidenceTier: 'observational',
  reviewStatus: 'reviewed',
  ...overrides,
})

test('only rows matching both subjectType and subjectKey are considered', () => {
  const evidence = [row(), row({ subjectKey: 'ashwagandha' }), row({ subjectType: 'behavior', subjectKey: 'magnesium_glycinate' })]
  const [link] = evidenceForIntervention('ingredient', 'magnesium_glycinate', ['sleep_duration'], evidence)
  assert.equal(link.evidence.length, 1)
})

test('evidence is grouped per outcome, not collapsed across outcomes', () => {
  const evidence = [row({ outcomeKey: 'sleep_duration' }), row({ outcomeKey: 'hrv', evidenceTier: 'controlled_human' })]
  const links = evidenceForIntervention('ingredient', 'magnesium_glycinate', ['sleep_duration', 'hrv'], evidence)
  assert.equal(links.length, 2)
  assert.equal(links.find((l) => l.outcomeKey === 'sleep_duration')!.evidence.length, 1)
  assert.equal(links.find((l) => l.outcomeKey === 'hrv')!.evidence.length, 1)
})

test('an outcome with no matching evidence still gets an entry, with an empty array', () => {
  const links = evidenceForIntervention('ingredient', 'magnesium_glycinate', ['mood'], [row({ outcomeKey: 'sleep_duration' })])
  assert.equal(links.length, 1)
  assert.deepEqual(links[0], { outcomeKey: 'mood', evidence: [] })
})

test('stronger tiers sort first within an outcome', () => {
  const evidence = [
    row({ evidenceTier: 'mechanistic' }),
    row({ evidenceTier: 'meta_analysis' }),
    row({ evidenceTier: 'observational' }),
    row({ evidenceTier: 'controlled_human' }),
  ]
  const [link] = evidenceForIntervention('ingredient', 'magnesium_glycinate', ['sleep_duration'], evidence)
  assert.deepEqual(link.evidence.map((e) => e.evidenceTier), ['meta_analysis', 'controlled_human', 'observational', 'mechanistic'])
})

test('a reviewed row breaks a tie with an unreviewed row of the same tier, but unreviewed rows are kept', () => {
  const evidence = [row({ reviewStatus: 'unreviewed' }), row({ reviewStatus: 'reviewed' })]
  const [link] = evidenceForIntervention('ingredient', 'magnesium_glycinate', ['sleep_duration'], evidence)
  assert.equal(link.evidence.length, 2)
  assert.equal(link.evidence[0].reviewStatus, 'reviewed')
  assert.equal(link.evidence[1].reviewStatus, 'unreviewed')
})

test('a null evidenceTier sorts last, not first', () => {
  const evidence = [row({ evidenceTier: null }), row({ evidenceTier: 'animal' })]
  const [link] = evidenceForIntervention('ingredient', 'magnesium_glycinate', ['sleep_duration'], evidence)
  assert.equal(link.evidence[0].evidenceTier, 'animal')
  assert.equal(link.evidence[1].evidenceTier, null)
})

test('an intervention with no intended outcomes gets no links', () => {
  assert.deepEqual(evidenceForIntervention('ingredient', 'magnesium_glycinate', [], [row()]), [])
})
