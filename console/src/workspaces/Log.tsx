// LOG (web transition plan, phases 2 and 4): every entry of a day, or of a range of days, by kind, with filters. An entry
// typed in by hand can be corrected or deleted here, with the same tables and columns as the phone's edit sheets
// (lib/logEdit.ts); imported and synced rows (Health Connect, Zepp, Notion, the scale) are read-only. Where the
// Today's DAY LOG lands. Logged times (meals, supplements, sessions, stool, notes, body check-ins, grip) are the
// phone's wall clock labelled +00: their digits are local, so a day is matched on those digits and never shifted.
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { localDateString, localToday } from '../lib/dates'
import { buildPatch, type EditSpec, isManual, MEAL_SLOTS, shown } from '../lib/logEdit'
import './PlanBlock.css'
import './Log.css'

interface Item { description: string | null; calories: number | null; quantity: number | null; quantity_unit: string | null; is_beverage: boolean | null }
interface Meal { id: number; logged_at: string; description: string | null; meal_slot: string | null; calories: number | null; carbs_g: number | null; protein_g: number | null; fat_g: number | null; source: string | null; meal_items: Item[] }
interface Supp { id: number; taken_at: string; supplement_name: string | null; dose_value: number | null; dose_unit: string | null; serving_count: number | null; source: string | null }
interface Session { id: number; start_time: string; type: string | null; duration_min: number | null; distance_km: number | null; avg_hr: number | null; rpe: number | null; source: string | null; notes: string | null }
interface Evening { date: string; drinks: number | null; stress: number | null; caffeine_after_14: boolean | null; late_meal: boolean | null; late_screen: boolean | null; gut_comfort: number | null; gut_symptoms: string[] | null; note: string | null }
interface Wellbeing { id: number; date: string; energy: number | null; mood: number | null; stress: number | null; soreness: number | null }
interface Stool { id: number; occurred_at: string; bristol_type: number | null; discomfort: number | null; notes: string | null }
interface Note { id: number; occurred_at: string; text: string | null }
interface BodyCheck { id: number; occurred_at: string; fasted: boolean | null; before_training: boolean | null; notes: string | null }
interface Drink { id: number; date: string; ml: number | null; source: string | null }
interface Grip { id: number; occurred_at: string; hand: string | null; kg: number | null }

interface DayLog {
  meals: Meal[]; supps: Supp[]; sessions: Session[]; evening: Evening[]; wellbeing: Wellbeing[]; stool: Stool[]; notes: Note[]
  body: BodyCheck[]; drinks: Drink[]; grip: Grip[]
}

const KINDS = [
  { id: 'food', label: 'FOOD' }, { id: 'sessions', label: 'SESSIONS' }, { id: 'supps', label: 'SUPPLEMENTS' },
  { id: 'checkins', label: 'CHECK-INS' }, { id: 'digestion', label: 'DIGESTION' }, { id: 'body', label: 'BODY' }, { id: 'notes', label: 'NOTES' },
] as const
type Kind = (typeof KINDS)[number]['id']

const MAX_DAYS = 31

function shift(day: string, n: number): string {
  const [y, m, d] = day.split('-').map(Number)
  return localDateString(new Date(y, m - 1, d + n))
}
const daysBetween = (a: string, b: string) => Math.round((Date.parse(`${b}T12:00:00`) - Date.parse(`${a}T12:00:00`)) / 86400000)

/** A logged time's own digits ("2026-11-17T12:30:00+00:00" → "12:30"): wall clock, never converted. */
const wall = (ts: string): string => ts.slice(11, 16)
const n0 = (x: number | null | undefined): string => (x == null ? '–' : String(Math.round(x)))
const yes = (b: boolean | null | undefined): string => (b ? 'yes' : 'no')

