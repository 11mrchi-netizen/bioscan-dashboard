import { lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { Shell } from './components/layout/Shell'
import { Login } from './workspaces/Login'

const Overview        = lazy(() => import('./workspaces/Overview'))
const Timeline        = lazy(() => import('./workspaces/Timeline'))
const Training        = lazy(() => import('./workspaces/Training'))
const Recovery        = lazy(() => import('./workspaces/Recovery'))
const Nutrition       = lazy(() => import('./workspaces/Nutrition'))
const DataExplorer    = lazy(() => import('./workspaces/DataExplorer'))
const Comparison      = lazy(() => import('./workspaces/Comparison'))
const RelationshipLab = lazy(() => import('./workspaces/RelationshipLab'))

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
        <Route index element={<Navigate to="overview" replace />} />
        <Route path="overview"      element={<Overview />} />
        <Route path="timeline"      element={<Timeline />} />
        <Route path="training"      element={<Training />} />
        <Route path="recovery"      element={<Recovery />} />
        <Route path="nutrition"     element={<Nutrition />} />
        <Route path="comparison"    element={<Comparison />} />
        <Route path="data-explorer" element={<DataExplorer />} />
        <Route path="relationship"  element={<RelationshipLab />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
