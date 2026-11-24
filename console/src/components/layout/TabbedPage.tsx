// A page of several tabs, the same everywhere (web redesign, phase 5): a title and a row of chips, the tab in the path
// (#/<base>/<tab>), a sub-view in the query where a tab has several. Lab, Plan and Setup are built from it.
import { Suspense, type ComponentType, type LazyExoticComponent } from 'react'
import { Navigate, NavLink, useParams } from 'react-router-dom'
import './TabbedPage.css'

export interface TabDef { id: string; label: string; View: LazyExoticComponent<ComponentType> }

export function TabbedPage({ title, base, tabs }: { title: string; base: string; tabs: readonly TabDef[] }) {
  const { tab } = useParams()
  const known = tabs.find((t) => t.id === tab)
  if (!known) return <Navigate to={`/${base}/${tabs[0].id}`} replace />
  const View = known.View
  return (
    <div className="tabbed">
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
