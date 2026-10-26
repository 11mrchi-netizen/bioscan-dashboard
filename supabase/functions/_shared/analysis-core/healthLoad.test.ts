import assert from 'node:assert/strict'
import { test } from 'node:test'
import { baselineExclusionSpans } from './healthLoad.ts'
import type { HealthInputs } from './healthLoad.ts'
import { fakeAdmin, legacyIllnessCopy } from './healthTestKit.ts'
import { buildFrame, loadFrame } from './runRelationships.ts'
import type { LoadedFrame } from './runRelationships.ts'

const TODAY = '2026-10-21'
const ILLNESSES = [
  { id: 1, start_date: '2026-10-03', end_date: '2026-10-06', status: 'resolved' },
  { id: 2, start_date: '2026-10-05', end_date: '2026-10-07', status: 'resolved' },
  { id: 3, start_date: '2026-05-10', end_date: '2026-05-10', status: 'resolved' },
  { id: 4, start_date: '2026-10-18', end_date: null, status: 'active' },
]

function copied(): HealthInputs {
  const parts = ILLNESSES.map(legacyIllnessCopy)
  return {
    concerns: parts.map((p) => p.concern),
    observations: parts.map((p) => p.observation),
    signals: parts.map((p) => p.signal),
    marks: parts.flatMap((p) => p.marks),
  }
}

test('copied legacy illnesses give exactly the spans the frame already leaves out', () => {
  const spans = baselineExclusionSpans(copied(), TODAY)
  assert.deepEqual(
    spans.map((s) => [s.start_date, s.end_date]).sort(),
    ILLNESSES.map((i) => [i.start_date, i.end_date]).sort(),
  )
})

test('golden: the frame excludes the same days with the legacy rows alone and with both (dual read)', () => {
  const empty = { wearable: [], sleep: [], wellbeing: [], meals: [], sessions: [], recovery: [] }
  const legacyOnly = buildFrame({ ...empty, illnesses: ILLNESSES }, TODAY)
  const both = buildFrame({ ...empty, illnesses: [...ILLNESSES, ...baselineExclusionSpans(copied(), TODAY)] }, TODAY)
  assert.deepEqual(both.excluded, legacyOnly.excluded)
  assert.equal(legacyOnly.excluded.filter(Boolean).length, 6 + 1 + 1 + 4) // 3-8 Oct, 10-11 May, 18-21 Oct
})

test('a new illness logged only as a concern is left out of comparisons; a non-excluding concern is not', () => {
  const ill = legacyIllnessCopy({ id: 9, start_date: '2026-09-01', end_date: null, status: 'active' })
  const inputs: HealthInputs = {
    concerns: [ill.concern, { ...ill.concern, id: 'knee', kind: 'anatomical', excludes_from_baselines: false }],
    observations: [
      ill.observation,
      { ...ill.observation, id: 'f1', local_date: '2026-09-04', onset: false, symptom_free: true },
      { ...ill.observation, id: 'f2', local_date: '2026-09-05', onset: false, symptom_free: true },
      { ...ill.observation, id: 'f3', local_date: '2026-09-06', onset: false, symptom_free: true },
      { ...ill.observation, id: 'k1', concern_id: 'knee', local_date: '2026-09-20', onset: false },
    ],
    signals: [{ ...ill.signal, severity: 6 }, { ...ill.signal, observation_id: 'k1', concern_id: 'knee', severity: 6 }],
    marks: [],
  }
  assert.deepEqual(baselineExclusionSpans(inputs, TODAY), [{ start_date: '2026-09-01', end_date: '2026-09-04' }])
})

test('loadFrame reads the health tables softly: missing tables leave the frame as before', async () => {
  const now = new Date('2026-10-21T06:00:00Z')
  const wearable = Array.from({ length: 30 }, (_, i) => ({ user_id: 'u1', date: new Date(Date.parse('2026-09-22T00:00:00Z') + i * 86400000).toISOString().slice(0, 10), hrv: 60, rhr: 50, steps: 8000 }))
  const tables = { wearable_daily: wearable, illnesses: ILLNESSES.map((i) => ({ ...i, user_id: 'u1' })) } as unknown as Record<string, Record<string, unknown>[]>
  const withMissing = (await loadFrame(fakeAdmin(tables, ['health_concerns', 'health_observations', 'health_signals', 'health_episode_marks']), 'u1', now)) as LoadedFrame
  const plain = (await loadFrame(fakeAdmin(tables), 'u1', now)) as LoadedFrame
  assert.ok(!('error' in withMissing))
  assert.deepEqual(withMissing.frame.excluded, plain.frame.excluded)
})
