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