async function loadRange(from: string, to: string): Promise<DayLog> {
  const lo = `${from}T00:00:00+00:00`
  const hi = `${shift(to, 1)}T00:00:00+00:00`
  type R<T> = Promise<{ data: T[] | null; error: { message: string } | null }>
  const between = <T,>(table: string, cols: string, col: string) => supabase.from(table).select(cols).gte(col, lo).lt(col, hi).order(col) as unknown as R<T>
  const dated = <T,>(table: string, cols: string) => supabase.from(table).select(cols).gte('date', from).lte('date', to).order('date') as unknown as R<T>
  const [m, s, x, e, w, st, nt, b, dr, g] = await Promise.all([
    between<Meal>('meals', 'id,logged_at,description,meal_slot,calories,carbs_g,protein_g,fat_g,source,meal_items(description,calories,quantity,quantity_unit,is_beverage)', 'logged_at'),
    between<Supp>('supplement_log', 'id,taken_at,supplement_name,dose_value,dose_unit,serving_count,source', 'taken_at'),
    between<Session>('exercise_sessions', 'id,start_time,type,duration_min,distance_km,avg_hr,rpe,source,notes', 'start_time'),
    dated<Evening>('evening_checkins', 'date,drinks,stress,caffeine_after_14,late_meal,late_screen,gut_comfort,gut_symptoms,note'),
    dated<Wellbeing>('wellbeing_daily', 'id,date,energy,mood,stress,soreness'),
    between<Stool>('stool_log', 'id,occurred_at,bristol_type,discomfort,notes', 'occurred_at'),
    between<Note>('notes', 'id,occurred_at,text', 'occurred_at'),
    between<BodyCheck>('body_checkins', 'id,occurred_at,fasted,before_training,notes', 'occurred_at'),
    dated<Drink>('hydration_daily', 'id,date,ml,source'),
    between<Grip>('grip_strength_log', 'id,occurred_at,hand,kg', 'occurred_at'),
  ])
  for (const [name, r] of [['meals', m], ['supplement_log', s], ['exercise_sessions', x]] as const) if (r.error) throw new Error(`${name}: ${r.error.message}`)
  const ok = <T,>(r: { data: T[] | null; error: unknown }) => (r.error ? [] : r.data ?? [])
  return {
    meals: ok(m), supps: ok(s), sessions: ok(x), evening: ok(e), wellbeing: ok(w), stool: ok(st), notes: ok(nt), body: ok(b), drinks: ok(dr), grip: ok(g),
  }
}

/** The part of the range's log that falls on [day]: logged times by their digits, the rest by their date. */
function onDay(log: DayLog, day: string): DayLog {
  const at = (ts: string) => ts.slice(0, 10) === day
  return {
    meals: log.meals.filter((x) => at(x.logged_at)), supps: log.supps.filter((x) => at(x.taken_at)), sessions: log.sessions.filter((x) => at(x.start_time)),
    evening: log.evening.filter((x) => x.date === day), wellbeing: log.wellbeing.filter((x) => x.date === day), stool: log.stool.filter((x) => at(x.occurred_at)),
    notes: log.notes.filter((x) => at(x.occurred_at)), body: log.body.filter((x) => at(x.occurred_at)), drinks: log.drinks.filter((x) => x.date === day),
    grip: log.grip.filter((x) => at(x.occurred_at)),
  }
}

const isEmpty = (l: DayLog) => Object.values(l).every((v) => (v as unknown[]).length === 0)

// ---- what can be corrected, per kind (android data/AddEntryRepository.kt) ----

