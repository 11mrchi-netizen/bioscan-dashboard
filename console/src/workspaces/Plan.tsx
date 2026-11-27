// PLAN (web redesign, phase 5): what is planned and what was done, in the phone's tab names where a page maps to one
// (Plan › Training, Food, Race, Sessions): OVERVIEW, TRAINING (the block planner), FOOD (the fuel plan, and the meal prep), SESSIONS, GEAR (straps, belt and the rest: when you use them, with and without), RACES.
// #/plan/<tab>
import { lazy } from 'react'
import { TabbedPage, type TabDef } from '../components/layout/TabbedPage'

const TABS: readonly TabDef[] = [
  { id: 'overview', label: 'OVERVIEW', View: lazy(() => import('./PlanOverview')) },
  { id: 'training', label: 'TRAINING', View: lazy(() => import('./PlanBlock')) },
  { id: 'food', label: 'FOOD', View: lazy(() => import('./Food')) },
  { id: 'sessions', label: 'SESSIONS', View: lazy(() => import('./Sessions')) },
  { id: 'gear', label: 'GEAR', View: lazy(() => import('./Gear')) },
  { id: 'races', label: 'RACES', View: lazy(() => import('./Races')) },
]

export default function Plan() {
  return <TabbedPage base="plan" tabs={TABS} />
}
