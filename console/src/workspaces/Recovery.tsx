// DAV-315: Recovery / Body workspace.
// Small-multiples: sleep, HRV, RHR, wellbeing, body metrics, injury periods.
// Injury bands overlay on HRV chart via annotation bands.

import { useEffect, useMemo, useState } from 'react'
import { supabase } from '../lib/supabase'
import { useRange } from '../lib/analysisStore'
import { fetchMetric, METRICS } from '../lib/metricAdapter'
import { CHART_SPECS } from '../lib/chartSpec'
import { FTChart } from '../components/shared/FTChart'
import type { MetricResult } from '../lib/metricAdapter'
import type { ChartSpec } from '../lib/chartSpec'
import './Recovery.css'

interface InjuryBand { start_date: string; end_date: string | null; body_part: string | null }

const RECOVERY_METRICS = [
  METRICS.HRV,
  METRICS.RHR,
  METRICS.SLEEP_DURATION,
  METRICS.ENERGY_LEVEL,
  METRICS.MOOD,
] as const

const BODY_METRICS: ChartSpec[] = [
  { specVersion: 1, metricId: 'body_metrics.weight_kg', title: 'Weight', unit: 'kg', kind: 'line', smooth: true },
]

export function Recovery() {
  const range = useRange()
  const [results, setResults] = useState<Record<string, MetricResult>>({})
  const [injuries, setInjuries] = useState<InjuryBand[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    const { start, end } = range
    Promise.all([
      Promise.all(
        RECOVERY_METRICS.map(m =>
          fetchMetric({ ...m, range, aggregation: 'daily_avg' })
            .then(r => [r.metricId, r] as const)
        )
      ),
      fetchMetric({ table: 'body_metrics', column: 'weight_kg', range, aggregation: 'daily_avg' })
        .then(r => [r.metricId, r] as const),
      supabase.from('injuries')
        .select('start_date, end_date, body_part')
        .lte('start_date', end).or(`end_date.gte.${start},end_date.is.null`)
        .then(({ data }) => data ?? []),
    ]).then(([metricEntries, bodyEntry, injuryData]) => {
      setResults(Object.fromEntries([...metricEntries, [bodyEntry[0], bodyEntry[1]]]))
      setInjuries(injuryData as InjuryBand[])
      setLoading(false)
    })
  }, [range])

  if (loading) return <div className="state-loading mono">LOADING RECOVERY…</div>

  const hrvMetricId = `${METRICS.HRV.table}.${METRICS.HRV.column}`

  const hrvSpec = useMemo<ChartSpec>(() => {
    const bands = injuries.map(inj => ({
      lower: 0, upper: 9999,
      label: inj.body_part ?? 'Injury',
      color: 'rgba(239,68,68,0.12)',
    }))
    return { ...CHART_SPECS[hrvMetricId], bands: bands.length ? bands : undefined }
  }, [injuries, hrvMetricId])

  return (
    <div className="recovery">
      <h2 className="recovery-title">Recovery &amp; Body</h2>

      <section className="recovery-section">
        <div className="recovery-section-label mono">READINESS SIGNALS</div>
        <div className="recovery-grid">
          {/* HRV with injury bands */}
          {results[hrvMetricId] && (
            <div className="card recovery-card">
              <FTChart spec={hrvSpec} result={results[hrvMetricId]} height={160} />
              {injuries.length > 0 && (
                <p className="recovery-band-note mono">
                  INJURY PERIODS SHADED
                </p>
              )}
            </div>
          )}
          {/* Remaining readiness metrics */}
          {([METRICS.RHR, METRICS.SLEEP_DURATION, METRICS.ENERGY_LEVEL, METRICS.MOOD] as const).map(m => {
            const mid = `${m.table}.${m.column}`
            const result = results[mid]
            const spec = CHART_SPECS[mid]
            if (!result || !spec) return null
            return (
              <div key={mid} className="card recovery-card">
                <FTChart spec={spec} result={result} height={160} />
              </div>
            )
          })}
        </div>
      </section>

      <section className="recovery-section">
        <div className="recovery-section-label mono">BODY METRICS</div>
        <div className="recovery-grid">
          {BODY_METRICS.map(spec => {
            const result = results[spec.metricId]
            if (!result) return null
            return (
              <div key={spec.metricId} className="card recovery-card">
                <FTChart spec={spec} result={result} height={160} />
              </div>
            )
          })}
        </div>
      </section>

      {injuries.length > 0 && (
        <section className="recovery-section">
          <div className="recovery-section-label mono">INJURY LOG</div>
          <div className="recovery-injury-list">
            {injuries.map((inj, i) => (
              <div key={i} className="recovery-injury-row">
                <span className="recovery-injury-dates mono">
                  {inj.start_date} → {inj.end_date ?? 'ongoing'}
                </span>
                <span className="recovery-injury-part">{inj.body_part ?? '—'}</span>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

export default Recovery
