// Analysis hub phase E: named views (saved_views). A view is the console route with its filters and the date range, the
// range kept as rangeStore's SavedRange so a preset stays relative to today. Pure helpers; the Shell's control does I/O.
import { fromSaved, toSaved, type SavedRange } from './rangeStore.ts'
import type { DateRange } from '../types/analysis'

export interface SavedView {
  id: number
  name: string
  path: string
  range: SavedRange
}

// The routes a view can be saved from: a metric page, a Lab tab or a Setup tab (the tab is part of the path). The redirects of the
// routes that moved (App.tsx) stay for bookmarks and links, but no view was ever saved under an old name (saved_views was empty when the
// old names were dropped), so none is accepted here.
const ROUTES = new Set(['m', 'lab', 'setup'])

/** The route to save: the hash path with its query, only for a page of metrics, the Lab or Setup. */
export function viewPath(pathname: string, search: string): string | null {
  const parts = pathname.replace(/^\/+/, '').split('/')
  const route = parts[0]
  if (!ROUTES.has(route)) return null
  // A metric page is #/m/<ref>: the ref is part of the view (a comparison is saved with its metrics, which are repeated ?with=).
  if ((route === 'm' || route === 'lab' || route === 'setup') && !parts[1]) return null
  return `/${route === 'm' || route === 'lab' || route === 'setup' ? parts.slice(0, 2).join('/') : route}${search && search !== '?' ? search : ''}`
}

/** Opening a view: the route to navigate to and the range to set, or null for a row that names no savable route. */
export function openView(v: Pick<SavedView, 'path' | 'range'>, now: Date = new Date()): { path: string; range: DateRange | null } | null {
  const [pathname, query = ''] = v.path.split('?')
  const path = viewPath(pathname, query ? `?${query}` : '')
  return path ? { path, range: fromSaved(v.range, now) } : null
}

export function viewRow(name: string, pathname: string, search: string, range: DateRange, now: Date = new Date()): { name: string; path: string; range: SavedRange } | { error: string } {
  const n = name.trim()
  if (!n || n.length > 80) return { error: 'a name of 1 to 80 characters' }
  const path = viewPath(pathname, search)
  if (!path) return { error: 'only a workspace view can be saved' }
  return { name: n, path, range: toSaved(range, now) }
}
