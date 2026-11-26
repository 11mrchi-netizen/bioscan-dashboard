// Draws a Me tab's document (meView.ts): sections of tiles, rows, trend charts, rings and notes, as the phone laid them out.
import { useMemo, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { hueVar, type MeBars, type MeChart, type MeItem, type MeRadar, type MeRibbons, type MeRing, type MeRow, type MeSection, type MeTile, type Point } from './meData'
import { meRef, metricHref } from '../lib/metrics'
import { Board } from '../components/layout/Board'

export function MeSections({ sections, sinceDay, tab }: { sections: MeSection[]; sinceDay: string | null; tab: string }) {
  // Layout (Nov 2026): every single reading of the tab goes up front in one band of tiles, each opening its history; the
  // charts, lists and notes stay in their sections on a packed board below. A section left with nothing but its tiles goes.
  const tiles = sections.flatMap((s) => s.items.filter((i): i is MeTile => i.kind === 'tile').map((t) => ({ t, section: s.title })))
  const rest = sections.map((s) => ({ ...s, items: s.items.filter((i) => i.kind !== 'tile') })).filter((s) => s.items.length > 0)
  return (
    <>
      {tiles.length > 0 && (
        <div className="ft-band me-band" aria-label="Readings">
          {tiles.map(({ t, section }, j) => <Tile key={`${section}:${t.label}:${j}`} t={t} sinceDay={sinceDay} tab={tab} section={section} />)}
        </div>
      )}
      {rest.length > 0 && (
        <Board className="me-sections">
          {rest.map((s) => (
            <section key={s.title} className="ft-card me-section">
              <h3 className="me-section-title">{s.title}</h3>
              <Items items={s.items} sinceDay={sinceDay} tab={tab} section={s.title} />
            </section>
          ))}
        </Board>
      )}
    </>
  )
}

function Items({ items, sinceDay, tab, section }: { items: MeItem[]; sinceDay: string | null; tab: string; section: string }) {
  // Tiles side by side, rows as a list, the rest full width, in the order given.
  const groups: MeItem[][] = []
  for (const i of items) {
    const last = groups[groups.length - 1]
    if (last && last[0].kind === i.kind && (i.kind === 'tile' || i.kind === 'row')) last.push(i)
    else groups.push([i])
  }
  return (
    <>
      {groups.map((g, k) => {
        const first = g[0]
        if (first.kind === 'tile') return <div key={k} className="me-tiles">{(g as MeTile[]).map((t, j) => <Tile key={j} t={t} sinceDay={sinceDay} tab={tab} section={section} />)}</div>
        if (first.kind === 'row') return <ol key={k} className="me-rows">{(g as MeRow[]).map((r, j) => <Row key={j} r={r} />)}</ol>
        if (first.kind === 'chart') return <Chart key={k} c={first} sinceDay={sinceDay} tab={tab} section={section} />
        if (first.kind === 'bars') return <Bars key={k} b={first} sinceDay={sinceDay} />
        if (first.kind === 'radar') return <Radar key={k} r={first} />
        if (first.kind === 'ribbons') return <Ribbons key={k} r={first} />
        if (first.kind === 'ring') return <Ring key={k} r={first} />
        return <p key={k} className={`me-note ${first.muted ? 'muted' : ''}`}>{first.text}</p>
      })}
    </>
  )
}

const TONE: Record<string, string> = { ok: 'var(--ok)', warn: 'var(--warn)', bad: 'var(--bad)' }

function Tile({ t, sinceDay, tab, section }: { t: MeTile; sinceDay: string | null; tab: string; section: string }) {
  const pts = (t.series ?? []).filter((p) => !sinceDay || p[0] >= sinceDay)
  // A tile that carries its points opens their history (the metric page); one without has nothing to open.
  const opens = (t.series?.length ?? 0) > 1
  const Box = opens ? 'a' : 'div'
  return (
    <Box className={`me-tile${opens ? ' tappable' : ''}`} title={[section, t.info].filter(Boolean).join(' · ')} {...(opens ? { href: metricHref(meRef(tab, section, t.label)) } : {})}>
      <span className="me-tile-label">{t.label}</span>
      <span className="me-tile-value" style={{ color: t.tone ? TONE[t.tone] : undefined }}>{t.value}{t.unit && <small> {t.unit}</small>}</span>
      {t.sub && <span className="me-tile-sub">{t.sub}</span>}
      {pts.length > 1 && <Spark points={pts} hue={hueVar(t.hue)} unit={t.unit} />}
    </Box>
  )
}

function Row({ r }: { r: MeRow }) {
  return (
    <li className="me-row">
      <span className="me-row-label">{r.hue && <b className="me-swatch" style={{ background: hueVar(r.hue) }} />}{r.label}</span>
      {r.value && <span className="me-row-value" style={{ color: r.tone ? TONE[r.tone] : r.hue ? hueVar(r.hue) : undefined }}>{r.value}</span>}
      {r.state && <span className={`me-row-state st-${r.state.replace(/\s+/g, '-').toLowerCase()}`}>{r.state}</span>}
      {r.sub && <span className="me-row-sub">{r.sub}</span>}
    </li>
  )
}

function Spark({ points, hue, unit }: { points: Point[]; hue: string; unit?: string }) {
  const d = useMemo(() => path(points, 120, 28, 2), [points])
  const e = useMemo(() => extent(points), [points])
  const fx = (p: Point) => (2 + ((dayMs(p[0]) - e.t0) / (e.t1 - e.t0 || 1)) * 116) / 120
  const hover = useHover(points.length, (f) => {
    let best = 0
    points.forEach((p, i) => { if (Math.abs(fx(p) - f) < Math.abs(fx(points[best]) - f)) best = i })
    return best
  })
  const h = hover.at
  const p = h != null ? points[h] : null
  const yOf = (v: number) => 28 - 2 - ((v - e.v0) / (e.v1 - e.v0 || 1)) * 24
  return (
    <span className="me-plot me-spark-plot">
      <svg className="me-spark me-hoverable" viewBox="0 0 120 28" preserveAspectRatio="none" aria-hidden="true" {...hover.handlers}>
        <path d={d} fill="none" stroke={hue} strokeWidth="1.6" vectorEffect="non-scaling-stroke" />
        {p && <circle cx={2 + ((dayMs(p[0]) - e.t0) / (e.t1 - e.t0 || 1)) * 116} cy={yOf(p[1])} r="2.5" fill={hue} />}
      </svg>
      {p && <Readout fx={fx(p)} title={dayLabel(dayMs(p[0]))} lines={[{ hue, value: `${trim(p[1])}${unit ? ` ${unit}` : ''}` }]} />}
    </span>
  )
}

/** A reading as the tile shows it: no more decimals than it needs. */
const trim = (v: number): string => String(Math.round(v * 100) / 100)

const dayLabel = (t: number) => new Date(t).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }).toUpperCase()

