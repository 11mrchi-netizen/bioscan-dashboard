// PLAN › SESSIONS › one planned session (#/plan/sessions/planned/<id>): fine-tune it while it is still planned. Its date (the first
// date it was planned for stays on record), time, length and a note; for a strength session the sets, reps and load of each lift
// (a new revision: the old one is set aside); or take it out of the plan (kept, never deleted). The title is the program's name for the
// session and is not edited. The rules are the database's (supabase/migrations/20261124063309_plan_edit.sql); this page says them first.
import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useLoadEffect } from '../lib/useLoadEffect'
import { localToday } from '../lib/dates'
import { longDate } from '../lib/planReads'
import {
  editPlannedSession, loadEquipment, loadOneRms, loadPlannedFull, MigrationMissing, revisePlannedSession, setAsidePlannedSession, type BlockLite, type PlannedFull,
} from '../lib/planEditData'
import { buildPrescription, formOf, liftForms, prescriptionChanged, sessionChanges, startShiftDays, whyLocked, movedFrom, type LiftForm, type LoadChoice, type SessionForm } from '../lib/planEdit'
import { movementKey } from '../../../supabase/functions/_shared/training-core/maxes.ts'
import type { ResolveContext } from '../../../supabase/functions/_shared/training-core/index.ts'
import './PlanBlock.css'
import './Pages.css'
import './SessionDetail.css'

type Loaded = { session: PlannedFull; block: BlockLite; ctx: Omit<ResolveContext, 'oneRmKg'>; oneRms: Map<string, number> }

export default function PlannedSessionEdit() {
  const { id } = useParams()
  const sid = Number(id)
  const [data, setData] = useState<Loaded | null | undefined>(undefined)
  const [rev, setRev] = useState(0)
  const st = useLoadEffect(async (cur) => {
    if (!Number.isInteger(sid)) { if (cur()) setData(null); return }
    const r = await loadPlannedFull(sid)
    if (!r) { if (cur()) setData(null); return }
    const [ctx, oneRms] = await Promise.all([loadEquipment(), loadOneRms()])
    if (cur()) setData({ ...r, ctx, oneRms })
  }, [sid, rev])
  if (st.error) return <div className="pg"><p className="pb-warn">Could not load: {st.error} <button className="btn-ghost mono" onClick={st.retry}>RETRY</button></p></div>
  if (data === undefined) return <div className="pg"><p className="state-loading mono">LOADING…</p></div>
  if (data === null) return <div className="pg"><p className="state-empty">NO SUCH SESSION. <Link to="/plan/sessions">PLANNED SESSIONS</Link></p></div>
  return <Editor key={`${data.session.id}:${rev}`} d={data} onReload={() => setRev((r) => r + 1)} />
}

