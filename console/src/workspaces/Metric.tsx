// METRIC (web redesign, phase 2): the one page every tappable number opens, as the phone's MetricHistoryOverlay does: the
// latest value and where it sits, its evolution over 30 days to all of it with your usual band, the numbers of the window,
// what it is, and the days behind it. A ring, one of its inputs, a daily series or a tile or chart of a Me tab (lib/metrics.ts).
// Address: #/m/<ref>?range=90&day=2026-11-17 (day marks a morning; refs of the old History page redirect here).
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import type { EChartsCoreOption } from 'echarts/core'
import { useLoadEffect } from '../lib/useLoadEffect'
import { useChart } from '../lib/useChart'
import { localDaysAgo, localToday } from '../lib/dates'
import { directionWords } from '../lib/scoreText'
import { METRIC_RANGES, metricHref, parseMetricRef, refString, seriesBehind, windowStats } from '../lib/metrics'
import { loadMetric, type MetricData } from '../lib/metricData'
import { PALETTE } from '../lib/palette'
import { seriesById } from '../lib/schema'
import './PlanBlock.css'
import './Metric.css'

/** ECharts draws on a canvas and cannot read `var(--token)`: resolve it to the colour the page is using. */
function cssColor(v: string): string {
  const m = /^var\((--[a-z0-9-]+)\)$/i.exec(v)
  if (!m) return v
  return getComputedStyle(document.documentElement).getPropertyValue(m[1]).trim() || PALETTE.textMuted
}

