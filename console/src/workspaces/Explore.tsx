// Explore workspace: the one general charting surface of the analysis hub. It replaces the Overview, Timeline, Training,
// Recovery, Nutrition and Comparison dashboards removed in Phase 0 and is built in Phase B on the aggregation contract
// (docs/analysis-console/PLAN.md).

import { WorkspaceStub } from './WorkspaceStub'

export function Explore() {
  return (
    <WorkspaceStub
      name="Explore"
      phase="Phase B · query foundation"
      purpose="Any metrics over the selected range at a resolution that follows the range (day, week, month), with drill-down from a period to its days and from a day to its source records."
    />
  )
}

export default Explore
