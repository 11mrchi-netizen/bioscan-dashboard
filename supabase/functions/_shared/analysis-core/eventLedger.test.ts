// The ledger rows of the event evidence: the verdict follows the ladder, never significance.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { eventLedgerRows, ledgerVerdict } from './eventEvidence.ts'
import type { EventEvidenceResult, ExpectationResult } from './eventEvidence.ts'

test('the ledger word follows the ladder and what was observed', () => {
  assert.equal(ledgerVerdict('anecdote', 'as_expected'), 'insufficient')
  assert.equal(ledgerVerdict('emerging', 'too_few'), 'insufficient')
  assert.equal(ledgerVerdict('early_signal', 'as_expected'), 'possible')
  assert.equal(ledgerVerdict('emerging', 'opposite'), 'observed')
  assert.equal(ledgerVerdict('ready', 'unexpected_change'), 'observed')
  assert.equal(ledgerVerdict('emerging', 'no_difference'), 'negligible')
  assert.equal(ledgerVerdict('early_signal', 'no_difference'), 'unclear')
  assert.equal(ledgerVerdict('ready', 'unclear'), 'unclear')
})

const exp = (over: Partial<ExpectationResult>): ExpectationResult => ({
  expectationId: 'x1', outcomeKey: 'sleep_respiratory_rate', label: 'Respiratory rate, sleep', role: 'primary', exploratory: false, direction: 'down',
  windowStartH: 0, windowEndH: 12, nWithData: 5, nReference: 20, withMedian: 13.8, referenceMedian: 14.2, diff: -0.4, lo: -0.7, hi: -0.1, diffSd: -0.8,
  unit: 'br/min', observed: 'as_expected', p: null, pAdjusted: null, firstThree: null, summary: '', ...over,
})

test('only pre-declared primary and secondary expectations reach the ledger', () => {
  const result = {
    interventionId: 'i1', asOf: '2026-10-29', nEvents: 6, nUsed: 5, ladder: 'early_signal',
    expectations: [exp({}), exp({ expectationId: 'x2', role: 'secondary' }), exp({ expectationId: 'x3', role: 'watch' }), exp({ expectationId: 'x4', exploratory: true })],
  } as unknown as EventEvidenceResult
  const rows = eventLedgerRows('u1', '2026-10-29', '3.0.0', 'h', result)
  assert.deepEqual(rows.map((r) => r.question_id), ['event_x1', 'event_x2'])
  assert.equal(rows[0].source, 'event')
  assert.equal(rows[0].verdict, 'possible')
  assert.equal(rows[0].n, 5)
  assert.equal(rows[0].n_on, 5)
  assert.equal(rows[0].estimate, -0.4)
})