function extent(points: Point[]) {
  const ts = points.map((p) => Date.parse(`${p[0]}T12:00:00Z`))
  const vs = points.map((p) => p[1])
  return { t0: Math.min(...ts), t1: Math.max(...ts), v0: Math.min(...vs), v1: Math.max(...vs) }
}

function path(points: Point[], w: number, h: number, pad: number, e = extent(points)): string {
  const dt = e.t1 - e.t0 || 1, dv = e.v1 - e.v0 || 1
  return points.map((p, i) => {
    const x = pad + ((Date.parse(`${p[0]}T12:00:00Z`) - e.t0) / dt) * (w - 2 * pad)
    const y = h - pad - ((p[1] - e.v0) / dv) * (h - 2 * pad)
    return `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`
  }).join('')
}

/** Hover (or arrow keys) over a chart: which of `n` slots the pointer is over, from its x across the drawn width. */
function useHover(n: number, slotAt: (fx: number) => number) {
  const [at, setAt] = useState<number | null>(null)
  const onPointerMove = (e: PointerEvent<SVGSVGElement>) => {
    const r = e.currentTarget.getBoundingClientRect()
    if (r.width > 0) setAt(slotAt((e.clientX - r.left) / r.width))
  }
  const onKeyDown = (e: KeyboardEvent<SVGSVGElement>) => {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
    e.preventDefault()
    setAt((a) => Math.max(0, Math.min(n - 1, a == null ? n - 1 : a + (e.key === 'ArrowLeft' ? -1 : 1))))
  }
  const off = () => setAt(null)
  return { at: at != null && at < n ? at : null, handlers: { onPointerMove, onPointerLeave: off, onBlur: off, onKeyDown, tabIndex: 0 } }
}

/** The readout over a hovered chart: placed at fx (0-1 across the chart), kept inside it. */
function Readout({ fx, title, lines }: { fx: number; title: string; lines: { hue?: string; name?: string; value: string }[] }) {
  const right = fx > 0.6
  return (
    <div className="me-readout" role="status" style={right ? { right: `${(1 - fx) * 100}%`, marginRight: 10 } : { left: `${fx * 100}%`, marginLeft: 10 }}>
      <span className="me-readout-title">{title}</span>
      {lines.map((l, i) => (
        <span key={i} className="me-readout-line">
          {l.name && <span className="me-readout-name">{l.hue && <b style={{ background: l.hue }} />}{l.name}</span>}
          <span className="me-readout-value" style={{ color: l.hue }}>{l.value}</span>
        </span>
      ))}
    </div>
  )
}

