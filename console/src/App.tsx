import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { Shell } from './components/layout/Shell'
import { Login } from './workspaces/Login'
import { WorkspaceStub } from './workspaces/WorkspaceStub'

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
        <Route path="overview"      element={<WorkspaceStub name="Overview" />} />
        <Route path="timeline"      element={<WorkspaceStub name="Timeline" />} />
        <Route path="training"      element={<WorkspaceStub name="Training" />} />
        <Route path="recovery"      element={<WorkspaceStub name="Recovery / Body" />} />
        <Route path="nutrition"     element={<WorkspaceStub name="Nutrition" />} />
        <Route path="comparison"    element={<WorkspaceStub name="Comparison" />} />
        <Route path="data-explorer" element={<WorkspaceStub name="Data Explorer" />} />
        <Route path="relationship"  element={<WorkspaceStub name="Relationship Lab" />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
