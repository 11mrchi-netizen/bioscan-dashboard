// METRIC HISTORY (web transition plan, phase 2): one score or one of its inputs over any range, where the Dashboard's rings
// and overlays land ("OPEN IN CONSOLE"). CHARGE and the five rings come from score_history (since Mar 2026), each input with
// its usual range from the kept mornings (score_snapshots, since 6 Oct 2026), both written by the phone and never
// recomputed here. A day opens its log, or the Dashboard's Today for that day.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { EChartsCoreOption } from 'echarts/core'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { useChart } from '../lib/useChart'
import { localDaysAgo, localToday } from '../lib/dates'
import { dashboardHref } from '../lib/surfaces'
import { PALETTE } from '../lib/palette'
import { directionWords, type Direction, type DomainKey, formatInputValue, type GroupScore, referenceLine } from '../lib/scoreText'
import './PlanBlock.css'
import './History.css'

const RING_HEX: Record<string, string> = { charge: '#19c7e0', sleep: '#8b7cff', recovery: '#2fd6c3', load: '#ff8a5b', fuel: '#8ce06b', balance: '#ff6ec7' }
const RINGS = ['charge', 'recovery', 'sleep', 'load', 'fuel', 'balance'] as const

/** The ring an input belongs to, from its key's prefix (domain/score/ScoreDomains.kt). */
function domainOf(key: string): DomainKey | null {
  const p = key.split('.')[0]
  return ({ sleep: 'SLEEP', recovery: 'RECOVERY', load: 'LOAD', fuel: 'FUEL', gut: 'FUEL', ans: 'BALANCE', mind: 'BALANCE', rhythm: 'BALANCE' } as Record<string, DomainKey>)[p] ?? null
}

const RANGES = [{ id: '30', label: '30 DAYS', days: 30 }, { id: '90', label: '90 DAYS', days: 90 }, { id: '365', label: '1 YEAR', days: 365 }, { id: 'all', label: 'ALL', days: 4000 }]

interface Point { day: string; value: number | null; reference?: number | null; refLine?: string | null; direction?: Direction }

