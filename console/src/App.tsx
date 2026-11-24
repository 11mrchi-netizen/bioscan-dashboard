import { lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { Shell } from './components/layout/Shell'
import { Login } from './workspaces/Login'
import { MePage, TodayPage } from './dashboard/DayPages'
import { RedirectKeep } from './components/layout/Redirect'

// The pages of the web app (docs/web-redesign/README.md): Today and Me (dashboard/DayPages), the Lab, Plan and Setup tab pages,
// the metric page, the Log.
// Planning (Nov 2026): the long-form work prepared on the web and carried out on the phone.
// Where Today's DAY LOG lands, and the metric page every tappable number opens.
const Log          = lazy(() => import('./workspaces/Log'))
const Lab          = lazy(() => import('./workspaces/Lab'))
const Plan         = lazy(() => import('./workspaces/Plan'))
const Setup        = lazy(() => import('./workspaces/Setup'))
const SessionDetail = lazy(() => import('./workspaces/SessionDetail'))
const PlannedSessionEdit = lazy(() => import('./workspaces/PlannedSessionEdit'))
const Metric       = lazy(() => import('./workspaces/Metric'))
const HistoryRedirect = lazy(() => import('./workspaces/Metric').then((m) => ({ default: m.HistoryRedirect })))
// The tab pages: each holds the pages that were separate workspaces before the redesign.

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
  // web redesign, phase 5: Plan and Setup tabs, Body and Risk inside Me
  'plan-block': { to: '/plan/training' }, 'meal-prep': { to: '/plan/food' }, sessions: { to: '/plan/sessions' }, races: { to: '/plan/races' },
  integrity: { to: '/setup/integrity' }, data: { to: '/setup/data' }, connections: { to: '/setup/connections' },
  body: { to: '/me', add: { tab: 'BODY' } }, risk: { to: '/me', add: { tab: 'EXPOSURES' } },
  // the removed v1 dashboards and renamed workspaces
  overview: { to: '/lab/compare', add: { view: 'periods' } }, timeline: { to: '/lab/compare', add: { view: 'periods' } },
  training: { to: '/lab/compare', add: { view: 'periods' } }, recovery: { to: '/lab/compare', add: { view: 'periods' } },
  nutrition: { to: '/lab/compare', add: { view: 'periods' } }, comparison: { to: '/lab/compare', add: { view: 'periods' } },
  'data-explorer': { to: '/setup/data' }, relationship: { to: '/lab/evidence' }, 'data-quality': { to: '/setup/integrity' },
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
        <Route path="log" element={<Log />} />
        <Route path="m/:ref" element={<Metric />} />
        <Route path="history" element={<HistoryRedirect />} />
        <Route path="lab" element={<Navigate to="/lab/compare" replace />} />
        <Route path="plan" element={<Navigate to="/plan/overview" replace />} />
        <Route path="plan/sessions/planned/:id" element={<PlannedSessionEdit />} />
        <Route path="plan/sessions/:id" element={<SessionDetail />} />
        <Route path="plan/:tab" element={<Plan />} />
        <Route path="setup" element={<Navigate to="/setup/integrity" replace />} />
        <Route path="setup/:tab" element={<Setup />} />
        <Route path="lab/:tab" element={<Lab />} />
        {Object.entries(MOVED).map(([from, m]) => (
          <Route key={from} path={from} element={<RedirectKeep to={m.to} add={m.add} />} />
        ))}
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
