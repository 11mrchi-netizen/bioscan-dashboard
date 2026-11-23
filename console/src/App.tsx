import { lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { Shell } from './components/layout/Shell'
import { Login } from './workspaces/Login'
import { MePage, TodayPage } from './dashboard/DayPages'

// The analysis hub's five workspaces (docs/analysis-console/IA.md §4).
const Integrity    = lazy(() => import('./workspaces/Integrity'))
const Evidence     = lazy(() => import('./workspaces/Evidence'))
const Explore      = lazy(() => import('./workspaces/Explore'))
const DataExplorer = lazy(() => import('./workspaces/DataExplorer'))
const Models       = lazy(() => import('./workspaces/Models'))
const Body         = lazy(() => import('./workspaces/Body'))
// Planning (Nov 2026): the long-form work prepared on the web and carried out on the phone.
const PlanBlock    = lazy(() => import('./workspaces/PlanBlock'))
const MealPrep     = lazy(() => import('./workspaces/MealPrep'))
// Web transition plan, phase 2: where the Dashboard's DAY LOG and OPEN IN CONSOLE land.
const Log          = lazy(() => import('./workspaces/Log'))
const History      = lazy(() => import('./workspaces/History'))
// Phase 4: the Console catch-up (LAB, PLAN and SYSTEM pages).
const Experiments   = lazy(() => import('./workspaces/LabExperiments'))
const Interventions = lazy(() => import('./workspaces/LabInterventions'))
const Regimen       = lazy(() => import('./workspaces/Regimen'))
const Routine       = lazy(() => import('./workspaces/Routine'))
const Tests         = lazy(() => import('./workspaces/Tests'))
const PlanOverview  = lazy(() => import('./workspaces/PlanOverview'))
const Sessions      = lazy(() => import('./workspaces/Sessions'))
const Races         = lazy(() => import('./workspaces/Races'))
const Connections   = lazy(() => import('./workspaces/Connections'))
const Risk          = lazy(() => import('./workspaces/Risk'))

// Routes of the removed dashboards and the renamed workspaces, so bookmarks still land somewhere sensible.
const LEGACY_ROUTES: Record<string, string> = {
  overview: 'explore', timeline: 'explore', training: 'explore', recovery: 'explore', nutrition: 'explore',
  comparison: 'explore', 'data-explorer': 'data', relationship: 'evidence', 'data-quality': 'integrity',
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
        <Route path="evidence"  element={<Evidence />} />
        <Route path="explore"   element={<Explore />} />
        <Route path="data"      element={<DataExplorer />} />
        <Route path="models"    element={<Models />} />
        <Route path="body"      element={<Body />} />
        <Route path="plan-block" element={<PlanBlock />} />
        <Route path="meal-prep" element={<MealPrep />} />
        <Route path="log" element={<Log />} />
        <Route path="history" element={<History />} />
        <Route path="experiments" element={<Experiments />} />
        <Route path="interventions" element={<Interventions />} />
        <Route path="regimen" element={<Regimen />} />
        <Route path="routine" element={<Routine />} />
        <Route path="tests" element={<Tests />} />
        <Route path="plan" element={<PlanOverview />} />
        <Route path="sessions" element={<Sessions />} />
        <Route path="races" element={<Races />} />
        <Route path="risk" element={<Risk />} />
        <Route path="connections" element={<Connections />} />
        {Object.entries(LEGACY_ROUTES).map(([from, to]) => (
          <Route key={from} path={from} element={<Navigate to={`/${to}`} replace />} />
        ))}
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
