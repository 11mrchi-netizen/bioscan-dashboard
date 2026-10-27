// Types and the pure result assembly of the metric adapter. No Supabase import, so node --test can load it.
import type { DateRange, Source } from '../types/analysis'
import { aggregateDays, type Method } from '../../../supabase/functions/_shared/analysis-core/seriesContract.ts'

// How rows on one day combine (the series contract's day methods), or 'raw' for every row as stored.
export type Aggregation = Method | 'raw'

export interface MetricRequest {
  table: string
  column: string
  dateColumn?: string       // defaults to 'date'
  range: DateRange
  aggregation: Aggregation
  sources?: Source[]
  extraFilters?: Record<string, string | number | boolean>
}

export interface MetricPoint {
  date: string
  value: number | null
  source: string | null
  confidence: string | null
  provenance: string | null
}

export interface MetricResult {
  metricId: string
  unit: string | null
  points: MetricPoint[]
  missingness: 'none' | 'gaps' | 'sparse' | 'unavailable'
  error: string | null
}

export function daysBetween(a: string, b: string): number {
  return Math.max(1, Math.round((new Date(b).getTime() - new Date(a).getTime()) / 86_400_000))
}

export function metricIdOf(req: Pick<MetricRequest, 'table' | 'column'>): string {
  return `${req.table}.${req.column}`
}

// Same classification the adapter always used: how much of the requested range actually has a value.
export function assembleResult(req: MetricRequest, points: MetricPoint[]): MetricResult {
  const rangeLength = daysBetween(req.range.start, req.range.end)
  const nonNull = points.filter(p => p.value !== null).length
  const missingness =
    points.length === 0 ? 'unavailable' :
    nonNull / rangeLength < 0.3 ? 'sparse' :
    nonNull < points.length ? 'gaps' :
    'none'
  return { metricId: metricIdOf(req), unit: null, points, missingness, error: null }
}

export function errorResult(req: MetricRequest, message: string): MetricResult {
  return { metricId: metricIdOf(req), unit: null, points: [], missingness: 'unavailable', error: message }
}

// Analysis review A6: the adapter always returned raw rows, whatever aggregation a chart asked for, so a table with
// several rows a day (body_metrics, sessions) plotted every row and the inspector still said "daily_avg". This applies
// the requested day method (analysis-core/seriesContract.ts) per calendar day, the first 10 characters of the date or
// timestamp. Phase B replaces that with the series' time contract (localDateOf). Days whose values are all null stay in
// the result as a null point, so gaps remain gaps.
export function aggregatePoints(points: MetricPoint[], aggregation: Aggregation): MetricPoint[] {
  if (aggregation === 'raw') return points
  const days = aggregateDays(points.map((p) => ({ date: p.date.slice(0, 10), value: p.value, source: p.source })), aggregation)
  return days.map((d) => ({
    date: d.date,
    value: d.value,
    source: d.sources.length === 1 ? d.sources[0] : d.sources.length > 1 ? 'mixed' : null,
    confidence: null,
    provenance: null,
  }))
}
