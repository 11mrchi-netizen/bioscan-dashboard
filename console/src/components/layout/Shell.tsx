// Three-zone shell: left rail · central canvas · right inspector
// IA.md §3.2 — inspector visibility driven by AnalysisContext.inspectionTarget
import { useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import { useAnalysis } from '../../context/AnalysisContext'
import { Inspector } from '../shared/Inspector'
import { DateRangeControl } from './DateRangeControl'
import './Shell.css'

const NAV = [
  { to: 'overview',       label: 'Overview' },
  { to: 'timeline',       label: 'Timeline' },
  { to: 'training',       label: 'Training' },
  { to: 'recovery',       label: 'Recovery / Body' },
  { to: 'nutrition',      label: 'Nutrition' },
  { to: 'comparison',     label: 'Comparison' },
  { to: 'data-explorer',  label: 'Data Explorer' },
  { to: 'relationship',   label: 'Relationship Lab' },
]

export function Shell() {
  const { user, signOut } = useAuth()
  const { ctx, clearInspection } = useAnalysis()
  const [railCollapsed, setRailCollapsed] = useState(false)

  const inspectorOpen = ctx.inspectionTarget !== null

  return (
    <div className={`shell ${railCollapsed ? 'rail-collapsed' : ''} ${inspectorOpen ? 'inspector-open' : ''}`}>
      <header className="shell-header">
        <button className="rail-toggle mono" onClick={() => setRailCollapsed(v => !v)} aria-label="Toggle navigation">
          {railCollapsed ? '›' : '‹'}
        </button>
        <span className="shell-wordmark mono">FIELD TERMINAL · CONSOLE</span>
        <DateRangeControl />
        <div className="header-actions">
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
          <Outlet />
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
