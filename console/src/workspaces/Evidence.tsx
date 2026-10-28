// Evidence workspace (was the Relationship Lab). Phase 0 of the analysis hub (docs/analysis-console/PLAN.md) moved the
// existing views here unchanged; Phase D replaces the tabs with the decision queue, the evidence workbench and decision
// records, and the views below become evidence-item views. Views:
//   DECISIONS     the decision queue, the evidence workbench and decision records (phase D; Decisions.tsx). Default tab;
//                 links to a question or a result (lib/links.ts) open the item here.
//   EVIDENCE      the evidence board (analysis review phase D): every question of the fixed catalogue drawn to scale
//                 against its no-effect zone with a verdict word, its event and lag curves, and what came before off
//                 mornings (case-crossover, exploratory). Internal key 'confirmatory'.
//   DISCOVERY     the corrected discovery screen (phase C2; Discovery.tsx), replacing the exploratory lag scan retired in
//                 October 2026: registrable questions only, older two-thirds of the history, false-discovery corrected.
//   EXPERIMENTS   randomised self-experiments (read-only; plans are registered in the app).
//   INTERVENTIONS, FUELING  read-only views of those engines.
//   LEDGER        every nightly verdict of every question, and every change of verdict (analysis review phase G).
//   EVENTS        the event explorer: any logged exposure against a morning outcome, on the nightly engine, in the browser.
// The model diagnostics that used to be a tab here are the Models workspace.

import { useState } from 'react'
import { Confirmatory } from './Confirmatory'
import { Experiments } from './Experiments'
import { Interventions } from './Interventions'
import { Fueling } from './Fueling'
import { Ledger } from './Ledger'
import { EventExplorer } from './EventExplorer'
import { Discovery } from './Discovery'
import { Decisions } from './Decisions'
import './Evidence.css'

type Tab = 'decisions' | 'confirmatory' | 'discovery' | 'experiments' | 'interventions' | 'fueling' | 'ledger' | 'events'

export function Evidence() {
  const [tab, setTab] = useState<Tab>('decisions')
  return (
    <div className="rlab-shell">
      <div className="rlab-tabs">
        <h2 className="rlab-shell-title">Evidence</h2>
        {(['decisions', 'confirmatory', 'ledger', 'events', 'discovery', 'experiments', 'interventions', 'fueling'] as const).map(t => (
          <button key={t} className={`rlab-tab mono ${tab === t ? 'rlab-tab-on' : ''}`} onClick={() => setTab(t)}>{t === 'confirmatory' ? 'EVIDENCE' : t.toUpperCase()}</button>
        ))}
      </div>
      {tab === 'decisions' ? <div className="rlab"><Decisions /></div>
        : tab === 'confirmatory' ? <div className="rlab"><Confirmatory /></div>
        : tab === 'experiments' ? <div className="rlab"><Experiments /></div>
        : tab === 'interventions' ? <div className="rlab"><Interventions /></div>
        : tab === 'fueling' ? <div className="rlab"><Fueling /></div>
        : tab === 'ledger' ? <div className="rlab"><Ledger /></div>
        : tab === 'events' ? <div className="rlab"><EventExplorer /></div>
        : <div className="rlab"><Discovery /></div>}
    </div>
  )
}

export default Evidence
