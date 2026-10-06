// Comparison workspace — side-by-side overlay of any two metrics over the primary range.
// Both series on a shared x-axis; optional second y-axis for different units.

import { useEffect, useRef, useState } from 'react'
import { useChart } from '../lib/useChart'
import { useRange } from '../lib/analysisStore'
import { fetchMetric, type MetricResult } from '../lib/metricAdapter'
import { PALETTE } from '../lib/palette'
import { METRIC_OPTIONS, type MetricOption } from '../lib/metricOptions'
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
  const [loading, setLoading] = useState(false)
  const chartRef = useRef<HTMLDivElement>(null)
  const chartInstance = useChart(chartRef)

  useEffect(() => {
    setLoading(true)
    Promise.all([
      fetchMetric({ table: metricA.table, column: metricA.column, range, aggregation: 'daily_avg' }),
      fetchMetric({ table: metricB.table, column: metricB.column, range, aggregation: 'daily_avg' }),
    ]).then(([a, b]) => { setResultA(a); setResultB(b); setLoading(false) })
  }, [metricA, metricB, range])

  // Update chart when data arrives
  useEffect(() => {
    const chart = chartInstance.current
    if (!chart || !resultA || !resultB) return

    const datesA = resultA.points.map(p => p.date)
    const valsA  = resultA.points.map(p => p.value)
    const datesB = resultB.points.map(p => p.date)
    const valsB  = resultB.points.map(p => p.value)

    // Union of all dates for shared x-axis
    const allDates = [...new Set([...datesA, ...datesB])].sort()
    const mapA = Object.fromEntries(datesA.map((d, i) => [d, valsA[i]]))
    const mapB = Object.fromEntries(datesB.map((d, i) => [d, valsB[i]]))
    const seriesValsA = allDates.map(d => mapA[d] ?? null)
    const seriesValsB = allDates.map(d => mapB[d] ?? null)

    const sameUnit = metricA.unit === metricB.unit

    chart.setOption({
      backgroundColor: 'transparent',
      animation: false,
      grid: { top: 16, right: sameUnit ? 12 : 60, bottom: 40, left: 52 },
      xAxis: {
        type: 'category', data: allDates,
        axisLine: { lineStyle: { color: PALETTE.border } },
        axisTick: { show: false },
        axisLabel: { color: PALETTE.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        splitLine: { show: false },
      },
      yAxis: [
        {
          type: 'value', name: metricA.unit,
          nameTextStyle: { color: COLORS.a, fontSize: 10 },
          axisLabel: { color: COLORS.a, fontSize: 10, fontFamily: 'Roboto Mono' },
          splitLine: { lineStyle: { color: PALETTE.border } },
          axisLine: { show: false }, axisTick: { show: false },
        },
        {
          type: 'value', name: sameUnit ? '' : metricB.unit,
          nameTextStyle: { color: COLORS.b, fontSize: 10 },
          axisLabel: sameUnit ? { show: false } : { color: COLORS.b, fontSize: 10, fontFamily: 'Roboto Mono' },
          splitLine: { show: false },
          axisLine: { show: false }, axisTick: { show: false },
        },
      ],
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
        {
          name: metricA.label, type: 'line', yAxisIndex: 0,
          data: seriesValsA, smooth: true, symbolSize: 3,
          itemStyle: { color: COLORS.a }, lineStyle: { color: COLORS.a },
          connectNulls: false,
        },
        {
          name: metricB.label, type: 'line', yAxisIndex: sameUnit ? 0 : 1,
          data: seriesValsB, smooth: true, symbolSize: 3,
          itemStyle: { color: COLORS.b }, lineStyle: { color: COLORS.b },
          connectNulls: false,
        },
      ],
    }, true)
  }, [resultA, resultB, metricA, metricB])

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
