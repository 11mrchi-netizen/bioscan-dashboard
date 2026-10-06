// Relationship Lab — scatter plot and lag-correlation between two metrics.
// Explicit n= and missingness caveats shown on every view.

import { useEffect, useState, useMemo, useRef } from 'react'
import { useChart } from '../lib/useChart'
import { useRange } from '../lib/analysisStore'
import { fetchMetric, type MetricResult } from '../lib/metricAdapter'
import { pearson, buildPairs } from '../lib/stats'
import { PALETTE } from '../lib/palette'
import { METRIC_OPTIONS, type MetricOption } from '../lib/metricOptions'
import './RelationshipLab.css'

export function RelationshipLab() {
  const range = useRange()
  const [metricX, setMetricX] = useState<MetricOption>(METRIC_OPTIONS[3]) // Sleep
  const [metricY, setMetricY] = useState<MetricOption>(METRIC_OPTIONS[0]) // HRV
  const [lag, setLag]         = useState(0)
  const [resultX, setResultX] = useState<MetricResult | null>(null)
  const [resultY, setResultY] = useState<MetricResult | null>(null)
  const [loading, setLoading] = useState(false)

  const scatterRef = useRef<HTMLDivElement>(null)
  const lagRef     = useRef<HTMLDivElement>(null)
  const scatterChart = useChart(scatterRef)
  const lagChart     = useChart(lagRef)

  useEffect(() => {
    setLoading(true)
    Promise.all([
      fetchMetric({ table: metricX.table, column: metricX.column, range, aggregation: 'daily_avg' }),
      fetchMetric({ table: metricY.table, column: metricY.column, range, aggregation: 'daily_avg' }),
    ]).then(([rx, ry]) => { setResultX(rx); setResultY(ry); setLoading(false) })
  }, [metricX, metricY, range])

  // Scatter chart
  const { xs, ys, dates } = useMemo(() => {
    if (!resultX || !resultY) return { xs: [], ys: [], dates: [] }
    return buildPairs(resultX, resultY, lag)
  }, [resultX, resultY, lag])

  useEffect(() => {
    const chart = scatterChart.current
    if (!chart) return
    chart.setOption({
      backgroundColor: 'transparent', animation: false,
      grid: { top: 16, right: 16, bottom: 40, left: 52 },
      xAxis: {
        type: 'value', name: metricX.unit,
        nameTextStyle: { color: PALETTE.textMuted, fontSize: 10 },
        axisLabel: { color: PALETTE.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        splitLine: { lineStyle: { color: PALETTE.border } },
        axisLine: { show: false }, axisTick: { show: false },
      },
      yAxis: {
        type: 'value', name: metricY.unit,
        nameTextStyle: { color: PALETTE.textMuted, fontSize: 10 },
        axisLabel: { color: PALETTE.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        splitLine: { lineStyle: { color: PALETTE.border } },
        axisLine: { show: false }, axisTick: { show: false },
      },
      tooltip: {
        trigger: 'item', backgroundColor: PALETTE.surface, borderColor: PALETTE.border,
        textStyle: { color: PALETTE.text, fontFamily: 'Roboto Mono', fontSize: 11 },
        formatter: (p: { dataIndex: number; data: [number, number] }) =>
          `${dates[p.dataIndex] ?? ''}<br/>${metricX.label}: <b>${p.data[0]}</b><br/>${metricY.label}: <b>${p.data[1]}</b>`,
      },
      series: [{
        type: 'scatter',
        data: xs.map((x, i) => [x, ys[i]]),
        symbolSize: 7,
        itemStyle: { color: PALETTE.emerald, opacity: 0.7 },
      }],
    }, true)
  }, [xs, ys, dates, metricX, metricY])

  // Lag correlation chart — compute r across lags -14..+14
  const lagCorrs = useMemo(() => {
    if (!resultX || !resultY) return []
    return Array.from({ length: 29 }, (_, i) => {
      const l = i - 14
      const { xs: lxs, ys: lys } = buildPairs(resultX, resultY, l)
      return { lag: l, r: pearson(lxs, lys), n: lxs.length }
    })
  }, [resultX, resultY])

  useEffect(() => {
    const chart = lagChart.current
    if (!chart || !lagCorrs.length) return
    chart.setOption({
      backgroundColor: 'transparent', animation: false,
      grid: { top: 16, right: 16, bottom: 40, left: 52 },
      xAxis: {
        type: 'category', data: lagCorrs.map(l => String(l.lag)),
        axisLine: { lineStyle: { color: PALETTE.border } },
        axisTick: { show: false },
        axisLabel: { color: PALETTE.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        name: 'Lag (days)', nameTextStyle: { color: PALETTE.textMuted, fontSize: 10 },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value', name: 'r', min: -1, max: 1,
        nameTextStyle: { color: PALETTE.textMuted, fontSize: 10 },
        axisLabel: { color: PALETTE.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        splitLine: { lineStyle: { color: PALETTE.border } },
        axisLine: { show: false }, axisTick: { show: false },
      },
      tooltip: {
        trigger: 'axis', backgroundColor: PALETTE.surface, borderColor: PALETTE.border,
        textStyle: { color: PALETTE.text, fontFamily: 'Roboto Mono', fontSize: 11 },
        formatter: (params: { axisValue: string; data: number; dataIndex: number }[]) => {
          const p = params[0]
          const c = lagCorrs[p.dataIndex]
          return `Lag ${p.axisValue}d · r=${p.data?.toFixed(3)} · n=${c?.n}`
        },
      },
      series: [{
        type: 'bar', data: lagCorrs.map(l => isNaN(l.r) ? null : Math.round(l.r * 1000) / 1000),
        itemStyle: {
          color: (p: { data: number }) => p.data >= 0 ? PALETTE.emerald : PALETTE.critical,
          opacity: 0.85,
        },
      }],
    }, true)
  }, [lagCorrs])

  const r = isNaN(pearson(xs, ys)) ? null : pearson(xs, ys)
  const caveatStr = `n=${xs.length}  ·  lag=${lag}d  ·  r=${r !== null ? r.toFixed(3) : '—'}  ·  observational only`

  return (
    <div className="rlab">
      <div className="rlab-header">
        <h2 className="rlab-title">Relationship Lab</h2>
        <div className="rlab-selectors">
          <div className="rlab-selector-group">
            <span className="rlab-selector-label mono">X</span>
            <select className="rlab-select mono" value={metricX.id}
              onChange={e => setMetricX(METRIC_OPTIONS.find(m => m.id === e.target.value) ?? METRIC_OPTIONS[0])}>
              {METRIC_OPTIONS.map(m => <option key={m.id} value={m.id}>{m.label}</option>)}
            </select>
          </div>
          <div className="rlab-selector-group">
            <span className="rlab-selector-label mono">Y</span>
            <select className="rlab-select mono" value={metricY.id}
              onChange={e => setMetricY(METRIC_OPTIONS.find(m => m.id === e.target.value) ?? METRIC_OPTIONS[0])}>
              {METRIC_OPTIONS.map(m => <option key={m.id} value={m.id}>{m.label}</option>)}
            </select>
          </div>
          <div className="rlab-selector-group">
            <span className="rlab-selector-label mono">LAG</span>
            <input className="rlab-lag mono" type="number" min={-14} max={14} value={lag}
              onChange={e => setLag(Number(e.target.value))} />
            <span className="rlab-selector-label mono">days</span>
          </div>
        </div>
      </div>

      <div className="rlab-caveat mono">{caveatStr}</div>

      {loading && <div className="state-loading mono">LOADING…</div>}

      <div className="card rlab-chart-card" style={{ visibility: loading ? 'hidden' : 'visible' }}>
        <div className="rlab-chart-label mono">SCATTER  {metricX.label} → {metricY.label}</div>
        <div ref={scatterRef} style={{ width: '100%', height: 300 }} />
      </div>

      <div className="card rlab-chart-card" style={{ visibility: loading ? 'hidden' : 'visible' }}>
        <div className="rlab-chart-label mono">LAG CORRELATION  −14d to +14d</div>
        <div ref={lagRef} style={{ width: '100%', height: 200 }} />
        <p className="rlab-footnote mono">
          Pearson r per lag window · each bar's n may differ · correlation ≠ causation
        </p>
      </div>
    </div>
  )
}

export default RelationshipLab
