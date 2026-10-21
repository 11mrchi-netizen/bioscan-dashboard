// Comparison workspace — any two metrics over the primary range, on one time axis. Same unit: one panel. Different
// units: two aligned panels (no second y-axis).

import { LoadBanner } from '../components/shared/LoadBanner'
import { useLoadEffect } from '../lib/useLoadEffect'
import { useEffect, useRef, useState } from 'react'
import { useChart } from '../lib/useChart'
import { useRange } from '../lib/analysisStore'
import { fetchMetric, type MetricResult } from '../lib/metricAdapter'
import { PALETTE } from '../lib/palette'
import { METRIC_OPTIONS, type MetricOption } from '../lib/metricOptions'
import { dailyAxis } from '../lib/dates'
import './Comparison.css'

const COLORS = {
  a: PALETTE.emerald,
  b: PALETTE.amber,
}

export function Comparison() {
  const range = useRange()
  const [metricA, setMetricA] = useState<MetricOption>(METRIC_OPTIONS[0])
  const [metricB, setMetricB] = useState<MetricOption>(METRIC_OPTIONS[2])
  const [resultA, setResultA] = useState<MetricResult | null>(null)
  const [resultB, setResultB] = useState<MetricResult | null>(null)
  const chartRef = useRef<HTMLDivElement>(null)
  const chartInstance = useChart(chartRef)

  const { loading, error: loadError, retry } = useLoadEffect(async (isCurrent) => {
    const [a, b] = await Promise.all([
      fetchMetric({ table: metricA.table, column: metricA.column, range, aggregation: 'daily_avg' }),
      fetchMetric({ table: metricB.table, column: metricB.column, range, aggregation: 'daily_avg' }),
    ])
    if (isCurrent()) { setResultA(a); setResultB(b) }
  }, [metricA, metricB, range])

  // Update chart when data arrives.
  // Analysis review A6: two metrics with different units get two aligned panels on one time axis (small multiples), never
  // a second y-axis, which readers misjudge; the time axis is a real one and every day in the range is a slot, so missing
  // days show as gaps instead of being squeezed out of a category axis.
  useEffect(() => {
    const chart = chartInstance.current
    if (!chart || !resultA || !resultB) return

    const days = dailyAxis(range.start, range.end)
    const toSeries = (r: MetricResult) => {
      const byDay = new Map(r.points.map(p => [p.date.slice(0, 10), p.value]))
      return days.map(d => [d, byDay.get(d) ?? null])
    }
    const sameUnit = metricA.unit === metricB.unit
    const xAxisBase = {
      type: 'time' as const,
      axisLine: { lineStyle: { color: PALETTE.border } },
      axisTick: { show: false },
      axisLabel: { color: PALETTE.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
      splitLine: { show: false },
    }
    const yAxisBase = (unit: string) => ({
      type: 'value' as const, name: unit, scale: true,
      nameTextStyle: { color: PALETTE.textMuted, fontSize: 10 },
      axisLabel: { color: PALETTE.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
      splitLine: { lineStyle: { color: PALETTE.border } },
      axisLine: { show: false }, axisTick: { show: false },
    })
    const line = (name: string, color: string, data: (string | number | null)[][], index: number) => ({
      name, type: 'line', xAxisIndex: index, yAxisIndex: index, data, symbolSize: 4,
      itemStyle: { color }, lineStyle: { color, width: 2 }, connectNulls: false,
    })

    chart.setOption({
      backgroundColor: 'transparent',
      animation: false,
      grid: sameUnit
        ? [{ top: 28, right: 12, bottom: 40, left: 52 }]
        : [{ top: 28, right: 12, height: '34%', left: 52 }, { top: '58%', right: 12, bottom: 40, left: 52 }],
      xAxis: sameUnit
        ? [xAxisBase]
        : [{ ...xAxisBase, gridIndex: 0, axisLabel: { show: false } }, { ...xAxisBase, gridIndex: 1 }],
      yAxis: sameUnit
        ? [yAxisBase(metricA.unit)]
        : [{ ...yAxisBase(metricA.unit), gridIndex: 0 }, { ...yAxisBase(metricB.unit), gridIndex: 1 }],
      axisPointer: { link: [{ xAxisIndex: 'all' }] },
      tooltip: {
        trigger: 'axis',
        backgroundColor: PALETTE.surface,
        borderColor: PALETTE.border,
        textStyle: { color: PALETTE.text, fontFamily: 'Roboto Mono', fontSize: 11 },
      },
      legend: {
        data: [metricA.label, metricB.label],
        textStyle: { color: PALETTE.text, fontFamily: 'Roboto Mono', fontSize: 11 },
        top: 0,
      },
      series: [
        line(metricA.label, COLORS.a, toSeries(resultA), 0),
        line(metricB.label, COLORS.b, toSeries(resultB), sameUnit ? 0 : 1),
      ],
    }, true)
  }, [resultA, resultB, metricA, metricB, range])

  return (
    <div className="comparison">
      <div className="comparison-header">
        <h2 className="comparison-title">Comparison</h2>
        <div className="comparison-selectors">
          <div className="comparison-selector">
            <span className="comparison-selector-dot" style={{ background: COLORS.a }} />
            <select
              className="comparison-select mono"
              value={metricA.id}
              onChange={e => setMetricA(METRIC_OPTIONS.find(m => m.id === e.target.value) ?? METRIC_OPTIONS[0])}
            >
              {METRIC_OPTIONS.map(m => <option key={m.id} value={m.id}>{m.label}</option>)}
            </select>
          </div>
          <div className="comparison-selector">
            <span className="comparison-selector-dot" style={{ background: COLORS.b }} />
            <select
              className="comparison-select mono"
              value={metricB.id}
              onChange={e => setMetricB(METRIC_OPTIONS.find(m => m.id === e.target.value) ?? METRIC_OPTIONS[1])}
            >
              {METRIC_OPTIONS.map(m => <option key={m.id} value={m.id}>{m.label}</option>)}
            </select>
          </div>
        </div>
      </div>

      <div className="card comparison-chart-card">
        {loading && <div className="state-loading mono">LOADING…</div>}
        {loadError && <LoadBanner error={loadError} onRetry={retry} />}
        <div ref={chartRef} style={{ width: '100%', height: 360, visibility: loading ? 'hidden' : 'visible' }} />
      </div>

      {resultA && resultB && !loading && (
        <div className="comparison-stats mono">
          <ComparisonStat label={metricA.label} result={resultA} color={COLORS.a} unit={metricA.unit} />
          <ComparisonStat label={metricB.label} result={resultB} color={COLORS.b} unit={metricB.unit} />
        </div>
      )}
    </div>
  )
}

function ComparisonStat({ label, result, color, unit }: {
  label: string; result: MetricResult; color: string; unit: string
}) {
  const vals = result.points.map(p => p.value).filter(v => v !== null) as number[]
  if (!vals.length) return null
  const avg = vals.reduce((a, b) => a + b, 0) / vals.length
  const min = Math.min(...vals)
  const max = Math.max(...vals)
  return (
    <div className="comparison-stat-block" style={{ borderColor: color }}>
      <div className="comparison-stat-label" style={{ color }}>{label}</div>
      <div className="comparison-stat-row">
        <span className="comparison-stat-key">AVG</span>
        <span className="comparison-stat-val">{avg.toFixed(1)} {unit}</span>
      </div>
      <div className="comparison-stat-row">
        <span className="comparison-stat-key">MIN</span>
        <span className="comparison-stat-val">{min.toFixed(1)}</span>
      </div>
      <div className="comparison-stat-row">
        <span className="comparison-stat-key">MAX</span>
        <span className="comparison-stat-val">{max.toFixed(1)}</span>
      </div>
      <div className="comparison-stat-row">
        <span className="comparison-stat-key">N</span>
        <span className="comparison-stat-val">{vals.length}</span>
      </div>
    </div>
  )
}

export default Comparison
