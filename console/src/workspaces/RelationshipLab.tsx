// Relationship Lab — scatter plot and lag-correlation between two metrics.
// Explicit n= and missingness caveats shown on every view.

import { useEffect, useState, useMemo, useRef } from 'react'
import * as echarts from 'echarts'
import { useAnalysis } from '../context/AnalysisContext'
import { fetchMetric, METRICS, type MetricResult } from '../lib/metricAdapter'
import './RelationshipLab.css'

const METRIC_OPTIONS = [
  { id: `${METRICS.HRV.table}.${METRICS.HRV.column}`,   label: 'HRV (RMSSD)', unit: 'ms',    ...METRICS.HRV },
  { id: `${METRICS.RHR.table}.${METRICS.RHR.column}`,   label: 'Resting HR',  unit: 'bpm',   ...METRICS.RHR },
  { id: `${METRICS.STEPS.table}.${METRICS.STEPS.column}`, label: 'Steps',     unit: 'steps', ...METRICS.STEPS },
  { id: `${METRICS.SLEEP_DURATION.table}.${METRICS.SLEEP_DURATION.column}`, label: 'Sleep Duration', unit: 'min', ...METRICS.SLEEP_DURATION },
  { id: `${METRICS.ENERGY_LEVEL.table}.${METRICS.ENERGY_LEVEL.column}`, label: 'Energy Level', unit: '/10', ...METRICS.ENERGY_LEVEL },
  { id: `${METRICS.MOOD.table}.${METRICS.MOOD.column}`,  label: 'Mood',        unit: '/10',   ...METRICS.MOOD },
  { id: 'body_metrics.weight_kg', label: 'Weight', unit: 'kg', table: 'body_metrics', column: 'weight_kg' },
]

const COLORS = {
  scatter:   '#10B981',
  text:      '#A4AFBA',
  textMuted: '#66717C',
  border:    'rgba(255,255,255,0.09)',
  surface:   '#171E23',
}

interface MetricOption { id: string; label: string; unit: string; table: string; column: string }

// Pearson r
function pearson(xs: number[], ys: number[]): number {
  const n = xs.length
  if (n < 2) return NaN
  const meanX = xs.reduce((a, b) => a + b, 0) / n
  const meanY = ys.reduce((a, b) => a + b, 0) / n
  let num = 0, sdX = 0, sdY = 0
  for (let i = 0; i < n; i++) {
    num  += (xs[i] - meanX) * (ys[i] - meanY)
    sdX  += (xs[i] - meanX) ** 2
    sdY  += (ys[i] - meanY) ** 2
  }
  return num / Math.sqrt(sdX * sdY)
}

// Build paired arrays at a given lag (lag > 0 means Y leads X by lag days)
function buildPairs(
  resultX: MetricResult, resultY: MetricResult, lag: number
): { xs: number[]; ys: number[]; dates: string[] } {
  const mapY = new Map(resultY.points.map(p => [p.date, p.value]))

  // All dates in X
  const xs: number[] = [], ys: number[] = [], dates: string[] = []
  for (const { date, value: vx } of resultX.points) {
    if (vx === null) continue
    // Find date for Y with lag offset
    const yDate = new Date(date)
    yDate.setDate(yDate.getDate() + lag)
    const yDateStr = yDate.toISOString().slice(0, 10)
    const vy = mapY.get(yDateStr)
    if (vy === null || vy === undefined) continue
    xs.push(vx); ys.push(vy); dates.push(date)
  }
  return { xs, ys, dates }
}

