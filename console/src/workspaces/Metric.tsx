// METRIC (web redesign, phases 2 and 3): the one page every tappable number opens, as the phone's MetricHistoryOverlay does:
// the latest value and where it sits, its evolution over 30 days to all of it with your usual band, the numbers of the
// window, what it is, and the days behind it. A ring, one of its inputs, a daily series or a tile or chart of a Me tab
// (lib/metrics.ts).
//
// COMPARE (phase 3): `+ COMPARE` draws up to three more metrics on the same days (#/m/<ref>?with=<ref>&with=<ref>). Metrics of
// one unit share a panel, another unit gets its own, all on one time axis. With one extra the page also reads the pair as
// the phone's Lab › EXPLORE does (lib/compare.ts): this metric first, the other a chosen number of days later, as a scatter
// with r and its interval. Address: #/m/<ref>?range=90&day=2026-11-17&with=...&lag=1 (day marks a morning; refs of the old
// History page redirect here).
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import type { EChartsCoreOption } from 'echarts/core'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { useChart } from '../lib/useChart'
import { localDaysAgo, localToday } from '../lib/dates'
import { DOMAINS, directionGlyph, directionWords, type DomainKey, type GroupScore } from '../lib/scoreText'
import {
  compareRefs, INPUT_HUE, MAX_COMPARE, METRIC_RANGES, metricHref, parseMetricRef, rangeFor, refString, seriesBehind, seriesGroup, SERIES_HUE,
  splitMeLabel, washFor, windowStats, RING_KEYS, type MetricRef,
} from '../lib/metrics'
import { loadMetric, type MetricData } from '../lib/metricData'
import { ASSOCIATION_NOTE, correlate, correlationWords, panelsByUnit } from '../lib/compare'
import { seriesById, SERIES } from '../lib/schema'
import { Wash } from '../dashboard/DayPages'
import './Metric.css'

/** ECharts draws on a canvas and cannot read `var(--token)`: resolve it to the colour the page is using. */
function cssColor(v: string): string {
  const m = /^var\((--[a-z0-9-]+)\)$/i.exec(v)
  if (!m) return v
  return getComputedStyle(document.documentElement).getPropertyValue(m[1]).trim() || '#8a949a'
}

