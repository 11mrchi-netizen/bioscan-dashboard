import assert from 'node:assert/strict'
import { test } from 'node:test'
import { checkReply, type ToolOutput } from './guards.ts'

const outputs: ToolOutput[] = [
  { tool: 'get_results', data: { kind: 'expected_band', key: 'hrv', as_of: '2026-10-27', state: 'BELOW EXPECTED', value: 38.4, band: { lo: 44, hi: 61 }, display: '44–61 ms' } },
  { tool: 'get_integrity', data: { series: 'hrv', coverage: '27 of 28 nights', issues: [] } },
]
const question = 'Why does THIS MORNING say BELOW EXPECTED?'

test('a grounded reply in the screen vocabulary passes unchanged', () => {
  const reply = 'Your HRV this morning was 38 ms, under the band of 44 to 61 ms expected for you, so the card reads BELOW EXPECTED. The data is complete: 27 of 28 nights.'
  const r = checkReply({ reply, question, outputs })
  assert.deepEqual(r.violations, [])
  assert.equal(r.cleaned, reply)
})

test('each rule reports its own violation, and cleaning drops only those sentences', () => {
  const reply = 'Your HRV was 38 ms, BELOW EXPECTED. That is 15 % lower than usual. Your readiness score is low. It reads UNUSUALLY LOW. Late training caused it.'
  const r = checkReply({ reply, question, outputs })
  assert.deepEqual(r.violations.map((v) => v.code).sort(), ['causal_claim', 'invented_score', 'label_not_from_tools', 'ungrounded_number'])
  assert.equal(r.cleaned, 'Your HRV was 38 ms, BELOW EXPECTED.')
})

test('the question and screen context ground numbers and labels too', () => {
  const r = checkReply({ reply: 'Over the last 7 days READY FOR stayed the same.', question: 'What changed over the last 7 days?', context: { card: 'READY FOR' }, outputs: [] })
  assert.equal(r.ok, true)
})

test('an empty reply, or one with nothing left after cleaning, has no cleaned text', () => {
  assert.equal(checkReply({ reply: ' ', question, outputs }).cleaned, null)
  assert.equal(checkReply({ reply: 'It was 99 ms.', question, outputs }).cleaned, null)
})
