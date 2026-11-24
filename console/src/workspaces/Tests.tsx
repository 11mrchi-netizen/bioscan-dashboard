// LAB › TESTS (web transition plan, phase 4): the tests the phone runs (Lab › TESTS: grip, body check-in, sweat test,
// running form), each with its history, then every blood draw with its results; a marker opens its history across
// draws. Read-only: tests are taken on the phone. The judgement of a marker against your own variation (RCV) is on
// Me › Bloodwork, as the phone works it out. Logged times (occurred_at) are wall clock: their digits as they are.
import { useMemo, useState } from 'react'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import './PlanBlock.css'
import './Pages.css'

interface Draw { id: number; draw_date: string; draw_time: string | null; lab_name: string | null; fasted: boolean | null; hours_since_training: number | null }
interface Result { id: number; draw_id: number; marker_name: string; value: number | null; value_text: string | null; unit: string | null; ref_low: number | null; ref_high: number | null; flag: string | null }

interface Grip { id: number; occurred_at: string; hand: string | null; kg: number | null }
interface BodyCheck { id: number; occurred_at: string; fasted: boolean | null; before_training: boolean | null; caliper: string | null; notes: string | null }
interface Sweat { id: number; tested_on: string; session_type: string | null; intensity: string | null; duration_min: number | null; weight_before_kg: number | null; weight_after_kg: number | null; fluid_in_ml: number | null; urine_ml: number | null; temp_c: number | null }
interface Form { id: number; recorded_on: string; view: string | null; surface: string | null; quality: string | null; steps_measured: number | null; notes: string | null }
interface Taken { grip: Grip[]; body: BodyCheck[]; sweat: Sweat[]; form: Form[] }

/** Sweat rate in ml an hour, the phone's rule (domain/fuel/SweatProfile.kt): null when impossible (under 100 or over 4000). */
function sweatRate(t: Sweat): number | null {
  if (!t.duration_min || t.duration_min <= 0 || t.weight_before_kg == null || t.weight_after_kg == null) return null
  const loss = (t.weight_before_kg - t.weight_after_kg) * 1000 + (t.fluid_in_ml ?? 0) - (t.urine_ml ?? 0)
  const rate = loss / (t.duration_min / 60)
  return rate >= 100 && rate <= 4000 ? rate : null
}

const range = (r: Result) => r.ref_low != null && r.ref_high != null ? `${r.ref_low}–${r.ref_high}` : r.ref_low != null ? `≥ ${r.ref_low}` : r.ref_high != null ? `≤ ${r.ref_high}` : ''
const outside = (r: Result) => r.value != null && ((r.ref_low != null && r.value < r.ref_low) || (r.ref_high != null && r.value > r.ref_high))

