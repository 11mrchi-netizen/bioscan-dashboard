// LAB › EVIDENCE (was the Evidence workspace and the Models workspace; web redesign, phase 4). The view is in the address
// (#/lab/evidence?view=board), so a link can open any of them. Views:
//   (queue)   the decision queue, the evidence workbench and decision records (phase D; Decisions.tsx). Default; links to a
//             question or a result (lib/links.ts) open the item here.
//   board     the evidence board (analysis review phase D): every question of the fixed catalogue drawn to scale against its
//             no-effect zone with a verdict word, its event and lag curves, and what came before off mornings (Confirmatory.tsx).
//   ledger    every nightly verdict of every question, and every change of verdict (analysis review phase G).
//   events    the event explorer: any logged exposure against a morning outcome, on the nightly engine, in the browser.
//   discovery the corrected discovery screen (phase C2): registrable questions only, false-discovery corrected.
//   trust     how far the models can be trusted (Diagnostics.tsx; was the Models workspace).
// The experiments, interventions, repeatable and fuelling views moved to LAB › EXPERIMENTS.
import { useSearchParams } from 'react-router-dom'
import { Confirmatory } from './Confirmatory'
import { Ledger } from './Ledger'
import { EventExplorer } from './EventExplorer'
import { Discovery } from './Discovery'
import { Decisions } from './Decisions'
import { Diagnostics } from './Diagnostics'
import './Evidence.css'

export const EVIDENCE_VIEWS = [
  { id: 'queue', label: 'DECISIONS' }, { id: 'board', label: 'EVIDENCE BOARD' }, { id: 'ledger', label: 'LEDGER' },
  { id: 'events', label: 'EVENTS' }, { id: 'discovery', label: 'DISCOVERY' }, { id: 'trust', label: 'TRUST' },
] as const

export function Evidence() {
  const [params, setParams] = useSearchParams()
  const view = EVIDENCE_VIEWS.find((v) => v.id === params.get('view'))?.id ?? 'queue'
  const open = (id: string) => setParams(id === 'queue' ? {} : { view: id }, { replace: true })
  return (
    <>
      <div className="tabbed-views" role="group" aria-label="Evidence">
        {EVIDENCE_VIEWS.map((v) => <button key={v.id} className="ft-chip" aria-current={v.id === view} onClick={() => open(v.id)}>{v.label}</button>)}
      </div>
      <div className="rlab">
        {view === 'queue' ? <Decisions />
          : view === 'board' ? <Confirmatory />
          : view === 'ledger' ? <Ledger />
          : view === 'events' ? <EventExplorer />
          : view === 'discovery' ? <Discovery />
          : <Diagnostics />}
      </div>
    </>
  )
}

export default Evidence
