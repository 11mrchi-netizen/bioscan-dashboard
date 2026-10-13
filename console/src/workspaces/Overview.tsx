// DAV-310 / DAV-313: Overview workspace — synchronized multi-chart canvas.
// Proves: AnalysisContext drives 4+ independent components; cursor syncs across all.

import { LoadBanner } from '../components/shared/LoadBanner'
import { useLoadEffect } from '../lib/useLoadEffect'
import { useState } from 'react'
import { useRange, useSelectedDate, useSetSelectedDate, useClearInspection } from '../lib/analysisStore'
import { fetchMetrics, METRICS, type MetricResult } from '../lib/metricAdapter'
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

  // Old results stay on screen while a new range loads; a failure shows a banner with RETRY.
  const { loading, error, retry } = useLoadEffect(async (isCurrent) => {
    // Perf pass: metrics in the same table share one query (6 requests -> 4).
    const fetched = await fetchMetrics(OVERVIEW_METRICS.map(m => ({ ...m, range, aggregation: 'daily_avg' as const })))
    if (isCurrent()) setResults(Object.fromEntries(fetched.map(r => [r.metricId, r] as const)))
  }, [range])

  if (loading) return <div className="state-loading mono">LOADING OVERVIEW…</div>
  const failed = Object.values(results).filter(r => r.error)
  const banner = error ?? (failed.length > 0 ? `${failed.length} of ${OVERVIEW_METRICS.length} metrics failed: ${failed[0].error}` : null)

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

      {banner && <LoadBanner error={banner} onRetry={retry} />}

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