export default function Tests() {
  const [draws, setDraws] = useState<Draw[]>([])
  const [results, setResults] = useState<Result[]>([])
  const [marker, setMarker] = useState<string | null>(null)
  const [q, setQ] = useState('')
  const [taken, setTaken] = useState<Taken>({ grip: [], body: [], sweat: [], form: [] })
  const st = useLoadEffect(async (cur) => {
    const [g, b, sw, f] = await Promise.all([
      supabase.from('grip_strength_log').select('id,occurred_at,hand,kg').order('occurred_at', { ascending: false }).limit(60),
      supabase.from('body_checkins').select('id,occurred_at,fasted,before_training,caliper,notes').order('occurred_at', { ascending: false }).limit(24),
      supabase.from('sweat_tests').select('id,tested_on,session_type,intensity,duration_min,weight_before_kg,weight_after_kg,fluid_in_ml,urine_ml,temp_c').order('tested_on', { ascending: false }).limit(24),
      supabase.from('running_form_analyses').select('id,recorded_on,view,surface,quality,steps_measured,notes').order('recorded_on', { ascending: false }).limit(24),
    ])
    if (cur()) setTaken({ grip: (g.data ?? []) as Grip[], body: (b.data ?? []) as BodyCheck[], sweat: (sw.data ?? []) as Sweat[], form: (f.data ?? []) as Form[] })
    const [d, r] = await Promise.all([
      supabase.from('lab_draws').select('id,draw_date,draw_time,lab_name,fasted,hours_since_training').order('draw_date', { ascending: false }),
      supabase.from('lab_results').select('id,draw_id,marker_name,value,value_text,unit,ref_low,ref_high,flag').order('marker_name').limit(5000),
    ])
    if (d.error) throw new Error(`lab_draws: ${d.error.message}`)
    if (r.error) throw new Error(`lab_results: ${r.error.message}`)
    if (cur()) { setDraws((d.data ?? []) as Draw[]); setResults((r.data ?? []) as Result[]) }
  }, [])
  const byDraw = useMemo(() => {
    const m = new Map<number, Result[]>()
    for (const r of results) m.set(r.draw_id, [...(m.get(r.draw_id) ?? []), r])
    return m
  }, [results])
  const dateOf = useMemo(() => new Map(draws.map((d) => [d.id, d.draw_date])), [draws])
  const history = marker ? results.filter((r) => r.marker_name === marker).sort((a, b) => (dateOf.get(b.draw_id) ?? '').localeCompare(dateOf.get(a.draw_id) ?? '')) : []
  const match = (r: Result) => !q || r.marker_name.toLowerCase().includes(q.toLowerCase())

  return (
    <div className="pg">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">TESTS</h2>
          <p className="pg-sub">The tests taken on the phone (Lab › TESTS) and every blood draw with its results. Tap a marker for its history across draws. How a change compares with your own variation is on the <a href="#/me?tab=BLOODWORK">Me › Bloodwork</a>.</p>
        </div>
        <input className="pb-input" placeholder="Find a marker" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Find a marker" />
      </header>
      {st.error && <p className="pb-warn">Could not load: {st.error}</p>}
      {st.loading && draws.length === 0 && <p className="state-loading">LOADING…</p>}

      <div className="pg-grid">
        <section className="pg-card">
          <h3 className="pg-card-title mono"><span>GRIP STRENGTH</span><span>{taken.grip.length}</span></h3>
          {taken.grip.length === 0 ? <p className="pg-note">No grip test yet.</p> : (
            <ul className="pg-rows">{taken.grip.slice(0, 8).map((x) => (
              <li key={x.id} className="pg-row"><span className="pg-row-title mono">{x.occurred_at.slice(0, 10)} · {x.occurred_at.slice(11, 16)} · {(x.hand ?? '').toUpperCase()}</span><span className="pg-row-value">{x.kg ?? '–'} kg</span></li>
            ))}</ul>
          )}
        </section>
        <section className="pg-card">
          <h3 className="pg-card-title mono"><span>BODY CHECK-INS</span><span>{taken.body.length}</span></h3>
          {taken.body.length === 0 ? <p className="pg-note">No body check-in yet. Monthly, before breakfast: grip, tape girths, skinfolds.</p> : (
            <ul className="pg-rows">{taken.body.slice(0, 8).map((x) => (
              <li key={x.id} className="pg-row"><span className="pg-row-title mono">{x.occurred_at.slice(0, 10)} · {x.occurred_at.slice(11, 16)}</span><span className="pg-row-value">{x.fasted ? 'fasted' : 'not fasted'}</span>
                <span className="pg-row-sub">{[x.before_training != null && (x.before_training ? 'before training' : 'after training'), x.caliper && `caliper ${x.caliper}`, x.notes].filter(Boolean).join(' · ')} · <a href={`#/log?day=${x.occurred_at.slice(0, 10)}`}>day log</a></span></li>
            ))}</ul>
          )}
        </section>
        <section className="pg-card">
          <h3 className="pg-card-title mono"><span>SWEAT TESTS</span><span>{taken.sweat.length}</span></h3>
          {taken.sweat.length === 0 ? <p className="pg-note">No sweat test yet. Weigh before and after a session of an hour or more.</p> : (
            <ul className="pg-rows">{taken.sweat.map((x) => {
              const r = sweatRate(x)
              return (
                <li key={x.id} className="pg-row"><span className="pg-row-title mono">{x.tested_on}</span><span className="pg-row-value">{r != null ? `${Math.round(r)} ml/h` : 'not readable'}</span>
                  <span className="pg-row-sub">{[x.session_type, x.intensity, x.duration_min && `${Math.round(x.duration_min)} min`, x.temp_c != null && `${x.temp_c} °C`].filter(Boolean).join(' · ')}</span></li>
              )
            })}</ul>
          )}
        </section>
        <section className="pg-card">
          <h3 className="pg-card-title mono"><span>RUNNING FORM</span><span>{taken.form.length}</span></h3>
          {taken.form.length === 0 ? <p className="pg-note">No clip analysed yet.</p> : (
            <ul className="pg-rows">{taken.form.map((x) => (
              <li key={x.id} className="pg-row"><span className="pg-row-title mono">{x.recorded_on}</span><span className="pg-row-value">{(x.quality ?? '').toUpperCase()}</span>
                <span className="pg-row-sub">{[x.view, x.surface, x.steps_measured != null && `${x.steps_measured} steps`, x.notes].filter(Boolean).join(' · ')}. The read itself is on the phone.</span></li>
            ))}</ul>
          )}
        </section>
      </div>

      <h3 className="pg-title mono" style={{ fontSize: 12 }}>BLOOD DRAWS</h3>
      {!st.loading && draws.length === 0 && !st.error && <p className="state-empty">NO DRAWS YET.</p>}

      {marker && (
        <section className="pg-card wide">
          <h3 className="pg-card-title mono"><span>{marker.toUpperCase()} · {history.length} DRAW{history.length === 1 ? '' : 'S'}</span><button className="pb-chip" onClick={() => setMarker(null)}>CLOSE</button></h3>
          <ul className="pg-rows">{history.map((r) => (
            <li key={r.id} className="pg-row"><span className="pg-row-title mono">{dateOf.get(r.draw_id)}</span>
              <span className={`pg-row-value ${outside(r) ? 'pg-warn' : ''}`}>{r.value ?? r.value_text ?? '–'} {r.unit ?? ''}</span>
              {range(r) && <span className="pg-row-sub">reference {range(r)}{r.flag ? ` · ${r.flag}` : ''}</span>}</li>
          ))}</ul>
        </section>
      )}

      <div className="pg-grid">
        {draws.map((d) => {
          const rows = (byDraw.get(d.id) ?? []).filter(match)
          if (q && rows.length === 0) return null
          return (
            <section key={d.id} className="pg-card">
              <h3 className="pg-card-title mono"><span>{d.draw_date}{d.draw_time ? ` · ${d.draw_time.slice(0, 5)}` : ''}</span><span>{d.lab_name ?? ''}</span></h3>
              <p className="pg-note">{[d.fasted != null && (d.fasted ? 'fasted' : 'not fasted'), d.hours_since_training != null && `${d.hours_since_training} h after training`, `${(byDraw.get(d.id) ?? []).length} results`].filter(Boolean).join(' · ')}</p>
              <ul className="pg-rows">{rows.map((r) => (
                <li key={r.id} className="pg-row">
                  <button className="pg-row-title pg-link" onClick={() => setMarker(r.marker_name)}>{r.marker_name}</button>
                  <span className={`pg-row-value ${outside(r) ? 'pg-warn' : ''}`}>{r.value ?? r.value_text ?? '–'} {r.unit ?? ''}</span>
                  {(range(r) || r.flag) && <span className="pg-row-sub">{range(r) && `reference ${range(r)}`}{r.flag ? ` · ${r.flag}` : ''}</span>}
                </li>
              ))}</ul>
            </section>
          )
        })}
      </div>
    </div>
  )
}
