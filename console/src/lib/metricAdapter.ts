// DAV-309: Metric query adapter — bridge between AnalysisContext and Supabase tables.
// Components request metrics through this; no component queries Supabase directly.

import { supabase } from './supabase'
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

const cache = new Map<string, { result: MetricResult; ts: number }>()
const CACHE_TTL = 5 * 60 * 1000

function cacheKey(req: MetricRequest): string {
  return `${req.table}.${req.column}:${req.range.start}:${req.range.end}:${req.aggregation}`
}

export async function fetchMetric(req: MetricRequest): Promise<MetricResult> {
  const key = cacheKey(req)
  const cached = cache.get(key)
  if (cached && Date.now() - cached.ts < CACHE_TTL) return cached.result

  const dateCol = req.dateColumn ?? 'date'
  const metricId = `${req.table}.${req.column}`

  let query = supabase
    .from(req.table)
    .select(`${dateCol}, ${req.column}, source, confidence, provenance`)
    .gte(dateCol, req.range.start)
    .lte(dateCol, req.range.end)
    .order(dateCol)

  if (req.extraFilters) {
    for (const [k, v] of Object.entries(req.extraFilters)) {
      query = query.eq(k, v)
    }
  }

  const { data, error } = await query

  if (error) {
    return { metricId, unit: null, points: [], missingness: 'unavailable', error: error.message }
  }

  const points: MetricPoint[] = ((data ?? []) as unknown as Record<string, unknown>[]).map((row) => ({
    date: row[dateCol] as string,
    value: row[req.column] as number | null,
    source: (row['source'] as string | null) ?? null,
    confidence: (row['confidence'] as string | null) ?? null,
    provenance: (row['provenance'] as string | null) ?? null,
  }))

  const rangeLength = daysBetween(req.range.start, req.range.end)
  const nonNull = points.filter(p => p.value !== null).length
  const missingness =
    points.length === 0 ? 'unavailable' :
    nonNull / rangeLength < 0.3 ? 'sparse' :
    nonNull < points.length ? 'gaps' :
    'none'

  const result: MetricResult = { metricId, unit: null, points, missingness, error: null }
  cache.set(key, { result, ts: Date.now() })
  return result
}

function daysBetween(a: string, b: string): number {
  return Math.max(1, Math.round((new Date(b).getTime() - new Date(a).getTime()) / 86_400_000))
}

// Well-known metric IDs for type-safe usage across workspaces.
export const METRICS = {
  HRV:              { table: 'wearable_daily',   column: 'hrv_rmssd_ms' },
  RHR:              { table: 'wearable_daily',   column: 'resting_hr_bpm' },
  STEPS:            { table: 'wearable_daily',   column: 'steps' },
  SLEEP_DURATION:   { table: 'sleep_daily',      column: 'total_sleep_minutes' },
  SLEEP_EFFICIENCY: { table: 'sleep_daily',      column: 'sleep_efficiency_pct' },
  BODY_WEIGHT:      { table: 'body_metrics',     column: 'weight_kg' },
  KCAL_TOTAL:       { table: 'wellbeing_daily',  column: 'energy_level' },   // placeholder
  ENERGY_LEVEL:     { table: 'wellbeing_daily',  column: 'energy_level' },
  MOOD:             { table: 'wellbeing_daily',  column: 'mood' },
  STRESS:           { table: 'wellbeing_daily',  column: 'stress_level' },
  SORENESS:         { table: 'wellbeing_daily',  column: 'muscle_soreness' },
} as const
