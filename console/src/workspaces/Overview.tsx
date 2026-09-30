// DAV-310 / DAV-313: Overview workspace — synchronized multi-chart canvas.
// Proves: AnalysisContext drives 4+ independent components; cursor syncs across all.

import { useEffect, useState } from 'react'
import { useAnalysis } from '../context/AnalysisContext'
import { fetchMetric, METRICS, type MetricResult } from '../lib/metricAdapter'
import { CHART_SPECS } from '../lib/chartSpec'
import { FTChart } from '../components/shared/FTChart'
import './Overview.css'

const OVERVIEW_METRICS = [
  METRICS.HRV,
  METRICS.RHR,
  METRICS.STEPS,
  METRICS.SLEEP_DURATION,
  METRICS.ENERGY_LEVEL,
  METRICS.MOOD,
] as const

export function Overview() {
  const { ctx, setSelectedDate } = useAnalysis()
  const [results, setResults] = useState<Record<string, MetricResult>>({})
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    Promise.all(
      OVERVIEW_METRICS.map(m =>
        fetchMetric({ ...m, range: ctx.primaryRange, aggregation: 'daily_avg' })
          .then(r => [r.metricId, r] as const)
      )
    ).then(entries => {
      setResults(Object.fromEntries(entries))
      setLoading(false)
    })
  }, [ctx.primaryRange])

  if (loading) return <div className="state-loading mono">LOADING OVERVIEW…</div>

  return (
    <div className="overview">
      <div className="overview-header">
        <h2 className="overview-title">Overview</h2>
        {ctx.selectedDate && (
          <div className="overview-cursor mono">
            CURSOR: {ctx.selectedDate}
            <button className="overview-cursor-clear mono" onClick={() => setSelectedDate(null)}>✕</button>
          </div>
        )}
      </div>

      <div className="overview-grid">
        {OVERVIEW_METRICS.map(m => {
          const metricId = `${m.table}.${m.column}`
          const result = results[metricId]
          const spec = CHART_SPECS[metricId]
          if (!result || !spec) return null
          return (
            <div key={metricId} className="card overview-card">
              <FTChart
                spec={spec}
                result={result}
                height={160}
                onDateSelect={setSelectedDate}
              />
            </div>
          )
        })}
      </div>
    </div>
  )
}
