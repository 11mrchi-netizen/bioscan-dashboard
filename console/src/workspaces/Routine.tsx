// LAB › ROUTINE (web transition plan, phase 4): the practices and pastimes on the routine list with their schedule, and
// what was logged against them in the last 8 weeks. Practices are added and changed on the phone (Lab › ROUTINE); where the
// time went (ribbons, places, phone) is on the Dashboard's Me › Routine. Logged times (started_at, ended_at) are the phone's
// wall clock: their digits are shown as they are.
import { useState } from 'react'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { localDaysAgo } from '../lib/dates'
import { dashboardHref } from '../lib/surfaces'
import './PlanBlock.css'
import './Pages.css'

interface Activity { id: number; name: string; kind: string; category: string | null; target_min: number | null; days: number[] | null; time_of_day: string | null; archived_at: string | null }
interface RLog { id: number; activity_id: number; date: string; started_at: string | null; ended_at: string | null; minutes: number | null; status: string; source: string | null; note: string | null }

const DAY = ['', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
/** The phone's schedule words (domain/routine/Practices.kt scheduleWords). */
function scheduleWords(days: number[] | null): string {
  if (!days || days.length === 0) return 'No schedule'
  const s = [...new Set(days)].sort()
  if (s.length === 7) return 'Every day'
  if (s.join() === '1,2,3,4,5') return 'Weekdays'
  if (s.join() === '6,7') return 'Weekends'
  return s.map((d) => DAY[d]).join(' ')
}
const wallMin = (ts: string) => Number(ts.slice(11, 13)) * 60 + Number(ts.slice(14, 16))
const length = (l: RLog): number => {
  if (l.status === 'skipped') return 0
  if (l.minutes != null) return l.minutes
  if (l.started_at && l.ended_at) { const m = wallMin(l.ended_at) - wallMin(l.started_at); return m < 0 ? m + 1440 : m }
  return 0
}
const minutesWords = (m: number) => { const r = Math.round(m); return r < 60 ? `${r} min` : `${Math.floor(r / 60)} h ${String(r % 60).padStart(2, '0')}` }

export default function Routine() {
  const [acts, setActs] = useState<Activity[]>([])
  const [logs, setLogs] = useState<RLog[]>([])
  const [showArchived, setShowArchived] = useState(false)
  const from = localDaysAgo(55)
  const st = useLoadEffect(async (cur) => {
    const [a, l] = await Promise.all([
      supabase.from('routine_activities').select('id,name,kind,category,target_min,days,time_of_day,archived_at').order('name'),
      supabase.from('routine_logs').select('id,activity_id,date,started_at,ended_at,minutes,status,source,note').gte('date', from).order('date', { ascending: false }),
    ])
    if (a.error) throw new Error(`routine_activities: ${a.error.message}`)
    if (cur()) { setActs((a.data ?? []) as Activity[]); setLogs((l.data ?? []) as RLog[]) }
  }, [from])
  const shown = acts.filter((a) => showArchived || !a.archived_at)
  const nameOf = new Map(acts.map((a) => [a.id, a.name]))

  return (
    <div className="pg">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">ROUTINE</h2>
          <p className="pg-sub">Your practices and pastimes, and what was logged against them in the last 8 weeks. Where the days' time went is on the <a href={dashboardHref('#/me?tab=ROUTINE')}>Me › Routine</a>; a wrong entry is fixed on the phone.</p>
        </div>
        <div className="pb-chips"><button className={`pb-chip ${showArchived ? 'on' : ''}`} onClick={() => setShowArchived((v) => !v)}>{showArchived ? 'HIDE REMOVED' : 'SHOW REMOVED'}</button></div>
      </header>
      {st.error && <p className="pb-warn">Could not load: {st.error}</p>}
      {st.loading && acts.length === 0 && <p className="state-loading">LOADING…</p>}
      <div className="pg-grid">
        {(['practice', 'pastime'] as const).map((kind) => {
          const rows = shown.filter((a) => a.kind === kind)
          return (
            <section key={kind} className="pg-card">
              <h3 className="pg-card-title mono">{kind === 'practice' ? 'PRACTICES' : 'PASTIMES'}</h3>
              {rows.length === 0 ? <p className="pg-note">None yet. They are added on the phone, Lab › ROUTINE.</p> : (
                <ul className="pg-rows">{rows.map((a) => {
                  const mine = logs.filter((l) => l.activity_id === a.id && l.status === 'done' && l.date >= localDaysAgo(27))
                  return (
                    <li key={a.id} className="pg-row">
                      <span className="pg-row-title">{a.name}{a.archived_at && <span className="pg-tag mono">REMOVED</span>}</span>
                      <span className="pg-row-value">{mine.length}× · {minutesWords(mine.reduce((s, l) => s + length(l), 0))}</span>
                      <span className="pg-row-sub">{[scheduleWords(a.days), a.time_of_day?.slice(0, 5), a.target_min ? `${a.target_min} min` : null, a.category?.toLowerCase()].filter(Boolean).join(' · ')} · last 28 days</span>
                    </li>
                  )
                })}</ul>
              )}
            </section>
          )
        })}
        <section className="pg-card wide">
          <h3 className="pg-card-title mono"><span>LOGGED · LAST 8 WEEKS</span><span>{logs.length}</span></h3>
          {logs.length === 0 ? <p className="pg-note">Nothing logged in the last 8 weeks.</p> : (
            <div className="pg-table-wrap"><table className="pb-table">
              <thead><tr><th>DAY</th><th>WHAT</th><th>WHEN</th><th>LENGTH</th><th>HOW</th><th>NOTE</th></tr></thead>
              <tbody>{logs.map((l) => (
                <tr key={l.id}>
                  <td className="mono"><a href={`#/log?day=${l.date}`}>{l.date}</a></td>
                  <td>{nameOf.get(l.activity_id) ?? '—'}</td>
                  <td className="mono">{l.started_at ? `${l.started_at.slice(11, 16)}${l.ended_at ? `–${l.ended_at.slice(11, 16)}` : ''}` : ''}</td>
                  <td className="mono">{l.status === 'skipped' ? 'SKIPPED' : minutesWords(length(l))}</td>
                  <td className="pb-muted">{l.source ?? ''}</td>
                  <td className="pb-muted">{l.note ?? ''}</td>
                </tr>
              ))}</tbody>
            </table></div>
          )}
        </section>
      </div>
    </div>
  )
}
