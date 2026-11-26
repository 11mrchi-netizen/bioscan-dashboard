// PLAN › SESSIONS › one recorded workout (#/plan/sessions/<id>): what the watch measured (read-only) and what the person says
// (editable), by the phone's rule for every source (lib/sessionEdit.ts): the effort (RPE 0-10), notes, a run's route and run type,
// a strength session's exercises and sets. The plan it belongs to is shown, and a still-open plan of that day can be linked to it.
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { hm, longDate } from '../lib/planReads'
import { metricHref, sessionSeries, type SessionField } from '../lib/metrics'
import { linkPlanned, loadPlannedFor, loadSession, saveSession, type PlannedLink, type SessionRow } from '../lib/sessionData'
import {
  blankExercise, blankSet, exercisesOf, paceOrSpeed, ROUTE_TYPES, RUN_TYPES, sessionPatch, toEditable, toExercise, type EditableExercise,
} from '../lib/sessionEdit'
import './PlanBlock.css'
import './Pages.css'
import './SessionDetail.css'

/** A logged time's own digits (the phone's wall clock labelled +00): never converted. */
const wallDay = (ts: string) => ts.slice(0, 10)
const wallTime = (ts: string | null) => (ts ? ts.slice(11, 16) : null)
const n = (x: number | null | undefined, unit = '', digits = 0) => (x == null ? '–' : `${digits ? x.toFixed(digits) : Math.round(x)}${unit}`)

export default function SessionDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const sid = Number(id)
  const [row, setRow] = useState<SessionRow | null | undefined>(undefined)
  const [plan, setPlan] = useState<{ linked: PlannedLink | null; candidates: PlannedLink[] }>({ linked: null, candidates: [] })
  const [rev, setRev] = useState(0)
  const st = useLoadEffect(async (cur) => {
    if (!Number.isInteger(sid)) { if (cur()) setRow(null); return }
    const r = await loadSession(sid)
    const p = r ? await loadPlannedFor(sid, wallDay(r.start_time)).catch(() => ({ linked: null, candidates: [] as PlannedLink[] })) : { linked: null, candidates: [] as PlannedLink[] }
    if (cur()) { setRow(r); setPlan(p) }
  }, [sid, rev])

  if (st.error) return <div className="pg"><p className="pb-warn">Could not load: {st.error} <button className="btn-ghost mono" onClick={st.retry}>RETRY</button></p></div>
  if (row === undefined) return <div className="pg"><p className="state-loading mono">LOADING…</p></div>
  if (row === null) return <div className="pg"><p className="state-empty">NO SUCH SESSION. <Link to="/review/sessions">ALL RECORDED SESSIONS</Link></p></div>
  return <Detail key={`${row.id}:${rev}`} row={row} plan={plan} onChanged={() => setRev((r) => r + 1)} onBack={() => (window.history.state?.idx > 0 ? navigate(-1) : navigate('/review/sessions'))} />
}

