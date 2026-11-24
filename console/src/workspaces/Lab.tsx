// LAB (web redesign, phase 4): ask, compare, test, decide, in one place. The phone's Lab (what is tried, tested and tracked)
// joined to the console's analysis workspaces. The tab is in the path (#/lab/<tab>), a sub-view in the query (?view=board).
//
//   COMPARE      two ways to look at metrics side by side (see Compare.tsx)
//   EVIDENCE     the decision queue, the evidence board, the ledger, events, discovery, and how far the models can be trusted
//   EXPERIMENTS  randomised self-experiments, interventions, repeatable ones and fuelling experiments
//   REGIMEN      what is taken, and what is left of it
//   TESTS        the tests the phone runs, and every blood draw
//   ROUTINE      practices and what was logged against them
import { lazy, Suspense } from 'react'
import { Navigate, NavLink, useParams } from 'react-router-dom'
import './Lab.css'

const Compare = lazy(() => import('./Compare'))
const Evidence = lazy(() => import('./Evidence'))
const LabExperiments = lazy(() => import('./LabExperiments'))
const Regimen = lazy(() => import('./Regimen'))
const Tests = lazy(() => import('./Tests'))
const Routine = lazy(() => import('./Routine'))

export const LAB_TABS = [
  { id: 'compare', label: 'COMPARE' },
  { id: 'evidence', label: 'EVIDENCE' },
  { id: 'experiments', label: 'EXPERIMENTS' },
  { id: 'regimen', label: 'REGIMEN' },
  { id: 'tests', label: 'TESTS' },
  { id: 'routine', label: 'ROUTINE' },
] as const
type LabTab = typeof LAB_TABS[number]['id']

const VIEWS: Record<LabTab, React.LazyExoticComponent<React.ComponentType>> = {
  compare: Compare, evidence: Evidence, experiments: LabExperiments, regimen: Regimen, tests: Tests, routine: Routine,
}

export default function Lab() {
  const { tab } = useParams()
  const known = LAB_TABS.find((t) => t.id === tab)
  if (!known) return <Navigate to="/lab/compare" replace />
  const View = VIEWS[known.id]
  return (
    <div className="lab">
      <nav className="lab-tabs" aria-label="Lab">
        <h2 className="lab-title">LAB</h2>
        {LAB_TABS.map((t) => (
          <NavLink key={t.id} to={`/lab/${t.id}`} className="ft-chip lab-tab" aria-current={t.id === known.id}>{t.label}</NavLink>
        ))}
      </nav>
      <Suspense fallback={<div className="state-loading mono">LOADING…</div>}><View /></Suspense>
    </div>
  )
}
