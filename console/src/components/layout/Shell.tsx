// Three-zone shell: left rail · central canvas · right inspector
// IA.md §3.2 — inspector visibility driven by AnalysisContext.inspectionTarget
import { Suspense, useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import { useInspection, useClearInspection } from '../../lib/analysisStore'
import { ErrorBoundary } from '../shared/ErrorBoundary'
import { Inspector } from '../shared/Inspector'
import { DateRangeControl } from './DateRangeControl'
import { SavedViews } from './SavedViews'
import { dashboardHref } from '../../lib/surfaces'
import './Shell.css'

// Web transition plan, phase 4: five groups in the phone's language. The Console is where the data is dug into, fixed and
// planned; logging stays on the phone and glancing on the Dashboard.
const GROUPS: { title: string; items: { to: string; label: string }[] }[] = [
  { title: 'LOG', items: [{ to: 'log', label: 'Day log' }] },
  { title: 'LAB', items: [
    { to: 'regimen', label: 'Regimen' }, { to: 'experiments', label: 'Experiments' }, { to: 'interventions', label: 'Interventions' },
    { to: 'routine', label: 'Routine' }, { to: 'tests', label: 'Tests' },
  ] },
  { title: 'PLAN', items: [
    { to: 'plan', label: 'Overview' }, { to: 'plan-block', label: 'Plan block' }, { to: 'meal-prep', label: 'Meal prep' },
    { to: 'sessions', label: 'Sessions' }, { to: 'races', label: 'Races' },
  ] },
  { title: 'ANALYSIS', items: [
    { to: 'history', label: 'History' }, { to: 'evidence', label: 'Evidence' }, { to: 'explore', label: 'Explore' },
    { to: 'data', label: 'Data' }, { to: 'models', label: 'Models' }, { to: 'body', label: 'Body' },
    { to: 'risk', label: 'Risk' },
  ] },
  { title: 'SYSTEM', items: [{ to: 'integrity', label: 'Integrity' }, { to: 'connections', label: 'Connections' }] },
]

/** Where to land after Google's consent page sends the browser back (set by Connections before it leaves). */
export const RETURN_KEY = 'ft.console.returnTo'

export function Shell() {
  const { user, signOut } = useAuth()
  const location = useLocation()

  // Perf pass: the 600 kB charting chunk used to start downloading only after the first workspace had loaded. Fetch it as
  // soon as the browser is idle after sign-in, so the first chart does not wait for it (an import of an already-loaded
  // module is free).
  useEffect(() => {
    const idle = (globalThis as { requestIdleCallback?: (cb: () => void) => number }).requestIdleCallback
    const t = idle ? idle(() => { void import('../../lib/echarts') }) : window.setTimeout(() => { void import('../../lib/echarts') }, 1500)
    return () => { if (!idle) window.clearTimeout(t) }
  }, [])
  // Back from Google's consent page: the browser returns to the Console's root, so go on to the page that sent it there.
  const navigate = useNavigate()
  useEffect(() => {
    let to: string | null = null
    try { to = sessionStorage.getItem(RETURN_KEY); sessionStorage.removeItem(RETURN_KEY) } catch { /* storage off */ }
    if (to && /^\/[a-z-]+$/.test(to)) navigate(to, { replace: true })
  }, [navigate])
  const inspectionTarget = useInspection()
  const clearInspection = useClearInspection()
  const [railToggled, setRailCollapsed] = useState(false)
  // Below 1280 px the rail is a bar across the top (Shell.css) that always shows every label.
  const [narrow, setNarrow] = useState(() => window.matchMedia('(max-width: 1279px)').matches)
  useEffect(() => {
    const mq = window.matchMedia('(max-width: 1279px)')
    const on = () => setNarrow(mq.matches)
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])
  const railCollapsed = railToggled && !narrow

  const inspectorOpen = inspectionTarget !== null

  return (
    <div className={`shell ${railCollapsed ? 'rail-collapsed' : ''} ${inspectorOpen ? 'inspector-open' : ''}`}>
      <header className="shell-header">
        <button className="rail-toggle mono" onClick={() => setRailCollapsed(v => !v)} aria-label="Toggle navigation">
          {railCollapsed ? '›' : '‹'}
        </button>
        <a className="btn-ghost mono shell-dash-link" href={dashboardHref()} title="The Dashboard: Today and Me">← DASHBOARD</a>
        <span className="shell-wordmark mono">FIELD TERMINAL · CONSOLE</span>
        <DateRangeControl />
        <div className="header-actions">
          <SavedViews />
          <span className="mono user-label">{user?.email}</span>
          <button className="btn-ghost mono" onClick={signOut}>SIGN OUT</button>
        </div>
      </header>

      <div className="shell-body">
        <nav className="shell-rail" aria-label="Workspace navigation">
          {GROUPS.map((g) => (
            <div key={g.title} className="rail-group" role="group" aria-label={g.title}>
              <span className="rail-section mono">{railCollapsed ? '—' : g.title}</span>
              {g.items.map(({ to, label }) => (
                <NavLink key={to} to={to} className={({ isActive }) => `rail-item mono ${isActive ? 'active' : ''}`}>
                  {railCollapsed ? label.slice(0, 2).toUpperCase() : label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <main className="shell-canvas">
          {/* keyed by route: leaving a crashed workspace and coming back mounts it fresh instead of staying on the error screen */}
          <ErrorBoundary level="workspace" key={location.pathname}>
            <Suspense fallback={<div className="state-loading mono">LOADING…</div>}>
              <Outlet />
            </Suspense>
          </ErrorBoundary>
        </main>

        {inspectorOpen && (
          <aside className="shell-inspector">
            <Inspector onClose={clearInspection} />
          </aside>
        )}
      </div>
    </div>
  )
}