function Detail({ row, plan, onChanged, onBack }: { row: SessionRow; plan: { linked: PlannedLink | null; candidates: PlannedLink[] }; onChanged: () => void; onBack: () => void }) {
  const isRun = row.type === 'run', isStrength = row.type === 'strength'
  const [rpe, setRpe] = useState(row.rpe == null ? '' : String(row.rpe))
  const [notes, setNotes] = useState(row.notes ?? '')
  const [routeType, setRouteType] = useState<string>(String(row.details?.route_type ?? ''))
  const [runType, setRunType] = useState<string>(String(row.details?.run_type ?? ''))
  const [exs, setExs] = useState<EditableExercise[]>(() => { const e = exercisesOf(row.details).map(toEditable); return e.length ? e : isStrength ? [blankExercise()] : [] })
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)

  const input = useMemo(() => ({
    rpe, notes,
    edits: {
      routeType: isRun ? routeType || null : undefined, runType: isRun ? runType || null : undefined,
      exercises: isStrength ? exs.map(toExercise).filter((e): e is NonNullable<typeof e> => e != null) : undefined,
    },
  }), [rpe, notes, routeType, runType, exs, isRun, isStrength])
  const verdict = useMemo(() => {
    try {
      const p = sessionPatch(row.details, input)
      const dirty = p.rpe !== row.rpe || p.notes !== (row.notes ?? null) || JSON.stringify(p.details) !== JSON.stringify(row.details ?? {})
      return { dirty, error: null as string | null }
    } catch (e) { return { dirty: false, error: e instanceof Error ? e.message : String(e) } }
  }, [input, row])

  const save = async () => {
    setBusy(true); setMessage(null)
    try { await saveSession(row.id, input); setMessage({ ok: true, text: 'Saved. The phone shows it the next time it reads the session.' }); onChanged() }
    catch (e) { setMessage({ ok: false, text: e instanceof Error ? e.message : String(e) }) }
    finally { setBusy(false) }
  }
  const link = async (plannedId: number) => {
    setBusy(true); setMessage(null)
    try { await linkPlanned(plannedId, row.id); onChanged() } catch (e) { setMessage({ ok: false, text: e instanceof Error ? e.message : String(e) }) } finally { setBusy(false) }
  }
  useEffect(() => { setMessage((m) => (m && m.ok ? m : null)) }, [rpe, notes])

  const day = wallDay(row.start_time)
  const speed = paceOrSpeed(row.type, row.duration_min, row.distance_km)
  const opens = (field: SessionField, v: number | null): string | undefined => {
    const id = v == null ? null : sessionSeries(row.type, field)
    return id ? metricHref(`series:${id}`, { day }) : undefined
  }
  const from = row.source ? row.source.replaceAll('_', ' ') : 'by hand'
  return (
    <div className="pg sd">
      <header className="pg-head">
        <div>
          <button className="btn-ghost mono" onClick={onBack}>‹ BACK</button>
          <h2 className="pg-title mono sd-title">{(row.type ?? 'session').toUpperCase()} · {longDate(day).toUpperCase()} · {wallTime(row.start_time)}{wallTime(row.end_time) ? `–${wallTime(row.end_time)}` : ''}</h2>
          <p className="pg-sub">From {from}. Times, distance, heart rate and the rest of what the watch measured are not edited here; the effort, notes and the details below are yours.</p>
        </div>
        <Link className="btn-ghost mono" to={`/review/log?day=${day}`}>THE DAY'S LOG →</Link>
      </header>

      <section className="sd-tiles" aria-label="What was measured">
        <Tile k="DURATION" v={row.duration_min != null ? hm(row.duration_min) : '–'} href={opens('duration', row.duration_min)} />
        <Tile k="DISTANCE" v={n(row.distance_km, ' km', 2)} href={opens('distance', row.distance_km)} />
        <Tile k={row.type === 'run' || row.type === 'hike' ? 'PACE' : 'SPEED'} v={speed ?? '–'} />
        <Tile k="AVG HEART RATE" v={n(row.avg_hr, ' bpm')} href={opens('avg_hr', row.avg_hr)} />
        <Tile k="MAX HEART RATE" v={n(row.max_hr, ' bpm')} />
        <Tile k="CLIMB" v={n(row.elevation_gain_m, ' m')} />
        <Tile k="ENERGY" v={n(row.calories_active ?? row.calories_total, ' kcal')} />
        {row.avg_power_w != null && <Tile k="AVG POWER" v={n(row.avg_power_w, ' W')} />}
      </section>

      <SessionFuel id={row.id} />

      <section className="pg-card sd-plan">
        <h3 className="pg-card-title mono"><span>THE PLAN</span></h3>
        {plan.linked ? (
          <p className="pg-note">{plan.linked.title ?? 'Planned session'} · {longDate(plan.linked.scheduled_date)} · {plan.linked.status}{plan.linked.match_status ? ` (${plan.linked.match_status})` : ''}. <Link to={`/plan/sessions?w=both`}>PLANNED SESSIONS →</Link></p>
        ) : plan.candidates.length === 0 ? (
          <p className="pg-note">Not linked to a planned session, and no open plan on this day.</p>
        ) : (
          <>
            <p className="pg-note">Not linked yet. Was it one of these? Your answer always wins.</p>
            <ul className="pg-rows">{plan.candidates.map((c) => (
              <li key={c.id} className="pg-row"><span className="pg-row-title">{c.title ?? c.domain ?? 'Planned session'}</span><span className="pg-row-value"><button className="btn-ghost mono" disabled={busy} onClick={() => void link(c.id)}>THIS ONE</button></span></li>
            ))}</ul>
          </>
        )}
      </section>

      <section className="pg-card sd-form">
        <h3 className="pg-card-title mono"><span>YOURS TO CHANGE</span></h3>
        <div className="sd-fields">
          <label className="sd-field"><span className="mono">RPE (EFFORT, 0–10)</span>
            <input className="pb-input" inputMode="numeric" value={rpe} onChange={(e) => setRpe(e.target.value)} placeholder="not rated" style={{ width: 120 }} />
          </label>
          {isRun && (
            <>
              <Choice label="ROUTE" options={ROUTE_TYPES} value={routeType} onChange={setRouteType} />
              <Choice label="RUN TYPE" options={RUN_TYPES} value={runType} onChange={setRunType} />
            </>
          )}
        </div>
        <label className="sd-field"><span className="mono">NOTES</span>
          <textarea className="pb-input" rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Anything else worth noting" />
        </label>

        {isStrength && <Exercises value={exs} onChange={setExs} source={row.details?.exercises_source as string | undefined} />}

        <div className="sd-actions">
          <button className="pb-primary mono" disabled={busy || !verdict.dirty || !!verdict.error} onClick={() => void save()}>{busy ? 'SAVING…' : 'SAVE'}</button>
          {(verdict.dirty || verdict.error) && <button className="btn-ghost mono" disabled={busy} onClick={onChanged}>DISCARD CHANGES</button>}
          {verdict.error && <span className="pb-warn">{verdict.error}</span>}
          {message && <span className={message.ok ? 'pb-ok' : 'pb-warn'} role="status">{message.text}</span>}
        </div>
      </section>
    </div>
  )
}

