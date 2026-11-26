// REVIEW (web desk, W1): how did the period go? The past, in one place: the weekly and monthly reports, every recorded workout,
// and the day log (with its fixes). #/review/<tab>. Recorded workouts and the log were under Plan and at #/log; the reports
// under Lab (their old addresses redirect, App.tsx).
import { lazy } from 'react'
import { TabbedPage, type TabDef } from '../components/layout/TabbedPage'

const TABS: readonly TabDef[] = [
  { id: 'reports', label: 'REPORTS', View: lazy(() => import('./Reports')) },
  { id: 'sessions', label: 'SESSIONS', View: lazy(() => import('./RecordedSessions')) },
  { id: 'log', label: 'LOG', View: lazy(() => import('./Log')) },
]

export default function Review() {
  return <TabbedPage base="review" tabs={TABS} />
}