const shortDay = (d: string) => new Date(`${d}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })
const LAGS = [{ n: 0, label: 'SAME DAY' }, { n: 1, label: '+1' }, { n: 2, label: '+2' }, { n: 3, label: '+3' }]

interface Item { ref: MetricRef; key: string; data: MetricData }

/** The metric page of the address (#/m/<ref>). */
export default function Metric() {
  const { ref } = useParams()
  return <MetricView rawRef={ref} />
}

/**
 * The page's body. Embedded (Lab › COMPARE, days view) it takes its metric from the caller, has no wash of its own (the Lab
 * is the page) and no back button, and offers CHANGE METRIC, which uses the compare picker to choose the main metric.
 */
export function MetricView({ rawRef: raw, embedded = false, setPrimary }: { rawRef: string | undefined; embedded?: boolean; setPrimary?: (ref: string) => void }) {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const ref = useMemo(() => parseMetricRef(raw), [raw])
  const extraRefs = useMemo(() => compareRefs(params.getAll('with'), ref), [params, ref])
  const extraKey = extraRefs.map(refString).join('\n')
  const focus = params.get('day')
  // A day opened from Today may be months back: with no range chosen, the smallest range that holds it.
  const range = METRIC_RANGES.find((r) => r.id === params.get('range')) ?? rangeFor(focus, localToday())
  const from = localDaysAgo(range.days - 1)
  const to = localToday()
  const [items, setItems] = useState<Item[]>([])
  const [failed, setFailed] = useState<{ key: string; message: string }[]>([])
  const [picking, setPicking] = useState(false)
  const [changing, setChanging] = useState(false)

  const status = useLoadEffect(async (isCurrent) => {
    if (!ref) return
    const ctl = new AbortController()
    const all = [ref, ...extraRefs]
    // One extra that cannot load (a saved view or shared link can name one that is gone) must not blank the page: only the
    // page's own metric is fatal; an extra shows as a chip that says so.
    const settled = await Promise.allSettled(all.map(async (r) => ({ ref: r, key: refString(r), data: await loadMetric(r, from, to, ctl.signal) })))
    if (settled[0].status === 'rejected') throw settled[0].reason
    if (!isCurrent()) return
    setItems(settled.flatMap((x) => (x.status === 'fulfilled' ? [x.value] : [])))
    setFailed(settled.slice(1).flatMap((x, i) => (x.status === 'rejected' ? [{ key: refString(extraRefs[i]), message: x.reason instanceof Error ? x.reason.message : String(x.reason) }] : [])))
  }, [raw, extraKey, from])

  const primary = items[0]?.key === (ref ? refString(ref) : '') ? items[0] : undefined
  const data = primary?.data
  const extras = items.slice(1)
  const withValue = useMemo(() => (data?.points ?? []).filter((p) => p.value != null), [data])
  const latest = withValue[withValue.length - 1]
  const stats = useMemo(() => windowStats(data?.points ?? []), [data])
  const color = data ? cssColor(data.hue) : cssColor('var(--ft-platinum)')

  const setParam = (patch: Record<string, string | null>) => {
    const n = new URLSearchParams(params)
    for (const [k, v] of Object.entries(patch)) { if (v == null) n.delete(k); else n.set(k, v) }
    setParams(n, { replace: true })
  }
  const setWith = (refs: string[]) => {
    const n = new URLSearchParams(params)
    n.delete('with')
    for (const r of refs) n.append('with', r)
    if (refs.length === 0) n.delete('lag')
    setParams(n, { replace: true })
  }
  const toggle = (r: string) => {
    const cur = extraRefs.map(refString)
    setWith(cur.includes(r) ? cur.filter((x) => x !== r) : cur.length < MAX_COMPARE ? [...cur, r] : cur)
  }

  if (!raw || !ref) {
    const none = <section className="ft-card"><p className="me-note">No such metric. {embedded ? <button className="dash-link" onClick={() => setPrimary?.('series:hrv')}>START FROM HRV</button> : <Link className="dash-link" to="/today">TODAY</Link>}</p></section>
    return embedded ? <main className="dash-main">{none}</main> : <Wash hue="var(--ft-domain-balance)"><main className="dash-main">{none}</main></Wash>
  }
  const setRange = (id: string) => setParam({ range: id })
  const behind = seriesBehind(ref)
  const unit = data?.unit ? ` ${data.unit}` : ''
  const withUnit = (v: number) => `${data!.fmt(v)}${unit}`
  const lag = Math.min(3, Math.max(0, Number(params.get('lag') ?? 1) || 0))
  const compared = extras.length > 0
  const panels = panelsByUnit(items.map((it) => ({ ...it, unit: it.data.axis })))

  const content = (
      <main className="dash-main metric">
        <header className="metric-head">
          {!embedded && <button className="dash-link" onClick={() => (window.history.state?.idx > 0 ? navigate(-1) : navigate('/today'))} aria-label="Back">‹ BACK</button>}
          <div className="metric-title">
            <h2 className="metric-name">{data?.label ?? (ref.kind === 'me' ? splitMeLabel(ref.label).item : refString(ref))}</h2>
            {data && <p className="metric-sub">{data.source}</p>}
          </div>
          <div className="metric-ranges">
            {METRIC_RANGES.map((r) => <button key={r.id} className="ft-chip" aria-current={range.id === r.id} onClick={() => setRange(r.id)}>{r.label}</button>)}
          </div>
        </header>

        <div className="metric-compare-bar">
          {embedded && <button className="ft-chip" aria-expanded={changing} onClick={() => { setChanging((v) => !v); setPicking(false) }}>CHANGE METRIC ▾</button>}
          {extras.map((e) => (
            <button key={e.key} className="ft-chip metric-with" onClick={() => toggle(e.key)} aria-label={`Remove ${e.data.label}`}>
              <b className="metric-swatch" style={{ background: cssColor(e.data.hue) }} />{e.data.label} ✕
            </button>
          ))}
          {failed.map((f) => (
            <button key={f.key} className="ft-chip metric-with metric-failed" title={f.message} onClick={() => toggle(f.key)} aria-label={`Remove ${f.key}`}>{f.key} · could not load ✕</button>
          ))}
          {extraRefs.length < MAX_COMPARE && <button className="ft-chip" aria-expanded={picking} onClick={() => { setPicking((v) => !v); setChanging(false) }}>+ COMPARE</button>}
          {compared && <button className="ft-chip" onClick={() => setWith([])}>CLEAR</button>}
        </div>
        {changing && <Candidates own={null} chosen={[refString(ref)]} full={false} what="Choose the metric this page is about." onPick={(r) => { setPrimary?.(r); setChanging(false) }} />}
        {picking && <Candidates own={ref} chosen={extraRefs.map(refString)} full={extraRefs.length >= MAX_COMPARE} onPick={(r) => { toggle(r); setPicking(false) }} />}

        {status.error && <section className="ft-card"><p className="me-note">Could not load: {status.error} <button className="dash-link" onClick={status.retry}>RETRY</button></p></section>}
        {data && !status.loading && withValue.length === 0 && !status.error && <section className="ft-card"><p className="me-note muted">No values in this range.</p></section>}

        {panels.map((panel, pi) => (
          <section key={panel.unit} className="ft-card metric-card">
            {pi === 0 && latest && data && !compared && (
              <div className="metric-latest">
                <span className="metric-latest-v" style={{ color }}>{withUnit(latest.value!)}</span>
                <span className="metric-latest-k">LATEST · {latest.day}</span>
                {latest.direction && <span className="metric-ref">{directionGlyph(latest.direction)} {directionWords(latest.direction)}</span>}
                {latest.refLine && <span className="metric-ref">{latest.refLine}</span>}
              </div>
            )}
            {(compared || panels.length > 1) && (
              <div className="metric-legend">
                {panel.items.map((it) => {
                  const last = [...it.data.points].reverse().find((p) => p.value != null)
                  return <span key={it.key}><b className="metric-swatch" style={{ background: cssColor(it.data.hue) }} />{it.data.label}{last ? <i> {it.data.fmt(last.value!)}{it.data.unit ? ` ${it.data.unit}` : ''}</i> : null}</span>
                })}
                {panel.items[0].data.unit && <span className="metric-unit">{panel.items[0].data.unit}</span>}
              </div>
            )}
            <PanelChart items={panel.items.map((it) => it)} from={from} to={to} focus={focus} ring={ref.kind === 'ring' && panel.items.length === 1} />
            {pi === 0 && data?.alsoInChart && data.alsoInChart.length > 0 && <p className="metric-sub">The phone's chart also draws {data.alsoInChart.join(', ')}; this page shows the first line.</p>}
          </section>
        ))}

        {compared && data && <Pair a={data} b={extras[0].data} extras={extras} lag={lag} setLag={(n) => setParam({ lag: String(n) })} thenKey={params.get('then')} setThen={(k) => setParam({ then: k })} from={from} />}

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
              <thead><tr><th>DAY</th><th>{compared ? data.label.toUpperCase() : 'VALUE'}</th>{extras.map((e) => <th key={e.key}>{e.data.label.toUpperCase()}</th>)}{data.usual && !compared && <th>AGAINST USUAL</th>}<th /></tr></thead>
              <tbody>
                {[...withValue].reverse().slice(0, 120).map((p) => (
                  <tr key={p.day} className={p.day === focus ? 'sel' : ''}>
                    <td className="k">{p.day}</td>
                    <td className="v">{withUnit(p.value!)}</td>
                    {extras.map((e) => { const v = e.data.points.find((q) => q.day === p.day)?.value; return <td key={e.key} className="v">{v == null ? '–' : `${e.data.fmt(v)}${e.data.unit ? ` ${e.data.unit}` : ''}`}</td> })}
                    {data.usual && !compared && <td className="muted">{p.direction ? directionWords(p.direction) : ''}{p.refLine ? ` · ${p.refLine}` : ''}</td>}
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
  )
  return embedded ? content : <Wash hue={`var(--${washFor(ref)})`}>{content}</Wash>
}

const tableOf = (id: string) => seriesById(id)?.table ?? ''

function Tile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return <div className="me-tile"><span className="me-tile-label">{label}</span><span className="me-tile-value">{value}</span>{sub && <span className="me-tile-sub">{sub}</span>}</div>
}

/** One panel: every metric of one axis on the same time axis (the same from and to in every panel, so stacked panels line up). */
function PanelChart({ items, from, to, focus, ring }: { items: Item[]; from: string; to: string; focus: string | null; ring: boolean }) {
  const el = useRef<HTMLDivElement>(null)
  const chart = useChart(el)
  const option = useMemo<EChartsCoreOption>(() => {
    const muted = cssColor('var(--ft-muted)'), line = cssColor('var(--ft-line)'), fg = cssColor('var(--ft-fg)')
    const mono = 'IBM Plex Mono, monospace'
    const first = items[0]?.data
    const seen: string[] = []
    const series: unknown[] = items.map((it, i) => {
      const c = cssColor(it.data.hue)
      // Two metrics of one hue (calories eaten and burned, sleep hours and sleep debt) are told apart by the line, never by a new colour.
      const dup = seen.filter((x) => x === c).length
      seen.push(c)
      return {
        name: it.data.label, type: 'line', data: it.data.points.map((p) => [p.day, p.value]), connectNulls: false, showSymbol: it.data.points.length < 120, symbolSize: 5,
        itemStyle: { color: c }, lineStyle: { color: c, width: 2, type: dup === 0 ? 'solid' : dup === 1 ? 'dashed' : 'dotted' },
        markLine: i === 0 && focus ? { symbol: 'none', silent: true, lineStyle: { color: fg, type: 'dashed' }, label: { color: fg, formatter: focus }, data: [{ xAxis: focus }] } : undefined,
      }
    })
    if (items.length === 1 && first?.usual) series.push({ name: 'Your usual', type: 'line', data: first.points.map((p) => [p.day, p.reference ?? null]), showSymbol: false, lineStyle: { color: muted, width: 1, type: 'dashed' }, itemStyle: { color: muted } })
    return {
      animation: false,
      textStyle: { fontFamily: mono },
      grid: { left: 52, right: 16, top: 16, bottom: 28 },
      tooltip: {
        trigger: 'axis', backgroundColor: cssColor('var(--ft-bg)'), borderColor: line, textStyle: { color: fg, fontFamily: mono, fontSize: 12 },
        valueFormatter: (v: unknown) => (typeof v === 'number' ? `${first?.fmt(v) ?? v}${first?.unit ? ` ${first.unit}` : ''}` : '–'),
      },
      xAxis: { type: 'time', min: from, max: to, axisLabel: { color: muted, fontFamily: mono, fontSize: 10 }, axisLine: { lineStyle: { color: line } } },
      yAxis: { type: 'value', scale: !ring, min: ring ? 0 : undefined, axisLabel: { color: muted, fontFamily: mono, fontSize: 10, formatter: (v: number) => first?.fmt(v) ?? String(v) }, splitLine: { lineStyle: { color: line } } },
      series,
    }
  }, [items, from, to, focus, ring])
  useEffect(() => { chart.current?.setOption(option, true) }, [chart, option])
  const hidden = items.every((it) => it.data.points.every((p) => p.value == null))
  return <div ref={el} className="metric-chart" style={hidden ? { display: 'none' } : undefined} />
}

/** The pair: this metric first, the chosen one a number of days later (the phone's Lab › EXPLORE, lib/compare.ts). */
function Pair({ a, b, extras, lag, setLag, thenKey, setThen, from }: {
  a: MetricData; b: MetricData; extras: Item[]; lag: number; setLag: (n: number) => void; thenKey: string | null; setThen: (k: string) => void; from: string
}) {
  const then = extras.find((e) => e.key === thenKey)?.data ?? b
  const c = useMemo(() => {
    const m = (d: MetricData) => new Map(d.points.filter((p) => p.value != null).map((p) => [p.day, p.value as number]))
    return correlate(m(a), m(then), lag, from)
  }, [a, then, lag, from])
  const el = useRef<HTMLDivElement>(null)
  const chart = useChart(el)
  useEffect(() => {
    if (!c) { chart.current?.clear(); return }
    const muted = cssColor('var(--ft-muted)'), line = cssColor('var(--ft-line)'), fg = cssColor('var(--ft-fg)')
    const mono = 'IBM Plex Mono, monospace'
    chart.current?.setOption({
      animation: false, textStyle: { fontFamily: mono },
      grid: { left: 52, right: 16, top: 12, bottom: 34 },
      tooltip: { trigger: 'item', backgroundColor: cssColor('var(--ft-bg)'), borderColor: line, textStyle: { color: fg, fontFamily: mono, fontSize: 12 }, formatter: (p: { data: [number, number, string] }) => `${p.data[2]}<br/>${a.label}: ${a.fmt(p.data[0])}<br/>${then.label}${lag ? ` +${lag} d` : ''}: ${then.fmt(p.data[1])}` },
      xAxis: { type: 'value', scale: true, name: a.label, nameLocation: 'middle', nameGap: 22, nameTextStyle: { color: muted, fontSize: 10 }, axisLabel: { color: muted, fontSize: 10, formatter: (v: number) => a.fmt(v) }, axisLine: { lineStyle: { color: line } }, splitLine: { lineStyle: { color: line } } },
      yAxis: { type: 'value', scale: true, axisLabel: { color: muted, fontSize: 10, formatter: (v: number) => then.fmt(v) }, splitLine: { lineStyle: { color: line } } },
      series: [{ type: 'scatter', symbolSize: 8, itemStyle: { color: cssColor(then.hue), opacity: 0.7 }, data: c.pairs.map((p) => [p.x, p.y, p.day]) }],
    }, true)
  }, [chart, c, a, then, lag])
  const differ = a.dating !== then.dating
  const dated = (d: MetricData) => (d.dating === 'morning' ? 'the morning it is published on' : 'the day its record falls on')
  return (
    <section className="ft-card metric-pair">
      <h3 className="me-section-title">{a.label.toUpperCase()} → {then.label.toUpperCase()}{lag > 0 ? ` +${lag} D` : ''}</h3>
      <div className="metric-pair-pick">
        {extras.length > 1 && (
          <span className="metric-pair-row"><span className="metric-latest-k">THEN</span>
            {extras.map((e) => <button key={e.key} className="ft-chip" aria-current={e.data === then} onClick={() => setThen(e.key)}>{e.data.label}</button>)}
          </span>
        )}
        <span className="metric-pair-row"><span className="metric-latest-k">DAYS LATER</span>
          {LAGS.map((l) => <button key={l.n} className="ft-chip" aria-current={lag === l.n} onClick={() => setLag(l.n)}>{l.label}</button>)}
        </span>
      </div>
      {c ? <p className={`me-note ${c.clear ? '' : 'muted'}`} data-testid="pair-words">{correlationWords(c)}</p> : <p className="me-note muted">Fewer than 10 days have both in this range.</p>}
      <div ref={el} className="metric-scatter" style={c ? undefined : { display: 'none' }} />
      {c && <p className="metric-sub">Each dot is a day: {a.label} that day across, {then.label}{lag ? ` ${lag} day${lag > 1 ? 's' : ''} later` : ' the same day'} up. The slope is {c.slope.toFixed(2)} per unit.</p>}
      {differ && <p className="metric-sub">{a.label} is dated by {dated(a)}, {then.label} by {dated(then)}: SAME DAY pairs the same calendar date.</p>}
      <p className="metric-sub">{ASSOCIATION_NOTE} The evidence board (Evidence) tests chosen questions with corrections for trend and repeated looks.</p>
    </section>
  )
}

/** What can be put beside the page's metric: the rings, the inputs of the newest kept morning, the series by ring. */
function Candidates({ own, chosen, full, onPick, what }: { own: MetricRef | null; chosen: string[]; full: boolean; onPick: (ref: string) => void; what?: string }) {
  const [inputs, setInputs] = useState<{ key: string; label: string; domain: DomainKey }[]>([])
  useLoadEffect(async (isCurrent) => {
    const r = await supabase.from('score_snapshots').select('day,scores').order('day', { ascending: false }).limit(1).maybeSingle()
    if (r.error || !r.data) return
    const s = (r.data as { scores: { domains: Partial<Record<DomainKey, { raw: { groups: GroupScore[] } }>> } }).scores
    const out: { key: string; label: string; domain: DomainKey }[] = []
    for (const [k, d] of Object.entries(s.domains)) for (const g of d?.raw.groups ?? []) for (const i of g.inputs) out.push({ key: i.spec.key, label: i.spec.label, domain: k as DomainKey })
    if (isCurrent()) setInputs(out)
  }, [])
  const ownKey = own ? refString(own) : ''
  // An input and a series can carry one name (Resting heart rate): they are different pages (the input has your usual and starts on
  // 6 Oct 2026, the series has the whole history), so a clash is told apart in the label.
  const clash = new Set(inputs.map((i) => i.label).filter((l) => SERIES.some((s) => s.label === l)))
  const chip = (ref: string, label: string, hue: string) => (
    <button key={ref} className="ft-chip" aria-current={chosen.includes(ref)} disabled={ref === ownKey || (full && !chosen.includes(ref))} onClick={() => onPick(ref)}>
      <b className="metric-swatch" style={{ background: `var(--${hue})` }} />{label}
    </button>
  )
  return (
    <section className="ft-card metric-picker" aria-label="Compare with">
      <p className="metric-sub">{what ?? `Pick up to ${MAX_COMPARE} to draw on the same days. ${full ? 'Remove one first.' : ''}`}</p>
      <div className="metric-pick-group"><span className="metric-latest-k">SCORES</span>{RING_KEYS.map((k) => chip(k, k.toUpperCase(), k === 'charge' ? 'ft-charge-b' : DOMAINS.find((d) => d.key.toLowerCase() === k)?.hue.slice(6, -1) ?? 'ft-platinum'))}</div>
      {DOMAINS.map((d) => {
        const ins = inputs.filter((i) => i.domain === d.key)
        const ser = SERIES.filter((s) => seriesGroup(s.id) === d.key)
        if (ins.length === 0 && ser.length === 0) return null
        return (
          <div key={d.key} className="metric-pick-group">
            <span className="metric-latest-k">{d.label}</span>
            {ins.map((i) => chip(i.key, clash.has(i.label) ? `${i.label} · SCORE INPUT` : i.label, INPUT_HUE[i.key] ?? 'm-weight-ema'))}
            {ser.map((s) => chip(`series:${s.id}`, clash.has(s.label) ? `${s.label} · SERIES` : s.label, SERIES_HUE[s.id] ?? 'ft-platinum'))}
          </div>
        )
      })}
    </section>
  )
}

/** The address of the retired History page: #/history?metric=<ref>&day=&range= lands on the metric page. */
export function HistoryRedirect() {
  const [params] = useSearchParams()
  const m = params.get('metric') ?? 'charge'
  const ref = parseMetricRef(m)
  return <Navigate to={ref ? metricHref(ref, { day: params.get('day') ?? undefined, range: params.get('range') ?? undefined }).slice(1) : '/today'} replace />
}