const shortDay = (d: string) => new Date(`${d}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })

export default function Metric() {
  const { ref: raw } = useParams()
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const ref = useMemo(() => parseMetricRef(raw), [raw])
  const focus = params.get('day')
  const rangeId = params.get('range') ?? '90'
  const range = METRIC_RANGES.find((r) => r.id === rangeId) ?? METRIC_RANGES[1]
  const from = localDaysAgo(range.days - 1)
  const [data, setData] = useState<MetricData | null>(null)

  const status = useLoadEffect(async (isCurrent) => {
    if (!ref) return
    const ctl = new AbortController()
    const d = await loadMetric(ref, from, localToday(), ctl.signal)
    if (isCurrent()) setData(d)
  }, [raw, from])

  const withValue = useMemo(() => (data?.points ?? []).filter((p) => p.value != null), [data])
  const latest = withValue[withValue.length - 1]
  const stats = useMemo(() => windowStats(data?.points ?? []), [data])
  const color = data ? cssColor(data.hue) : PALETTE.emerald

  const option = useMemo<EChartsCoreOption>(() => {
    if (!data) return {}
    const isRing = ref?.kind === 'ring'
    return {
      grid: { left: 56, right: 20, top: 24, bottom: 32 },
      tooltip: { trigger: 'axis', valueFormatter: (v: unknown) => (typeof v === 'number' ? `${data.fmt(v)} ${data.unit}`.trim() : '–') },
      xAxis: { type: 'time', axisLabel: { color: PALETTE.textMuted }, axisLine: { lineStyle: { color: PALETTE.border } } },
      yAxis: { type: 'value', scale: !isRing, min: isRing ? 0 : undefined, axisLabel: { color: PALETTE.textMuted, formatter: (v: number) => data.fmt(v) }, splitLine: { lineStyle: { color: PALETTE.border } } },
      series: [
        { name: data.label, type: 'line', data: data.points.map((p) => [p.day, p.value]), connectNulls: false, showSymbol: data.points.length < 120, symbolSize: 5, itemStyle: { color }, lineStyle: { color, width: 2 },
          markLine: focus ? { symbol: 'none', silent: true, lineStyle: { color: PALETTE.text, type: 'dashed' }, label: { color: PALETTE.text, formatter: focus }, data: [{ xAxis: focus }] } : undefined },
        ...(data.usual ? [{ name: 'Your usual', type: 'line', data: data.points.map((p) => [p.day, p.reference ?? null]), showSymbol: false, lineStyle: { color: PALETTE.textMuted, width: 1, type: 'dashed' }, itemStyle: { color: PALETTE.textMuted } }] : []),
      ],
    }
  }, [data, color, focus, ref])

  if (!raw || !ref) {
    return <div className="metric"><p className="state-empty">NO SUCH METRIC. <Link to="/today">BACK TO TODAY</Link></p></div>
  }
  const setRange = (id: string) => { const n = new URLSearchParams(params); n.set('range', id); setParams(n, { replace: true }) }
  const behind = seriesBehind(ref)
  const unit = data?.unit ? ` ${data.unit}` : ''
  const withUnit = (v: number) => `${data!.fmt(v)}${unit}`

  return (
    <div className="metric">
      <header className="metric-head">
        <button className="btn-ghost mono" onClick={() => navigate(-1)} aria-label="Back">‹ BACK</button>
        <div className="metric-title">
          <h2 className="metric-name mono">{data?.label ?? (ref.kind === 'me' ? ref.label : refString(ref))}</h2>
          {data && <p className="metric-sub">{data.source}</p>}
        </div>
        <div className="pb-chips">
          {METRIC_RANGES.map((r) => <button key={r.id} className={`pb-chip ${range.id === r.id ? 'on' : ''}`} onClick={() => setRange(r.id)}>{r.label}</button>)}
        </div>
      </header>

      {status.error && <p className="pb-warn">Could not load: {status.error} <button className="btn-ghost mono" onClick={status.retry}>RETRY</button></p>}
      {data && !status.loading && withValue.length === 0 && !status.error && <p className="state-empty">NO VALUES IN THIS RANGE.</p>}

      <section className="metric-card">
        {latest && data && (
          <div className="metric-latest">
            <span className="metric-latest-v" style={{ color }}>{withUnit(latest.value!)}</span>
            <span className="mono">LATEST · {latest.day}</span>
            {latest.direction && <span className="metric-ref">{directionWords(latest.direction)}</span>}
            {latest.refLine && <span className="metric-ref">{latest.refLine}</span>}
          </div>
        )}
        <Chart option={option} hidden={withValue.length === 0} />
        {data?.alsoInChart && data.alsoInChart.length > 0 && <p className="metric-sub">The phone's chart also draws {data.alsoInChart.join(', ')}; this page shows the first line.</p>}
      </section>

      {data && stats && (
        <section className="metric-tiles" aria-label={`In these ${range.id === 'all' ? 'days' : `${range.days} days`}`}>
          <Tile label="AVERAGE" value={withUnit(stats.average)} />
          <Tile label="READINGS" value={String(stats.readings)} sub={range.id === 'all' ? undefined : `of ${range.days} days`} />
          <Tile label="LOWEST" value={withUnit(stats.lowest.value)} sub={shortDay(stats.lowest.day)} />
          <Tile label="HIGHEST" value={withUnit(stats.highest.value)} sub={shortDay(stats.highest.day)} />
        </section>
      )}

      {behind && (
        <p className="metric-sub">This input is a daily series as it is: <Link to={metricHref(`series:${behind}`, { range: 'all' })}>its whole history →</Link> (the kept mornings start on 6 Oct 2026).</p>
      )}
      {data?.info && <section className="metric-card"><h3 className="metric-h mono">WHAT IS IT?</h3><p className="metric-sub">{data.info}</p></section>}

      {data && withValue.length > 0 && (
        <section className="metric-days">
          <table className="pb-table">
            <thead><tr><th>DAY</th><th>VALUE</th>{data.usual && <th>AGAINST USUAL</th>}<th /></tr></thead>
            <tbody>
              {[...withValue].reverse().slice(0, 120).map((p) => (
                <tr key={p.day} className={p.day === focus ? 'pb-sel' : ''}>
                  <td className="mono">{p.day}</td>
                  <td className="mono">{withUnit(p.value!)}</td>
                  {data.usual && <td className="pb-muted">{p.direction ? directionWords(p.direction) : ''}{p.refLine ? ` · ${p.refLine}` : ''}</td>}
                  <td className="metric-links">
                    <Link to={`/log?day=${p.day}`}>DAY LOG</Link>
                    <Link to={`/today?day=${p.day}`}>TODAY</Link>
                    {ref.kind === 'series' && <Link to={`/data?table=${encodeURIComponent(tableOf(ref.key))}&date=${p.day}`}>RECORDS</Link>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </div>
  )
}

const tableOf = (id: string) => seriesById(id)?.table ?? ''

function Tile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return <div className="metric-tile"><span className="metric-tile-label mono">{label}</span><span className="metric-tile-value">{value}</span>{sub && <span className="metric-tile-sub">{sub}</span>}</div>
}

function Chart({ option, hidden }: { option: EChartsCoreOption; hidden: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  const chart = useChart(ref)
  useEffect(() => { chart.current?.setOption(option, true) }, [chart, option])
  return <div ref={ref} className="metric-chart" style={hidden ? { display: 'none' } : undefined} />
}

/** The address of the retired History page: #/history?metric=<ref>&day=&range= lands on the metric page. */
export function HistoryRedirect() {
  const [params] = useSearchParams()
  const m = params.get('metric') ?? 'charge'
  const ref = parseMetricRef(m)
  return <Navigate to={ref ? metricHref(ref, { day: params.get('day') ?? undefined, range: params.get('range') ?? undefined }).slice(1) : '/today'} replace />
}
