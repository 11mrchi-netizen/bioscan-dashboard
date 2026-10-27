// Run: node --test console/src/lib/links.test.ts
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { hrefFor } from './links.ts'

test('each reference kind links to its workspace', () => {
  assert.equal(hrefFor({ kind: 'record', table: 'meals', id: 42 }), '#/data?table=meals&id=42')
  assert.equal(hrefFor({ kind: 'day', series: 'hrv', date: '2026-10-27' }), '#/data?table=wearable_daily&date=2026-10-27')
  assert.equal(hrefFor({ kind: 'period', series: 'run_distance', start: '2026-10-01', end: '2026-10-31' }), '#/explore?series=run_distance&start=2026-10-01&end=2026-10-31')
  assert.equal(hrefFor({ kind: 'result', resultKind: 'relationship', key: 'alcohol->hrv@1', asOf: '2026-10-26' }), '#/evidence?result=relationship&key=alcohol-%3Ehrv%401&asOf=2026-10-26')
  assert.equal(hrefFor({ kind: 'question', questionId: 'reg_3' }), '#/evidence?question=reg_3')
})

test('an unknown series has no link rather than a wrong one', () => {
  assert.equal(hrefFor({ kind: 'day', series: 'nope', date: '2026-10-27' }), null)
  assert.equal(hrefFor({ kind: 'period', series: 'nope', start: '2026-10-01', end: '2026-10-02' }), null)
})
