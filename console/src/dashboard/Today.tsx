// Today: the phone's TODAY for any day, read from what the phone saved (todayData.ts): CHARGE and the five
// rings, the overlays behind them (what moved CHARGE, each ring's inputs against your usual), the day's plan as a
// timeline and eaten so far. Every score and input opens its history (workspaces/Metric.tsx); the day's log is one tap away.
import { useEffect, useState } from 'react'
import { useLoadEffect } from '../lib/useLoadEffect'
import { localDateString, localToday } from '../lib/dates'
import { metricHref } from '../lib/metrics'
import { Board } from '../components/layout/Board'
import { supabase } from '../lib/supabase'
import { headline, latest, type PeriodReport, reportHref, spanText } from '../lib/reports'
import {
  bandWord, chargeRankLine, directionGlyph, directionWords, DOMAINS, domainSentence, driverSummary, formatJudgedValue, referenceLine,
  type DayScores, type DomainKey,
} from '../lib/scoreText'
import { loadToday, shownScore, stripCharge, type TodayData } from './todayData'
import { type LiveKind, type LivePlan, livePlan } from './todayLive'
import { SubstanceCard } from './Substances'
import { MealOverlay } from './MealOverlay'

const STRIP_DAYS = 28
const REFRESH_MS = 60_000 // while today is on screen

function shift(day: string, n: number): string {
  const [y, m, d] = day.split('-').map(Number)
  return localDateString(new Date(y, m - 1, d + n))
}

const longDate = (day: string) => new Date(`${day}T12:00:00`).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
const round = (x: number | null) => (x == null ? null : Math.round(x))
const historyHref = (metric: string, day: string) => metricHref(metric, { day })

type Overlay = 'CHARGE' | DomainKey | null

