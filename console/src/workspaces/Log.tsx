// LOG (web transition plan): every entry of one day, read-only for now (correcting the entries typed by hand arrives in phase
// 4). Where the Dashboard's DAY LOG lands. Logged times (meals, supplements, sessions, stool, notes, body check-ins) are the
// phone's wall clock labelled +00: their digits are local, so the day is matched on those digits and never shifted.
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { localDateString, localToday } from '../lib/dates'
import { dashboardHref } from '../lib/surfaces'
import './PlanBlock.css'
import './Log.css'

interface Item { description: string | null; calories: number | null; quantity: number | null; quantity_unit: string | null; is_beverage: boolean | null }
interface Meal { id: number; logged_at: string; description: string | null; meal_slot: string | null; calories: number | null; carbs_g: number | null; protein_g: number | null; fat_g: number | null; source: string | null; meal_items: Item[] }
interface Supp { id: number; taken_at: string; supplement_name: string | null; dose_value: number | null; dose_unit: string | null; serving_count: number | null; source: string | null }
interface Session { id: number; start_time: string; type: string | null; duration_min: number | null; distance_km: number | null; avg_hr: number | null; rpe: number | null; source: string | null; notes: string | null }
interface Evening { date: string; drinks: number | null; stress: number | null; caffeine_after_14: boolean | null; late_meal: boolean | null; late_screen: boolean | null; gut_comfort: number | null; gut_symptoms: string[] | null; note: string | null }
interface Wellbeing { date: string; energy: number | null; mood: number | null; stress: number | null; soreness: number | null }
interface Stool { id: number; occurred_at: string; bristol_type: number | null; discomfort: number | null; notes: string | null }
interface Note { id: number; occurred_at: string; text: string | null }
interface BodyCheck { id: number; occurred_at: string; fasted: boolean | null; before_training: boolean | null; notes: string | null }

interface DayLog { meals: Meal[]; supps: Supp[]; sessions: Session[]; evening: Evening | null; wellbeing: Wellbeing | null; stool: Stool[]; notes: Note[]; body: BodyCheck[] }

function shift(day: string, n: number): string {
  const [y, m, d] = day.split('-').map(Number)
  return localDateString(new Date(y, m - 1, d + n))
}

/** A logged time's own digits ("2026-11-17T12:30:00+00:00" → "12:30"): wall clock, never converted. */
const wall = (ts: string): string => ts.slice(11, 16)
const n0 = (x: number | null | undefined): string => (x == null ? '–' : String(Math.round(x)))
const yes = (b: boolean | null | undefined): string => (b ? 'yes' : 'no')

async function loadDay(day: string): Promise<DayLog> {
  const lo = `${day}T00:00:00+00:00`
  const hi = `${shift(day, 1)}T00:00:00+00:00`
  const between = <T,>(table: string, cols: string, col: string, order = col) =>
    supabase.from(table).select(cols).gte(col, lo).lt(col, hi).order(order) as unknown as Promise<{ data: T[] | null; error: { message: string } | null }>
  const [m, s, x, e, w, st, nt, b] = await Promise.all([
    between<Meal>('meals', 'id,logged_at,description,meal_slot,calories,carbs_g,protein_g,fat_g,source,meal_items(description,calories,quantity,quantity_unit,is_beverage)', 'logged_at'),
    between<Supp>('supplement_log', 'id,taken_at,supplement_name,dose_value,dose_unit,serving_count,source', 'taken_at'),
    between<Session>('exercise_sessions', 'id,start_time,type,duration_min,distance_km,avg_hr,rpe,source,notes', 'start_time'),
    supabase.from('evening_checkins').select('date,drinks,stress,caffeine_after_14,late_meal,late_screen,gut_comfort,gut_symptoms,note').eq('date', day).maybeSingle(),
    supabase.from('wellbeing_daily').select('date,energy,mood,stress,soreness').eq('date', day).maybeSingle(),
    between<Stool>('stool_log', 'id,occurred_at,bristol_type,discomfort,notes', 'occurred_at'),
    between<Note>('notes', 'id,occurred_at,text', 'occurred_at'),
    between<BodyCheck>('body_checkins', 'id,occurred_at,fasted,before_training,notes', 'occurred_at'),
  ])
  for (const [name, r] of [['meals', m], ['supplement_log', s], ['exercise_sessions', x]] as const) if (r.error) throw new Error(`${name}: ${r.error.message}`)
  return {
    meals: m.data ?? [], supps: s.data ?? [], sessions: x.data ?? [],
    evening: (e.error ? null : e.data) as Evening | null, wellbeing: (w.error ? null : w.data) as Wellbeing | null,
    stool: st.error ? [] : st.data ?? [], notes: nt.error ? [] : nt.data ?? [], body: b.error ? [] : b.data ?? [],
  }
}

