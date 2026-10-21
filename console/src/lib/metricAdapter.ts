// DAV-309: Metric query adapter — bridge between AnalysisContext and Supabase tables.
// Components request metrics through this; no component queries Supabase directly.

import { supabase } from './supabase'
import { TABLES_WITH_SOURCE } from './schema'
import { SeriesCache } from './metricCache'
import {
  aggregatePoints,
  assembleResult,
  errorResult,
  metricIdOf,
  type MetricPoint,
  type MetricRequest,
  type MetricResult,
} from './metricTypes'

export type { Aggregation, MetricPoint, MetricRequest, MetricResult } from './metricTypes'

const cache = new SeriesCache()

// Forget everything fetched so far (the refresh button, after a data change).
export function clearMetricCache(): void {
  cache.clear()
}

// Request counter for before/after measurements: window.__metricRequests in the browser console.
let requestCount = 0
function countRequest(label: string, columns: string[]) {
  requestCount++
  ;(globalThis as { __metricRequests?: number }).__metricRequests = requestCount
  performance.mark?.(`metric:${label}:${columns.join('+')}`)
}

interface Row extends Record<string, unknown> {}

async function query(table: string, dateCol: string, columns: string[], req: MetricRequest): Promise<{ rows: Row[]; error: string | null }> {
  // No table has `confidence` or `provenance` columns (see lib/schema.ts); only select `source` where it exists.
  const hasSource = TABLES_WITH_SOURCE.has(table)
  const select = [dateCol, ...columns, ...(hasSource ? ['source'] : [])].join(', ')
  // Analysis review A6: paged. PostgREST stops at its row cap (1,000 by default on Supabase), which silently cut long
  // ranges; keep asking for the next page until a short one comes back.
  const PAGE = 1000
  const rows: Row[] = []
  for (let from = 0; ; from += PAGE) {
    let q = supabase
      .from(table)
      .select(select)
      .gte(dateCol, req.range.start)
      .lte(dateCol, req.range.end)
      .order(dateCol)
    if (req.extraFilters) {
      for (const [k, v] of Object.entries(req.extraFilters)) q = q.eq(k, v)
    }
    countRequest(table, columns)
    const { data, error } = await q.range(from, from + PAGE - 1)
    if (error) return { rows: [], error: error.message }
    const page = (data ?? []) as unknown as Row[]
    rows.push(...page)
    if (page.length < PAGE) return { rows, error: null }
  }
}

function toPoints(rows: Row[], dateCol: string, column: string): MetricPoint[] {
  return rows.map((row) => ({
    date: row[dateCol] as string,
    value: row[column] as number | null,
    source: (row['source'] as string | null) ?? null,
    confidence: null,
    provenance: null,
  }))
}

export async function fetchMetric(req: MetricRequest): Promise<MetricResult> {
  const hit = cache.lookup(req)
  if (hit) return assembleResult(req, hit)

  const dateCol = req.dateColumn ?? 'date'
  return cache.dedupe(`${cache.seriesKey(req)}|${req.range.start}|${req.range.end}`, async () => {
    const { rows, error } = await query(req.table, dateCol, [req.column], req)
    if (error) return errorResult(req, error)
    const points = aggregatePoints(toPoints(rows, dateCol, req.column), req.aggregation)
    cache.store(req, points)
    return assembleResult(req, points)
  })
}

// Several metrics at once. Metrics that live in the same table (and share range, date column and filters) are fetched with
// ONE query that selects all their columns, so Overview needs 4 requests instead of 6 and Recovery 4 instead of 5.
// Results come back in the order requested; each is exactly what fetchMetric would have returned for it.
export async function fetchMetrics(reqs: MetricRequest[]): Promise<MetricResult[]> {
  const results: (MetricResult | undefined)[] = new Array(reqs.length)
  const groups = new Map<string, number[]>()
  reqs.forEach((req, i) => {
    const hit = cache.lookup(req)
    if (hit) { results[i] = assembleResult(req, hit); return }
    const gkey = [req.table, req.dateColumn ?? 'date', req.range.start, req.range.end, JSON.stringify(req.extraFilters ?? {})].join('|')
    groups.set(gkey, [...(groups.get(gkey) ?? []), i])
  })

  await Promise.all([...groups.values()].map(async (idx) => {
    if (idx.length === 1) { results[idx[0]] = await fetchMetric(reqs[idx[0]]); return }
    const first = reqs[idx[0]]
    const dateCol = first.dateColumn ?? 'date'
    const columns = [...new Set(idx.map(i => reqs[i].column))]
    const { rows, error } = await cache.dedupe(`batch|${first.table}|${columns.join(',')}|${first.range.start}|${first.range.end}`, () =>
      query(first.table, dateCol, columns, first))
    for (const i of idx) {
      const req = reqs[i]
      if (error) { results[i] = errorResult(req, error); continue }
      const points = aggregatePoints(toPoints(rows, dateCol, req.column), req.aggregation)
      cache.store(req, points)
      results[i] = assembleResult(req, points)
    }
  }))

  return results as MetricResult[]
}

// Well-known metric IDs live in ./schema (importable without a Supabase client).
export { METRICS } from './schema'
export { metricIdOf }
