// PLAN › SESSIONS › RECORDED: every recorded workout, newest first (the phone's Plan › Sessions list), each a link to its page. The
// type is a parameter of the page (?type=): by default every workout but the walks, which are auto-detected background movement and would
// bury them. Pages of 30 by keyset, so LOAD MORE never repeats or skips a row.
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { hm, longDate } from '../lib/planReads'
import { loadRecordedPage, PAGE, type ListRow } from '../lib/sessionData'
import { metricHref, sessionSeries, type SessionField } from '../lib/metrics'
import './PlanBlock.css'
import './Pages.css'

const TYPES = ['run', 'strength', 'ride', 'hike', 'yoga', 'other', 'walk']

/** A number of a session that a series follows opens that series on the session's day; the others are plain text. */
function SessionNum({ s, field, children }: { s: ListRow; field: SessionField; children: string }) {
  const id = sessionSeries(s.type, field)
  return id ? <a className="pg-num" href={metricHref(`series:${id}`, { day: s.start_time.slice(0, 10) })}>{children}</a> : <>{children}</>
}

/** Parts joined by " · ", the falsy ones left out. */
const joined = (parts: (React.ReactNode | false | null)[]) => parts.filter(Boolean).flatMap((p, i) => (i ? [' · ', p] : [p]))

export default function RecordedSessions() {
  const [params, setParams] = useSearchParams()
  const type = TYPES.includes(params.get('type') ?? '') ? params.get('type') : null
  const [rows, setRows] = useState<ListRow[]>([])
  const [more, setMore] = useState(false)
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    setBusy(true); setError(null); setRows([])
    loadRecordedPage(type, null).then((r) => { if (live) { setRows(r); setMore(r.length === PAGE); setBusy(false) } })
      .catch((e: unknown) => { if (live) { setError(e instanceof Error ? e.message : String(e)); setBusy(false) } })
    return () => { live = false }
  }, [type])

  const loadMore = async () => {
    const last = rows[rows.length - 1]
    if (!last) return
    setBusy(true)
    try { const r = await loadRecordedPage(type, { start_time: last.start_time, id: last.id }); setRows((x) => [...x, ...r]); setMore(r.length === PAGE) }
    catch (e) { setError(e instanceof Error ? e.message : String(e)) } finally { setBusy(false) }
  }
  const setType = (t: string | null) => { const n = new URLSearchParams(params); if (t) n.set('type', t); else n.delete('type'); setParams(n, { replace: true }) }

  return (
    <div className="pg">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">RECORDED SESSIONS</h2>
          <p className="pg-sub">Every recorded workout, newest first. Open one for its numbers and to add the effort, notes and details; what the watch measured is not edited.</p>
        </div>
        <div className="pb-chips">
          <button className={`pb-chip ${!type ? 'on' : ''}`} onClick={() => setType(null)}>WORKOUTS</button>
          {TYPES.map((t) => <button key={t} className={`pb-chip ${type === t ? 'on' : ''}`} onClick={() => setType(t)}>{t.toUpperCase()}</button>)}
        </div>
      </header>
      {error && <p className="pb-warn">Could not load: {error}</p>}
      {!busy && rows.length === 0 && !error && <p className="state-empty">NO RECORDED SESSIONS OF THIS KIND.</p>}
      <ul className="pg-rows rs-rows">
        {rows.map((s) => (
          <li key={s.id} className="pg-row">
            <span className="pg-row-title">
              <Link to={`/plan/sessions/${s.id}`} className="rs-link"><span className="mono">{longDate(s.start_time.slice(0, 10)).toUpperCase()} · {s.start_time.slice(11, 16)}</span>{'  '}{(s.type ?? 'session').toUpperCase()}</Link>
            </span>
            <span className="pg-row-value mono">{joined([
              s.duration_min != null && <SessionNum key="d" s={s} field="duration">{hm(s.duration_min)}</SessionNum>,
              s.distance_km != null && <SessionNum key="k" s={s} field="distance">{`${s.distance_km.toFixed(1)} km`}</SessionNum>,
            ])}</span>
            <span className="pg-row-sub">{joined([
              s.avg_hr != null && <SessionNum key="h" s={s} field="avg_hr">{`avg ${Math.round(s.avg_hr)} bpm`}</SessionNum>,
              s.rpe != null && <SessionNum key="r" s={s} field="rpe">{`RPE ${s.rpe}`}</SessionNum>,
              s.source?.replaceAll('_', ' ') || null, s.notes || null,
            ])}</span>
          </li>
        ))}
      </ul>
      {more && <button className="btn-ghost mono" disabled={busy} onClick={() => void loadMore()}>{busy ? 'LOADING…' : 'LOAD MORE'}</button>}
    </div>
  )
}
