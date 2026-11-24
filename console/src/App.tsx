import { lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { Shell } from './components/layout/Shell'
import { Login } from './workspaces/Login'
import { MePage, TodayPage } from './dashboard/DayPages'
import { RedirectKeep } from './components/layout/Redirect'

// The analysis hub's five workspaces (docs/analysis-console/IA.md §4).
const Integrity    = lazy(() => import('./workspaces/Integrity'))
const DataExplorer = lazy(() => import('./workspaces/DataExplorer'))
const Body         = lazy(() => import('./workspaces/Body'))
// Planning (Nov 2026): the long-form work prepared on the web and carried out on the phone.
const PlanBlock    = lazy(() => import('./workspaces/PlanBlock'))
const MealPrep     = lazy(() => import('./workspaces/MealPrep'))
// Web transition plan, phase 2: where the Dashboard's DAY LOG and OPEN IN CONSOLE land.
const Log          = lazy(() => import('./workspaces/Log'))
const Lab          = lazy(() => import('./workspaces/Lab'))
const Metric       = lazy(() => import('./workspaces/Metric'))
const HistoryRedirect = lazy(() => import('./workspaces/Metric').then((m) => ({ default: m.HistoryRedirect })))
// Phase 4: the Console catch-up (LAB, PLAN and SYSTEM pages).
const PlanOverview  = lazy(() => import('./workspaces/PlanOverview'))
const Sessions      = lazy(() => import('./workspaces/Sessions'))
const Races         = lazy(() => import('./workspaces/Races'))
const Connections   = lazy(() => import('./workspaces/Connections'))
const Risk          = lazy(() => import('./workspaces/Risk'))

// Routes that moved, with where they went and what the new place needs besides the old query (web redesign, phase 4: the Lab
// merge). A redirect keeps the query it was given, so #/explore?series=hrv&grain=week, #/evidence?question=... and the
// views saved before the merge land on the same thing. The v1 dashboard aliases point straight at the final place.
const MOVED: Record<string, { to: string; add?: Record<string, string> }> = {
  explore: { to: '/lab/compare', add: { view: 'periods' } },
  evidence: { to: '/lab/evidence' },
  models: { to: '/lab/evidence', add: { view: 'trust' } },
  experiments: { to: '/lab/experiments' },
  interventions: { to: '/lab/experiments', add: { view: 'interventions' } },
  regimen: { to: '/lab/regimen' },
  routine: { to: '/lab/routine' },
  tests: { to: '/lab/tests' },
  // the removed v1 dashboards and renamed workspaces
  overview: { to: '/lab/compare', add: { view: 'periods' } }, timeline: { to: '/lab/compare', add: { view: 'periods' } },
  training: { to: '/lab/compare', add: { view: 'periods' } }, recovery: { to: '/lab/compare', add: { view: 'periods' } },
  nutrition: { to: '/lab/compare', add: { view: 'periods' } }, comparison: { to: '/lab/compare', add: { view: 'periods' } },
  'data-explorer': { to: '/data' }, relationship: { to: '/lab/evidence' }, 'data-quality': { to: '/integrity' },
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { session, loading } = useAuth()
  if (loading) return <div className="state-loading">AUTHENTICATING…</div>
  if (!session) return <Navigate to="/login" replace />
  return <>{children}</>
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <Shell />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="today" replace />} />
        <Route path="today" element={<TodayPage />} />
        <Route path="me" element={<MePage />} />
        <Route path="integrity" element={<Integrity />} />
        <Route path="data"      element={<DataExplorer />} />
        <Route path="body"      element={<Body />} />
        <Route path="plan-block" element={<PlanBlock />} />
        <Route path="meal-prep" element={<MealPrep />} />
        <Route path="log" element={<Log />} />
        <Route path="m/:ref" element={<Metric />} />
        <Route path="history" element={<HistoryRedirect />} />
        <Route path="plan" element={<PlanOverview />} />
        <Route path="sessions" element={<Sessions />} />
        <Route path="races" element={<Races />} />
        <Route path="risk" element={<Risk />} />
        <Route path="connections" element={<Connections />} />
        <Route path="lab" element={<Navigate to="/lab/compare" replace />} />
        <Route path="lab/:tab" element={<Lab />} />
        {Object.entries(MOVED).map(([from, m]) => (
          <Route key={from} path={from} element={<RedirectKeep to={m.to} add={m.add} />} />
        ))}
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