const dayMs = (d: string) => Date.parse(`${d}T12:00:00Z`)

function Chart({ c, sinceDay, tab, section }: { c: MeChart; sinceDay: string | null; tab: string; section: string }) {
  const series = c.series.map((s) => ({ ...s, points: s.points.filter((p) => !sinceDay || p[0] >= sinceDay) })).filter((s) => s.points.length > 0)
  const all = series.flatMap((s) => s.points)
  const W = 600, H = 180
  const e = all.length ? extent(all) : { t0: 0, t1: 1, v0: 0, v1: 1 }
  // The days that carry a reading in any series, in order: the hover moves from one to the next.
  const days = [...new Set(all.map((p) => p[0]))].sort()
  const xOf = (t: number) => 46 + 8 + ((t - e.t0) / (e.t1 - e.t0 || 1)) * (W - 50 - 16)
  const dayX = days.map((d) => xOf(dayMs(d)) / W)
  const hover = useHover(days.length, (fx) => {
    let best = 0
    dayX.forEach((x, i) => { if (Math.abs(x - fx) < Math.abs(dayX[best] - fx)) best = i })
    return best
  })
  if (all.length < 2) return <p className="me-note muted">{c.caption ?? ''} Not enough readings in this range.</p>
  const padV = (e.v1 - e.v0) * 0.1 || 1
  const ext = { ...e, v0: e.v0 - padV, v1: e.v1 + padV }
  const fmt = (v: number) => (c.format === 'pace' ? pace(v) : v.toFixed(c.decimals))
  const ticks = [ext.v0 + padV, (ext.v0 + ext.v1) / 2, ext.v1 - padV]
  const yOf = (v: number) => H - 8 - ((v - ext.v0) / (ext.v1 - ext.v0)) * (H - 16)
  const day = (t: number) => new Date(t).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })
  const hd = hover.at != null ? days[hover.at] : null
  const hits = hd ? series.flatMap((s) => { const p = s.points.find((q) => q[0] === hd); return p ? [{ s, v: p[1] }] : [] }) : []
  const unit = c.unit ? ` ${c.unit}` : ''
  return (
    <figure className="me-chart">
      <div className="me-plot">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" className="me-hoverable" {...hover.handlers} aria-label={`${c.label}: ${series.map((s) => `${s.name} ${fmt(s.points[s.points.length - 1][1])}`).join(', ')}. Use the arrow keys to read each day.`}>
          {ticks.map((t, i) => (
            <g key={i}><line x1="44" x2={W} y1={yOf(t)} y2={yOf(t)} className="me-grid" /><text x="40" y={yOf(t) + 4} textAnchor="end" className="me-axis">{fmt(t)}</text></g>
          ))}
          {hd && <line className="me-cursor" x1={xOf(dayMs(hd))} x2={xOf(dayMs(hd))} y1="4" y2={H - 4} />}
          <g transform="translate(46,0)">
            {series.map((s) => <path key={s.name} d={path(s.points, W - 50, H, 8, ext)} fill="none" stroke={hueVar(s.hue)} strokeWidth="2" vectorEffect="non-scaling-stroke" />)}
            {!hd && series.map((s) => { const p = s.points[s.points.length - 1]; return <circle key={s.name} cx={xOf(dayMs(p[0])) - 46} cy={yOf(p[1])} r="3.5" fill={hueVar(s.hue)} /> })}
            {hits.map(({ s, v }) => <circle key={s.name} cx={xOf(dayMs(hd!)) - 46} cy={yOf(v)} r="4.5" fill={hueVar(s.hue)} stroke="var(--ft-bg)" strokeWidth="1.5" />)}
          </g>
        </svg>
        {hd && hits.length > 0 && (
          <Readout fx={dayX[hover.at!]} title={day(dayMs(hd)).toUpperCase()} lines={hits.map(({ s, v }) => ({ hue: hueVar(s.hue), name: series.length > 1 ? s.name : undefined, value: `${fmt(v)}${unit}` }))} />
        )}
      </div>
      <figcaption className="me-chart-cap">
        <span>{day(ext.t0)} – {day(ext.t1)}{c.unit ? ` · ${c.unit}` : ''}</span>
        {series.length > 1 && <span className="me-legend">{series.map((s) => <span key={s.name}><b style={{ background: hueVar(s.hue) }} />{s.name}</span>)}</span>}
        {c.caption && <span className="me-chart-word">{c.caption}</span>}
        <a className="dash-link" href={metricHref(meRef(tab, section, c.label))}>HISTORY →</a>
      </figcaption>
    </figure>
  )
}

