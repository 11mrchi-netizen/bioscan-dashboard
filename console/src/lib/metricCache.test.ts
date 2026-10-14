import test from 'node:test'
import assert from 'node:assert/strict'
import { SeriesCache } from './metricCache.ts'
import { assembleResult, type MetricPoint, type MetricRequest } from './metricTypes.ts'

const req = (start: string, end: string, extra: Partial<MetricRequest> = {}): MetricRequest => ({
  table: 'wearable_daily', column: 'hrv', range: { start, end }, aggregation: 'daily_avg', ...extra,
})
const pts = (dates: string[]): MetricPoint[] => dates.map((date, i) => ({ date, value: 40 + i, source: null, confidence: null, provenance: null }))
const days = (from: number, to: number) => Array.from({ length: to - from + 1 }, (_, i) => `2026-10-${String(from + i).padStart(2, '0')}`)

test('a wider stored range answers narrower requests without another fetch', () => {
  const c = new SeriesCache()
  c.store(req('2026-10-01', '2026-10-30'), pts(days(1, 30)))
  const hit = c.lookup(req('2026-10-10', '2026-10-12'))
  assert.deepEqual(hit?.map(p => p.date), ['2026-10-10', '2026-10-11', '2026-10-12'])
  assert.equal(c.lookup(req('2026-09-25', '2026-10-12')), null, 'a request that starts earlier than the stored range must refetch')
  assert.equal(c.lookup(req('2026-10-10', '2026-10-31')), null, 'a request that ends later must refetch')
})

test('the key includes column, date column, aggregation and filters', () => {
  const c = new SeriesCache()
  const base = req('2026-10-01', '2026-10-30')
  c.store(base, pts(days(1, 30)))
  assert.equal(c.lookup({ ...base, column: 'rhr' }), null)
  assert.equal(c.lookup({ ...base, dateColumn: 'logged_at' }), null)
  assert.equal(c.lookup({ ...base, extraFilters: { source: 'zepp' } }), null)
  assert.equal(c.lookup({ ...base, aggregation: 'raw' }), null)
  assert.notEqual(c.lookup(base), null)
})

test('filter key order does not matter', () => {
  const c = new SeriesCache()
  const a = req('2026-10-01', '2026-10-30', { extraFilters: { a: 1, b: 2 } })
  const b = req('2026-10-01', '2026-10-30', { extraFilters: { b: 2, a: 1 } })
  assert.equal(c.seriesKey(a), c.seriesKey(b))
})

test('entries expire after the ttl', () => {
  let t = 0
  const c = new SeriesCache(1000, () => t)
  c.store(req('2026-10-01', '2026-10-30'), pts(days(1, 30)))
  t = 999
  assert.notEqual(c.lookup(req('2026-10-05', '2026-10-06')), null)
  t = 1001
  assert.equal(c.lookup(req('2026-10-05', '2026-10-06')), null)
})

test('a narrower fetch does not replace a wider fresh series', () => {
  const c = new SeriesCache()
  c.store(req('2026-10-01', '2026-10-30'), pts(days(1, 30)))
  c.store(req('2026-10-10', '2026-10-12'), pts(days(10, 12)))
  assert.equal(c.lookup(req('2026-10-01', '2026-10-30'))?.length, 30)
})

test('concurrent identical requests share one load; the next one after it settles loads again', async () => {
  const c = new SeriesCache()
  let loads = 0
  const load = async () => { loads++; await new Promise(r => setTimeout(r, 20)); return 'x' }
  const [a, b, d] = await Promise.all([c.dedupe('k', load), c.dedupe('k', load), c.dedupe('k', load)])
  assert.deepEqual([a, b, d], ['x', 'x', 'x'])
  assert.equal(loads, 1)
  await c.dedupe('k', load)
  assert.equal(loads, 2)
})

test('a failed load is not remembered and the in-flight entry is cleared', async () => {
  const c = new SeriesCache()
  await assert.rejects(c.dedupe('k', async () => { throw new Error('offline') }))
  assert.equal(await c.dedupe('k', async () => 'ok'), 'ok')
})

test('missingness classification is unchanged', () => {
  const r = req('2026-10-01', '2026-10-10')
  assert.equal(assembleResult(r, []).missingness, 'unavailable')
  assert.equal(assembleResult(r, pts(['2026-10-01'])).missingness, 'sparse') // 1 of 9 days
  assert.equal(assembleResult(r, pts(days(1, 10))).missingness, 'none')
  const gaps = pts(days(1, 10)); gaps[3].value = null
  assert.equal(assembleResult(r, gaps).missingness, 'gaps')
})
