// A page of several tabs, the same everywhere (web redesign, phase 5): the tab in the path (#/<base>/<tab>), a sub-view in the query
// where a tab has several. Lab, Plan and Setup are built from it. The tabs and views themselves are in the side menu (lib/nav.ts).
// Every tab page is in the Futuristic Material look on the plain ground (SkinPage).
import { Suspense, type ComponentType, type LazyExoticComponent } from 'react'
import { Navigate, useParams } from 'react-router-dom'
import { SkinPage } from './SkinPage'
import './TabbedPage.css'

export interface TabDef { id: string; label: string; View: LazyExoticComponent<ComponentType> }

export function TabbedPage({ base, tabs }: { base: string; tabs: readonly TabDef[] }) {
  const { tab } = useParams()
  const known = tabs.find((t) => t.id === tab)
  if (!known) return <Navigate to={`/${base}/${tabs[0].id}`} replace />
  const View = known.View
  return (
    <SkinPage className="tabbed">
      <Suspense fallback={<div className="state-loading mono">LOADING…</div>}><View /></Suspense>
    </SkinPage>
  )
}