function Ring({ r }: { r: MeRing }) {
  const R = 42, C = 2 * Math.PI * R, f = Math.max(0, Math.min(1, r.fraction))
  return (
    <div className="me-ring">
      <svg viewBox="0 0 100 100" width="112" height="112" role="img" aria-label={`${r.label} ${r.value}`}>
        <circle cx="50" cy="50" r={R} fill="none" stroke="var(--ft-line)" strokeWidth="9" />
        {f > 0 && <circle cx="50" cy="50" r={R} fill="none" stroke={hueVar(r.hue)} strokeWidth="9" strokeLinecap="round" strokeDasharray={`${C * f} ${C}`} transform="rotate(-90 50 50)" />}
        <text x="50" y="56" textAnchor="middle" className="ring-value" style={{ fontSize: 20 }}>{r.value}</text>
      </svg>
      <div className="me-ring-lines">{r.lines.map((l, i) => <p key={i} className={i === 0 ? 'lead' : ''}>{l}</p>)}</div>
    </div>
  )
}

/** Minutes per km as m:ss, as the phone's formatPace. */
function pace(minPerKm: number): string {
  const total = Math.round(minPerKm * 60)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

function Bars({ b, sinceDay }: { b: MeBars; sinceDay: string | null }) {
  // The weeks whose Monday is in the range (or whose week reaches into it).
  const keep = b.weeks.map((w) => !sinceDay || addDays(w, 6) >= sinceDay)
  const weeks = b.weeks.filter((_, i) => keep[i])
  const series = b.series.map((s) => ({ ...s, values: s.values.filter((_, i) => keep[i]) }))
  const totals = weeks.map((_, i) => series.reduce((a, s) => a + (s.values[i] ?? 0), 0))
  const top = Math.max(0.5, ...totals)
  const W = 600, H = 160, gap = weeks.length > 30 ? 2 : 4
  const bw = (W - 40 - gap * (weeks.length - 1)) / Math.max(1, weeks.length)
  const sum = totals.reduce((a, x) => a + x, 0)
  const hover = useHover(weeks.length, (fx) => Math.max(0, Math.min(weeks.length - 1, Math.floor((fx * W - 40 + gap / 2) / (bw + gap)))))
  const h = hover.at
  const wc = (w: string) => `W/C ${new Date(`${w}T12:00:00Z`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' }).toUpperCase()}`
  return (
    <figure className="me-chart">
      <div className="me-plot">
        <svg viewBox={`0 0 ${W} ${H}`} role="img" className="me-hoverable" {...hover.handlers} aria-label={`${b.label}: ${sum.toFixed(1)} ${b.unit} over ${weeks.length} weeks. Use the arrow keys to read each week.`}>
          {[0.5, 1].map((f) => <g key={f}><line x1="36" x2={W} y1={H - 14 - (H - 24) * f} y2={H - 14 - (H - 24) * f} className="me-grid" /><text x="32" y={H - 10 - (H - 24) * f} textAnchor="end" className="me-axis">{(top * f).toFixed(1)}</text></g>)}
          {h != null && <rect className="me-cursor-band" x={40 + h * (bw + gap) - gap / 2} y="4" width={bw + gap} height={H - 14} />}
          {weeks.map((w, i) => {
            let y = H - 14
            const x = 40 + i * (bw + gap)
            const partial = b.last_partial && i === weeks.length - 1
            return (
              <g key={w} opacity={h != null && h !== i ? 0.45 : partial ? 0.5 : 1}>
                {series.map((s) => {
                  const bh = ((s.values[i] ?? 0) / top) * (H - 24)
                  if (bh <= 0) return null
                  y -= bh
                  return <rect key={s.name} x={x} y={y} width={bw} height={bh} rx={Math.min(2, bw / 3)} fill={hueVar(s.hue)} />
                })}
              </g>
            )
          })}
        </svg>
        {h != null && (
          <Readout
            fx={(40 + h * (bw + gap) + bw / 2) / W}
            title={`${wc(weeks[h])}${b.last_partial && h === weeks.length - 1 ? ' · SO FAR' : ''}`}
            lines={[
              { value: `${totals[h].toFixed(1)} ${b.unit}`, name: 'ALL' },
              ...series.filter((s) => (s.values[h] ?? 0) > 0).map((s) => ({ hue: hueVar(s.hue), name: s.name, value: `${(s.values[h] ?? 0).toFixed(1)} ${b.unit}` })),
            ]}
          />
        )}
      </div>
      <figcaption className="me-chart-cap">
        <span>{weeks.length} weeks · {sum.toFixed(1)} {b.unit} in all</span>
        <span className="me-legend">{series.filter((s) => s.values.some((v) => v > 0)).map((s) => <span key={s.name}><b style={{ background: hueVar(s.hue) }} />{s.name}</span>)}</span>
      </figcaption>
    </figure>
  )
}

function addDays(day: string, n: number): string {
  const d = new Date(`${day}T12:00:00Z`)
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}

const STATE_HUE: Record<string, string> = { FAVORABLE: 'var(--ok)', OPPORTUNITY: 'var(--warn)', NEUTRAL: 'var(--ft-fg)' }

/** The Healthspan radar: each factor at its score (1-100), the 25 and 50 lines drawn, a dashed spoke for one not assessed. */
function Radar({ r }: { r: MeRadar }) {
  const n = r.axes.length, C = 150, R = 105
  const at = (i: number, v: number) => {
    const a = -Math.PI / 2 + (2 * Math.PI * i) / n
    return [C + Math.cos(a) * R * (v / 100), C + Math.sin(a) * R * (v / 100)] as const
  }
  const ring = (v: number) => r.axes.map((_, i) => at(i, v).join(',')).join(' ')
  const scored = r.axes.map((a, i) => (a.score != null ? at(i, a.score) : null))
  const poly = scored.filter((p): p is readonly [number, number] => p != null).map((p) => p.join(',')).join(' ')
  return (
    <figure className="me-radar">
      <svg viewBox="0 0 300 300" role="img" aria-label={`${r.label}: ${r.axes.map((a) => `${a.label} ${a.score ?? 'not assessed'}`).join(', ')}`}>
        <polygon points={ring(100)} fill="none" className="me-grid" />
        {r.lines.map((l) => <polygon key={l} points={ring(l)} fill="none" stroke="var(--ft-muted)" strokeDasharray={l === 50 ? '0' : '3 3'} strokeOpacity="0.6" />)}
        {r.axes.map((a, i) => { const [x, y] = at(i, 100); return <line key={a.short} x1={C} y1={C} x2={x} y2={y} stroke="var(--ft-line)" strokeDasharray={a.score == null ? '3 4' : undefined} /> })}
        {poly && <polygon points={poly} fill="color-mix(in srgb, var(--ok) 18%, transparent)" stroke="var(--ok)" strokeWidth="1.5" />}
        {r.axes.map((a, i) => scored[i] && <circle key={a.short} cx={scored[i]![0]} cy={scored[i]![1]} r="4" fill={STATE_HUE[a.state] ?? 'var(--ft-fg)'} />)}
        {r.axes.map((a, i) => { const [x, y] = at(i, 122); return <text key={a.short} x={x} y={y + 3} textAnchor="middle" className="me-axis">{a.short}{a.score == null ? ' —' : ` ${a.score}`}</text> })}
      </svg>
    </figure>
  )
}

const DAY_LABEL = (d: string) => new Date(`${d}T12:00:00`).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric' }).toUpperCase()

/** The last days as 24-hour ribbons, newest last, as the phone draws them (each piece of the day in its kind's hue). */
function Ribbons({ r }: { r: MeRibbons }) {
  return (
    <figure className="me-ribbons">
      <div className="me-ribbon-hours"><span /><span className="mono">00</span><span className="mono">06</span><span className="mono">12</span><span className="mono">18</span><span className="mono">24</span></div>
      {r.days.map((d) => (
        <a key={d.date} className="me-ribbon-day" href={`#/log?day=${d.date}`} title={`Open the log of ${d.date}`}>
          <span className="mono">{DAY_LABEL(d.date)}</span>
          <svg viewBox={`0 0 ${r.slots} 1`} preserveAspectRatio="none" role="img" aria-label={d.date}>
            {d.spans.map(([f, t, h], i) => <rect key={i} x={f} y="0" width={Math.max(0, t - f - 0.15)} height="1" fill={hueVar(h)} opacity={h === 'ft-line' ? 0.5 : 1} />)}
          </svg>
        </a>
      ))}
      <figcaption>
        <span className="me-legend">{r.legend.map((l) => <span key={l.label}><b style={{ background: hueVar(l.hue) }} />{l.label.toUpperCase()}{l.value ? ` ${l.value.toUpperCase()}` : ''}</span>)}</span>
        {r.caption && <p className="me-note muted">{r.caption}</p>}
      </figcaption>
    </figure>
  )
}
