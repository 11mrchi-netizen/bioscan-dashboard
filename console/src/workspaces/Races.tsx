// PLAN › RACES (web transition plan, phase 4): the races and long sessions ahead and behind, each with its course, target
// and fuel plan where one was made. Read-only: events and their fuel plans are made on the phone.
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { localToday } from '../lib/dates'
import { type AthleteEvent, daysUntil, hm, loadAthleteEvents, longDate } from '../lib/planReads'
import './PlanBlock.css'
import './Pages.css'

interface FuelSchedule { kind: string; ref_key: string; duration_min: number | null; blocks: unknown; updated_at: string }
interface Aid { name?: string; km?: number; distance_km?: number }

const aidList = (a: unknown): Aid[] => (Array.isArray(a) ? (a as Aid[]) : [])

export default function Races() {
  const today = localToday()
  const [events, setEvents] = useState<AthleteEvent[]>([])
  const [fuel, setFuel] = useState<FuelSchedule[]>([])
  const [past, setPast] = useState(false)
  const st = useLoadEffect(async (cur) => {
    const [e, f] = await Promise.all([loadAthleteEvents(), supabase.from('fuel_schedules').select('kind,ref_key,duration_min,blocks,updated_at')])
    if (cur()) { setEvents(e); setFuel((f.data ?? []) as FuelSchedule[]) }
  }, [])
  const shown = events.filter((e) => (past ? e.event_date < today : e.event_date >= today))
  if (past) shown.reverse()
  // The phone keeps an event's fuel schedule under its plan item's id: "calendar:<event id>" (data/FuelScheduleRepository.kt planKey).
  const fuelFor = (e: AthleteEvent) => fuel.find((f) => (e.calendar_event_id && f.ref_key === `calendar:${e.calendar_event_id}`.slice(0, 64)) || f.ref_key === `event:${e.id}`)

  return (
    <div className="pg">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">RACES</h2>
          <p className="pg-sub">The races and long sessions on your calendar, with course, target and fuel plan. They are added and fuelled on the phone; the training toward them is planned in Plan block.</p>
        </div>
        <div className="pb-chips">
          <button className={`pb-chip ${!past ? 'on' : ''}`} onClick={() => setPast(false)}>AHEAD</button>
          <button className={`pb-chip ${past ? 'on' : ''}`} onClick={() => setPast(true)}>PAST</button>
        </div>
      </header>
      {st.error && <p className="pb-warn">Could not load: {st.error}</p>}
      {st.loading && events.length === 0 && <p className="state-loading">LOADING…</p>}
      {!st.loading && shown.length === 0 && !st.error && <p className="state-empty">{past ? 'NO PAST EVENTS.' : 'NO EVENTS AHEAD.'}</p>}
      <div className="pg-grid">
        {shown.map((e) => {
          const f = fuelFor(e)
          const aid = aidList(e.aid_stations)
          const n = daysUntil(e.event_date, today)
          return (
            <section key={e.id} className="pg-card">
              <h3 className="pg-card-title mono"><span>{e.kind.replaceAll('_', ' ').toUpperCase()}{e.priority ? ` · PRIORITY ${e.priority}` : ''}</span><span>{n === 0 ? 'TODAY' : n > 0 ? `IN ${n} DAYS` : `${-n} DAYS AGO`}</span></h3>
              <p className="pg-row-title" style={{ fontSize: 16, margin: '0 0 4px' }}>{e.name}</p>
              <p className="pg-note">{longDate(e.event_date)}{e.start_time ? ` · ${new Date(e.start_time).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })}` : ''}{e.location_name ? ` · ${e.location_name}` : ''}</p>
              <ul className="pg-rows">
                <li className="pg-row"><span className="pg-row-title">Course</span><span className="pg-row-value">{[e.distance_km != null && `${e.distance_km} km`, e.ascent_m != null && `+${Math.round(e.ascent_m)} m`, e.descent_m != null && `−${Math.round(e.descent_m)} m`].filter(Boolean).join(' · ') || '–'}</span>
                  {e.course_source && <span className="pg-row-sub">from {e.course_source.replaceAll('_', ' ')}</span>}</li>
                <li className="pg-row"><span className="pg-row-title">Target</span><span className="pg-row-value">{e.target_time_min != null ? hm(e.target_time_min) : e.expected_duration_min != null ? `about ${hm(e.expected_duration_min)}` : '–'}</span></li>
                <li className="pg-row"><span className="pg-row-title">Aid stations</span><span className="pg-row-value">{aid.length || '–'}</span>
                  {aid.length > 0 && <span className="pg-row-sub">{aid.map((a) => `${a.name ?? 'aid'}${(a.km ?? a.distance_km) != null ? ` (km ${a.km ?? a.distance_km})` : ''}`).join(' · ')}</span>}</li>
                <li className="pg-row"><span className="pg-row-title">Fuel plan</span><span className="pg-row-value">{f ? `${Array.isArray(f.blocks) ? f.blocks.length : 0} blocks` : 'not saved'}</span>
                  {!f && n >= 0 && <span className="pg-row-sub">Made on the phone, in the event's FUEL tab.</span>}
                  {f && <span className="pg-row-sub">updated {new Date(f.updated_at).toLocaleDateString('en-GB')}{f.duration_min ? ` · for ${hm(f.duration_min)}` : ''}</span>}</li>
                {e.exercise_session_id && <li className="pg-row"><span className="pg-row-title">Result</span><span className="pg-row-value pg-ok">RECORDED</span></li>}
              </ul>
              {e.status !== 'cancelled' && e.status !== 'dns' && <div className="pg-actions"><Link className="btn-ghost mono" to={`/plan/races/${e.id}`}>FUEL PLAN →</Link></div>}
              {e.notes && <p className="pg-note">{e.notes}</p>}
            </section>
          )
        })}
      </div>
    </div>
  )
}