export function Today({ day, onDay }: { day: string; onDay: (d: string) => void }) {
  const [data, setData] = useState<TodayData | null>(null)
  const [readAt, setReadAt] = useState<Date | null>(null)
  // Bumped to read the day again: once a minute while today is on screen, and when the tab comes back into view.
  const [tick, setTick] = useState(0)
  // An overlay belongs to the day it was opened on: picking another day closes it.
  const [opened, setOpened] = useState<{ day: string; which: Overlay }>({ day, which: null })
  const overlay = opened.day === day ? opened.which : null
  const setOverlay = (which: Overlay) => setOpened({ day, which })
  const from = shift(day, -(STRIP_DAYS - 1))
  const status = useLoadEffect(async (isCurrent) => {
    const d = await loadToday(day, from)
    if (isCurrent()) { setData(d); setReadAt(new Date()) }
  }, [day, tick])
  const isToday = day === localToday()
  useEffect(() => {
    if (!isToday) return
    // A reload keeps the page on screen (useLoadEffect: refreshing) and only replaces it once the new read is in.
    const again = () => { if (document.visibilityState === 'visible') setTick((t) => t + 1) }
    const timer = window.setInterval(again, REFRESH_MS)
    document.addEventListener('visibilitychange', again)
    window.addEventListener('focus', again)
    return () => { window.clearInterval(timer); document.removeEventListener('visibilitychange', again); window.removeEventListener('focus', again) }
  }, [isToday])

  const strip = Array.from({ length: STRIP_DAYS }, (_, i) => shift(from, i))
  const charge = data ? round(shownScore(data, day, 'CHARGE')) : null
  const hasDay = data != null && (charge != null || DOMAINS.some((x) => shownScore(data, day, x.key) != null))
  const details = data?.scores ?? null

  return (
    <div className="today">
      {/* Layout (Nov 2026): the scores and the 28 days on the left, the day's plan beside them, packed so neither leaves a hole. */}
      <Board className="today-board">
        <section className="ft-card today-hero" aria-label="CHARGE">
          <div className="today-day">{longDate(day)}</div>
          {status.loading && !data ? <div className="today-empty">Loading the day…</div>
            : status.error && !data ? <div className="today-empty">Could not load the scores: {status.error} <button className="dash-link" onClick={status.retry}>RETRY</button></div>
            : !hasDay ? <div className="today-empty">No scores were saved for this day. The phone saves them each day it is opened.</div>
            : (
              <>
                <div className="today-scores">
                  <Ring value={charge} size={168} stroke="url(#charge-spectrum)" label="CHARGE" big onOpen={details ? () => setOverlay('CHARGE') : undefined} />
                  <div className="today-rings">
                    {DOMAINS.map((r) => (
                      <Ring key={r.key} value={round(shownScore(data!, day, r.key))} size={92} stroke={r.hue} label={r.label}
                        onOpen={details?.domains[r.key] ? () => setOverlay(r.key) : undefined} />
                    ))}
                  </div>
                </div>
                {details ? <p className="today-hint">{chargeRankLine(details.charge) ?? ''} Tap a ring for what moved it.</p>
                  : <p className="today-hint">The scores of this day were saved without their detail (kept from 6 Oct 2026 on): the rings do not open.</p>}
              </>
            )}
          <div className="today-links">
            <a className="dash-link" href={`#/review/log?day=${day}`}>DAY LOG →</a>
            <a className="dash-link" href={historyHref('charge', day)}>CHARGE HISTORY →</a>
          </div>
        </section>

        {data && <PlanCard data={data} day={day} readAt={readAt} />}

        <SubstanceCard day={day} isToday={isToday} nowMin={readAt ? readAt.getHours() * 60 + readAt.getMinutes() : null} />

        <section className="ft-card today-strip" aria-label={`CHARGE, the ${STRIP_DAYS} days to ${day}`}>
          <div className="today-strip-head"><span>CHARGE · {STRIP_DAYS} DAYS</span><span>tap a day</span></div>
          <div className="today-bars">
            {strip.map((d) => {
              const v = data ? round(stripCharge(data, d)) : null
              return (
                <button key={d} className={`today-bar ${d === day ? 'on' : ''}`} onClick={() => onDay(d)} title={`${d}: ${v ?? 'no score'}`} aria-label={`${d}, CHARGE ${v ?? 'none'}`}>
                  <span className="today-bar-fill" style={{ height: `${v == null ? 0 : Math.max(4, Math.min(100, v))}%` }} />
                  <span className="today-bar-day">{Number(d.slice(8))}</span>
                </button>
              )
            })}
          </div>
        </section>

        <ReportCard />
      </Board>

      {overlay && details && <ScoreOverlay scores={details} which={overlay} day={day} onPick={setOverlay} onClose={() => setOverlay(null)} />}

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

function Ring({ value, size, stroke, label, big, onOpen }: { value: number | null; size: number; stroke: string; label: string; big?: boolean; onOpen?: () => void }) {
  const r = 42, c = 2 * Math.PI * r
  const frac = value == null ? 0 : Math.max(0, Math.min(100, value)) / 100
  const body = (
    <>
      <svg viewBox="0 0 100 100" width={size} height={size} role="img" aria-label={`${label} ${value ?? 'no score'}`}>
        <circle cx="50" cy="50" r={r} fill="none" stroke="var(--ft-line)" strokeWidth={big ? 7 : 8} />
        {frac > 0 && <circle cx="50" cy="50" r={r} fill="none" stroke={stroke} strokeWidth={big ? 7 : 8} strokeLinecap="round"
          strokeDasharray={`${c * frac} ${c}`} transform="rotate(-90 50 50)" />}
        <text x="50" y={big ? 54 : 56} textAnchor="middle" className="ring-value">{value ?? '–'}</text>
      </svg>
      <span className="ring-label">{label}</span>
    </>
  )
  return onOpen
    ? <button type="button" className={`ring ring-btn ${big ? 'big' : ''}`} style={{ width: size }} onClick={onOpen}>{body}</button>
    : <figure className={`ring ${big ? 'big' : ''}`} style={{ width: size }}>{body}</figure>
}

// ---------------- the latest weekly and monthly report ----------------

/** The newest weekly and monthly report (Lab › Reports), so a report is one tap away all week, not only on Monday. */
function ReportCard() {
  const [reports, setReports] = useState<PeriodReport[] | null>(null)
  useLoadEffect(async (cur) => {
    const { data, error } = await supabase.from('analysis_results').select('payload').eq('kind', 'period_report').order('as_of', { ascending: false }).limit(6)
    if (error) throw new Error(error.message)
    if (cur()) setReports(((data ?? []) as { payload: PeriodReport }[]).map((r) => r.payload).filter((p) => p && typeof p.key === 'string'))
  }, [])
  if (!reports) return null
  const shown = [latest(reports, 'week'), latest(reports, 'month')].filter((r): r is PeriodReport => r !== null)
  return (
    <section className="ft-card today-plan today-report" aria-label="Reports">
      <div className="today-strip-head"><span>REPORTS</span><a className="rp-link" href="#/review/reports">ALL ›</a></div>
      {shown.length === 0 ? <p className="today-hint">The first weekly report is written on Monday morning, for the week before.</p> : shown.map((r) => (
        <a key={r.key} className="today-report-row" href={reportHref(r.key)}>
          <span className="today-report-title mono">{r.grain === 'week' ? 'WEEK' : 'MONTH'} · {spanText(r)}</span>
          <span className="today-hint">{headline(r)}{r.suggestions[0] ? ` ${r.suggestions[0].text}` : ''}</span>
        </a>
      ))}
    </section>
  )
}

// ---------------- the day's plan and eaten so far ----------------

const KIND_HUE: Record<LiveKind, string> = {
  MEAL: 'var(--ft-domain-fuel)', SESSION: 'var(--ft-domain-load)', SUPPLEMENTS: 'var(--ft-domain-balance)', CLOSE: 'var(--ft-muted)',
  ACTIVITY: 'var(--ft-domain-cardio)',
}
const hhmm = (d: Date) => d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })

