import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fnv1a, hashSeries } from './hash.ts'

test('fnv1a matches the reference vectors', () => {
  assert.equal(fnv1a(''), '811c9dc5')
  assert.equal(fnv1a('a'), 'e40c292c')
  assert.equal(fnv1a('foobar'), 'bf9cf968')
})

test('series hash changes with values and with gaps', () => {
  const d = ['2026-01-01', '2026-01-02', '2026-01-03']
  const a = hashSeries(d, [1, 2, 3])
  assert.equal(a, hashSeries(d, [1, 2, 3]))
  assert.notEqual(a, hashSeries(d, [1, 2, 4]))
  assert.notEqual(a, hashSeries(d, [1, null, 3]))
})
