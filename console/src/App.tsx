import { lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { Shell } from './components/layout/Shell'
import { Login } from './workspaces/Login'
import { MePage, TodayPage } from './dashboard/DayPages'
import { RedirectKeep, RedirectTab } from './components/layout/Redirect'
import { SkinPage } from './components/layout/SkinPage'

// The pages of the web app (docs/web-redesign/README.md): Today and Me (dashboard/DayPages), the Lab, Plan and Setup tab pages,
// the metric page, the Log.
// Planning (Nov 2026): the long-form work prepared on the web and carried out on the phone.
// Where Today's DAY LOG lands, and the metric page every tappable number opens.
const Lab          = lazy(() => import('./workspaces/Lab'))
const Review       = lazy(() => import('./workspaces/Review'))
const Plan         = lazy(() => import('./workspaces/Plan'))
const Setup        = lazy(() => import('./workspaces/Setup'))
const SessionDetail = lazy(() => import('./workspaces/SessionDetail'))
const PlannedSessionEdit = lazy(() => import('./workspaces/PlannedSessionEdit'))
const RaceFuelPage = lazy(() => import('./workspaces/RaceFuel'))
const Metric       = lazy(() => import('./workspaces/Metric'))
const HistoryRedirect = lazy(() => import('./workspaces/Metric').then((m) => ({ default: m.HistoryRedirect })))
// The tab pages: each holds the pages that were separate workspaces before the redesign. The Log and Plan's own pages are
// wrapped in SkinPage here, as the tab pages wrap themselves: all of them in the Futuristic Material look, on the plain ground.

// Routes that moved, with where they went and what the new place needs besides the old query (web redesign, phase 4: the Lab
// merge). A redirect keeps the query it was given, so #/explore?series=hrv&grain=week, #/evidence?question=... and the
// views saved before the merge land on the same thing. The v1 dashboard aliases point straight at the final place.
const MOVED: Record<string, { to: string; add?: Record<string, string> }> = {
  explore: { to: '/analyse/compare', add: { view: 'periods' } },
  evidence: { to: '/analyse/evidence' },
  models: { to: '/analyse/evidence', add: { view: 'trust' } },
  experiments: { to: '/analyse/experiments' },
  interventions: { to: '/analyse/experiments', add: { view: 'interventions' } },
  regimen: { to: '/analyse/regimen' },
  routine: { to: '/analyse/routine' },
  tests: { to: '/analyse/tests' },
  // web redesign, phase 5: Plan and Setup tabs, Body and Risk inside Me
  'plan-block': { to: '/plan/training' }, 'meal-prep': { to: '/plan/food', add: { view: 'prep' } }, sessions: { to: '/plan/sessions' }, races: { to: '/plan/races' },
  integrity: { to: '/data/integrity' }, data: { to: '/data/rows' }, connections: { to: '/data/connections' },
  body: { to: '/me', add: { tab: 'BODY' } }, risk: { to: '/me', add: { tab: 'EXPOSURES' } },
  // the removed v1 dashboards and renamed workspaces
  overview: { to: '/analyse/compare', add: { view: 'periods' } }, timeline: { to: '/analyse/compare', add: { view: 'periods' } },
  training: { to: '/analyse/compare', add: { view: 'periods' } }, recovery: { to: '/analyse/compare', add: { view: 'periods' } },
  nutrition: { to: '/analyse/compare', add: { view: 'periods' } }, comparison: { to: '/analyse/compare', add: { view: 'periods' } },
  'data-explorer': { to: '/data/rows' }, relationship: { to: '/analyse/evidence' }, 'data-quality': { to: '/data/integrity' },
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
        <Route path="m/:ref" element={<Metric />} />
        <Route path="history" element={<HistoryRedirect />} />
        <Route path="analyse" element={<Navigate to="/analyse/compare" replace />} />
        <Route path="review" element={<Navigate to="/review/reports" replace />} />
        <Route path="plan" element={<Navigate to="/plan/overview" replace />} />
        <Route path="plan/races/:id" element={<SkinPage><RaceFuelPage /></SkinPage>} />
        <Route path="plan/sessions/planned/:id" element={<SkinPage><PlannedSessionEdit /></SkinPage>} />
        <Route path="review/sessions/:id" element={<SkinPage><SessionDetail /></SkinPage>} />
        <Route path="plan/sessions/:tab" element={<RedirectTab where={(id) => `/review/sessions/${id}`} fallback="/plan/sessions" />} />
        <Route path="plan/:tab" element={<Plan />} />
        {/* #/data alone is the old Data page: the MOVED table sends it, with its query, to Data › Rows */}
        <Route path="data/:tab" element={<Setup />} />
        <Route path="analyse/:tab" element={<Lab />} />
        <Route path="review/:tab" element={<Review />} />
        {/* the old names (before web desk W1): Lab, Setup and the Log, with the query kept */}
        <Route path="lab" element={<Navigate to="/analyse/compare" replace />} />
        <Route path="lab/:tab" element={<RedirectTab where={(t) => (t === 'reports' ? '/review/reports' : `/analyse/${t}`)} fallback="/analyse/compare" />} />
        <Route path="setup" element={<Navigate to="/data/integrity" replace />} />
        <Route path="setup/:tab" element={<RedirectTab where={(t) => (t === 'data' ? '/data/rows' : `/data/${t}`)} fallback="/data/integrity" />} />
        <Route path="log" element={<RedirectKeep to="/review/log" />} />
        {Object.entries(MOVED).map(([from, m]) => (
          <Route key={from} path={from} element={<RedirectKeep to={m.to} add={m.add} />} />
        ))}
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
