// ANALYSIS › RISK › AS WORKED OUT ON THE PHONE (risk profile R3-R5): the phone's preventive care, substances, home and
// water, heart, sugar and brain screens, read from its published Me › EXPOSURES document (me_views) and drawn here in the
// console's row style, so the numbers are the phone's own and nothing is worked out twice. Only tiles, rows and notes
// occur in these sections. The air and the questionnaires have their own views above, so they are left out here.
import { useEffect, useState } from 'react'
import { loadMeView, type MeItem, type MeSection } from '../dashboard/meData'
import { Board } from '../components/layout/Board'

const TONE: Record<string, string> = { ok: 'var(--ft-ok, #10B981)', warn: 'var(--ft-warn, #F59E0B)', bad: 'var(--ft-bad, #EF4444)' }
// Sections the console draws itself (air, questionnaires): skipped here.
const OWN = /^(AIR|WAYS TO IMPROVE|OUTDOOR SESSIONS|MIND|CONNECTION|WORK)\b/

function Item({ it }: { it: MeItem }) {
  if (it.kind === 'note') return <li className="pg-row"><span className="pg-note" style={{ margin: 0 }}>{it.text}</span></li>
  if (it.kind === 'tile' || it.kind === 'row') {
    const value = it.kind === 'tile' ? `${it.value}${it.unit ? ` ${it.unit}` : ''}` : it.value
    return (
      <li className="pg-row" style={{ alignItems: 'flex-start' }}>
        <span className="pg-row-title" style={{ flex: 1 }}>
          {it.label}
          {it.sub && <span className="pg-note" style={{ display: 'block', margin: '2px 0 0' }}>{it.sub}</span>}
        </span>
        {value && <span className="pg-row-value" style={{ color: it.tone ? TONE[it.tone] : undefined }}>{value}</span>}
      </li>
    )
  }
  return null
}

export function RiskFromPhone() {
  const [sections, setSections] = useState<MeSection[] | null>(null)
  const [builtAt, setBuiltAt] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    let live = true
    loadMeView('EXPOSURES').then((v) => {
      if (!live) return
      setSections(v ? v.view.sections.filter((s) => !OWN.test(s.title)) : [])
      setBuiltAt(v?.builtAt ?? null)
    }).catch((e) => live && setError(String(e)))
    return () => { live = false }
  }, [])
  if (error) return <p className="pb-warn">Could not read the phone's Me › EXPOSURES: {error}</p>
  if (!sections) return null
  if (sections.length === 0) return <p className="pg-note">The phone has not published its screens yet: open Me › EXPOSURES on the phone once.</p>
  return (
    <>
      <p className="pg-note">As worked out on the phone{builtAt ? `, ${builtAt.slice(0, 16).replace('T', ' ')} UTC` : ''}: answer the background, the checklist and preventive records there.</p>
      <Board className="pg-grid">
        {sections.map((s) => (
          <section key={s.title} className="pg-card">
            <h3 className="pg-card-title mono"><span>{s.title}</span></h3>
            <ul className="pg-rows">{s.items.map((it, i) => <Item key={i} it={it} />)}</ul>
          </section>
        ))}
      </Board>
    </>
  )
}
