// A page of several tabs, the same everywhere (web redesign, phase 5): the tab in the path (#/<base>/<tab>), a sub-view in the query
// where a tab has several. Lab, Plan and Setup are built from it. The tabs and views themselves are in the side menu (lib/nav.ts).
import { Suspense, type ComponentType, type LazyExoticComponent } from 'react'
import { Navigate, useParams } from 'react-router-dom'
import '../../ftSkin.css'
import './TabbedPage.css'

export interface TabDef { id: string; label: string; View: LazyExoticComponent<ComponentType> }

/**
 * `skin` gives the page the Futuristic Material look (ftSkin.css) on a domain wash of `wash` (a token such as `ft-charge-b`):
 * the page fills the canvas, its cards are glass, its selection platinum. Without it the page keeps the console's look.
 */
export function TabbedPage({ base, tabs, skin = false, wash = 'ft-charge-b' }: { base: string; tabs: readonly TabDef[]; skin?: boolean; wash?: string }) {
  const { tab } = useParams()
  const known = tabs.find((t) => t.id === tab)
  if (!known) return <Navigate to={`/${base}/${tabs[0].id}`} replace />
  const View = known.View
  return (
    <div className={skin ? 'tabbed dash dash-embed ft-page ft-wash ft-skin' : 'tabbed'} style={skin ? { ['--ft-wash' as string]: `var(--${wash})` } : undefined}>
      <Suspense fallback={<div className="state-loading mono">LOADING…</div>}><View /></Suspense>
    </div>
  )
}
