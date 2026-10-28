// Explore workspace (analysis hub phase B): the one general charting surface. Any series of the contract file over the
// selected range, at a resolution that follows the range (daily to 90 days, weekly or daily by series to a year, monthly
// beyond; seriesContract.ts grainFor) or one chosen by hand. Series with the same unit share a panel.
//
// Drill-down: clicking a week or month opens its days (the window goes into the URL, so back returns); clicking a day
// opens the Inspector with the rows behind it. Every point keeps its aggregation facts, a point with less than half of
// its days observed is drawn faded, and a period without an observation is a gap, never 0.
//
// URL: #/explore?series=hrv,rhr&start=2026-10-01&end=2026-10-31&grain=week  (start/end: a window inside the range; the
// link convention of lib/links.ts for period references).

import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { LoadBanner } from '../components/shared/LoadBanner'
import { useInspect, useRange } from '../lib/analysisStore'
import { useChart } from '../lib/useChart'
import { SERIES, seriesById } from '../lib/schema'
import { chartSpecFor } from '../lib/chartSpec'
import { cacheCounters, loadDaysMany, queryStats } from '../lib/seriesQuery'
import { isAbort } from '../lib/rangeCache'
import { PALETTE } from '../lib/palette'
import {
  aggregatePeriods,
  daySpan,
  grainFor,
  isIsoDate,
  type DayValue,
  type Grain,
  type PeriodPoint,
  type SeriesContract,
} from '../../../supabase/functions/_shared/analysis-core/seriesContract.ts'
import './Explore.css'

const DEFAULT_SERIES = ['hrv', 'rhr', 'sleep_hours', 'steps']
const COLORS = ['#10B981', '#60A5FA', '#F59E0B', '#A78BFA', '#F43F5E', '#22D3EE', '#818CF8']
type GrainChoice = 'auto' | Grain

interface Loaded {
  days: Record<string, DayValue[]>
  stats: { ms: number; requests: number; rows: number; hits: number; misses: number }
}

function label(p: PeriodPoint): string {
  return p.grain === 'month' ? p.start.slice(0, 7) : p.start.slice(5)
}

function fmt(v: number | null): string {
  if (v === null) return '—'
  const a = Math.abs(v)
  return a >= 1000 ? Math.round(v).toLocaleString() : a >= 100 ? v.toFixed(0) : a >= 10 ? v.toFixed(1) : v.toFixed(2)
}

const METHOD = { mean: 'avg', sum: 'total', last: 'last' } as const
const DAY_METHOD = { mean: 'daily average', sum: 'daily total', last: 'daily value' } as const

