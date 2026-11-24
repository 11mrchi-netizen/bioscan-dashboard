// SETUP (web redesign, phase 5): the plumbing, off to the side as the phone's gear is: is the data arriving and sound
// (INTEGRITY), the raw rows of the canonical tables (DATA), and the outside services (CONNECTIONS). #/setup/<tab>
import { lazy } from 'react'
import { TabbedPage, type TabDef } from '../components/layout/TabbedPage'

const TABS: readonly TabDef[] = [
  { id: 'integrity', label: 'INTEGRITY', View: lazy(() => import('./Integrity')) },
  { id: 'data', label: 'DATA', View: lazy(() => import('./DataExplorer')) },
  { id: 'connections', label: 'CONNECTIONS', View: lazy(() => import('./Connections')) },
]

export default function Setup() {
  return <TabbedPage base="setup" tabs={TABS} />
}