const mealEdit = (m: Meal): EditSpec => ({
  table: 'meals', id: m.id, title: 'Meal', values: { ...m },
  fields: [
    { key: 'logged_at', label: 'Time', kind: 'time' },
    { key: 'meal_slot', label: 'Slot', kind: 'select', options: MEAL_SLOTS, optional: true },
    { key: 'description', label: 'Description', kind: 'text' },
    { key: 'calories', label: 'kcal', kind: 'number', min: 0, optional: true },
    { key: 'carbs_g', label: 'Carbs g', kind: 'number', min: 0, optional: true },
    { key: 'protein_g', label: 'Protein g', kind: 'number', min: 0, optional: true },
    { key: 'fat_g', label: 'Fat g', kind: 'number', min: 0, optional: true },
  ],
})
const suppEdit = (x: Supp): EditSpec => ({
  table: 'supplement_log', id: x.id, title: x.supplement_name ?? 'Supplement', values: { ...x },
  fields: [
    { key: 'taken_at', label: 'Time', kind: 'time' },
    { key: 'dose_value', label: 'Dose', kind: 'number', min: 0, optional: true },
    { key: 'dose_unit', label: 'Unit', kind: 'text', optional: true },
  ],
})
const stoolEdit = (x: Stool): EditSpec => ({
  table: 'stool_log', id: x.id, title: 'Digestion', values: { ...x },
  fields: [
    { key: 'occurred_at', label: 'Time', kind: 'time' },
    { key: 'bristol_type', label: 'Bristol type', kind: 'int', min: 1, max: 7 },
    { key: 'discomfort', label: 'Discomfort 0–10', kind: 'int', min: 0, max: 10, optional: true },
  ],
})
const noteEdit = (x: Note): EditSpec => ({
  table: 'notes', id: x.id, title: 'Note', values: { ...x },
  fields: [{ key: 'occurred_at', label: 'Time', kind: 'time' }, { key: 'text', label: 'Text', kind: 'longtext' }],
})
const wellbeingEdit = (x: Wellbeing): EditSpec => ({
  table: 'wellbeing_daily', id: x.id, title: 'Morning check-in', values: { ...x },
  fields: (['energy', 'mood', 'stress', 'soreness'] as const).map((k) => ({ key: k, label: `${k[0].toUpperCase()}${k.slice(1)} 1–5`, kind: 'int' as const, min: 1, max: 5, optional: true })),
})
const drinkEdit = (x: Drink): EditSpec => ({
  table: 'hydration_daily', id: x.id, title: 'Water that day', values: { ...x },
  fields: [{ key: 'ml', label: 'Total ml', kind: 'int', min: 0, max: 15000 }],
})
const gripEdit = (x: Grip): EditSpec => ({
  table: 'grip_strength_log', id: x.id, title: 'Grip', values: { ...x },
  fields: [
    { key: 'occurred_at', label: 'Time', kind: 'time' },
    { key: 'hand', label: 'Hand', kind: 'select', options: ['left', 'right'] },
    { key: 'kg', label: 'kg', kind: 'number', min: 0, max: 150 },
  ],
})

export default function Log() {
  const [params, setParams] = useSearchParams()
  const today = localToday()
  const valid = (d: string | null) => (d && /^\d{4}-\d{2}-\d{2}$/.test(d) ? d : null)
  const day = valid(params.get('day')) ?? today
  const rawTo = valid(params.get('to'))
  const to = rawTo && rawTo > day ? (daysBetween(day, rawTo) >= MAX_DAYS ? shift(day, MAX_DAYS - 1) : rawTo) : day
  const isRange = to !== day
  const showParam = params.get('show')
  const show = new Set<Kind>(showParam ? (showParam.split(',').filter((k) => KINDS.some((x) => x.id === k)) as Kind[]) : KINDS.map((k) => k.id))
  const [log, setLog] = useState<DayLog | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const status = useLoadEffect(async (isCurrent) => {
    const d = await loadRange(day, to)
    if (isCurrent()) setLog(d)
  }, [day, to])

  const set = (patch: Record<string, string | null>) => {
    const n = new URLSearchParams(params)
    for (const [k, v] of Object.entries(patch)) { if (v == null) n.delete(k); else n.set(k, v) }
    setParams(n)
  }
  const go = (d: string) => set({ day: d, to: null })
  const toggle = (k: Kind) => {
    const next = new Set(show)
    if (next.has(k)) next.delete(k); else next.add(k)
    set({ show: next.size === KINDS.length || next.size === 0 ? null : [...next].join(',') })
  }
  const changed = (what: string) => { setDone(what); status.retry() }
  const long = (d: string) => new Date(`${d}T12:00:00`).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
  const days: string[] = []
  for (let d = to; d >= day; d = shift(d, -1)) days.push(d)

  return (
    <div className="log">
      <header className="log-head">
        <div>
          <h2 className="log-title mono">DAY LOG</h2>
          <p className="log-sub">{isRange ? `${long(day)} to ${long(to)}` : long(day)}. Entries you typed in can be corrected or deleted; imported ones (watch, Health Connect, scale) are read-only.</p>
        </div>
        <div className="pb-row tight">
          {!isRange && <button className="btn-ghost mono" onClick={() => go(shift(day, -1))}>← PREVIOUS</button>}
          <input className="pb-input" type="date" value={day} max={today} onChange={(e) => e.target.value && set({ day: e.target.value, to: isRange && to > e.target.value ? to : null })} aria-label="From" />
          <span className="pb-muted mono">TO</span>
          <input className="pb-input" type="date" value={to} min={day} max={today} onChange={(e) => e.target.value && set({ to: e.target.value === day ? null : e.target.value })} aria-label="To" />
          {!isRange && <button className="btn-ghost mono" disabled={day >= today} onClick={() => go(shift(day, 1))}>NEXT →</button>}
          {isRange && <button className="btn-ghost mono" onClick={() => set({ to: null })}>ONE DAY</button>}
          {!isRange && <a className="btn-ghost mono log-dash" href={`#/today?day=${day}`}>TODAY →</a>}
        </div>
      </header>
      <div className="pb-chips log-filters" role="group" aria-label="Show">
        {KINDS.map((k) => <button key={k.id} className={`pb-chip ${show.has(k.id) ? 'on' : ''}`} aria-pressed={show.has(k.id)} onClick={() => toggle(k.id)}>{k.label}</button>)}
      </div>
      {isRange && daysBetween(day, rawTo ?? day) >= MAX_DAYS && <p className="pb-warn">A range is at most {MAX_DAYS} days: showing {long(day)} to {long(to)}.</p>}

      {done && <p className="pb-ok" role="status">{done} The phone picks the change up on its next refresh; <a href="#/setup/integrity">Integrity</a> checks the data again each time it is opened.</p>}
      {status.error && <p className="pb-warn">Could not load: {status.error}</p>}
      {status.loading && !log && <p className="state-loading">LOADING…</p>}
      {log && isEmpty(log) && <p className="state-empty">{isRange ? 'NOTHING WAS LOGGED IN THESE DAYS.' : 'NOTHING WAS LOGGED ON THIS DAY.'}</p>}

      {log && days.map((d) => {
        const l = isRange ? onDay(log, d) : log
        if (isRange && isEmpty(l)) return null
        return (
          <div key={d} className="log-day">
            {isRange && <h3 className="log-day-title mono"><a href={`#/log?day=${d}`}>{long(d).toUpperCase()}</a></h3>}
            <DayGrid log={l} show={show} onChanged={changed} />
          </div>
        )
      })}
    </div>
  )
}

