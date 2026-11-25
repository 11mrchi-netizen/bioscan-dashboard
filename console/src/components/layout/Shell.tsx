// Shell: one top bar, a side menu that can be hidden, the page, and the Inspector on the right.
// IA.md §3.2 — inspector visibility driven by AnalysisContext.inspectionTarget
import { Suspense, useEffect, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import { useInspection, useClearInspection } from '../../lib/analysisStore'
import { usePageTitle } from '../../lib/pageTitle'
import { ErrorBoundary } from '../shared/ErrorBoundary'
import { Inspector } from '../shared/Inspector'
import { DateRangeControl } from './DateRangeControl'
import { SavedViews } from './SavedViews'
import { crumbWords, SideMenu } from './SideMenu'
import './Shell.css'

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

const MENU_KEY = 'ft.menu.open'
const readMenu = (): boolean => { try { return localStorage.getItem(MENU_KEY) !== '0' } catch { return true } }
const writeMenu = (open: boolean) => { try { localStorage.setItem(MENU_KEY, open ? '1' : '0') } catch { /* storage off */ } }

export function Shell() {
  const { user, signOut } = useAuth()
  const location = useLocation()

  // Perf pass: the 600 kB charting chunk used to start downloading only after the first workspace had loaded. Fetch it as
  // soon as the browser is idle after sign-in, so the first chart does not wait for it (an import of an already-loaded
  // module is free). Not on a slow or data-saving connection (205 kB gzipped, and Today has no charts): there the chart
  // pages load it when they are opened, as every page still does on its own.
  useEffect(() => {
    const conn = (navigator as { connection?: { saveData?: boolean; effectiveType?: string } }).connection
    if (conn?.saveData || /(^|-)(2g|3g)$/.test(conn?.effectiveType ?? '')) return
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
  const pageTitle = usePageTitle((s) => s.title)

  // Below 1280 px the menu is a drawer over the page: closed until asked for, closed again by choosing a page or Escape. Above it
  // it sits beside the page and the choice (shown or hidden) is remembered.
  const [narrow, setNarrow] = useState(() => window.matchMedia('(max-width: 1279px)').matches)
  const [menuOpen, setMenuOpen] = useState(() => (window.matchMedia('(max-width: 1279px)').matches ? false : readMenu()))
  useEffect(() => {
    const mq = window.matchMedia('(max-width: 1279px)')
    const on = () => { setNarrow(mq.matches); setMenuOpen(mq.matches ? false : readMenu()) }
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])
  const toggleMenu = () => setMenuOpen((open) => { if (!narrow) writeMenu(!open); return !open })
  useEffect(() => { if (narrow) setMenuOpen(false) }, [location.pathname, location.search, narrow])
  useEffect(() => {
    if (!narrow || !menuOpen) return
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') setMenuOpen(false) }
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [narrow, menuOpen])

  const inspectorOpen = inspectionTarget !== null
  const words = crumbWords(location.pathname, location.search, pageTitle)

  return (
    <div className={`shell ${menuOpen ? 'menu-open' : 'menu-closed'} ${narrow ? 'narrow' : ''} ${inspectorOpen ? 'inspector-open' : ''}`}>
      <header className="shell-header ft-skin">
        <button className="menu-toggle mono" onClick={toggleMenu} aria-label={menuOpen ? 'Hide the menu' : 'Show the menu'} aria-expanded={menuOpen}>☰</button>
        <h1 className="crumbs mono" aria-label="Where you are">
          {words.map((w, i) => <span key={i} className={i === words.length - 1 ? 'crumb here' : 'crumb'}>{i > 0 && <i aria-hidden="true"> › </i>}{w}</span>)}
        </h1>
        {usesGlobalRange(location.pathname, location.search) && <DateRangeControl />}
        <div className="header-actions">
          <SavedViews />
          {!narrow && <span className="mono user-label">{user?.email}</span>}
          {!narrow && <button className="btn-ghost mono" onClick={signOut}>SIGN OUT</button>}
        </div>
      </header>

      <div className="shell-body">
        <SideMenu email={user?.email} onSignOut={signOut} narrow={narrow} />
        {narrow && menuOpen && <div className="menu-scrim" onClick={() => setMenuOpen(false)} aria-hidden="true" />}

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
