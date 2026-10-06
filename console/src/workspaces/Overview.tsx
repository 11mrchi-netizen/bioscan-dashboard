// DAV-310 / DAV-313: Overview workspace — synchronized multi-chart canvas.
// Proves: AnalysisContext drives 4+ independent components; cursor syncs across all.

import { useEffect, useState } from 'react'
import { useRange, useSelectedDate, useSetSelectedDate, useClearInspection } from '../lib/analysisStore'
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
  const range = useRange()
  const selectedDate = useSelectedDate()
  const setSelectedDate = useSetSelectedDate()
  const clearInspection = useClearInspection()
  const [results, setResults] = useState<Record<string, MetricResult>>({})
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    Promise.all(
      OVERVIEW_METRICS.map(m =>
        fetchMetric({ ...m, range, aggregation: 'daily_avg' })
          .then(r => [r.metricId, r] as const)
      )
    ).then(entries => {
      setResults(Object.fromEntries(entries))
      setLoading(false)
    })
  }, [range])

  if (loading) return <div className="state-loading mono">LOADING OVERVIEW…</div>

  return (
    <div className="overview">
      <div className="overview-header">
        <h2 className="overview-title">Overview</h2>
        {selectedDate && (
          <div className="overview-cursor mono">
            CURSOR: {selectedDate}
            <button className="overview-cursor-clear mono" onClick={() => { setSelectedDate(null); clearInspection() }}>✕</button>
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
              />
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default Overview
