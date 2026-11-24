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
import './Shell.css'

// One web app (web redesign): five tabs in the phone's order, in the rail as on the phone's dock. Setup, the plumbing, sits
// apart at the foot as the phone's gear does.
const GROUPS: { title: string; items: { to: string; label: string }[] }[] = [
  { title: '', items: [{ to: 'today', label: 'Today' }, { to: 'me', label: 'Me' }, { to: 'lab', label: 'Lab' }, { to: 'plan', label: 'Plan' }, { to: 'log', label: 'Log' }] },
  { title: '', items: [{ to: 'setup', label: '⚙ Setup' }] },
]

/** Where to land after Google's consent page sends the browser back (set by Connections before it leaves). */
export const RETURN_KEY = 'ft.console.returnTo'

/**
 * Where the header's range applies: only the pages that read it (analysisStore's range): Integrity and Compare's PERIODS view
 * (Explore). Every other page has its own (a day, a tab, range chips, filters) or none, so the control would only mislead.
 */
function usesGlobalRange(pathname: string, search: string): boolean {
  if (pathname === '/setup/integrity') return true
  return pathname === '/lab/compare' && new URLSearchParams(search).get('view') === 'periods'
}

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
  // Back from Google's consent page: the browser returns to the app's root, so go on to the page that sent it there.
  const navigate = useNavigate()
  useEffect(() => {
    let to: string | null = null
    try { to = sessionStorage.getItem(RETURN_KEY); sessionStorage.removeItem(RETURN_KEY) } catch { /* storage off */ }
    // One or two segments (/log, /setup/connections); a value stored before the Setup move (/connections) still lands, through its redirect.
    if (to && /^\/[a-z-]+(\/[a-z-]+)?$/.test(to)) navigate(to, { replace: true })
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
      <header className="shell-header ft-skin">
        <button className="rail-toggle mono" onClick={() => setRailCollapsed(v => !v)} aria-label="Toggle navigation">
          {railCollapsed ? '›' : '‹'}
        </button>
        <span className="shell-wordmark mono">FIELD TERMINAL</span>
        {usesGlobalRange(location.pathname, location.search) && <DateRangeControl />}
        <div className="header-actions">
          <SavedViews />
          <span className="mono user-label">{user?.email}</span>
          <button className="btn-ghost mono" onClick={signOut}>SIGN OUT</button>
        </div>
      </header>

      <div className="shell-body">
        <nav className="shell-rail ft-skin" aria-label="Workspace navigation">
          {GROUPS.map((g) => (
            <div key={g.title} className="rail-group" role="group" aria-label={g.title}>
              {g.title && <span className="rail-section mono">{railCollapsed ? '—' : g.title}</span>}
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
          <aside className="shell-inspector ft-skin">
            <Inspector onClose={clearInspection} />
          </aside>
        )}
      </div>
    </div>
  )
}