export function Explore() {
  const [params, setParams] = useSearchParams()
  const globalRange = useRange()
  const inspect = useInspect()

  const selected = useMemo(() => {
    const raw = params.get('series')
    const ids = (raw ? raw.split(',') : DEFAULT_SERIES).filter(id => seriesById(id))
    return [...new Set(ids)]
  }, [params])
  const pStart = params.get('start')
  const pEnd = params.get('end')
  const windowRange = isIsoDate(pStart) && isIsoDate(pEnd) && pStart <= pEnd ? { start: pStart, end: pEnd } : null
  const range = windowRange ?? globalRange
  const grainChoice = (['day', 'week', 'month'].includes(params.get('grain') ?? '') ? params.get('grain') : 'auto') as GrainChoice

  const setParam = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    for (const [k, v] of Object.entries(patch)) {
      if (v === null) next.delete(k)
      else next.set(k, v)
    }
    setParams(next)
  }

  const toggleSeries = (id: string) => {
    const next = selected.includes(id) ? selected.filter(s => s !== id) : [...selected, id]
    setParam({ series: next.join(',') })
  }

  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const ac = new AbortController()
    const series = selected.map(id => seriesById(id)!).filter(Boolean)
    const before = { ...queryStats, ...cacheCounters() }
    const t0 = performance.now()
    setBusy(true)
    loadDaysMany(series, range, ac.signal)
      .then(results => {
        const entries = series.map((s, i) => [s.id, results[i]] as const)
        if (ac.signal.aborted) return
        const after = { ...queryStats, ...cacheCounters() }
        setLoaded({
          days: Object.fromEntries(entries),
          stats: {
            ms: Math.round(performance.now() - t0),
            requests: after.requests - before.requests,
            rows: after.rows - before.rows,
            hits: after.hits - before.hits,
            misses: after.misses - before.misses,
          },
        })
        setError(null)
        setBusy(false)
      })
      .catch((e: unknown) => {
        if (isAbort(e) || ac.signal.aborted) return
        setError(e instanceof Error ? e.message : String(e))
        setBusy(false)
      })
    return () => ac.abort()
  }, [selected.join(','), range.start, range.end, attempt]) // eslint-disable-line react-hooks/exhaustive-deps

  // One panel per unit, each series aggregated to the grain.
  const panels = useMemo(() => {
    if (!loaded) return []
    const byUnit = new Map<string, { series: SeriesContract; grain: Grain; periods: PeriodPoint[] }[]>()
    for (const id of selected) {
      const s = seriesById(id)
      const days = loaded.days[id]
      if (!s || !days) continue
      const grain = grainChoice === 'auto' ? grainFor(range.start, range.end, s) : grainChoice
      const periods = aggregatePeriods(days, range.start, range.end, grain, s.period)
      byUnit.set(s.unit, [...(byUnit.get(s.unit) ?? []), { series: s, grain, periods }])
    }
    return [...byUnit.entries()].map(([unit, items]) => ({ unit, items }))
  }, [loaded, selected, grainChoice, range.start, range.end])

  const openPoint = (s: SeriesContract, p: PeriodPoint) => {
    if (p.grain !== 'day') {
      setParam({ start: p.start, end: p.end, grain: null })
      return
    }
    inspect({
      kind: 'series_point', seriesId: s.id, start: p.start, end: p.end, grain: p.grain, method: p.method, value: p.value,
      daysObserved: p.daysObserved, days: p.days, samples: p.samples, min: p.min, max: p.max, sources: p.sources, clipped: p.clipped,
    })
  }

  const groups = useMemo(() => {
    const m = new Map<string, SeriesContract[]>()
    for (const s of SERIES) m.set(s.table, [...(m.get(s.table) ?? []), s])
    return [...m.entries()]
  }, [])

  const autoGrain = selected.length ? grainFor(range.start, range.end, seriesById(selected[0])!) : 'day'

  return (
    <div className="ex">
      <div className="ex-header">
        <h2 className="ex-title">Explore</h2>
        <div className="ex-crumbs mono">
          <span className={windowRange ? 'ex-crumb-link' : ''} onClick={() => windowRange && setParam({ start: null, end: null })}>
            RANGE {globalRange.start} → {globalRange.end}
          </span>
          {windowRange && <span> › {windowRange.start} → {windowRange.end} ({daySpan(windowRange.start, windowRange.end)} d) <button className="ex-x mono" onClick={() => setParam({ start: null, end: null })} aria-label="Back to the full range">✕</button></span>}
        </div>
        <div className="ex-grains mono" role="group" aria-label="Resolution">
          {(['auto', 'day', 'week', 'month'] as const).map(g => (
            <button key={g} className={`ex-grain ${grainChoice === g ? 'on' : ''}`} onClick={() => setParam({ grain: g === 'auto' ? null : g })}>
              {g === 'auto' ? `AUTO · ${autoGrain.toUpperCase()}` : g.toUpperCase()}
            </button>
          ))}
        </div>
      </div>

      <details className="ex-picker card" open={selected.length === 0}>
        <summary className="mono">SERIES · {selected.length} selected</summary>
        <div className="ex-groups">
          {groups.map(([table, list]) => (
            <div key={table} className="ex-group">
              <div className="ex-group-label mono">{table}</div>
              <div className="ex-chips">
                {list.map(s => (
                  <button key={s.id} className={`ex-chip ${selected.includes(s.id) ? 'on' : ''}`} onClick={() => toggleSeries(s.id)} title={`${s.table}.${s.column} · ${s.unit}`}>
                    {s.label}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      </details>

      {error && <LoadBanner error={error} onRetry={() => setAttempt(a => a + 1)} />}
      {!loaded && !error && <div className="state-loading mono">LOADING…</div>}
      {selected.length === 0 && <div className="state-empty mono">PICK A SERIES</div>}

      {panels.map(panel => (
        <div key={panel.unit} className={`card ex-panel ${busy ? 'ex-busy' : ''}`}>
          <div className="ex-panel-head mono">
            {panel.items.map((it, i) => (
              <span key={it.series.id} className="ex-legend">
                <i style={{ background: COLORS[selected.indexOf(it.series.id) % COLORS.length] ?? COLORS[i] }} />
                {it.series.label} · {it.grain === 'day' ? DAY_METHOD[it.series.day] : `${METHOD[it.series.period]} per ${it.grain}`}
              </span>
            ))}
            <span className="ex-unit">{panel.unit}</span>
          </div>
          <PeriodChart items={panel.items} colorOf={id => COLORS[selected.indexOf(id) % COLORS.length]} onPoint={openPoint} />
        </div>
      ))}

      {loaded && (
        <div className="ex-perf mono">
          {loaded.stats.requests} requests · {loaded.stats.rows.toLocaleString()} rows · {loaded.stats.hits} cached · {loaded.stats.misses} loaded · {loaded.stats.ms} ms
          {' · '}faded: under half the days observed · gaps: no observation
        </div>
      )}
    </div>
  )
}

function PeriodChart({ items, colorOf, onPoint }: {
  items: { series: SeriesContract; grain: Grain; periods: PeriodPoint[] }[]
  colorOf: (id: string) => string
  onPoint: (s: SeriesContract, p: PeriodPoint) => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  const chart = useChart(ref)
  const latest = useRef({ items, onPoint })
  latest.current = { items, onPoint }

  useEffect(() => {
    const c = chart.current
    if (!c) return
    const axis = items[0]?.periods ?? []
    const anyBar = items.some(it => chartSpecFor(it.series).kind === 'bar')
    c.setOption({
      animation: false,
      grid: { left: 52, right: 16, top: 12, bottom: 28 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: PALETTE.surface,
        borderColor: PALETTE.border,
        textStyle: { color: PALETTE.text, fontSize: 11 },
        formatter: (ps: { seriesIndex: number; dataIndex: number }[]) => {
          const lines = ps.map(p => {
            const it = latest.current.items[p.seriesIndex]
            const pt = it?.periods[p.dataIndex]
            if (!it || !pt) return ''
            const head = `${pt.start}${pt.end !== pt.start ? ` → ${pt.end}` : ''}${pt.clipped ? ' (partial)' : ''}`
            const facts = pt.value === null
              ? 'no observation'
              : pt.grain === 'day'
                ? `${pt.samples} row${pt.samples === 1 ? '' : 's'}`
                : `${METHOD[pt.method]} of ${pt.daysObserved}/${pt.days} days · ${pt.samples} rows · ${fmt(pt.min)}–${fmt(pt.max)}`
            return `<span style="color:${PALETTE.textMuted}">${head}</span><br/><b>${it.series.label}: ${fmt(pt.value)} ${it.series.unit}</b><br/><span style="color:${PALETTE.textMuted}">${facts}</span>`
          })
          return lines.filter(Boolean).join('<br/>')
        },
      },
      xAxis: {
        type: 'category',
        data: axis.map(label),
        axisLine: { lineStyle: { color: PALETTE.border } },
        axisLabel: { color: PALETTE.textMuted, fontSize: 10 },
      },
      yAxis: {
        type: 'value',
        scale: !anyBar, // bars are amounts: they start at zero
        splitLine: { lineStyle: { color: PALETTE.border } },
        axisLabel: { color: PALETTE.textMuted, fontSize: 10 },
      },
      series: items.map(it => {
        const bar = chartSpecFor(it.series).kind === 'bar'
        const color = colorOf(it.series.id)
        return {
          name: it.series.label,
          type: bar ? 'bar' : 'line',
          connectNulls: false,
          showSymbol: it.periods.length <= 120,
          symbolSize: 5,
          lineStyle: { width: 1.5, color },
          itemStyle: { color },
          barMaxWidth: 14,
          data: it.periods.map(p => ({ value: p.value, itemStyle: { color, opacity: p.value !== null && p.coverage < 0.5 ? 0.35 : 1 } })),
        }
      }),
    }, true)
  }, [chart, items, colorOf])

  // A click anywhere in a column opens that period: the series whose value is drawn nearest the pointer.
  useEffect(() => {
    const c = chart.current
    if (!c) return
    const zr = c.getZr()
    const onClick = (e: { offsetX: number; offsetY: number }) => {
      if (!c.containPixel({ gridIndex: 0 }, [e.offsetX, e.offsetY])) return
      const [col] = c.convertFromPixel({ gridIndex: 0 }, [e.offsetX, e.offsetY]) as number[]
      const { items: its, onPoint: open } = latest.current
      let best: { it: typeof its[number]; pt: PeriodPoint; d: number } | null = null
      for (const it of its) {
        const pt = it.periods[Math.round(col)]
        if (!pt) continue
        const y = pt.value === null ? null : (c.convertToPixel({ gridIndex: 0 }, [Math.round(col), pt.value]) as number[])[1]
        const d = y === null ? Number.POSITIVE_INFINITY : Math.abs(y - e.offsetY)
        if (!best || d < best.d) best = { it, pt, d }
      }
      if (best) open(best.it.series, best.pt)
    }
    zr.on('click', onClick)
    return () => { zr.off('click', onClick) }
  }, [chart])

  return <div ref={ref} className="ex-chart" />
}

export default Explore
