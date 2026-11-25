// The parts the views of Me › INTIMACY share: a canvas chart, tiles, share bars, the discreet name, and the encounter panel
// (read one encounter, fill in its tags and rating).
import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { useChart } from '../lib/useChart'
import type { EChartsCoreOption } from '../lib/echarts'
import { cssColor, PALETTE } from '../lib/palette'
import { metricHref } from '../lib/metrics'
import { type Encounter, initials, type Person, TAG_FIELDS, type TagKey, wallClock, withSaved } from '../lib/intimacy'
import { updateEncounter } from '../lib/intimacyData'

export const SELF_Q = 'Self-quantification only: counts and your own ratings, with no clinical meaning implied.'
export const tok = (name: string) => cssColor(`var(--${name})`)
export const HUE = { get partnered() { return tok('m-sex-partnered') }, get solo() { return tok('m-sex-solo') } }

const FULL = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
const SHORT = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' })
export const fullDate = (d: string | null | undefined) => (d ? FULL.format(new Date(`${d.slice(0, 10)}T00:00:00Z`)) : '–')
export const shortDate = (d: string) => SHORT.format(new Date(`${d.slice(0, 10)}T00:00:00Z`))
export const f1 = (v: number | null | undefined) => (v == null || !Number.isFinite(v) ? '–' : String(Number(v.toFixed(1))))
export const signed = (v: number) => `${v > 0 ? '+' : v < 0 ? '−' : '±'}${f1(Math.abs(v))}`
export const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`
export function ago(day: string, today: string): string {
  const d = Math.round((Date.parse(`${today}T00:00:00Z`) - Date.parse(`${day}T00:00:00Z`)) / 864e5)
  return d === 0 ? 'today' : d === 1 ? 'yesterday' : `${d} days ago`
}

// ---- discreet mode: names as initials, notes folded, everywhere on the page ----

interface Names { discreet: boolean; people: Map<number, Person> }
export const NamesContext = createContext<Names>({ discreet: true, people: new Map() })
export function useName(): (id: number | null | undefined) => string {
  const { discreet, people } = useContext(NamesContext)
  return (id) => {
    if (id == null) return 'no partner'
    const p = people.get(id)
    if (!p) return `#${id}`
    return discreet ? initials(p.name) : p.name
  }
}
export function useDiscreet(): boolean { return useContext(NamesContext).discreet }

/** A note: folded in discreet mode until asked for. */
export function Note({ text }: { text: string | null | undefined }) {
  const discreet = useDiscreet()
  const [open, setOpen] = useState(false)
  if (!text?.trim()) return null
  if (discreet && !open) return <button className="it-link mono" onClick={() => setOpen(true)}>SHOW NOTE</button>
  return <p className="me-note it-notes">{text}</p>
}

// ---- layout ----

export function Card({ title, aside, info, wide, children }: { title: string; aside?: ReactNode; info?: string; wide?: boolean; children: ReactNode }) {
  return (
    <section className={`ft-card it-card${wide ? ' wide' : ''}`}>
      <h3 className="it-card-title mono"><span>{title}</span>{aside != null && <span className="it-card-aside">{aside}</span>}</h3>
      {info && <p className="it-info">{info}</p>}
      {children}
    </section>
  )
}

export interface Tile { label: string; value: string; sub?: string | null; hue?: string; href?: string }
/** Tiles; only one with a metric page behind it is a link (nothing looks tappable that leads nowhere). */
export function Tiles({ tiles }: { tiles: Tile[] }) {
  return (
    <div className="me-tiles">
      {tiles.map((t) => {
        const body = (
          <>
            <span className="me-tile-label">{t.label}</span>
            <span className="me-tile-value" style={t.hue ? { color: `var(--${t.hue})` } : undefined}>{t.value}</span>
            {t.sub && <span className="me-tile-sub">{t.sub}</span>}
          </>
        )
        return t.href
          ? <a key={t.label} className="me-tile it-tile-link" href={t.href}>{body}<span className="it-chev" aria-hidden>›</span></a>
          : <div key={t.label} className="me-tile">{body}</div>
      })}
    </div>
  )
}

