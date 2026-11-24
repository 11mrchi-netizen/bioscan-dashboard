// PLAN (web redesign, phase 5): what is planned and what was done, in the phone's tab names where a page maps to one
// (Plan › Training, Food, Race, Sessions): OVERVIEW, TRAINING (the block planner), FOOD (meal prep), SESSIONS, RACES.
// The pages themselves are unchanged. #/plan/<tab>
import { lazy } from 'react'
import { TabbedPage, type TabDef } from '../components/layout/TabbedPage'

const TABS: readonly TabDef[] = [
  { id: 'overview', label: 'OVERVIEW', View: lazy(() => import('./PlanOverview')) },
  { id: 'training', label: 'TRAINING', View: lazy(() => import('./PlanBlock')) },
  { id: 'food', label: 'FOOD', View: lazy(() => import('./MealPrep')) },
  { id: 'sessions', label: 'SESSIONS', View: lazy(() => import('./Sessions')) },
  { id: 'races', label: 'RACES', View: lazy(() => import('./Races')) },
]

export default function Plan() {
  return <TabbedPage title="PLAN" base="plan" tabs={TABS} />
}
