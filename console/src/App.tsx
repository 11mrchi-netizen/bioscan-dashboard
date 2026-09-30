import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { Shell } from './components/layout/Shell'
import { Login } from './workspaces/Login'
import { Overview } from './workspaces/Overview'
import { Timeline } from './workspaces/Timeline'
import { Training } from './workspaces/Training'
import { Recovery } from './workspaces/Recovery'
import { Nutrition } from './workspaces/Nutrition'
import { DataExplorer } from './workspaces/DataExplorer'
import { Comparison } from './workspaces/Comparison'
import { RelationshipLab } from './workspaces/RelationshipLab'

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
