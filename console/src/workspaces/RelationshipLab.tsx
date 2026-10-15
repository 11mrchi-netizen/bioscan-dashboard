// Relationship Lab. Views:
//   EVIDENCE      the evidence board (analysis review phase D): every question of the fixed catalogue drawn to scale
//                 against its no-effect zone with a verdict word, its event and lag curves, and what came before off
//                 mornings (case-crossover, exploratory). Internal key 'confirmatory'.
//   EXPLORATORY   retired (analysis review, 2026-10): see RetiredScanNote and docs/analysis-review/README.md, "Revisit".
//   EXPERIMENTS   pre-registered supplement N-of-1 tests (read-only; plans are registered in the app).
//   INTERVENTIONS, FUELING  read-only views of those engines.

import { useState } from 'react'
import { Confirmatory } from './Confirmatory'
import { Experiments } from './Experiments'
import { Interventions } from './Interventions'
import { Fueling } from './Fueling'
import './RelationshipLab.css'

// The exploratory scan ran raw Pearson correlations over +/-14 lags between any two metrics, with no adjustment for day
// of week, trend or carry-over and no correction for the number of looks: seven metrics gave 609 correlations, about 30
// of them "significant" by chance on every view. The owner retired it in October 2026 and asked for it to be revisited.
function RetiredScanNote() {
  return (
    <div className="rlab">
      <div className="card rlab-chart-card">
        <div className="rlab-chart-label mono">EXPLORATORY SCAN · RETIRED · TO BE REVISITED</div>
        <p className="rlab-footnote">
          The lag scan between any two metrics was retired in October 2026. It tested hundreds of raw correlations per view
          with no adjustment for day of week, trends or day-to-day carry-over and no correction for the number of looks, so
          about one in twenty of its bars stood out by chance alone.
        </p>
        <p className="rlab-footnote">
          To revisit: a discovery screen that prewhitens each series, uses effective sample sizes and a false-discovery
          correction, draws chance bands on a lag heatmap, runs on the older two-thirds of the history only, and can register a
          promising pair as a question to confirm on new days. Until then, the EVIDENCE view here and the evidence board in the
          app are the places to read relationships.
        </p>
      </div>
    </div>
  )
}

type Tab = 'confirmatory' | 'exploratory' | 'experiments' | 'interventions' | 'fueling'

export function RelationshipLab() {
  const [tab, setTab] = useState<Tab>('confirmatory')
  return (
    <div className="rlab-shell">
      <div className="rlab-tabs">
        <h2 className="rlab-shell-title">Relationship Lab</h2>
        {(['confirmatory', 'exploratory', 'experiments', 'interventions', 'fueling'] as const).map(t => (
          <button key={t} className={`rlab-tab mono ${tab === t ? 'rlab-tab-on' : ''}`} onClick={() => setTab(t)}>{t === 'confirmatory' ? 'EVIDENCE' : t.toUpperCase()}</button>
        ))}
      </div>
      {tab === 'confirmatory' ? <div className="rlab"><Confirmatory /></div>
        : tab === 'experiments' ? <div className="rlab"><Experiments /></div>
        : tab === 'interventions' ? <div className="rlab"><Interventions /></div>
        : tab === 'fueling' ? <div className="rlab"><Fueling /></div>
        : <RetiredScanNote />}
    </div>
  )
}

export default RelationshipLab