function PlanCard({ data, day, readAt }: { data: TodayData; day: string; readAt: Date | null }) {
  // The meal rows open the per-meal view (MealOverlay.tsx); it belongs to the day it was opened on.
  const [meal, setMeal] = useState<{ day: string; meals: number[][] } | null>(null)
  // The phone's saved plan with what was logged since laid over it (todayLive.ts); the saved copy alone when the log failed to load.
  const plan: LivePlan | null = data.logs ? livePlan(data.plan, data.logs)
    : data.plan ? { rows: data.plan.rows, so_far: data.plan.so_far, builtAt: data.plan.builtAt } : null
  if (!plan || (plan.rows.length === 0 && !plan.so_far)) {
    return (
      <section className="ft-card today-plan">
        <div className="today-strip-head"><span>THE DAY'S PLAN</span></div>
        <p className="today-hint">No plan was saved for this day: the phone saves TODAY's plan from 18 Nov 2026 on, each time it is opened. What was logged is in the day's log.</p>
        <a className="dash-link" href={`#/review/log?day=${day}`}>DAY LOG →</a>
      </section>
    )
  }
  const isToday = day === localToday()
  const saved = plan.builtAt ? hhmm(new Date(plan.builtAt)) : null
  const live = data.logs != null
  const state = isToday ? (live && readAt ? `live · ${hhmm(readAt)}` : saved ? `as of ${saved}` : '')
    : live ? 'with everything logged' : 'as the phone last showed it'
  return (
    <section className="ft-card today-plan">
      <div className="today-strip-head"><span>THE DAY'S PLAN</span><span>{state}</span></div>
      {plan.so_far && <SoFarBar s={plan.so_far} />}
      <ol className="plan-rows">
        {plan.rows.map((r, i) => (
          <li key={i} className={`plan-row ${r.done ? 'done' : ''}`}>
            <span className="plan-time">{r.time ?? '—'}</span>
            <span className="plan-dot" style={{ background: KIND_HUE[r.kind] ?? 'var(--ft-muted)' }} aria-hidden="true" />
            {r.kind === 'MEAL' && r.meals?.length
              ? <button className="plan-open" onClick={() => setMeal({ day, meals: r.meals! })} aria-label={`Open ${r.title}`}>{r.title} ›</button>
              : <span className="plan-title">{r.title}</span>}
            {r.detail && <span className="plan-detail">{r.detail}</span>}
            <span className="plan-state">{r.logged ? 'LOGGED ✓' : r.done ? 'DONE ✓' : ''}</span>
          </li>
        ))}
      </ol>
      {meal && meal.day === day && <MealOverlay meals={meal.meals} day={day} onClose={() => setMeal(null)} />}
      {live && <p className="today-hint">{saved ? `Plan as the phone drew it at ${saved}` : 'No plan was saved by the phone for this day'}; meals, workouts and check-ins logged since are read live.</p>}
    </section>
  )
}

function SoFarBar({ s }: { s: NonNullable<LivePlan['so_far']> }) {
  const parts = [
    { label: 'Carbs', kcal: 4 * s.carbs_g, color: 'var(--m-carbs)', g: s.carbs_g },
    { label: 'Protein', kcal: 4 * s.protein_g, color: 'var(--m-protein)', g: s.protein_g },
    { label: 'Fat', kcal: 9 * s.fat_g, color: 'var(--m-fat)', g: s.fat_g },
  ]
  const target = s.target_kcal ?? null
  const full = Math.max(target ? target * 1.2 : 0, s.kcal, 1)
  return (
    <div className="sofar">
      <div className="sofar-head">
        <span className="sofar-kcal">{Math.round(s.kcal)}</span>
        <span className="sofar-of">{target ? `of ${Math.round(target)} kcal eaten` : 'kcal eaten'}</span>
      </div>
      <div className="sofar-track" role="img" aria-label={`${Math.round(s.kcal)} kcal${target ? ` of ${Math.round(target)}` : ''}`}>
        {parts.map((p) => <span key={p.label} style={{ width: `${(p.kcal / full) * 100}%`, background: p.color }} />)}
        {target && <i className="sofar-target" style={{ left: `${(target / full) * 100}%` }} />}
      </div>
      <div className="sofar-legend">
        {parts.map((p) => <span key={p.label}><b style={{ background: p.color }} />{p.label} {Math.round(p.g)} g</span>)}
      </div>
    </div>
  )
}