export function RelationshipLab() {
  const { ctx } = useAnalysis()
  const [metricX, setMetricX] = useState<MetricOption>(METRIC_OPTIONS[3]) // Sleep
  const [metricY, setMetricY] = useState<MetricOption>(METRIC_OPTIONS[0]) // HRV
  const [lag, setLag]         = useState(0)
  const [resultX, setResultX] = useState<MetricResult | null>(null)
  const [resultY, setResultY] = useState<MetricResult | null>(null)
  const [loading, setLoading] = useState(false)

  const scatterRef = useRef<HTMLDivElement>(null)
  const lagRef     = useRef<HTMLDivElement>(null)
  const scatterChart = useRef<echarts.EChartsType | null>(null)
  const lagChart     = useRef<echarts.EChartsType | null>(null)

  useEffect(() => {
    setLoading(true)
    Promise.all([
      fetchMetric({ table: metricX.table, column: metricX.column, range: ctx.primaryRange, aggregation: 'daily_avg' }),
      fetchMetric({ table: metricY.table, column: metricY.column, range: ctx.primaryRange, aggregation: 'daily_avg' }),
    ]).then(([rx, ry]) => { setResultX(rx); setResultY(ry); setLoading(false) })
  }, [metricX, metricY, ctx.primaryRange])

  // Init charts
  useEffect(() => {
    if (scatterRef.current) {
      scatterChart.current = echarts.init(scatterRef.current, null, { renderer: 'canvas' })
      const ro = new ResizeObserver(() => scatterChart.current?.resize())
      ro.observe(scatterRef.current)
      return () => { scatterChart.current?.dispose(); scatterChart.current = null; ro.disconnect() }
    }
  }, [])
  useEffect(() => {
    if (lagRef.current) {
      lagChart.current = echarts.init(lagRef.current, null, { renderer: 'canvas' })
      const ro = new ResizeObserver(() => lagChart.current?.resize())
      ro.observe(lagRef.current)
      return () => { lagChart.current?.dispose(); lagChart.current = null; ro.disconnect() }
    }
  }, [])

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
        nameTextStyle: { color: COLORS.textMuted, fontSize: 10 },
        axisLabel: { color: COLORS.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        splitLine: { lineStyle: { color: COLORS.border } },
        axisLine: { show: false }, axisTick: { show: false },
      },
      yAxis: {
        type: 'value', name: metricY.unit,
        nameTextStyle: { color: COLORS.textMuted, fontSize: 10 },
        axisLabel: { color: COLORS.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        splitLine: { lineStyle: { color: COLORS.border } },
        axisLine: { show: false }, axisTick: { show: false },
      },
      tooltip: {
        trigger: 'item', backgroundColor: COLORS.surface, borderColor: COLORS.border,
        textStyle: { color: COLORS.text, fontFamily: 'Roboto Mono', fontSize: 11 },
        formatter: (p: { dataIndex: number; data: [number, number] }) =>
          `${dates[p.dataIndex] ?? ''}<br/>${metricX.label}: <b>${p.data[0]}</b><br/>${metricY.label}: <b>${p.data[1]}</b>`,
      },
      series: [{
        type: 'scatter',
        data: xs.map((x, i) => [x, ys[i]]),
        symbolSize: 7,
        itemStyle: { color: COLORS.scatter, opacity: 0.7 },
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
        axisLine: { lineStyle: { color: COLORS.border } },
        axisTick: { show: false },
        axisLabel: { color: COLORS.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        name: 'Lag (days)', nameTextStyle: { color: COLORS.textMuted, fontSize: 10 },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value', name: 'r', min: -1, max: 1,
        nameTextStyle: { color: COLORS.textMuted, fontSize: 10 },
        axisLabel: { color: COLORS.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        splitLine: { lineStyle: { color: COLORS.border } },
        axisLine: { show: false }, axisTick: { show: false },
      },
      tooltip: {
        trigger: 'axis', backgroundColor: COLORS.surface, borderColor: COLORS.border,
        textStyle: { color: COLORS.text, fontFamily: 'Roboto Mono', fontSize: 11 },
        formatter: (params: { axisValue: string; data: number; dataIndex: number }[]) => {
          const p = params[0]
          const c = lagCorrs[p.dataIndex]
          return `Lag ${p.axisValue}d · r=${p.data?.toFixed(3)} · n=${c?.n}`
        },
      },
      series: [{
        type: 'bar', data: lagCorrs.map(l => isNaN(l.r) ? null : Math.round(l.r * 1000) / 1000),
        itemStyle: {
          color: (p: { data: number }) => p.data >= 0 ? COLORS.scatter : '#EF4444',
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
