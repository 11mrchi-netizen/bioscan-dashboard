import assert from 'node:assert/strict'
import { test } from 'node:test'
import { allowedLabels, inventedScores, labelsIn } from './vocabulary.ts'

test('capitalised labels are found; abbreviations and units are not labels', () => {
  assert.deepEqual(labelsIn('Your HRV reads BELOW EXPECTED and RHR is fine. IS TODAY TYPICAL? says MIXED SIGNALS.').map((l) => l.label), [
    'BELOW EXPECTED',
    'IS TODAY TYPICAL',
    'MIXED SIGNALS',
  ])
  assert.deepEqual(labelsIn('HRV BELOW EXPECTED').map((l) => l.label), ['BELOW EXPECTED'])
  assert.deepEqual(labelsIn('VO2MAX 52, HDL and LDL, 8 AM').map((l) => l.label), [])
})

test('a label is allowed when the sources use it, whole or as part of a longer label', () => {
  const ok = allowedLabels({ state: 'BELOW EXPECTED' }, { card: "TODAY'S RECOVERY" })
  assert.ok(ok.has('BELOW EXPECTED') && ok.has("TODAY'S RECOVERY") && ok.has('RECOVERY'))
  assert.ok(!ok.has('UNUSUALLY LOW'))
})

test('score names and n/100 ratings must come from the data', () => {
  assert.deepEqual(inventedScores('Your readiness score is high, 82/100.', {}).map((s) => s.phrase), ['readiness score', '82/100'])
  assert.deepEqual(inventedScores('Your sleep score was 81.', { sleep_score: 81, label: 'Sleep score' }), [])
})