/** A tile that a series follows opens its history (the metric page) on the session's day; the others open nothing. */
function Tile({ k, v, href }: { k: string; v: string; href?: string }) {
  const body = <><span className="sd-tile-k mono">{k}</span><span className="sd-tile-v">{v}</span></>
  return href ? <a className="sd-tile tappable" href={href}>{body}</a> : <div className="sd-tile">{body}</div>
}

function Choice({ label, options, value, onChange }: { label: string; options: readonly string[]; value: string; onChange: (v: string) => void }) {
  return (
    <div className="sd-field"><span className="mono">{label}</span>
      <div className="pb-chips">
        <button className={`pb-chip ${value === '' ? 'on' : ''}`} onClick={() => onChange('')}>NOT SAID</button>
        {options.map((o) => <button key={o} className={`pb-chip ${value === o ? 'on' : ''}`} onClick={() => onChange(o)}>{o.toUpperCase()}</button>)}
      </div>
    </div>
  )
}

function Exercises({ value, onChange, source }: { value: EditableExercise[]; onChange: (v: EditableExercise[]) => void; source?: string }) {
  const setEx = (i: number, f: (e: EditableExercise) => EditableExercise) => onChange(value.map((e, k) => (k === i ? f(e) : e)))
  return (
    <div className="sd-ex">
      <span className="mono sd-ex-head">EXERCISES{source === 'zepp' ? ' · FROM ZEPP: CHANGING ONE MAKES THE LIST YOURS, AND ZEPP NO LONGER OVERWRITES IT' : source === 'app' ? ' · YOURS' : ''}</span>
      {value.map((e, i) => (
        <div key={i} className="sd-exercise">
          <div className="sd-ex-line">
            <input className="pb-input sd-ex-name" value={e.name} placeholder="Exercise" onChange={(ev) => setEx(i, (x) => ({ ...x, name: ev.target.value }))} aria-label={`Exercise ${i + 1}`} />
            <button className="btn-ghost mono" onClick={() => onChange(value.filter((_, k) => k !== i))} disabled={value.length === 1 && !e.name && e.sets.every((s) => !s.reps && !s.weightKg)}>REMOVE</button>
          </div>
          <table className="sd-sets">
            <thead><tr><th>SET</th><th>REPS</th><th>KG</th><th>RPE</th><th>% 1RM</th><th>WATCH</th><th /></tr></thead>
            <tbody>
              {e.sets.map((s, j) => (
                <tr key={j}>
                  <td className="mono">{j + 1}</td>
                  {(['reps', 'weightKg', 'rpe', 'percentOneRm'] as const).map((f) => (
                    <td key={f}><input className="pb-input sd-num" inputMode="decimal" value={s[f]} aria-label={`${e.name || 'Exercise'} set ${j + 1} ${f}`} onChange={(ev) => setEx(i, (x) => ({ ...x, sets: x.sets.map((y, k) => (k === j ? { ...y, [f]: ev.target.value } : y)) }))} /></td>
                  ))}
                  <td className="sd-watch mono">{[s.carried.duration_sec != null && `${s.carried.duration_sec} s`, s.carried.avg_hr != null && `${s.carried.avg_hr} bpm`].filter(Boolean).join(' · ')}</td>
                  <td><button className="btn-ghost mono" onClick={() => setEx(i, (x) => ({ ...x, sets: x.sets.filter((_, k) => k !== j) }))} aria-label={`Remove set ${j + 1}`}>×</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <button className="btn-ghost mono" onClick={() => setEx(i, (x) => ({ ...x, sets: [...x.sets, blankSet()] }))}>+ ADD SET</button>
        </div>
      ))}
      <button className="btn-ghost mono" onClick={() => onChange([...value, blankExercise()])}>+ ADD EXERCISE</button>
      <p className="pg-note">A set with no reps or weight is left out when saving; an exercise with no name or no set is too.</p>
    </div>
  )
}

interface FuelRow {
  hours_since_meal: number | null; fasted: boolean; long: boolean; carbs_before_g: number; carbs_during_g: number | null
  protein_after_g: number; carbs_after_g: number; protein_dose_g: number
}

/**
 * FUEL: the food around the session (session_fuel, written nightly from analysis-core trainingFuel.ts,
 * docs/nutrition/food-and-training.md), as on the phone's session page. Absent for walks, short sessions and until the run.
 */
function SessionFuel({ id }: { id: number }) {
  const [f, setF] = useState<FuelRow | null>(null)
  useEffect(() => {
    let live = true
    void supabase.from('session_fuel').select('hours_since_meal, fasted, long, carbs_before_g, carbs_during_g, protein_after_g, carbs_after_g, protein_dose_g')
      .eq('session_id', id).maybeSingle().then(({ data }) => { if (live) setF((data as FuelRow | null) ?? null) })
    return () => { live = false }
  }, [id])
  if (!f) return null
  const warn = { color: 'var(--warn)' }
  return (
    <section className="pg-card sd-plan">
      <h3 className="pg-card-title mono"><span>FUEL</span><span>FROM THE MEALS LOGGED</span></h3>
      <ul className="pg-rows">
        <li className="pg-row">
          <span className="pg-row-title">Since the last meal</span>
          <span className="pg-row-value">{f.hours_since_meal == null ? 'none in 18 h' : `${f.hours_since_meal.toFixed(1)} h`}</span>
          {f.fasted && <span className="pg-row-sub" style={f.long ? warn : undefined}>Started fasted (no meal in the 4 h before and none for 8 h)</span>}
        </li>
        <li className="pg-row">
          <span className="pg-row-title">Carbs in the 4 h before</span>
          <span className="pg-row-value" style={{ color: 'var(--m-carbs)' }}>{f.carbs_before_g} g</span>
        </li>
        <li className="pg-row">
          <span className="pg-row-title">Carbs during</span>
          <span className="pg-row-value" style={f.carbs_during_g != null ? { color: 'var(--m-carbs)' } : undefined}>{f.carbs_during_g == null ? 'none logged' : `${f.carbs_during_g} g`}</span>
          {f.long && f.carbs_during_g == null && <span className="pg-row-sub" style={f.fasted ? warn : undefined}>A long session: 30–60 g an hour helps</span>}
        </li>
        <li className="pg-row">
          <span className="pg-row-title">Protein in the 2 h after</span>
          <span className="pg-row-value" style={{ color: 'var(--m-protein)' }}>{f.protein_after_g} g</span>
          <span className="pg-row-sub">{f.protein_after_g >= f.protein_dose_g ? `Reached your ${f.protein_dose_g} g` : `Under your ${f.protein_dose_g} g`}</span>
        </li>
        <li className="pg-row">
          <span className="pg-row-title">Carbs in the 2 h after</span>
          <span className="pg-row-value" style={{ color: 'var(--m-carbs)' }}>{f.carbs_after_g} g</span>
        </li>
      </ul>
      <p className="pg-note">Filled in by the nightly run, so today's session shows tomorrow.</p>
    </section>
  )
}