export default function History() {
  const [params, setParams] = useSearchParams()
  const metric = params.get('metric') ?? 'charge'
  const focus = params.get('day')
  const range = params.get('range') ?? (focus ? '90' : '90')
  const isRing = (RINGS as readonly string[]).includes(metric)
  const domain = isRing ? null : domainOf(metric)
  const from = localDaysAgo((RANGES.find((r) => r.id === range) ?? RANGES[1]).days - 1)
  const to = localToday()
  const [points, setPoints] = useState<Point[]>([])
  const [inputs, setInputs] = useState<{ key: string; label: string; domain: DomainKey }[]>([])
  const [label, setLabel] = useState(metric)

  // The inputs on offer: the newest kept morning's, by ring.
  useLoadEffect(async (isCurrent) => {
    const r = await supabase.from('score_snapshots').select('day,scores').order('day', { ascending: false }).limit(1).maybeSingle()
    if (r.error || !r.data) return
    const s = (r.data as { scores: { domains: Partial<Record<DomainKey, { raw: { groups: GroupScore[] } }>> } }).scores
    const out: { key: string; label: string; domain: DomainKey }[] = []
    for (const [k, d] of Object.entries(s.domains)) for (const g of d?.raw.groups ?? []) for (const i of g.inputs) out.push({ key: i.spec.key, label: i.spec.label, domain: k as DomainKey })
    if (isCurrent()) setInputs(out)
  }, [])

  const status = useLoadEffect(async (isCurrent) => {
    if (isRing) {
      const r = await supabase.from('score_history').select(`day,${metric}`).gte('day', from).lte('day', to).order('day')
      if (r.error) throw new Error(`score_history: ${r.error.message}`)
      if (!isCurrent()) return
      setPoints(((r.data ?? []) as unknown as Record<string, number | string | null>[]).map((x) => ({ day: x.day as string, value: x[metric] as number | null })))
      setLabel(metric.toUpperCase())
      return
    }
    if (!domain) throw new Error(`Unknown metric "${metric}"`)
    const r = await supabase.from('score_snapshots').select(`day,groups:scores->domains->${domain}->raw->groups`).gte('day', from).lte('day', to).order('day')
    if (r.error) throw new Error(`score_snapshots: ${r.error.message}`)
    if (!isCurrent()) return
    let name = metric
    const pts = ((r.data ?? []) as { day: string; groups: GroupScore[] | null }[]).map((x) => {
      const i = (x.groups ?? []).flatMap((g) => g.inputs).find((y) => y.spec.key === metric)
      if (i) name = i.spec.label
      return { day: x.day, value: i?.value ?? null, reference: i?.reference ?? null, refLine: i ? referenceLine(i) : null, direction: i?.direction }
    })
    setPoints(pts)
    setLabel(name)
  }, [metric, from])

  const fmt = useCallback((v: number) => (isRing ? String(Math.round(v)) : formatInputValue(metric, v)), [isRing, metric])
  const color = RING_HEX[isRing ? metric : (domain ?? 'charge').toLowerCase()] ?? PALETTE.emerald
  const withValue = points.filter((p) => p.value != null)
  const latest = withValue[withValue.length - 1]
  const option = useMemo<EChartsCoreOption>(() => ({
    grid: { left: 56, right: 20, top: 24, bottom: 32 },
    tooltip: { trigger: 'axis', valueFormatter: (v: unknown) => (typeof v === 'number' ? fmt(v) : '–') },
    xAxis: { type: 'time', axisLabel: { color: PALETTE.textMuted }, axisLine: { lineStyle: { color: PALETTE.border } } },
    yAxis: { type: 'value', scale: !isRing, min: isRing ? 0 : undefined, axisLabel: { color: PALETTE.textMuted, formatter: (v: number) => fmt(v) }, splitLine: { lineStyle: { color: PALETTE.border } } },
    series: [
      { name: label, type: 'line', data: points.map((p) => [p.day, p.value]), connectNulls: false, showSymbol: points.length < 120, symbolSize: 5, itemStyle: { color }, lineStyle: { color, width: 2 },
        markLine: focus ? { symbol: 'none', silent: true, lineStyle: { color: PALETTE.text, type: 'dashed' }, label: { color: PALETTE.text, formatter: focus }, data: [{ xAxis: focus }] } : undefined },
      ...(!isRing ? [{ name: 'Your usual', type: 'line', data: points.map((p) => [p.day, p.reference ?? null]), showSymbol: false, lineStyle: { color: PALETTE.textMuted, width: 1, type: 'dashed' }, itemStyle: { color: PALETTE.textMuted } }] : []),
    ],
  }), [points, label, color, focus, isRing, fmt])

  const set = (k: string, v: string | null) => { const n = new URLSearchParams(params); if (v == null) n.delete(k); else n.set(k, v); setParams(n) }
  const byDomain = (d: DomainKey) => inputs.filter((i) => i.domain === d)

  return (
    <div className="hist">
      <header className="hist-head">
        <div>
          <h2 className="hist-title mono">{label}</h2>
          <p className="hist-sub">{isRing ? 'Score per morning, as the phone saved it (from Mar 2026).' : 'Value per morning against your usual, as the phone scored it (from 6 Oct 2026).'}</p>
        </div>
        <label className="hist-pick">
          <span className="mono">METRIC</span>
          <select className="pb-input" value={metric} onChange={(e) => set('metric', e.target.value)}>
            <optgroup label="Scores">{RINGS.map((r) => <option key={r} value={r}>{r.toUpperCase()}</option>)}</optgroup>
            {(['RECOVERY', 'SLEEP', 'LOAD', 'FUEL', 'BALANCE'] as DomainKey[]).map((d) => byDomain(d).length > 0 && (
              <optgroup key={d} label={`${d} inputs`}>{byDomain(d).map((i) => <option key={i.key} value={i.key}>{i.label}</option>)}</optgroup>
            ))}
            {!isRing && !inputs.some((i) => i.key === metric) && <option value={metric}>{label}</option>}
          </select>
        </label>
        <div className="pb-chips">
          {RANGES.map((r) => <button key={r.id} className={`pb-chip ${range === r.id ? 'on' : ''}`} onClick={() => set('range', r.id)}>{r.label}</button>)}
        </div>
      </header>

      {status.error && <p className="pb-warn">Could not load: {status.error}</p>}
      {!status.loading && withValue.length === 0 && !status.error && <p className="state-empty">NO VALUES IN THIS RANGE.</p>}
      <section className="hist-chart-card">
        {latest && <div className="hist-latest"><span className="hist-latest-v">{fmt(latest.value!)}</span><span className="mono">LATEST · {latest.day}</span>{latest.refLine && <span className="hist-ref">{latest.refLine}</span>}</div>}
        <Chart option={option} hidden={withValue.length === 0} />
      </section>

      <section className="hist-days">
        <table className="pb-table">
          <thead><tr><th>DAY</th><th>{isRing ? 'SCORE' : 'VALUE'}</th>{!isRing && <th>AGAINST USUAL</th>}<th /></tr></thead>
          <tbody>
            {[...withValue].reverse().slice(0, 120).map((p) => (
              <tr key={p.day} className={p.day === focus ? 'pb-sel' : ''}>
                <td className="mono">{p.day}</td>
                <td className="mono">{fmt(p.value!)}</td>
                {!isRing && <td className="pb-muted">{p.direction ? directionWords(p.direction) : ''}{p.refLine ? ` · ${p.refLine}` : ''}</td>}
                <td className="hist-links">
                  <a href={`#/log?day=${p.day}`}>DAY LOG</a>
                  <a href={dashboardHref(`#/today?day=${p.day}`)}>TODAY</a>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  )
}

function Chart({ option, hidden }: { option: EChartsCoreOption; hidden: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  const chart = useChart(ref)
  useEffect(() => { chart.current?.setOption(option, true) }, [chart, option])
  return <div ref={ref} className="hist-chart" style={hidden ? { display: 'none' } : undefined} />
}
