import { localDaysAgo, localToday } from '../lib/dates'

// AnalysisContext — shared state that drives every analytical surface.
// Defined in IA.md §5.2 and DAV-308.

export type Domain = 'training' | 'recovery' | 'nutrition' | 'labs' | 'body' | 'wellbeing'
export type Source = 'health_connect' | 'wearable' | 'zepp' | 'manual' | 'derived' | 'model_estimated'
export type ActivityType = 'run' | 'trail_run' | 'strength' | 'conditioning' | 'other'

export interface DateRange {
  start: string // ISO date YYYY-MM-DD
  end: string
}

export interface EventRef {
  kind: 'session' | 'meal' | 'sleep' | 'lab_draw' | 'illness' | 'injury' | 'rest_day'
  id: string
}

export type MetricId = string

export interface BenchmarkRef {
  kind: 'personal_historical' | 'population'
  id: string
}

export interface QualityWarning {
  code: string
  message: string
  domains: Domain[]
}

// What the Inspector is currently showing.
export type InspectionKind = 'series_point' | 'event'

// One point of a series chart: a day or a whole week or month (analysis hub phase B). It carries the aggregation facts
// so the Inspector never shows an aggregate as if it were one reading.
export interface SeriesPointInspection {
  kind: 'series_point'
  seriesId: string
  start: string
  end: string
  grain: 'day' | 'week' | 'month'
  method: 'mean' | 'sum' | 'last'
  value: number | null
  daysObserved: number
  days: number
  samples: number
  min: number | null
  max: number | null
  sources: string[]
  clipped: boolean
}

export interface EventInspection {
  kind: 'event'
  eventRef: EventRef
  // Raw record loaded on demand by Inspector
}

export type InspectionTarget = SeriesPointInspection | EventInspection | null

export interface AnalysisContext {
  inspectionTarget: InspectionTarget
  primaryRange: DateRange
  comparisonRange: DateRange | null
  selectedDate: string | null        // YYYY-MM-DD
  selectedEvent: EventRef | null
  selectedMetrics: MetricId[]
  domainFilters: Domain[]
  sourceFilters: Source[]
  activityFilters: ActivityType[]
  rawVsDerived: 'raw' | 'derived' | 'both'
  benchmarkRef: BenchmarkRef | null
  activeWorkspaceId: string | null
  drillDownRoute: string | null
  timezone: string                   // IANA
  dataQualityWarnings: QualityWarning[]
}

// Default context — 90-day rolling window, no comparison, IANA timezone.
export function defaultContext(): AnalysisContext {
  return {
    primaryRange: { start: localDaysAgo(90), end: localToday() },
    inspectionTarget: null,
    comparisonRange: null,
    selectedDate: null,
    selectedEvent: null,
    selectedMetrics: [],
    domainFilters: [],
    sourceFilters: [],
    activityFilters: [],
    rawVsDerived: 'both',
    benchmarkRef: null,
    activeWorkspaceId: null,
    drillDownRoute: null,
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    dataQualityWarnings: [],
  }
}
