// Today and Me inside the one shell (web redesign, phase 1): the pages of the former Dashboard surface, now two routes beside the
// rest. The chosen day lives in the address (#/today?day=YYYY-MM-DD) so a past day can be bookmarked or linked to.
import { useSearchParams } from 'react-router-dom'
import { localToday } from '../lib/dates'
import { Today } from './Today'
import { Me } from './Me'

function useDay(): [string, (d: string) => void] {
  const [params, setParams] = useSearchParams()
  const today = localToday()
  const raw = params.get('day')
  const day = raw && /^\d{4}-\d{2}-\d{2}$/.test(raw) && raw <= today ? raw : today
  return [day, (d) => setParams(d === today ? {} : { day: d })]
}

/** The domain wash behind a page (visual contract: background = domain). */
export function Wash({ hue, children }: { hue: string; children: React.ReactNode }) {
  return <div className="dash dash-embed ft-page ft-wash" style={{ ['--ft-wash' as string]: hue }}>{children}</div>
}

export function TodayPage() {
  const [day, setDay] = useDay()
  return (
    <Wash hue="var(--ft-charge-b)">
      <div className="dash-bar">
        <input className="dash-date" type="date" value={day} max={localToday()} aria-label="Day" onChange={(e) => e.target.value && setDay(e.target.value)} />
      </div>
      <main className="dash-main"><Today day={day} onDay={setDay} /></main>
    </Wash>
  )
}

export function MePage() {
  return (
    <Wash hue="var(--ft-domain-balance)">
      <main className="dash-main"><Me /></main>
    </Wash>
  )
}