function DayGrid({ log, show, onChanged }: { log: DayLog; show: Set<Kind>; onChanged: (what: string) => void }) {
  const kcal = log.meals.reduce((a, m) => a + (m.calories ?? 0), 0)
  return (
    <div className="log-grid">
      {show.has('food') && (log.meals.length > 0 || log.drinks.length > 0) && (
        <Section title={`FOOD AND DRINK · ${Math.round(kcal)} KCAL`}>
          {log.meals.map((m) => (
            <Row key={m.id} time={wall(m.logged_at)} source={m.source} edit={isManual(m.source) ? mealEdit(m) : undefined} onChanged={onChanged}
              deleteNote={m.meal_items.length ? `Its ${m.meal_items.length} item${m.meal_items.length === 1 ? '' : 's'} go with it.` : undefined}
              title={`${m.meal_slot ? `${m.meal_slot.replace('_', '-').toLowerCase()}: ` : ''}${m.description ?? (m.meal_items.map((i) => i.description).filter(Boolean).join(', ') || 'meal')}`}
              detail={`${n0(m.calories)} kcal · carbs ${n0(m.carbs_g)} g · protein ${n0(m.protein_g)} g · fat ${n0(m.fat_g)} g`}
              items={m.meal_items.map((i) => `${i.description ?? 'item'}${i.quantity != null ? ` · ${i.quantity}${i.quantity_unit ? ` ${i.quantity_unit}` : ''}` : ''}${i.calories != null ? ` · ${Math.round(i.calories)} kcal` : ''}`)} />
          ))}
          {log.drinks.map((x) => (
            <Row key={`d${x.id}`} time="DAY" source={x.source} edit={isManual(x.source) ? drinkEdit(x) : undefined} onChanged={onChanged}
              title="Water" detail={`${n0(x.ml)} ml over the day`} />
          ))}
        </Section>
      )}
      {show.has('sessions') && log.sessions.length > 0 && (
        <Section title="SESSIONS">
          {log.sessions.map((x) => (
            <Row key={x.id} time={wall(x.start_time)} source={x.source} title={x.type ?? 'session'}
              detail={[x.duration_min != null && `${Math.round(x.duration_min)} min`, x.distance_km != null && `${x.distance_km.toFixed(1)} km`, x.avg_hr != null && `avg ${Math.round(x.avg_hr)} bpm`, x.rpe != null && `RPE ${x.rpe}`].filter(Boolean).join(' · ')}
              items={x.notes ? [x.notes] : []} />
          ))}
        </Section>
      )}
      {show.has('supps') && log.supps.length > 0 && (
        <Section title="SUPPLEMENTS AND MEDICATION">
          {log.supps.map((x) => (
            <Row key={x.id} time={wall(x.taken_at)} source={x.source} title={x.supplement_name ?? 'supplement'} edit={isManual(x.source) ? suppEdit(x) : undefined} onChanged={onChanged}
              detail={[x.dose_value != null && `${x.dose_value}${x.dose_unit ? ` ${x.dose_unit}` : ''}`, x.serving_count != null && `${x.serving_count} serving${x.serving_count === 1 ? '' : 's'}`].filter(Boolean).join(' · ')} />
          ))}
        </Section>
      )}
      {show.has('checkins') && (log.wellbeing.length > 0 || log.evening.length > 0) && (
        <Section title="CHECK-INS">
          {log.wellbeing.map((w) => <Row key={`w${w.id}`} time="AM" title="Morning check-in" edit={wellbeingEdit(w)} onChanged={onChanged}
            detail={`energy ${n0(w.energy)} · mood ${n0(w.mood)} · stress ${n0(w.stress)} · soreness ${n0(w.soreness)}`} />)}
          {log.evening.map((ev) => <Row key={`e${ev.date}`} time="PM" title="Closing the day"
            detail={`drinks ${n0(ev.drinks)} · stress ${n0(ev.stress)} · caffeine after 14:00 ${yes(ev.caffeine_after_14)} · late meal ${yes(ev.late_meal)} · late screen ${yes(ev.late_screen)}${ev.gut_comfort != null ? ` · gut comfort ${ev.gut_comfort}` : ''}`}
            items={[...(ev.gut_symptoms ?? []).map((g) => `gut: ${g}`), ...(ev.note ? [ev.note] : [])]} />)}
        </Section>
      )}
      {show.has('digestion') && log.stool.length > 0 && (
        <Section title="DIGESTION">
          {log.stool.map((x) => <Row key={x.id} time={wall(x.occurred_at)} title={`Bristol type ${x.bristol_type ?? '–'}`} edit={stoolEdit(x)} onChanged={onChanged}
            detail={x.discomfort != null ? `discomfort ${x.discomfort}` : ''} items={x.notes ? [x.notes] : []} />)}
        </Section>
      )}
      {show.has('body') && (log.body.length > 0 || log.grip.length > 0) && (
        <Section title="BODY">
          {log.body.map((x) => <Row key={x.id} time={wall(x.occurred_at)} title="Body check-in" detail={`fasted ${yes(x.fasted)} · before training ${yes(x.before_training)}`} items={x.notes ? [x.notes] : []} />)}
          {log.grip.map((x) => <Row key={`g${x.id}`} time={wall(x.occurred_at)} title={`Grip, ${x.hand ?? ''} hand`} detail={`${x.kg ?? '–'} kg`} edit={gripEdit(x)} onChanged={onChanged} />)}
        </Section>
      )}
      {show.has('notes') && log.notes.length > 0 && (
        <Section title="NOTES">
          {log.notes.map((x) => <Row key={x.id} time={wall(x.occurred_at)} title={x.text ?? ''} edit={noteEdit(x)} onChanged={onChanged} />)}
        </Section>
      )}
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return <section className="log-section"><h3 className="log-section-title mono">{title}</h3><ol className="log-rows">{children}</ol></section>
}

function Row({ time, title, detail, items, source, edit, deleteNote, onChanged }: {
  time: string; title: string; detail?: string; items?: string[]; source?: string | null
  edit?: EditSpec; deleteNote?: string; onChanged?: (what: string) => void
}) {
  const [mode, setMode] = useState<'view' | 'edit' | 'delete'>('view')
  return (
    <li className="log-row">
      <span className="log-time mono">{time}</span>
      <div className="log-body">
        <div className="log-row-head">
          <div className="log-row-title">{title}{source && <span className="log-source mono">{source}</span>}</div>
          {edit && mode === 'view' && (
            <span className="log-row-actions">
              <button className="log-act mono" onClick={() => setMode('edit')} aria-label={`Fix ${edit.title.toLowerCase()} at ${time}`}>FIX</button>
              <button className="log-act mono" onClick={() => setMode('delete')} aria-label={`Delete ${edit.title.toLowerCase()} at ${time}`}>DELETE</button>
            </span>
          )}
        </div>
        {detail && <div className="log-detail">{detail}</div>}
        {items && items.length > 0 && <ul className="log-items">{items.map((i, k) => <li key={k}>{i}</li>)}</ul>}
        {edit && mode === 'edit' && <EditForm spec={edit} onCancel={() => setMode('view')} onSaved={() => { setMode('view'); onChanged?.(`${edit.title} corrected.`) }} />}
        {edit && mode === 'delete' && <DeleteConfirm spec={edit} note={deleteNote} onCancel={() => setMode('view')} onDeleted={() => { setMode('view'); onChanged?.(`${edit.title} deleted.`) }} />}
      </div>
    </li>
  )
}

function EditForm({ spec, onCancel, onSaved }: { spec: EditSpec; onCancel: () => void; onSaved: () => void }) {
  const [form, setForm] = useState<Record<string, string>>(() => Object.fromEntries(spec.fields.map((f) => [f.key, shown(f, spec.values[f.key])])))
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const save = async () => {
    setErr(null)
    let patch: Record<string, unknown>
    try { patch = buildPatch(spec.fields, spec.values, form) } catch (e) { setErr((e as Error).message); return }
    if (Object.keys(patch).length === 0) { onCancel(); return }
    setBusy(true)
    const r = await supabase.from(spec.table).update(patch).eq('id', spec.id).select('id')
    setBusy(false)
    if (r.error) setErr(r.error.message)
    else if (!r.data?.length) setErr('Nothing was changed: the entry is gone or not yours.')
    else onSaved()
  }
  return (
    <form className="log-edit" onSubmit={(e) => { e.preventDefault(); void save() }}>
      {spec.fields.map((f) => (
        <label key={f.key} className={`log-field ${f.kind === 'longtext' || f.key === 'description' ? 'wide' : ''}`}>
          <span className="mono">{f.label.toUpperCase()}</span>
          {f.kind === 'select' ? (
            <select className="pb-input" value={form[f.key]} onChange={(e) => setForm({ ...form, [f.key]: e.target.value })}>
              {f.optional && <option value="">—</option>}
              {f.options!.map((o) => <option key={o} value={o}>{o.replace('_', '-')}</option>)}
            </select>
          ) : f.kind === 'longtext' ? (
            <textarea className="pb-input" rows={3} value={form[f.key]} onChange={(e) => setForm({ ...form, [f.key]: e.target.value })} />
          ) : (
            <input className="pb-input" type={f.kind === 'time' ? 'time' : 'text'} inputMode={f.kind === 'number' ? 'decimal' : f.kind === 'int' ? 'numeric' : undefined}
              value={form[f.key]} onChange={(e) => setForm({ ...form, [f.key]: e.target.value })} />
          )}
        </label>
      ))}
      {err && <p className="pb-warn log-edit-msg">{err}</p>}
      <div className="pb-row tight log-edit-msg">
        <button className="pb-primary mono" type="submit" disabled={busy}>{busy ? 'SAVING…' : 'SAVE'}</button>
        <button className="btn-ghost mono" type="button" onClick={onCancel}>CANCEL</button>
      </div>
    </form>
  )
}

function DeleteConfirm({ spec, note, onCancel, onDeleted }: { spec: EditSpec; note?: string; onCancel: () => void; onDeleted: () => void }) {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const del = async () => {
    setBusy(true); setErr(null)
    const r = await supabase.from(spec.table).delete().eq('id', spec.id).select('id')
    setBusy(false)
    if (r.error) setErr(r.error.message)
    else if (!r.data?.length) setErr('Nothing was deleted: the entry is gone or not yours.')
    else onDeleted()
  }
  return (
    <div className="log-edit" role="alertdialog" aria-label="Delete this entry?">
      <p className="log-edit-msg pb-warn">Delete this entry? This cannot be undone.{note ? ` ${note}` : ''}</p>
      {err && <p className="pb-warn log-edit-msg">{err}</p>}
      <div className="pb-row tight log-edit-msg">
        <button className="pb-primary mono log-danger" disabled={busy} onClick={() => void del()}>{busy ? 'DELETING…' : 'DELETE'}</button>
        <button className="btn-ghost mono" onClick={onCancel}>KEEP IT</button>
      </div>
    </div>
  )
}
