// PLAN › OVERVIEW (web transition plan, phase 4): where the plan stands. The block under way (end it early, add accessory
// work, as on Plan block), the next two weeks of planned sessions, the next events, and the way into each planning page.
import { useState } from 'react'
import { useLoadEffect } from '../lib/useLoadEffect'
import { localToday } from '../lib/dates'
import { type ExistingBlock, loadExistingBlocks, loadPlannerCatalog } from '../lib/blockPlannerData'
import type { PlannerCatalog } from '../lib/blockPlanner'
import { type AthleteEvent, daysUntil, loadAthleteEvents, loadPlannedSessions, longDate, type PlannedSession } from '../lib/planReads'
import { RunningBlocks } from './PlanBlock'
import { ShiftBlocks } from './ShiftBlocks'
import { PlanSessionRows } from './PlanSessionRows'
import './PlanBlock.css'
import './Pages.css'
import { Board } from '../components/layout/Board'

const plus = (d: string, n: number) => { const x = new Date(`${d}T12:00:00`); x.setDate(x.getDate() + n); return x.toLocaleDateString('sv-SE') }

export default function PlanOverview() {
  const today = localToday()
  const [cat, setCat] = useState<PlannerCatalog | null>(null)
  const [blocks, setBlocks] = useState<ExistingBlock[]>([])
  const [sessions, setSessions] = useState<{ sessions: PlannedSession[]; blockName: Map<number, string>; blockShift?: Map<number, number> }>({ sessions: [], blockName: new Map() })
  const [events, setEvents] = useState<AthleteEvent[]>([])
  const [rev, setRev] = useState(0)
  const st = useLoadEffect(async (cur) => {
    const [c, b, s, e] = await Promise.all([loadPlannerCatalog(), loadExistingBlocks(), loadPlannedSessions(today, plus(today, 13)), loadAthleteEvents()])
    if (cur()) { setCat(c); setBlocks(b); setSessions(s); setEvents(e) }
  }, [today, rev])
  const upcoming = events.filter((e) => e.event_date >= today && e.status !== 'cancelled').slice(0, 4)
  const running = blocks.filter((b) => (b.status === 'scheduled' || b.status === 'active') && b.end_date >= today)
  const next = blocks.filter((b) => b.start_date > today).sort((a, b) => a.start_date.localeCompare(b.start_date))

  return (
    <div className="pg">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">PLAN</h2>
          <p className="pg-sub">Where the plan stands: the block under way, the next two weeks, the next events. Training blocks are planned here and carried out on the phone.</p>
        </div>
        <div className="pb-row tight">
          <a className="btn-ghost mono" href="#/plan/training">PLAN A BLOCK →</a>
          <a className="btn-ghost mono" href="#/plan/food?view=prep">MEAL PREP →</a>
        </div>
      </header>
      {st.error && <p className="pb-warn">Could not load: {st.error}</p>}
      {st.loading && !cat && <p className="state-loading">LOADING…</p>}
      {cat && (
        <>
          {running.length === 0 && <p className="pg-note">No block under way.{next.length ? ` Next: ${next[0].name}, from ${longDate(next[0].start_date)}.` : ''} <a href="#/plan/training">Plan one</a>.</p>}
          <RunningBlocks blocks={blocks} cat={cat} onEnded={() => setRev((r) => r + 1)} onMaxes={() => setRev((r) => r + 1)} />
          <ShiftBlocks blocks={blocks} onMoved={() => setRev((r) => r + 1)} />
          <Board className="pg-grid">
            <section className="pg-card">
              <h3 className="pg-card-title mono"><span>NEXT TWO WEEKS</span><a href="#/plan/sessions">ALL SESSIONS →</a></h3>
              {sessions.sessions.length === 0 ? <p className="pg-note">Nothing planned in the next two weeks.</p>
                : <PlanSessionRows sessions={sessions.sessions} blockName={sessions.blockName} blockShift={sessions.blockShift} today={today} />}
            </section>
            <section className="pg-card">
              <h3 className="pg-card-title mono"><span>NEXT EVENTS</span><a href="#/plan/races">RACES →</a></h3>
              {upcoming.length === 0 ? <p className="pg-note">No events ahead. Races are added on the phone.</p> : (
                <ul className="pg-rows">{upcoming.map((e) => (
                  <li key={e.id} className="pg-row">
                    <span className="pg-row-title">{e.name}{e.priority && <span className="pg-tag mono">{e.priority}</span>}</span>
                    <span className="pg-row-value mono">{daysUntil(e.event_date, today) === 0 ? 'TODAY' : `IN ${daysUntil(e.event_date, today)} DAYS`}</span>
                    <span className="pg-row-sub">{longDate(e.event_date)} · {e.kind.replaceAll('_', ' ')}{e.distance_km ? ` · ${e.distance_km} km` : ''}{e.ascent_m ? ` · +${Math.round(e.ascent_m)} m` : ''}</span>
                  </li>
                ))}</ul>
              )}
            </section>
          </Board>
        </>
      )}
    </div>
  )
}
