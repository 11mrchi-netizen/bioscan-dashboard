// Run: node --test console/src/lib/metricTypes.test.ts
// Analysis review A6: the adapter applies the aggregation a chart asks for.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { aggregatePoints } from './metricTypes.ts'
import type { MetricPoint } from './metricTypes.ts'

const p = (date: string, value: number | null, source: string | null = 'a'): MetricPoint => ({ date, value, source, confidence: null, provenance: null })

test('daily_avg averages each calendar day and keeps all-null days as gaps', () => {
  const out = aggregatePoints([p('2026-10-01T08:00:00Z', 80), p('2026-10-01T20:00:00Z', 82, 'b'), p('2026-10-02', null), p('2026-10-03', 81)], 'daily_avg')
  assert.deepEqual(out.map((x) => [x.date, x.value, x.source]), [['2026-10-01', 81, 'mixed'], ['2026-10-02', null, 'a'], ['2026-10-03', 81, 'a']])
})

test('daily_sum, latest and raw', () => {
  const pts = [p('2026-10-01T08:00:00Z', 2), p('2026-10-01T09:00:00Z', 3)]
  assert.equal(aggregatePoints(pts, 'daily_sum')[0].value, 5)
  assert.equal(aggregatePoints(pts, 'latest')[0].value, 3)
  assert.equal(aggregatePoints(pts, 'raw').length, 2)
})
