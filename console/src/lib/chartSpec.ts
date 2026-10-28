// DAV-309: Versioned chart specification model.
// Charts are configured declaratively from this spec; no metric logic lives in components. Since the analysis hub's
// phase A a spec is derived from the series contract (title, unit, aggregation) instead of a hand-kept map.
import type { SeriesContract } from '../../../supabase/functions/_shared/analysis-core/seriesContract.ts'

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
  aggregation?: string          // how rows on one day combine (series contract day method)
}

// Quantities that add up (a day's steps, a week's run time) are bars; levels (HRV, weight, scores) are lines.
// metricId stays table.column: the Inspector's drill-down to Data reads the table from it.
export function chartSpecFor(series: SeriesContract): ChartSpec {
  const additive = series.day === 'sum' || series.period === 'sum' || series.unit === 'steps'
  return {
    specVersion: 1,
    kind: additive ? 'bar' : 'line',
    metricId: `${series.table}.${series.column}`,
    title: series.label,
    unit: series.unit || null,
    smooth: !additive,
    aggregation: series.day,
  }
}