// ---------------- overlays: CHARGE and the five rings ----------------

function ScoreOverlay({ scores, which, day, onPick, onClose }: { scores: DayScores; which: Exclude<Overlay, null>; day: string; onPick: (o: Overlay) => void; onClose: () => void }) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [onClose])
  return (
    <div className="ov-scrim" onClick={onClose}>
      <div className="ov ft-card" role="dialog" aria-modal="true" aria-label={`${which} overlay`} onClick={(e) => e.stopPropagation()}>
        <button className="ov-close" onClick={onClose} aria-label="Close">×</button>
        {which === 'CHARGE' ? <ChargeOverlay scores={scores} day={day} onPick={onPick} /> : <RingOverlay scores={scores} k={which} day={day} onPick={onPick} />}
      </div>
    </div>
  )
}

function ChargeOverlay({ scores, day, onPick }: { scores: DayScores; day: string; onPick: (o: Overlay) => void }) {
  const c = scores.charge
  const max = Math.max(1, ...c.drivers.map((d) => Math.abs(d.points)))
  return (
    <>
      <div className="ov-eyebrow">CHARGE · {longDate(day)}</div>
      <div className="ov-head"><span className="ov-score">{c.score == null ? '–' : Math.round(c.score)}</span><span className="ov-band">{c.score == null ? '' : bandWord(null, c.score)}</span></div>
      {c.cap && <p className="ov-warn">Capped: {c.cap.reason}.</p>}
      {chargeRankLine(c) && <p className="ov-line">{chargeRankLine(c)}</p>}
      {driverSummary(c) && <p className="ov-line">{driverSummary(c)}</p>}
      <div className="ov-label">WHAT MOVED IT</div>
      <div className="ov-drivers">
        {c.drivers.map((d) => {
          const hue = DOMAINS.find((x) => x.key === d.key)?.hue
          return (
            <button key={d.key} className="ov-driver" onClick={() => onPick(d.key)}>
              <span className="ov-driver-name">{d.key}</span>
              <span className="ov-driver-axis">
                <i style={{ [d.points >= 0 ? 'left' : 'right']: '50%', width: `${(Math.abs(d.points) / max) * 50}%`, background: hue }} />
              </span>
              <span className="ov-driver-pts">{d.points >= 0 ? '+' : ''}{Math.round(d.points)}</span>
            </button>
          )
        })}
      </div>
      <a className="dash-link" href={historyHref('charge', day)}>HISTORY →</a>
    </>
  )
}

function RingOverlay({ scores, k, day, onPick }: { scores: DayScores; k: DomainKey; day: string; onPick: (o: Overlay) => void }) {
  const d = scores.domains[k]
  if (!d) return <p className="ov-line">No detail was saved for {k} on this day.</p>
  const meta = DOMAINS.find((x) => x.key === k)!
  return (
    <>
      <div className="ov-eyebrow" style={{ color: meta.hue }}>{k} · {longDate(day)}</div>
      <div className="ov-head"><span className="ov-score">{d.score == null ? '–' : Math.round(d.score)}</span><span className="ov-band">{d.score == null ? 'NOT SCORED' : bandWord(k, d.score)}</span></div>
      <p className="ov-line">{domainSentence(d)}</p>
      {d.raw.groups.map((g) => (
        <div key={g.group.key} className="ov-group">
          {d.raw.groups.length > 1 && <div className="ov-label">{g.group.label.toUpperCase()}{g.score != null ? ` · ${Math.round(g.score)}` : ''}</div>}
          {g.inputs.length === 0 && <p className="today-hint">Nothing recorded.</p>}
          {g.inputs.map((x) => (
            <a key={x.spec.key} className="ov-input" href={historyHref(x.spec.key, day)}>
              <span className="ov-input-name">{x.spec.label}</span>
              <span className="ov-input-value">{formatJudgedValue(x)}</span>
              <span className={`ov-input-dir dir-${x.direction}`}>{directionGlyph(x.direction)} {directionWords(x.direction)}</span>
              <span className="ov-input-ref">{referenceLine(x) ?? ''}</span>
            </a>
          ))}
        </div>
      ))}
      <div className="today-links">
        <button className="dash-link" onClick={() => onPick('CHARGE')}>← CHARGE</button>
        <a className="dash-link" href={historyHref(k.toLowerCase(), day)}>HISTORY →</a>
      </div>
    </>
  )
}
