// Draws a Me tab's document (meView.ts): sections of tiles, rows, trend charts, rings and notes, as the phone laid them out.
import { useMemo } from 'react'
import { hueVar, type MeChart, type MeItem, type MeRing, type MeRow, type MeSection, type MeTile, type Point } from './meView'

export function MeSections({ sections, sinceDay }: { sections: MeSection[]; sinceDay: string | null }) {
  return (
    <div className="me-sections">
      {sections.map((s) => (
        <section key={s.title} className="ft-card me-section">
          <h3 className="me-section-title">{s.title}</h3>
          <Items items={s.items} sinceDay={sinceDay} />
        </section>
      ))}
    </div>
  )
}

function Items({ items, sinceDay }: { items: MeItem[]; sinceDay: string | null }) {
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
        if (first.kind === 'tile') return <div key={k} className="me-tiles">{(g as MeTile[]).map((t, j) => <Tile key={j} t={t} sinceDay={sinceDay} />)}</div>
        if (first.kind === 'row') return <ol key={k} className="me-rows">{(g as MeRow[]).map((r, j) => <Row key={j} r={r} />)}</ol>
        if (first.kind === 'chart') return <Chart key={k} c={first} sinceDay={sinceDay} />
        if (first.kind === 'ring') return <Ring key={k} r={first} />
        return <p key={k} className={`me-note ${first.muted ? 'muted' : ''}`}>{first.text}</p>
      })}
    </>
  )
}

const TONE: Record<string, string> = { ok: 'var(--ok)', warn: 'var(--warn)', bad: 'var(--bad)' }

function Tile({ t, sinceDay }: { t: MeTile; sinceDay: string | null }) {
  const pts = (t.series ?? []).filter((p) => !sinceDay || p[0] >= sinceDay)
  return (
    <div className="me-tile" title={t.info}>
      <span className="me-tile-label">{t.label}</span>
      <span className="me-tile-value" style={{ color: t.tone ? TONE[t.tone] : undefined }}>{t.value}{t.unit && <small> {t.unit}</small>}</span>
      {t.sub && <span className="me-tile-sub">{t.sub}</span>}
      {pts.length > 1 && <Spark points={pts} hue={hueVar(t.hue)} />}
    </div>
  )
}

function Row({ r }: { r: MeRow }) {
  return (
    <li className="me-row">
      <span className="me-row-label">{r.label}</span>
      {r.value && <span className="me-row-value" style={{ color: r.tone ? TONE[r.tone] : undefined }}>{r.value}</span>}
      {r.state && <span className={`me-row-state st-${r.state.replace(/\s+/g, '-').toLowerCase()}`}>{r.state}</span>}
      {r.sub && <span className="me-row-sub">{r.sub}</span>}
    </li>
  )
}

function Spark({ points, hue }: { points: Point[]; hue: string }) {
  const d = useMemo(() => path(points, 120, 28, 2), [points])
  return <svg className="me-spark" viewBox="0 0 120 28" preserveAspectRatio="none" aria-hidden="true"><path d={d} fill="none" stroke={hue} strokeWidth="1.6" vectorEffect="non-scaling-stroke" /></svg>
}

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

function Chart({ c, sinceDay }: { c: MeChart; sinceDay: string | null }) {
  const series = c.series.map((s) => ({ ...s, points: s.points.filter((p) => !sinceDay || p[0] >= sinceDay) })).filter((s) => s.points.length > 0)
  const all = series.flatMap((s) => s.points)
  if (all.length < 2) return <p className="me-note muted">{c.caption ?? ''} Not enough readings in this range.</p>
  const e = extent(all)
  const padV = (e.v1 - e.v0) * 0.1 || 1
  const ext = { ...e, v0: e.v0 - padV, v1: e.v1 + padV }
  const W = 600, H = 180
  const fmt = (v: number) => v.toFixed(c.decimals)
  const ticks = [ext.v0 + padV, (ext.v0 + ext.v1) / 2, ext.v1 - padV]
  const yOf = (v: number) => H - 8 - ((v - ext.v0) / (ext.v1 - ext.v0)) * (H - 16)
  const day = (t: number) => new Date(t).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })
  return (
    <figure className="me-chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${c.label}: ${series.map((s) => `${s.name} ${fmt(s.points[s.points.length - 1][1])}`).join(', ')}`}>
        {ticks.map((t, i) => (
          <g key={i}><line x1="44" x2={W} y1={yOf(t)} y2={yOf(t)} className="me-grid" /><text x="40" y={yOf(t) + 4} textAnchor="end" className="me-axis">{fmt(t)}</text></g>
        ))}
        <g transform="translate(46,0)">
          {series.map((s) => <path key={s.name} d={path(s.points, W - 50, H, 8, ext)} fill="none" stroke={hueVar(s.hue)} strokeWidth="2" vectorEffect="non-scaling-stroke" />)}
          {series.map((s) => { const p = s.points[s.points.length - 1]; const x = 8 + ((Date.parse(`${p[0]}T12:00:00Z`) - ext.t0) / (ext.t1 - ext.t0 || 1)) * (W - 50 - 16); return <circle key={s.name} cx={x} cy={yOf(p[1])} r="3.5" fill={hueVar(s.hue)} /> })}
        </g>
      </svg>
      <figcaption className="me-chart-cap">
        <span>{day(ext.t0)} – {day(ext.t1)}{c.unit ? ` · ${c.unit}` : ''}</span>
        {series.length > 1 && <span className="me-legend">{series.map((s) => <span key={s.name}><b style={{ background: hueVar(s.hue) }} />{s.name}</span>)}</span>}
        {c.caption && <span className="me-chart-word">{c.caption}</span>}
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
