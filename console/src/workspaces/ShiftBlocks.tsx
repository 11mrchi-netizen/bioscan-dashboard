// PLAN › OVERVIEW › blocks that have not begun: move a block's start. The block, its dates and all its sessions move together, in one
// database transaction that refuses a block that overlaps another, falls outside its training cycle, or has begun (a session linked,
// confirmed or past). Sessions already on the watch are replaced the next time the phone sends.
import { useState } from 'react'
import { localToday } from '../lib/dates'
import { longDate } from '../lib/planReads'
import { MigrationMissing, shiftTrainingBlock } from '../lib/planEditData'
import type { ExistingBlock } from '../lib/blockPlannerData'
import './PlanBlock.css'
import './Pages.css'

const days = (a: string, b: string) => Math.round((Date.parse(`${b}T12:00:00Z`) - Date.parse(`${a}T12:00:00Z`)) / 864e5)

export function ShiftBlocks({ blocks, onMoved }: { blocks: ExistingBlock[]; onMoved: () => void }) {
  const today = localToday()
  const ahead = blocks.filter((b) => (b.status === 'scheduled' || b.status === 'draft') && b.start_date >= today).sort((a, b) => a.start_date.localeCompare(b.start_date))
  if (ahead.length === 0) return null
  return (
    <section className="pg-card" aria-label="Blocks that have not begun">
      <h3 className="pg-card-title mono"><span>BLOCKS THAT HAVE NOT BEGUN</span></h3>
      <ul className="pg-rows">{ahead.map((b) => <Row key={b.id} b={b} today={today} onMoved={onMoved} />)}</ul>
    </section>
  )
}

function Row({ b, today, onMoved }: { b: ExistingBlock; today: string; onMoved: () => void }) {
  const [start, setStart] = useState(b.start_date)
  const [asking, setAsking] = useState(false)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const delta = /^\d{4}-\d{2}-\d{2}$/.test(start) ? days(b.start_date, start) : 0
  const move = async () => {
    setBusy(true); setMsg(null)
    try { await shiftTrainingBlock(b.id, today, start); setAsking(false); setMsg({ ok: true, text: `Moved to ${longDate(start)}.` }); onMoved() }
    catch (e) { setAsking(false); setMsg({ ok: false, text: e instanceof MigrationMissing ? e.message : e instanceof Error ? e.message : String(e) }) }
    finally { setBusy(false) }
  }
  return (
    <li className="pg-row">
      <span className="pg-row-title">{b.name}</span>
      <span className="pg-row-value mono">{longDate(b.start_date).toUpperCase()} → {longDate(b.end_date).toUpperCase()}</span>
      <span className="pg-row-sub">
        <label>MOVE THE START TO <input className="pb-input" type="date" min={today} value={start} onChange={(e) => { setStart(e.target.value); setAsking(false); setMsg(null) }} aria-label={`New start of ${b.name}`} /></label>{' '}
        <button className="btn-ghost mono" disabled={busy || delta === 0 || start < today} onClick={() => setAsking(true)}>MOVE…</button>
        {asking && (
          <span className="sd-confirm"> <span className="pb-warn">Moves "{b.name}" {Math.abs(delta)} day{Math.abs(delta) === 1 ? '' : 's'} {delta > 0 ? 'later' : 'earlier'}, to {longDate(start)}, and every session in it. If it was built toward an event, its taper no longer lines up with it. Sessions on the watch are replaced when the phone next sends.</span>
            <button className="pb-primary mono" disabled={busy} onClick={() => void move()}>{busy ? 'MOVING…' : 'YES, MOVE IT'}</button>
            <button className="btn-ghost mono" onClick={() => setAsking(false)}>CANCEL</button></span>
        )}
        {msg && <span className={msg.ok ? 'pb-ok' : 'pb-warn'} role="status"> {msg.text}</span>}
      </span>
    </li>
  )
}
