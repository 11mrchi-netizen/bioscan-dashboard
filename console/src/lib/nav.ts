// The navigation of the web app as data (web redesign, follow-up): one tree of sections, tabs and views, read by the side menu
// and by the breadcrumb in the top bar, so they cannot disagree with each other or with the pages' own defaults.
//
//   section  Today, Me, Plan, Analyse, Review, Data    (web desk, W1: the places by the job you came to do; Data is the gear)
//   tab      a page of a section: #/analyse/<tab>, or Me's ?tab=
//   view     a way of seeing a tab: ?view=            (the first view of a tab is the page's default, written as no parameter)
//
// Parameters that are not navigation (range chips, windows, filters, the metrics of a comparison) stay in their pages; a menu
// link that changes only the tab or the view keeps them (`navTo`).

export interface NavView { id: string; label: string; /** the ?view= value; null when it is the default (no parameter) */ value: string | null }
export interface NavTab { id: string; label: string; views?: readonly NavView[]; /** the view a page under the tab (#/plan/sessions/<id>) belongs to */ detailView?: string }
export interface NavSection { id: string; label: string; /** Me: the tab is the ?tab= parameter, not part of the path */ tabParam?: 'tab'; tabs?: readonly NavTab[] }

/** The phone's Me tabs, in the phone's order (MeScreen.kt); BODY is the page's default when no tab is given. */
export const ME_TAB_IDS = ['HEALTHSPAN', 'PERFORMANCE', 'BODY', 'HEALTH', 'BLOODWORK', 'INTIMACY', 'ROUTINE', 'EXPOSURES'] as const

/** Me › INTIMACY's views (workspaces/Intimacy.tsx), the one Me tab the web draws itself rather than from me_views. */
export const INTIMACY_VIEWS: readonly NavView[] = [
  { id: 'overview', label: 'Overview', value: null }, { id: 'encounters', label: 'Encounters', value: 'encounters' }, { id: 'mix', label: 'Mix', value: 'mix' },
  { id: 'solo', label: 'Solo', value: 'solo' }, { id: 'partners', label: 'Partners', value: 'partners' }, { id: 'health', label: 'Health', value: 'health' },
  { id: 'effects', label: 'Effects', value: 'effects' },
]
export const ME_DEFAULT_TAB = 'BODY'

const title = (s: string) => s[0] + s.slice(1).toLowerCase()

export const NAV: readonly NavSection[] = [
  { id: 'today', label: 'Today' },
  { id: 'me', label: 'Me', tabParam: 'tab', tabs: ME_TAB_IDS.map((t) => ({ id: t, label: title(t), ...(t === 'INTIMACY' ? { views: INTIMACY_VIEWS } : {}) })) },
  { id: 'plan', label: 'Plan', tabs: [
    { id: 'overview', label: 'Overview' }, { id: 'training', label: 'Training' }, { id: 'food', label: 'Food', views: [{ id: 'plan', label: 'Fuel plan', value: null }, { id: 'prep', label: 'Meal prep', value: 'prep' }] },
    { id: 'sessions', label: 'Sessions' },
    { id: 'races', label: 'Races' },
  ] },
  { id: 'analyse', label: 'Analyse', tabs: [
    { id: 'compare', label: 'Compare', views: [{ id: 'days', label: 'Days', value: null }, { id: 'periods', label: 'Periods', value: 'periods' }] },
    { id: 'evidence', label: 'Evidence', views: [
      { id: 'queue', label: 'Decisions', value: null }, { id: 'board', label: 'Evidence board', value: 'board' }, { id: 'ledger', label: 'Ledger', value: 'ledger' },
      { id: 'events', label: 'Events', value: 'events' }, { id: 'discovery', label: 'Discovery', value: 'discovery' }, { id: 'trust', label: 'Trust', value: 'trust' },
    ] },
    { id: 'experiments', label: 'Experiments', views: [
      { id: 'randomised', label: 'Randomised', value: null }, { id: 'interventions', label: 'Interventions', value: 'interventions' },
      { id: 'repeatable', label: 'Repeatable', value: 'repeatable' }, { id: 'fueling', label: 'Fuelling', value: 'fueling' },
    ] },
    { id: 'regimen', label: 'Regimen' }, { id: 'tests', label: 'Tests' }, { id: 'routine', label: 'Routine' },
  ] },
  { id: 'review', label: 'Review', tabs: [{ id: 'reports', label: 'Reports' }, { id: 'sessions', label: 'Sessions' }, { id: 'log', label: 'Log' }] },
  { id: 'data', label: 'Data', tabs: [{ id: 'integrity', label: 'Integrity' }, { id: 'rows', label: 'Rows' }, { id: 'connections', label: 'Connections' }] },
]

