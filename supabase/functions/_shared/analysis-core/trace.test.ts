import assert from 'node:assert/strict'
import { test } from 'node:test'
import { stepTimer } from './trace.ts'

test('stepTimer reports the time since the previous mark, and does nothing without a trace', () => {
  let t = 0
  const clock = () => t
  const seen: [string, number, Record<string, number> | undefined][] = []
  const mark = stepTimer((s, ms, i) => seen.push([s, ms, i]), clock)
  t = 5; mark('a', { n: 1 })
  t = 12; mark('b')
  assert.deepEqual(seen, [['a', 5, { n: 1 }], ['b', 7, undefined]])
  assert.doesNotThrow(() => stepTimer(undefined, clock)('x'))
})
