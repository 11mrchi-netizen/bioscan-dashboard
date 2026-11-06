import { lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { Shell } from './components/layout/Shell'
import { Login } from './workspaces/Login'

// The analysis hub's five workspaces (docs/analysis-console/IA.md §4).
const Integrity    = lazy(() => import('./workspaces/Integrity'))
const Evidence     = lazy(() => import('./workspaces/Evidence'))
const Explore      = lazy(() => import('./workspaces/Explore'))
const DataExplorer = lazy(() => import('./workspaces/DataExplorer'))
const Models       = lazy(() => import('./workspaces/Models'))
const Body         = lazy(() => import('./workspaces/Body'))

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
        <Route index element={<Navigate to="integrity" replace />} />
        <Route path="integrity" element={<Integrity />} />
        <Route path="evidence"  element={<Evidence />} />
        <Route path="explore"   element={<Explore />} />
        <Route path="data"      element={<DataExplorer />} />
        <Route path="models"    element={<Models />} />
        <Route path="body"      element={<Body />} />
        {Object.entries(LEGACY_ROUTES).map(([from, to]) => (
          <Route key={from} path={from} element={<Navigate to={`/${to}`} replace />} />
        ))}
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
