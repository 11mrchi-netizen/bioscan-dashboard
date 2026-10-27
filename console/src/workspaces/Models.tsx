// Models workspace: how far the analysis models can be trusted (expected-band calibration, flags and regimes, series
// breaks, typicality and its scorecard, the personal fitness-fatigue spans). Was the Relationship Lab's DIAGNOSTICS tab
// until Phase 0 of the analysis hub (docs/analysis-console/PLAN.md).

import { Diagnostics } from './Diagnostics'
import './Evidence.css'

export function Models() {
  return (
    <div className="rlab-shell">
      <div className="rlab-tabs">
        <h2 className="rlab-shell-title">Models</h2>
      </div>
      <div className="rlab"><Diagnostics /></div>
    </div>
  )
}

export default Models
