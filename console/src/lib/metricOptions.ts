import { METRICS } from './metricAdapter'

export interface MetricOption {
  id: string
  label: string
  unit: string
  table: string
  column: string
}

export const METRIC_OPTIONS: MetricOption[] = [
  { id: `${METRICS.HRV.table}.${METRICS.HRV.column}`,                          label: 'HRV (RMSSD)',    unit: 'ms',    ...METRICS.HRV },
  { id: `${METRICS.RHR.table}.${METRICS.RHR.column}`,                          label: 'Resting HR',     unit: 'bpm',   ...METRICS.RHR },
  { id: `${METRICS.STEPS.table}.${METRICS.STEPS.column}`,                      label: 'Steps',          unit: 'steps', ...METRICS.STEPS },
  { id: `${METRICS.SLEEP_DURATION.table}.${METRICS.SLEEP_DURATION.column}`,     label: 'Sleep Duration', unit: 'min',   ...METRICS.SLEEP_DURATION },
  { id: `${METRICS.ENERGY_LEVEL.table}.${METRICS.ENERGY_LEVEL.column}`,         label: 'Energy Level',   unit: '/10',   ...METRICS.ENERGY_LEVEL },
  { id: `${METRICS.MOOD.table}.${METRICS.MOOD.column}`,                         label: 'Mood',           unit: '/10',   ...METRICS.MOOD },
  { id: 'body_metrics.weight_kg',                                               label: 'Weight',         unit: 'kg',    table: 'body_metrics', column: 'weight_kg' },
]
