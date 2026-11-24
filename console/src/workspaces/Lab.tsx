// LAB (web redesign, phase 4): ask, compare, test, decide, in one place. The phone's Lab (what is tried, tested and tracked)
// joined to the console's analysis workspaces. The tab is in the path (#/lab/<tab>), a sub-view in the query (?view=board).
//
//   COMPARE      two ways to look at metrics side by side (see Compare.tsx)
//   EVIDENCE     the decision queue, the evidence board, the ledger, events, discovery, and how far the models can be trusted
//   EXPERIMENTS  randomised self-experiments, interventions, repeatable ones and fuelling experiments
//   REGIMEN      what is taken, and what is left of it
//   TESTS        the tests the phone runs, and every blood draw
//   ROUTINE      practices and what was logged against them
import { lazy } from 'react'
import { TabbedPage, type TabDef } from '../components/layout/TabbedPage'

const TABS: readonly TabDef[] = [
  { id: 'compare', label: 'COMPARE', View: lazy(() => import('./Compare')) },
  { id: 'evidence', label: 'EVIDENCE', View: lazy(() => import('./Evidence')) },
  { id: 'experiments', label: 'EXPERIMENTS', View: lazy(() => import('./LabExperiments')) },
  { id: 'regimen', label: 'REGIMEN', View: lazy(() => import('./Regimen')) },
  { id: 'tests', label: 'TESTS', View: lazy(() => import('./Tests')) },
  { id: 'routine', label: 'ROUTINE', View: lazy(() => import('./Routine')) },
]

export default function Lab() {
  return <TabbedPage base="lab" tabs={TABS} skin wash="ft-charge-b" />
}
