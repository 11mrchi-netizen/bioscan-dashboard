// Three-zone shell: left rail · central canvas · right inspector
// IA.md §3.2 — inspector visibility driven by AnalysisContext.inspectionTarget
import { Suspense, useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import { useInspection, useClearInspection } from '../../lib/analysisStore'
import { ErrorBoundary } from '../shared/ErrorBoundary'
import { Inspector } from '../shared/Inspector'
import { DateRangeControl } from './DateRangeControl'
import { SavedViews } from './SavedViews'
import './Shell.css'

// docs/analysis-console/IA.md §3.1
const NAV = [
  { to: 'integrity', label: 'Integrity' },
  { to: 'evidence',  label: 'Evidence' },
  { to: 'explore',   label: 'Explore' },
  { to: 'data',      label: 'Data' },
  { to: 'models',    label: 'Models' },
  { to: 'body',      label: 'Body' },
]

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
  const inspectionTarget = useInspection()
  const clearInspection = useClearInspection()
  const [railCollapsed, setRailCollapsed] = useState(false)

  const inspectorOpen = inspectionTarget !== null

  return (
    <div className={`shell ${railCollapsed ? 'rail-collapsed' : ''} ${inspectorOpen ? 'inspector-open' : ''}`}>
      <header className="shell-header">
        <button className="rail-toggle mono" onClick={() => setRailCollapsed(v => !v)} aria-label="Toggle navigation">
          {railCollapsed ? '›' : '‹'}
        </button>
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
          {NAV.map(({ to, label }) => (
            <NavLink key={to} to={to} className={({ isActive }) => `rail-item mono ${isActive ? 'active' : ''}`}>
              {railCollapsed ? label.slice(0, 2).toUpperCase() : label}
            </NavLink>
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