/** Horizontal share bars: a count per label against the largest. */
export function ShareBars({ rows, hue, total, onPick }: { rows: [string, number][]; hue?: string; total?: number; onPick?: (label: string) => void }) {
  const max = Math.max(1, ...rows.map((r) => r[1]))
  if (!rows.length) return <p className="it-empty">Nothing logged.</p>
  return (
    <ul className="it-bars">
      {rows.map(([label, n]) => (
        <li key={label}>
          {onPick ? <button className="it-bar-label it-link" onClick={() => onPick(label)}>{label}</button> : <span className="it-bar-label">{label}</span>}
          <span className="it-bar-track"><span className="it-bar-fill" style={{ width: `${(n / max) * 100}%`, background: hue ? `var(--${hue})` : 'var(--ft-platinum)' }} /></span>
          <span className="it-bar-n">{n}{total ? <span className="it-dim"> · {Math.round((n / total) * 100)}%</span> : null}</span>
        </li>
      ))}
    </ul>
  )
}

export function Rows({ rows }: { rows: { label: ReactNode; value: ReactNode; sub?: ReactNode; tone?: 'ok' | 'warn' | 'bad'; href?: string }[] }) {
  return (
    <ul className="me-rows">
      {rows.map((r, i) => (
        <li key={i} className="me-row">
          <span className="me-row-label">{r.href ? <a className="it-link" href={r.href}>{r.label} ›</a> : r.label}</span>
          <span className="me-row-value" style={r.tone ? { color: `var(--${r.tone})` } : undefined}>{r.value}</span>
          {r.sub && <span className="me-row-sub">{r.sub}</span>}
        </li>
      ))}
    </ul>
  )
}

