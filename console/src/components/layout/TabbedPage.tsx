// A page of several tabs, the same everywhere (web redesign, phase 5): a title and a row of chips, the tab in the path
// (#/<base>/<tab>), a sub-view in the query where a tab has several. Lab, Plan and Setup are built from it.
import { Suspense, type ComponentType, type LazyExoticComponent } from 'react'
import { Navigate, NavLink, useParams } from 'react-router-dom'
import '../../ftSkin.css'
import './TabbedPage.css'

export interface TabDef { id: string; label: string; View: LazyExoticComponent<ComponentType> }

/**
 * `skin` gives the page the Futuristic Material look (ftSkin.css) on a domain wash of `wash` (a token such as `ft-charge-b`):
 * the page fills the canvas, its cards are glass, its selection platinum. Without it the page keeps the console's look.
 */
export function TabbedPage({ title, base, tabs, skin = false, wash = 'ft-charge-b' }: { title: string; base: string; tabs: readonly TabDef[]; skin?: boolean; wash?: string }) {
  const { tab } = useParams()
  const known = tabs.find((t) => t.id === tab)
  if (!known) return <Navigate to={`/${base}/${tabs[0].id}`} replace />
  const View = known.View
  return (
    <div className={skin ? 'tabbed dash dash-embed ft-page ft-wash ft-skin' : 'tabbed'} style={skin ? { ['--ft-wash' as string]: `var(--${wash})` } : undefined}>
      <nav className="tabbed-tabs" aria-label={title}>
        <h2 className="tabbed-title">{title}</h2>
        {tabs.map((t) => (
          <NavLink key={t.id} to={`/${base}/${t.id}`} className="ft-chip tabbed-tab" aria-current={t.id === known.id}>{t.label}</NavLink>
        ))}
      </nav>
      <Suspense fallback={<div className="state-loading mono">LOADING…</div>}><View /></Suspense>
    </div>
  )
}