export default function Log() {
  const [params, setParams] = useSearchParams()
  const today = localToday()
  const raw = params.get('day')
  const day = raw && /^\d{4}-\d{2}-\d{2}$/.test(raw) ? raw : today
  const [log, setLog] = useState<DayLog | null>(null)
  const status = useLoadEffect(async (isCurrent) => {
    const d = await loadDay(day)
    if (isCurrent()) setLog(d)
  }, [day])
  const go = (d: string) => setParams({ day: d })
  const long = new Date(`${day}T12:00:00`).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
  const kcal = log?.meals.reduce((a, m) => a + (m.calories ?? 0), 0) ?? 0
  const empty = log && !log.meals.length && !log.supps.length && !log.sessions.length && !log.evening && !log.wellbeing && !log.stool.length && !log.notes.length && !log.body.length

  return (
    <div className="log">
      <header className="log-head">
        <div>
          <h2 className="log-title mono">DAY LOG</h2>
          <p className="log-sub">{long}. Everything logged that day; entries are read-only here for now.</p>
        </div>
        <div className="pb-row tight">
          <button className="btn-ghost mono" onClick={() => go(shift(day, -1))}>← PREVIOUS</button>
          <input className="pb-input" type="date" value={day} max={today} onChange={(e) => e.target.value && go(e.target.value)} aria-label="Day" />
          <button className="btn-ghost mono" disabled={day >= today} onClick={() => go(shift(day, 1))}>NEXT →</button>
          <a className="btn-ghost mono log-dash" href={dashboardHref(`#/today?day=${day}`)}>TODAY ON THE DASHBOARD →</a>
        </div>
      </header>

      {status.error && <p className="pb-warn">Could not load the day: {status.error}</p>}
      {status.loading && !log && <p className="state-loading">LOADING THE DAY…</p>}
      {empty && <p className="state-empty">NOTHING WAS LOGGED ON THIS DAY.</p>}

      {log && (
        <div className="log-grid">
          {log.meals.length > 0 && (
            <Section title={`FOOD AND DRINK · ${Math.round(kcal)} KCAL`}>
              {log.meals.map((m) => (
                <Row key={m.id} time={wall(m.logged_at)} source={m.source}
                  title={`${m.meal_slot ? `${m.meal_slot.toLowerCase()}: ` : ''}${m.description ?? m.meal_items.map((i) => i.description).filter(Boolean).join(', ') ?? 'meal'}`}
                  detail={`${n0(m.calories)} kcal · carbs ${n0(m.carbs_g)} g · protein ${n0(m.protein_g)} g · fat ${n0(m.fat_g)} g`}
                  items={m.meal_items.map((i) => `${i.description ?? 'item'}${i.quantity != null ? ` · ${i.quantity}${i.quantity_unit ? ` ${i.quantity_unit}` : ''}` : ''}${i.calories != null ? ` · ${Math.round(i.calories)} kcal` : ''}`)} />
              ))}
            </Section>
          )}
          {log.sessions.length > 0 && (
            <Section title="SESSIONS">
              {log.sessions.map((x) => (
                <Row key={x.id} time={wall(x.start_time)} source={x.source} title={x.type ?? 'session'}
                  detail={[x.duration_min != null && `${Math.round(x.duration_min)} min`, x.distance_km != null && `${x.distance_km.toFixed(1)} km`, x.avg_hr != null && `avg ${Math.round(x.avg_hr)} bpm`, x.rpe != null && `RPE ${x.rpe}`].filter(Boolean).join(' · ')}
                  items={x.notes ? [x.notes] : []} />
              ))}
            </Section>
          )}
          {log.supps.length > 0 && (
            <Section title="SUPPLEMENTS AND MEDICATION">
              {log.supps.map((x) => (
                <Row key={x.id} time={wall(x.taken_at)} source={x.source} title={x.supplement_name ?? 'supplement'}
                  detail={[x.dose_value != null && `${x.dose_value}${x.dose_unit ? ` ${x.dose_unit}` : ''}`, x.serving_count != null && `${x.serving_count} serving${x.serving_count === 1 ? '' : 's'}`].filter(Boolean).join(' · ')} />
              ))}
            </Section>
          )}
          {(log.wellbeing || log.evening) && (
            <Section title="CHECK-INS">
              {log.wellbeing && <Row time="AM" title="Morning check-in" detail={`energy ${n0(log.wellbeing.energy)} · mood ${n0(log.wellbeing.mood)} · stress ${n0(log.wellbeing.stress)} · soreness ${n0(log.wellbeing.soreness)}`} />}
              {log.evening && <Row time="PM" title="Closing the day"
                detail={`drinks ${n0(log.evening.drinks)} · stress ${n0(log.evening.stress)} · caffeine after 14:00 ${yes(log.evening.caffeine_after_14)} · late meal ${yes(log.evening.late_meal)} · late screen ${yes(log.evening.late_screen)}${log.evening.gut_comfort != null ? ` · gut comfort ${log.evening.gut_comfort}` : ''}`}
                items={[...(log.evening.gut_symptoms ?? []).map((g) => `gut: ${g}`), ...(log.evening.note ? [log.evening.note] : [])]} />}
            </Section>
          )}
          {log.stool.length > 0 && (
            <Section title="DIGESTION">
              {log.stool.map((x) => <Row key={x.id} time={wall(x.occurred_at)} title={`Bristol type ${x.bristol_type ?? '–'}`} detail={x.discomfort != null ? `discomfort ${x.discomfort}` : ''} items={x.notes ? [x.notes] : []} />)}
            </Section>
          )}
          {log.body.length > 0 && (
            <Section title="BODY CHECK-INS">
              {log.body.map((x) => <Row key={x.id} time={wall(x.occurred_at)} title="Body check-in" detail={`fasted ${yes(x.fasted)} · before training ${yes(x.before_training)}`} items={x.notes ? [x.notes] : []} />)}
            </Section>
          )}
          {log.notes.length > 0 && (
            <Section title="NOTES">
              {log.notes.map((x) => <Row key={x.id} time={wall(x.occurred_at)} title={x.text ?? ''} />)}
            </Section>
          )}
        </div>
      )}
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return <section className="log-section"><h3 className="log-section-title mono">{title}</h3><ol className="log-rows">{children}</ol></section>
}

function Row({ time, title, detail, items, source }: { time: string; title: string; detail?: string; items?: string[]; source?: string | null }) {
  return (
    <li className="log-row">
      <span className="log-time mono">{time}</span>
      <div className="log-body">
        <div className="log-row-title">{title}{source && <span className="log-source mono">{source}</span>}</div>
        {detail && <div className="log-detail">{detail}</div>}
        {items && items.length > 0 && <ul className="log-items">{items.map((i, k) => <li key={k}>{i}</li>)}</ul>}
      </div>
    </li>
  )
}
