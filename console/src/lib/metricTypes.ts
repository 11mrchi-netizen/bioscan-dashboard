// Types and the pure result assembly of the metric adapter. No Supabase import, so node --test can load it.
import type { DateRange, Source } from '../types/analysis'

export type Aggregation = 'latest' | 'daily_avg' | 'daily_sum' | 'raw'

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
// the requested aggregation per calendar day (the first 10 characters of the date or timestamp). Days whose values are
// all null stay in the result as a null point, so gaps remain gaps.
export function aggregatePoints(points: MetricPoint[], aggregation: Aggregation): MetricPoint[] {
  if (aggregation === 'raw') return points
  const byDay = new Map<string, MetricPoint[]>()
  for (const p of points) {
    const day = p.date.slice(0, 10)
    const list = byDay.get(day)
    if (list) list.push(p)
    else byDay.set(day, [p])
  }
  const out: MetricPoint[] = []
  for (const [day, list] of [...byDay.entries()].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))) {
    const values = list.map((p) => p.value).filter((v): v is number => v !== null && Number.isFinite(v))
    const sources = [...new Set(list.map((p) => p.source).filter((s): s is string => s !== null))]
    const value =
      values.length === 0 ? null :
      aggregation === 'daily_sum' ? values.reduce((a, b) => a + b, 0) :
      aggregation === 'latest' ? values[values.length - 1] :
      values.reduce((a, b) => a + b, 0) / values.length
    out.push({ date: day, value, source: sources.length === 1 ? sources[0] : sources.length > 1 ? 'mixed' : null, confidence: null, provenance: null })
  }
  return out
}
