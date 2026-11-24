import test from 'node:test'
import assert from 'node:assert/strict'
import { addDays, correlate, correlationWords, panelsByUnit } from './compare.ts'

const d0 = '2026-09-01'
const mapOf = (n: number, f: (i: number) => number, shift = 0) => new Map(Array.from({ length: n }, (_, i) => [addDays(d0, i + shift), f(i)] as [string, number]))

// The phone's own fixture (CorrelationTest.aLaggedLinkIsFoundOnlyAtItsLag): y the day after is 10 - 2x plus a little noise.
const x = mapOf(40, (i) => i % 5)
const y = mapOf(40, (i) => 10 - 2 * (i % 5) + (i % 3 === 0 ? 0.3 : -0.2), 1)

test('a lagged link is found at its lag and not at lag 0 (the phone pins the same fixture)', () => {
  const c = correlate(x, y, 1)!
  assert.equal(c.n, 40)
  assert.ok(c.r < -0.95 && c.clear)
  assert.ok(Math.abs(c.slope - -2) < 0.1)
  assert.ok(correlationWords(c).startsWith('A strong negative link'))
  const same = correlate(x, y, 0)!
  assert.ok(Math.abs(same.r) < 0.5, `r at lag 0 was ${same.r}`)
  assert.equal(correlate(new Map([...x].slice(0, 5)), y, 1), null)
})

test('the lag direction: THEN one day later pairs x[d] with y[d+1], never y[d-1]', () => {
  const xs = new Map([['2026-09-01', 1], ['2026-09-02', 2], ['2026-09-03', 3], ['2026-09-04', 4], ['2026-09-05', 5], ['2026-09-06', 6], ['2026-09-07', 7], ['2026-09-08', 8], ['2026-09-09', 9], ['2026-09-10', 10], ['2026-09-11', 11], ['2026-09-12', 12]])
  const ys = new Map([...xs].map(([d, v]) => [addDays(d, 1), v * 10] as [string, number]))
  const c = correlate(xs, ys, 1)!
  assert.deepEqual(c.pairs[0], { day: '2026-09-01', x: 1, y: 10 })
  assert.ok(c.r > 0.999)
  // the pairs are x on d with y on d+1 and nothing else: every pair's y is ten times its own x
  assert.ok(c.pairs.every((p) => p.y === p.x * 10))
  // on a non-trending pattern the right lag is the only strong one
  assert.ok(Math.abs(correlate(x, y, 1)!.r) > 0.95 && Math.abs(correlate(x, y, 0)!.r) < 0.5 && Math.abs(correlate(x, y, 2)!.r) < 0.5)
})

test('the sentence matches the phone: sign, strength, interval, and the zero note', () => {
  const c = correlate(x, mapOf(40, (i) => (i * 7919) % 13), 0)!
  assert.match(correlationWords(c), /^(No clear|A weak|A moderate|A strong) /)
  assert.match(correlationWords({ n: 64, r: -0.34, lo: -0.52, hi: -0.13, slope: 0, pairs: [], clear: true }), /^A moderate negative link: r −0\.34 \(95% interval −0\.52 to −0\.13\), 64 days\.$/)
  assert.match(correlationWords({ n: 20, r: 0.05, lo: -0.4, hi: 0.5, slope: 0, pairs: [], clear: false }), /^No clear link: r 0\.05 .*The interval includes zero\.$/)
})

test('window start, flat series and too few days', () => {
  assert.equal(correlate(x, y, 1, '2026-09-21')!.n, 20) // 21 Sep to 10 Oct of x, each with its next-day y
  assert.equal(correlate(x, mapOf(40, () => 5, 1), 1), null)
  assert.equal(correlate(x, y, 1, '2026-10-08'), null) // fewer than 10 days left from the 8th of October
})

test('panels: one per unit, same unit shares', () => {
  const p = panelsByUnit([{ unit: 'ms', id: 'a' }, { unit: 'h', id: 'b' }, { unit: 'ms', id: 'c' }])
  assert.deepEqual(p.map((q) => [q.unit, q.items.map((i) => i.id)]), [['ms', ['a', 'c']], ['h', ['b']]])
})
