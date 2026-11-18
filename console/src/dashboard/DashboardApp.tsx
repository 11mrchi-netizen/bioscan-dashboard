import { Navigate, NavLink, Route, Routes, useSearchParams } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { Login } from '../workspaces/Login'
import { localToday } from '../lib/dates'
import { consoleHref, legacyHref } from '../lib/surfaces'
import { Today } from './Today'
import { Me } from './Me'

export default function DashboardApp() {
  const { session, loading } = useAuth()
  if (loading) return <div className="state-loading">AUTHENTICATING…</div>
  if (!session) return <Login title="Dashboard" body="How you are today, or on any day before, and the slow-moving picture. Logging stays on the phone." />
  return (
    <Routes>
      <Route path="/today" element={<DashShell tab="today" />} />
      <Route path="/me" element={<DashShell tab="me" />} />
      <Route path="*" element={<Navigate to="/today" replace />} />
    </Routes>
  )
}

/** The chosen day lives in the address (#/today?day=YYYY-MM-DD) so a past day can be bookmarked or linked to. */
function useDay(): [string, (d: string) => void] {
  const [params, setParams] = useSearchParams()
  const today = localToday()
  const raw = params.get('day')
  const day = raw && /^\d{4}-\d{2}-\d{2}$/.test(raw) && raw <= today ? raw : today
  return [day, (d) => setParams(d === today ? {} : { day: d })]
}

function DashShell({ tab }: { tab: 'today' | 'me' }) {
  const { user, signOut } = useAuth()
  const [day, setDay] = useDay()
  const q = day === localToday() ? '' : `?day=${day}`
  return (
    <div className="dash ft-page ft-wash" style={{ ['--ft-wash' as string]: tab === 'today' ? 'var(--ft-charge-b)' : 'var(--ft-domain-balance)' }}>
      <header className="dash-header">
        <span className="dash-wordmark">FIELD TERMINAL · DASHBOARD</span>
        <nav className="ft-seg dash-tabs" aria-label="Dashboard">
          <NavLink to={`/today${q}`}>{({ isActive }) => <span aria-current={isActive}>TODAY</span>}</NavLink>
          <NavLink to={`/me${q}`}>{({ isActive }) => <span aria-current={isActive}>ME</span>}</NavLink>
        </nav>
        {tab === 'today' && (
          <input className="dash-date" type="date" value={day} max={localToday()} aria-label="Day" onChange={(e) => e.target.value && setDay(e.target.value)} />
        )}
        <div className="dash-actions">
          <a className="dash-link" href={consoleHref()}>CONSOLE →</a>
          <span className="dash-user">{user?.email}</span>
          <button className="dash-link" onClick={signOut}>SIGN OUT</button>
        </div>
      </header>
      <p className="dash-banner">
        The new Dashboard is being built in phases; until it is complete, the previous one stays at <a href={legacyHref()}>/legacy/</a>.
      </p>
      <main className="dash-main">{tab === 'today' ? <Today day={day} onDay={setDay} /> : <Me />}</main>
    </div>
  )
}