export function Chart({ option, height = 220, onPick, label }: { option: EChartsCoreOption; height?: number; onPick?: (v: string) => void; label: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const chart = useChart(ref)
  useEffect(() => { chart.current?.setOption(option, true) }, [chart, option])
  useEffect(() => {
    const c = chart.current
    if (!c || !onPick) return
    const h = (p: { value?: unknown; name?: string }) => { const v = Array.isArray(p.value) ? p.value[0] : p.name; if (typeof v === 'string') onPick(v) }
    c.on('click', h)
    return () => { c.off('click', h) }
  }, [chart, onPick])
  return <div ref={ref} role="img" aria-label={label} style={{ width: '100%', height }} />
}

export const AXES = {
  grid: { left: 40, right: 12, top: 30, bottom: 28 },
  tooltip: { trigger: 'axis' },
  legend: { top: 0, textStyle: { color: PALETTE.text } },
}
export const valueAxis = (name = ''): EChartsCoreOption => ({ type: 'value', name, minInterval: 1, nameTextStyle: { color: PALETTE.textMuted }, axisLabel: { color: PALETTE.textMuted }, splitLine: { lineStyle: { color: PALETTE.border } } })
export const catAxis = (data: string[]): EChartsCoreOption => ({ type: 'category', data, axisLabel: { color: PALETTE.textMuted }, axisLine: { lineStyle: { color: PALETTE.border } } })
export const timeAxis = (): EChartsCoreOption => ({ type: 'time', axisLabel: { color: PALETTE.textMuted }, axisLine: { lineStyle: { color: PALETTE.border } } })

/** A metric page link for a number (CLAUDE.md: a metric that looks tappable opens its history). */
export const metricLink = (ref: string) => metricHref(ref)

// ---- the encounter panel ----

const RATINGS = [1, 2, 3, 4, 5]

/** One encounter, read in full; its tags and your rating can be filled in (only those columns are written). */
export function EncounterPanel({ e, places, onClose, onSaved }: { e: Encounter; places: Map<string, string>; onClose: () => void; onSaved: () => void }) {
  const name = useName()
  const [editing, setEditing] = useState(false)
  const [tags, setTags] = useState<Record<TagKey, string[]>>(() => Object.fromEntries(TAG_FIELDS.map((f) => [f.key, [...(e[f.key] ?? [])]])) as Record<TagKey, string[]>)
  const [rating, setRating] = useState<number | null>(e.my_rating)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    const h = (ev: KeyboardEvent) => { if (ev.key === 'Escape') onClose() }
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [onClose])
  const time = wallClock(e.occurred_at)
  const toggle = (k: TagKey, v: string) => setTags((t) => ({ ...t, [k]: t[k].includes(v) ? t[k].filter((x) => x !== v) : [...t[k], v] }))
  async function save() {
    setSaving(true); setError(null)
    try {
      const patch = Object.fromEntries(TAG_FIELDS.map((f) => [f.key, tags[f.key].length ? tags[f.key] : null]))
      await updateEncounter(e.id, { ...patch, my_rating: rating })
      setEditing(false); onSaved()
    } catch (err) { setError(err instanceof Error ? err.message : String(err)) } finally { setSaving(false) }
  }
  const facts: [string, string | null][] = [
    ['When', `${fullDate(e.date)}${time ? ` · ${String(time.hour).padStart(2, '0')}:${String(time.minute).padStart(2, '0')}` : ''}`],
    ['Partner', name(e.person_id)],
    ['Kind', e.sexual ? 'with sex' : e.encounter_type ?? 'meetup, no sex'],
    ['Place', (e.place_id && places.get(e.place_id)) || e.location_type],
    ['Duration', e.duration_min != null ? `${e.duration_min} min` : null],
    ['Rounds', e.sexual && e.rounds != null ? String(e.rounds) : null],
    ['Orgasms', e.sexual && e.orgasm_count != null ? `${e.orgasm_count}${e.orgasm_intensity != null ? ` · strongest ${e.orgasm_intensity}/10` : ''}` : null],
    ['Your rating', e.my_rating != null ? `${e.my_rating} / 5` : null],
  ]
  return (
    <div className="it-scrim" onClick={onClose}>
      <aside className="it-panel ft-skin" role="dialog" aria-label="Encounter" onClick={(ev) => ev.stopPropagation()}>
        <header className="it-panel-head">
          <h3 className="mono">ENCOUNTER</h3>
          <button className="it-link mono" onClick={onClose}>CLOSE</button>
        </header>
        <Rows rows={facts.filter((f): f is [string, string] => !!f[1]).map(([label, value]) => ({ label, value }))} />
        {!editing && TAG_FIELDS.map((f) => (e[f.key]?.length ? <div key={f.key}><div className="me-label">{f.label}</div><p className="me-note">{e[f.key]!.join(', ')}</p></div> : null))}
        <Note text={e.notes} />
        {e.sexual && !editing && <button className="ft-chip" onClick={() => setEditing(true)}>{TAG_FIELDS.some((f) => e[f.key]?.length) ? 'EDIT TAGS AND RATING' : 'FILL IN TAGS AND RATING'}</button>}
        {editing && (
          <div className="it-edit">
            <div className="me-label">YOUR RATING</div>
            <div className="it-chips">{RATINGS.map((r) => <button key={r} className="ft-chip" aria-current={rating === r} onClick={() => setRating(rating === r ? null : r)}>{r}</button>)}</div>
            {TAG_FIELDS.map((f) => (
              <div key={f.key}>
                <div className="me-label">{f.label}</div>
                <div className="it-chips">{withSaved(f.vocab, tags[f.key]).map((v) => <button key={v} className="ft-chip" aria-pressed={tags[f.key].includes(v)} aria-current={tags[f.key].includes(v)} onClick={() => toggle(f.key, v)}>{v}</button>)}</div>
              </div>
            ))}
            {error && <p className="it-error">Couldn't save ({error}).</p>}
            <div className="it-chips">
              <button className="ft-chip it-primary" disabled={saving} onClick={() => void save()}>{saving ? 'SAVING…' : 'SAVE'}</button>
              <button className="ft-chip" onClick={() => setEditing(false)}>CANCEL</button>
            </div>
            <p className="it-info">Only the tags and your rating are written; the time, partner and counts stay as the phone saved them.</p>
          </div>
        )}
      </aside>
    </div>
  )
}
