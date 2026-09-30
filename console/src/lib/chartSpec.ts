// DAV-309: Versioned chart specification model.
// Charts are configured declaratively from this spec; no metric logic lives in components.

export type ChartKind = 'line' | 'area' | 'bar' | 'scatter' | 'dot_plot'

export interface ChartAnnotation {
  date: string
  label: string
  color?: string
}

export interface ChartBand {
  lower: number
  upper: number
  label: string
  color?: string
}

export interface ChartSpec {
  specVersion: 1
  kind: ChartKind
  metricId: string
  title: string
  unit: string | null
  yAxisLabel?: string
  comparison?: boolean          // show comparison range as a second series
  annotations?: ChartAnnotation[]
  bands?: ChartBand[]
  smooth?: boolean
  stack?: boolean               // for 'bar' kind
}

// Default specs for well-known metrics.
export const CHART_SPECS: Record<string, ChartSpec> = {
  'wearable_daily.hrv_rmssd_ms': {
    specVersion: 1, kind: 'area', metricId: 'wearable_daily.hrv_rmssd_ms',
    title: 'HRV (RMSSD)', unit: 'ms', yAxisLabel: 'ms', smooth: true,
  },
  'wearable_daily.resting_hr_bpm': {
    specVersion: 1, kind: 'line', metricId: 'wearable_daily.resting_hr_bpm',
    title: 'Resting HR', unit: 'bpm', smooth: true,
  },
  'wearable_daily.steps': {
    specVersion: 1, kind: 'bar', metricId: 'wearable_daily.steps',
    title: 'Steps', unit: 'steps',
  },
  'sleep_daily.total_sleep_minutes': {
    specVersion: 1, kind: 'bar', metricId: 'sleep_daily.total_sleep_minutes',
    title: 'Sleep Duration', unit: 'min',
  },
  'sleep_daily.sleep_efficiency_pct': {
    specVersion: 1, kind: 'line', metricId: 'sleep_daily.sleep_efficiency_pct',
    title: 'Sleep Efficiency', unit: '%', smooth: true,
  },
  'body_metrics.weight_kg': {
    specVersion: 1, kind: 'line', metricId: 'body_metrics.weight_kg',
    title: 'Body Weight', unit: 'kg', smooth: true,
  },
  'wellbeing_daily.energy_level': {
    specVersion: 1, kind: 'area', metricId: 'wellbeing_daily.energy_level',
    title: 'Energy Level', unit: '/10', smooth: true,
  },
}
