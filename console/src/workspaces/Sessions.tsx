// PLAN › SESSIONS (web transition plan, phase 4): every planned session in a window, week by week: what it is, which block
// and week it belongs to, whether it moved, and what became of it (matched to a recorded session, skipped). Read-only:
// sessions are made by Plan block and moved or confirmed on the phone.
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useLoadEffect } from '../lib/useLoadEffect'
import { localDaysAgo, localToday } from '../lib/dates'
import { loadPlannedSessions, longDate, mondayOf, type PlannedSession } from '../lib/planReads'
import { PlanSessionRows } from './PlanSessionRows'
import './PlanBlock.css'
import './Pages.css'

const plus = (d: string, n: number) => { const x = new Date(`${d}T12:00:00`); x.setDate(x.getDate() + n); return x.toLocaleDateString('sv-SE') }
const WINDOWS = [{ id: 'ahead', label: 'NEXT 6 WEEKS' }, { id: 'recent', label: 'LAST 6 WEEKS' }, { id: 'both', label: 'BOTH' }]

export default function Sessions() {
  const [params, setParams] = useSearchParams()
  const win = params.get('w') ?? 'ahead'
  const status = params.get('status')
  const today = localToday()
  const from = win === 'ahead' ? today : localDaysAgo(41)
  const to = win === 'recent' ? today : plus(today, 41)
  const [d, setD] = useState<{ sessions: PlannedSession[]; blockName: Map<number, string> } | null>(null)
  const st = useLoadEffect(async (cur) => { const x = await loadPlannedSessions(from, to); if (cur()) setD(x) }, [from, to])
  const set = (k: string, v: string | null) => { const n = new URLSearchParams(params); if (v == null) n.delete(k); else n.set(k, v); setParams(n) }
  const all = d?.sessions ?? []
  const statuses = [...new Set(all.map((s) => s.status))].sort()
  const shown = all.filter((s) => !status || s.status === status)
  const weeks = [...new Set(shown.map((s) => mondayOf(s.scheduled_date)))]
  if (win === 'recent') weeks.reverse()

  return (
    <div className="pg">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">SESSIONS</h2>
          <p className="pg-sub">The planned sessions week by week, with what became of each. They come from Plan block and are moved or confirmed on the phone.</p>
        </div>
        <div className="pb-chips">
          {WINDOWS.map((w) => <button key={w.id} className={`pb-chip ${win === w.id ? 'on' : ''}`} onClick={() => set('w', w.id)}>{w.label}</button>)}
        </div>
      </header>
      {statuses.length > 1 && (
        <div className="pb-chips">
          <button className={`pb-chip ${!status ? 'on' : ''}`} onClick={() => set('status', null)}>ALL · {all.length}</button>
          {statuses.map((s) => <button key={s} className={`pb-chip ${status === s ? 'on' : ''}`} onClick={() => set('status', s)}>{s.toUpperCase()} · {all.filter((x) => x.status === s).length}</button>)}
        </div>
      )}
      {st.error && <p className="pb-warn">Could not load: {st.error}</p>}
      {st.loading && !d && <p className="state-loading">LOADING…</p>}
      {d && shown.length === 0 && <p className="state-empty">NO PLANNED SESSIONS IN THIS WINDOW.</p>}
      <div className="pg-grid">
        {weeks.map((w) => {
          const rows = shown.filter((s) => mondayOf(s.scheduled_date) === w)
          return (
            <section key={w} className="pg-card">
              <h3 className="pg-card-title mono"><span>WEEK OF {longDate(w).toUpperCase()}</span><span>{rows.length}</span></h3>
              <PlanSessionRows sessions={rows} blockName={d!.blockName} today={today} />
            </section>
          )
        })}
      </div>
    </div>
  )
}
