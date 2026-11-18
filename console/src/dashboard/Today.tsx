// Dashboard › Today (phase 1): the scores the phone saved for the chosen day (score_history: CHARGE and the five rings),
// and CHARGE over the 28 days before it. The scores are never recomputed here, so a past day reads as the phone showed it.
// Phase 2 adds the ring overlays, the day's plan and eaten so far.
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { localDateString } from '../lib/dates'
import { consoleHref } from '../lib/surfaces'
import { useState } from 'react'

interface ScoreDay { day: string; charge: number | null; recovery: number | null; sleep: number | null; load: number | null; fuel: number | null; balance: number | null }

const RINGS: { key: keyof Omit<ScoreDay, 'day' | 'charge'>; label: string; hue: string }[] = [
  { key: 'recovery', label: 'RECOVERY', hue: 'var(--ft-domain-cardio)' },
  { key: 'sleep', label: 'SLEEP', hue: 'var(--ft-domain-sleep)' },
  { key: 'load', label: 'LOAD', hue: 'var(--ft-domain-load)' },
  { key: 'fuel', label: 'FUEL', hue: 'var(--ft-domain-fuel)' },
  { key: 'balance', label: 'BALANCE', hue: 'var(--ft-domain-balance)' },
]

const STRIP_DAYS = 28

function shift(day: string, n: number): string {
  const [y, m, d] = day.split('-').map(Number)
  return localDateString(new Date(y, m - 1, d + n))
}

const longDate = (day: string) => new Date(`${day}T12:00:00`).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
const round = (x: number | null) => (x == null ? null : Math.round(x))

export function Today({ day, onDay }: { day: string; onDay: (d: string) => void }) {
  const [rows, setRows] = useState<ScoreDay[]>([])
  const from = shift(day, -(STRIP_DAYS - 1))
  const status = useLoadEffect(async (isCurrent) => {
    const r = await supabase.from('score_history').select('day,charge,recovery,sleep,load,fuel,balance').gte('day', from).lte('day', day).order('day')
    if (r.error) throw new Error(`score_history: ${r.error.message}`)
    if (isCurrent()) setRows((r.data ?? []) as ScoreDay[])
  }, [day])

  const byDay = new Map(rows.map((r) => [r.day, r]))
  const today = byDay.get(day) ?? null
  const strip = Array.from({ length: STRIP_DAYS }, (_, i) => shift(from, i))
  const charge = round(today?.charge ?? null)

  return (
    <div className="today">
      <section className="ft-card today-hero" aria-label="CHARGE">
        <div className="today-day">{longDate(day)}</div>
        {status.loading && rows.length === 0 ? <div className="today-empty">Loading the day…</div>
          : status.error ? <div className="today-empty">Could not load the scores: {status.error} <button className="dash-link" onClick={status.retry}>RETRY</button></div>
          : !today ? <div className="today-empty">No scores were saved for this day. The phone saves them each day it is opened.</div>
          : (
            <div className="today-scores">
              <Ring value={charge} size={168} stroke="url(#charge-spectrum)" label="CHARGE" big />
              <div className="today-rings">
                {RINGS.map((r) => <Ring key={r.key} value={round(today[r.key])} size={92} stroke={r.hue} label={r.label} />)}
              </div>
            </div>
          )}
        <div className="today-links">
          <a className="dash-link" href={consoleHref(`#/data?table=meals&date=${day}`)}>DAY LOG →</a>
          <a className="dash-link" href={consoleHref(`#/explore`)}>HISTORY IN THE CONSOLE →</a>
        </div>
      </section>

      <section className="ft-card today-strip" aria-label={`CHARGE, the ${STRIP_DAYS} days to ${day}`}>
        <div className="today-strip-head"><span>CHARGE · {STRIP_DAYS} DAYS</span><span>tap a day</span></div>
        <div className="today-bars">
          {strip.map((d) => {
            const v = round(byDay.get(d)?.charge ?? null)
            return (
              <button key={d} className={`today-bar ${d === day ? 'on' : ''}`} onClick={() => onDay(d)} title={`${d}: ${v ?? 'no score'}`} aria-label={`${d}, CHARGE ${v ?? 'none'}`}>
                <span className="today-bar-fill" style={{ height: `${v == null ? 0 : Math.max(4, v)}%` }} />
                <span className="today-bar-day">{Number(d.slice(8))}</span>
              </button>
            )
          })}
        </div>
      </section>
      <svg width="0" height="0" aria-hidden="true" style={{ position: 'absolute' }}>
        <defs>
          <linearGradient id="charge-spectrum" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="var(--ft-charge-a)" /><stop offset=".3" stopColor="var(--ft-charge-b)" /><stop offset=".55" stopColor="var(--ft-charge-c)" />
            <stop offset=".8" stopColor="var(--ft-charge-d)" /><stop offset="1" stopColor="var(--ft-charge-e)" />
          </linearGradient>
        </defs>
      </svg>
    </div>
  )
}

function Ring({ value, size, stroke, label, big }: { value: number | null; size: number; stroke: string; label: string; big?: boolean }) {
  const r = 42, c = 2 * Math.PI * r
  const frac = value == null ? 0 : Math.max(0, Math.min(100, value)) / 100
  return (
    <figure className={`ring ${big ? 'big' : ''}`} style={{ width: size }}>
      <svg viewBox="0 0 100 100" width={size} height={size} role="img" aria-label={`${label} ${value ?? 'no score'}`}>
        <circle cx="50" cy="50" r={r} fill="none" stroke="var(--ft-line)" strokeWidth={big ? 7 : 8} />
        {frac > 0 && <circle cx="50" cy="50" r={r} fill="none" stroke={stroke} strokeWidth={big ? 7 : 8} strokeLinecap="round"
          strokeDasharray={`${c * frac} ${c}`} transform="rotate(-90 50 50)" />}
        <text x="50" y={big ? 54 : 56} textAnchor="middle" className="ring-value">{value ?? '–'}</text>
      </svg>
      <figcaption className="ring-label">{label}</figcaption>
    </figure>
  )
}
