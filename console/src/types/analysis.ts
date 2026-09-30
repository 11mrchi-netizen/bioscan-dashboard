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

export interface AnalysisContext {
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
  const end = new Date()
  const start = new Date(end)
  start.setDate(start.getDate() - 90)
  return {
    primaryRange: {
      start: start.toISOString().slice(0, 10),
      end: end.toISOString().slice(0, 10),
    },
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
