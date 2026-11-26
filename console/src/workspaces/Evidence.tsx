// LAB › EVIDENCE (was the Evidence workspace and the Models workspace; web redesign, phase 4). The view is in the address
// (#/analyse/evidence?view=board), so a link can open any of them; the side menu lists them (lib/nav.ts). Views:
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

export function Evidence() {
  const [params] = useSearchParams()
  const view = ['board', 'ledger', 'events', 'discovery', 'trust'].find((v) => v === params.get('view')) ?? 'queue'
  return (
    <div className="rlab">
      {view === 'queue' ? <Decisions />
        : view === 'board' ? <Confirmatory />
        : view === 'ledger' ? <Ledger />
        : view === 'events' ? <EventExplorer />
        : view === 'discovery' ? <Discovery />
        : <Diagnostics />}
    </div>
  )
}

export default Evidence