export interface NavLocation { section: NavSection | null; tab: NavTab | null; view: NavView | null }

/** Which section, tab and view an address is on. A page with no ?tab= or ?view= is on its default, as the page itself is. */
export function locate(pathname: string, search: string): NavLocation {
  const parts = pathname.replace(/^\/+/, '').split('/')
  const section = NAV.find((s) => s.id === parts[0]) ?? null
  if (!section) return { section: null, tab: null, view: null }
  const q = new URLSearchParams(search)
  let tab: NavTab | null = null
  if (section.tabs) {
    const want = section.tabParam ? (q.get(section.tabParam) ?? ME_DEFAULT_TAB).toUpperCase() : parts[1]
    tab = section.tabs.find((t) => t.id === want) ?? (section.tabParam ? section.tabs.find((t) => t.id === ME_DEFAULT_TAB) ?? null : null)
  }
  let view: NavView | null = null
  if (tab?.views) view = (parts[2] === 'planned' ? tab.views.find((v) => v.id === 'planned') : parts[2] && tab.detailView ? tab.views.find((v) => v.id === tab.detailView) : null) ?? tab.views.find((v) => v.value === q.get('view')) ?? tab.views[0]
  return { section, tab, view }
}

/** The words of the top bar: SECTION › TAB › VIEW (a tab without views has none; Today and Log are one word). */
export function crumbs(loc: NavLocation): string[] {
  return [loc.section?.label, loc.tab?.label, loc.view?.label].filter((x): x is string => !!x)
}

/** Where a menu item goes. A section opens its first tab; a tab opens its default view. */
export interface NavTarget { section: NavSection; tab?: NavTab; view?: NavView }

export function pathOf(t: NavTarget): string {
  if (t.section.tabParam) return `/${t.section.id}`
  const tab = t.tab ?? t.section.tabs?.[0]
  return tab ? `/${t.section.id}/${tab.id}` : `/${t.section.id}`
}

/**
 * The route (path and query) of a menu item. Changing only the tab (Me) or only the view of the page already open keeps every other
 * parameter (range, the metrics of a comparison, a window); going to another page starts clean.
 */
export function navTo(t: NavTarget, currentPath: string, currentSearch: string): string {
  const path = pathOf(t)
  const same = currentPath === path
  const q = new URLSearchParams(same ? currentSearch : '')
  if (t.section.tabParam) {
    // Me: the tab is a parameter; a tab change keeps range, and drops nothing else
    const sameSection = currentPath === path
    const next = new URLSearchParams(sameSection ? currentSearch : '')
    const tab = t.tab ?? t.section.tabs?.find((x) => x.id === ME_DEFAULT_TAB)
    if (tab) {
      // another tab's view (and the person shown on it) does not carry over; a view of the tab is set or, for its default, removed
      if (next.get(t.section.tabParam) !== tab.id) { next.delete('view'); next.delete('person') }
      next.set(t.section.tabParam, tab.id)
      const view = t.view ?? (tab.views ? tab.views[0] : undefined)
      if (view) { next.delete('person'); if (view.value == null) next.delete('view'); else next.set('view', view.value) }
    }
    const s = next.toString()
    return s ? `${path}?${s}` : path
  }
  const tab = t.tab ?? t.section.tabs?.[0]
  const view = t.view ?? tab?.views?.[0]
  if (view) { if (view.value == null) q.delete('view'); else q.set('view', view.value) }
  const s = q.toString()
  return s ? `${path}?${s}` : path
}
