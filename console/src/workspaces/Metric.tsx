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
import { directionGlyph, directionWords } from '../lib/scoreText'
import { Wash } from '../dashboard/DayPages'
import { METRIC_RANGES, metricHref, parseMetricRef, rangeFor, refString, seriesBehind, splitMeLabel, washFor, windowStats } from '../lib/metrics'
import { loadMetric, type MetricData } from '../lib/metricData'
import { seriesById } from '../lib/schema'
import './Metric.css'

/** ECharts draws on a canvas and cannot read `var(--token)`: resolve it to the colour the page is using. */
function cssColor(v: string): string {
  const m = /^var\((--[a-z0-9-]+)\)$/i.exec(v)
  if (!m) return v
  return getComputedStyle(document.documentElement).getPropertyValue(m[1]).trim() || '#8a949a'
}

const shortDay = (d: string) => new Date(`${d}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })

export default function Metric() {
  const { ref: raw } = useParams()
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const ref = useMemo(() => parseMetricRef(raw), [raw])
  const focus = params.get('day')
  // A day opened from Today may be months back: with no range chosen, the smallest range that holds it.
  const range = METRIC_RANGES.find((r) => r.id === params.get('range')) ?? rangeFor(focus, localToday())
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
  const color = data ? cssColor(data.hue) : cssColor('var(--ft-platinum)')

  const option = useMemo<EChartsCoreOption>(() => {
    if (!data) return {}
    const isRing = ref?.kind === 'ring'
    const muted = cssColor('var(--ft-muted)'), line = cssColor('var(--ft-line)'), fg = cssColor('var(--ft-fg)')
    const mono = 'IBM Plex Mono, monospace'
    return {
      textStyle: { fontFamily: mono },
      grid: { left: 52, right: 16, top: 20, bottom: 30 },
      tooltip: { trigger: 'axis', backgroundColor: cssColor('var(--ft-bg)'), borderColor: line, textStyle: { color: fg, fontFamily: mono, fontSize: 12 }, valueFormatter: (v: unknown) => (typeof v === 'number' ? `${data.fmt(v)} ${data.unit}`.trim() : '–') },
      xAxis: { type: 'time', axisLabel: { color: muted, fontFamily: mono, fontSize: 10 }, axisLine: { lineStyle: { color: line } } },
      yAxis: { type: 'value', scale: !isRing, min: isRing ? 0 : undefined, axisLabel: { color: muted, fontFamily: mono, fontSize: 10, formatter: (v: number) => data.fmt(v) }, splitLine: { lineStyle: { color: line } } },
      series: [
        { name: data.label, type: 'line', data: data.points.map((p) => [p.day, p.value]), connectNulls: false, showSymbol: data.points.length < 120, symbolSize: 5, itemStyle: { color }, lineStyle: { color, width: 2 },
          markLine: focus ? { symbol: 'none', silent: true, lineStyle: { color: fg, type: 'dashed' }, label: { color: fg, formatter: focus }, data: [{ xAxis: focus }] } : undefined },
        ...(data.usual ? [{ name: 'Your usual', type: 'line', data: data.points.map((p) => [p.day, p.reference ?? null]), showSymbol: false, lineStyle: { color: muted, width: 1, type: 'dashed' }, itemStyle: { color: muted } }] : []),
      ],
    }
  }, [data, color, focus, ref])

  if (!raw || !ref) {
    return <Wash hue="var(--ft-domain-balance)"><main className="dash-main"><section className="ft-card"><p className="me-note">No such metric. <Link className="dash-link" to="/today">TODAY</Link></p></section></main></Wash>
  }
  const setRange = (id: string) => { const n = new URLSearchParams(params); n.set('range', id); setParams(n, { replace: true }) }
  const behind = seriesBehind(ref)
  const unit = data?.unit ? ` ${data.unit}` : ''
  const withUnit = (v: number) => `${data!.fmt(v)}${unit}`

  return (
    <Wash hue={`var(--${washFor(ref)})`}>
      <main className="dash-main metric">
        <header className="metric-head">
          <button className="dash-link" onClick={() => (window.history.state?.idx > 0 ? navigate(-1) : navigate('/today'))} aria-label="Back">‹ BACK</button>
          <div className="metric-title">
            <h2 className="metric-name">{data?.label ?? (ref.kind === 'me' ? splitMeLabel(ref.label).item : refString(ref))}</h2>
            {data && <p className="metric-sub">{data.source}</p>}
          </div>
          <div className="metric-ranges">
            {METRIC_RANGES.map((r) => <button key={r.id} className="ft-chip" aria-current={range.id === r.id} onClick={() => setRange(r.id)}>{r.label}</button>)}
          </div>
        </header>

        {status.error && <section className="ft-card"><p className="me-note">Could not load: {status.error} <button className="dash-link" onClick={status.retry}>RETRY</button></p></section>}
        {data && !status.loading && withValue.length === 0 && !status.error && <section className="ft-card"><p className="me-note muted">No values in this range.</p></section>}

        <section className="ft-card metric-card">
          {latest && data && (
            <div className="metric-latest">
              <span className="metric-latest-v" style={{ color }}>{withUnit(latest.value!)}</span>
              <span className="metric-latest-k">LATEST · {latest.day}</span>
              {latest.direction && <span className="metric-ref">{directionGlyph(latest.direction)} {directionWords(latest.direction)}</span>}
              {latest.refLine && <span className="metric-ref">{latest.refLine}</span>}
            </div>
          )}
          <Chart option={option} hidden={withValue.length === 0} />
          {data?.alsoInChart && data.alsoInChart.length > 0 && <p className="metric-sub">The phone's chart also draws {data.alsoInChart.join(', ')}; this page shows the first line.</p>}
        </section>

        {data && stats && (
          <section className="metric-tiles" aria-label="The numbers of this range">
            <Tile label="AVERAGE" value={withUnit(stats.average)} />
            <Tile label="READINGS" value={String(stats.readings)} sub={range.id === 'all' ? undefined : `of ${range.days} days`} />
            <Tile label="LOWEST" value={withUnit(stats.lowest.value)} sub={shortDay(stats.lowest.day)} />
            <Tile label="HIGHEST" value={withUnit(stats.highest.value)} sub={shortDay(stats.highest.day)} />
          </section>
        )}

        {behind && (
          <p className="metric-note">This input is read straight from a daily series: <Link className="dash-link" to={metricHref(`series:${behind}`, { range: 'all' })}>ITS WHOLE HISTORY →</Link> The kept mornings start on 6 Oct 2026.</p>
        )}
        {data?.info && <section className="ft-card"><h3 className="me-section-title">WHAT IS IT?</h3><p className="me-note">{data.info}</p></section>}

        {data && withValue.length > 0 && (
          <section className="ft-card metric-days">
            <h3 className="me-section-title">THE DAYS</h3>
            <table className="metric-table">
              <thead><tr><th>DAY</th><th>VALUE</th>{data.usual && <th>AGAINST USUAL</th>}<th /></tr></thead>
              <tbody>
                {[...withValue].reverse().slice(0, 120).map((p) => (
                  <tr key={p.day} className={p.day === focus ? 'sel' : ''}>
                    <td className="k">{p.day}</td>
                    <td className="v">{withUnit(p.value!)}</td>
                    {data.usual && <td className="muted">{p.direction ? directionWords(p.direction) : ''}{p.refLine ? ` · ${p.refLine}` : ''}</td>}
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
      </main>
    </Wash>
  )
}

const tableOf = (id: string) => seriesById(id)?.table ?? ''

function Tile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return <div className="me-tile"><span className="me-tile-label">{label}</span><span className="me-tile-value">{value}</span>{sub && <span className="me-tile-sub">{sub}</span>}</div>
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