function Editor({ d, onReload }: { d: Loaded; onReload: () => void }) {
  const { session: s, block, ctx, oneRms } = d
  const navigate = useNavigate()
  const today = localToday()
  const locked = whyLocked(s, block.status, today)
  const [form, setForm] = useState<SessionForm>(() => formOf(s))
  const [lifts, setLifts] = useState<LiftForm[]>(() => liftForms(s.prescription))
  const [busy, setBusy] = useState(false)
  const [confirmAside, setConfirmAside] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const oneRmOf = (exercise: string) => oneRms.get(movementKey(exercise)) ?? null

  const built = useMemo(() => {
    let changes: Record<string, string | number | null> = {}, presc: unknown[] | null = null, error: string | null = null
    try { changes = sessionChanges(s, form, today) } catch (e) { error = e instanceof Error ? e.message : String(e) }
    if (!error && lifts.length) { try { const p = buildPrescription(s.prescription, lifts, ctx, oneRmOf); if (prescriptionChanged(s.prescription, p)) presc = p } catch (e) { error = e instanceof Error ? e.message : String(e) } }
    return { changes, presc, error }
  }, [form, lifts, s, ctx, today]) // eslint-disable-line react-hooks/exhaustive-deps
  const dirty = Object.keys(built.changes).length > 0 || built.presc != null
  // anything typed, valid or not: so a mistake can be discarded even when it cannot be saved
  const touched = JSON.stringify(form) !== JSON.stringify(formOf(s)) || JSON.stringify(lifts) !== JSON.stringify(liftForms(s.prescription))

  const run = async (f: () => Promise<void>) => {
    setBusy(true); setMessage(null)
    try { await f() } catch (e) { setMessage({ ok: false, text: e instanceof MigrationMissing ? e.message : e instanceof Error ? e.message : String(e) }) } finally { setBusy(false) }
  }
  const save = () => run(async () => {
    let id = s.id
    if (Object.keys(built.changes).length) await editPlannedSession(id, today, built.changes)
    if (built.presc) { const n = await revisePlannedSession(id, today, built.presc); id = n.id }
    if (id !== s.id) navigate(`/plan/sessions/planned/${id}`, { replace: true }); else onReload()
  })
  const aside = (on: boolean) => run(async () => { await setAsidePlannedSession(s.id, today, on); setConfirmAside(false); onReload() })

  const shift = startShiftDays(block.choices)
  const was = movedFrom(s.original_date, s.scheduled_date, shift)
  const setLift = (i: number, patch: Partial<LiftForm>) => setLifts((l) => l.map((x, k) => (k === i ? { ...x, ...patch } : x)))
  const canRestore = s.status === 'superseded' && s.scheduled_date >= today && block.status !== 'completed' && block.status !== 'abandoned'

  return (
    <div className="pg sd">
      <header className="pg-head">
        <div>
          <Link className="btn-ghost mono" to="/plan/sessions">‹ PLANNED SESSIONS</Link>
          <h2 className="pg-title mono sd-title">{s.title}</h2>
          <p className="pg-sub">{block.name} · week {s.week_index} · {s.domain.replaceAll('_', ' ')} · {s.status.toUpperCase()}{s.revision > 1 ? ` · revision ${s.revision}` : ''}. The title is the program's own name for this session, so it is not edited here.</p>
        </div>
      </header>

      {locked && <p className="pb-warn" role="status">{locked}{canRestore ? ' It can be brought back.' : ' It can no longer be changed.'}</p>}
      {s.zepp_template_id && !locked && <p className="pg-note">This session is on your watch. After a change the phone replaces its template the next time it sends (it looks a week ahead).</p>}
      {s.gcal_event_id && !locked && <p className="pg-note">It has a calendar entry. A change here does not move it: change that in the calendar.</p>}

      <section className="pg-card sd-form">
        <h3 className="pg-card-title mono"><span>WHEN AND HOW LONG</span></h3>
        <div className="sd-fields">
          <label className="sd-field"><span className="mono">DATE{was ? ` · FIRST PLANNED FOR ${longDate(was).toUpperCase()}` : ''}</span>
            <input className="pb-input" type="date" value={form.date} min={today} disabled={!!locked} onChange={(e) => setForm({ ...form, date: e.target.value })} /></label>
          <label className="sd-field"><span className="mono">START</span>
            <input className="pb-input" type="time" value={form.time} disabled={!!locked} onChange={(e) => setForm({ ...form, time: e.target.value })} /></label>
          <label className="sd-field"><span className="mono">MINUTES</span>
            <input className="pb-input" inputMode="numeric" value={form.duration} disabled={!!locked} onChange={(e) => setForm({ ...form, duration: e.target.value })} style={{ width: 100 }} /></label>
        </div>
        <label className="sd-field"><span className="mono">NOTE (SHOWN HERE; THE PHONE DOES NOT SHOW IT YET)</span>
          <textarea className="pb-input" rows={2} value={form.note} disabled={!!locked} onChange={(e) => setForm({ ...form, note: e.target.value })} placeholder="Anything to remember about this session" /></label>
      </section>

      {lifts.length > 0 && (
        <section className="pg-card sd-form">
          <h3 className="pg-card-title mono"><span>SETS, REPS AND LOAD</span></h3>
          <table className="sd-sets pe-lifts">
            <thead><tr><th>LIFT</th><th>SETS</th><th>REPS</th><th>LOAD</th><th /></tr></thead>
            <tbody>
              {lifts.map((l, i) => (
                <tr key={l.index}>
                  <td><b>{l.exercise}</b><span className="sd-watch mono"> {l.role}</span>
                    {!locked && <div><input className="pb-input" aria-label={`${l.exercise}: do instead`} placeholder="swap for…" value={l.swapTo} maxLength={80} onChange={(e) => setLift(i, { swapTo: e.target.value })} style={{ width: 160, marginTop: 4 }} /></div>}
                    {l.swapTo.trim() !== '' && l.swapTo.trim() !== l.exercise && <span className="sd-watch mono">→ {l.swapTo.trim()} in this session only</span>}</td>
                  <td className="pe-range"><input className="pb-input sd-num" inputMode="numeric" aria-label={`${l.exercise} sets from`} value={l.setsMin} disabled={!!locked} onChange={(e) => setLift(i, { setsMin: e.target.value })} />
                    {' – '}<input className="pb-input sd-num" inputMode="numeric" aria-label={`${l.exercise} sets to`} value={l.setsMax} disabled={!!locked} onChange={(e) => setLift(i, { setsMax: e.target.value })} /></td>
                  <td className="pe-range"><input className="pb-input sd-num" inputMode="numeric" aria-label={`${l.exercise} reps from`} value={l.repsMin} disabled={!!locked} onChange={(e) => setLift(i, { repsMin: e.target.value })} />
                    {' – '}<input className="pb-input sd-num" inputMode="numeric" aria-label={`${l.exercise} reps to`} value={l.repsMax} disabled={!!locked} onChange={(e) => setLift(i, { repsMax: e.target.value })} /></td>
                  <td>
                    <select className="pb-input" aria-label={`${l.exercise} load`} value={l.load} disabled={!!locked} onChange={(e) => setLift(i, { load: e.target.value as LoadChoice })}>
                      <option value="planned">AS PLANNED ({l.loadNow})</option>
                      <option value="fixed">A FIXED WEIGHT</option>
                      {oneRmOf(l.swapTo.trim() || l.exercise) != null && <option value="pct">% OF 1RM ({oneRmOf(l.swapTo.trim() || l.exercise)} kg)</option>}
                      <option value="rpe">BY RPE</option>
                    </select>
                    {l.load === 'fixed' && <> <input className="pb-input sd-num" inputMode="decimal" aria-label={`${l.exercise} weight`} placeholder="kg" value={l.kg} onChange={(e) => setLift(i, { kg: e.target.value })} /> kg</>}
                    {l.load === 'pct' && <> <input className="pb-input sd-num" inputMode="decimal" aria-label={`${l.exercise} percent`} placeholder="%" value={l.pct} onChange={(e) => setLift(i, { pct: e.target.value })} /> %</>}
                    {l.load === 'rpe' && <> <input className="pb-input sd-num" inputMode="decimal" aria-label={`${l.exercise} rpe`} placeholder="RPE" value={l.rpe} onChange={(e) => setLift(i, { rpe: e.target.value })} /></>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="pg-note">To do another exercise in this session only, type it under the lift's name (for the whole block, use SWAP AN EXERCISE on PLAN). Changing an exercise, sets, reps or a load makes a new version of this session and sets the old one aside (nothing is deleted). Accessory lines are changed the same way. A percentage needs a recorded 1RM. To change a max for every session of the block at once, use MAXES on <a href="#/plan">PLAN</a>.</p>
        </section>
      )}

      {!locked && (
        <div className="sd-actions">
          <button className="pb-primary mono" disabled={busy || !dirty || !!built.error} onClick={() => void save()}>{busy ? 'SAVING…' : 'SAVE'}</button>
          {touched && <button className="btn-ghost mono" disabled={busy} onClick={onReload}>DISCARD CHANGES</button>}
          {!confirmAside
            ? <button className="btn-ghost mono" disabled={busy} onClick={() => setConfirmAside(true)}>TAKE OUT OF THE PLAN</button>
            : <span className="sd-confirm"><span className="pb-warn">This sets the session aside: it stays on record, leaves the plan and comes off the watch. You can bring it back.</span>
                <button className="pb-primary mono" disabled={busy} onClick={() => void aside(true)}>YES, TAKE IT OUT</button>
                <button className="btn-ghost mono" onClick={() => setConfirmAside(false)}>CANCEL</button></span>}
        </div>
      )}
      {canRestore && <div className="sd-actions"><button className="pb-primary mono" disabled={busy} onClick={() => void aside(false)}>BRING IT BACK</button></div>}
      {built.error && !locked && <p className="pb-warn">{built.error}</p>}
      {message && <p className={message.ok ? 'pb-ok' : 'pb-warn'} role="status">{message.text}</p>}
    </div>
  )
}
