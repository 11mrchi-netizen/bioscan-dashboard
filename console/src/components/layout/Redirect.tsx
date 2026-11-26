// A redirect that keeps what the old address carried: its query string, plus any parameters the new place needs (web redesign,
// phase 4). `<Navigate to="/analyse/evidence">` alone would drop ?question=… and ?series=…; links in saved views, notes and the
// phone's pages would then land on the right page with none of the filters.
import { Navigate, useLocation, useParams } from 'react-router-dom'

export function RedirectKeep({ to, add }: { to: string; add?: Record<string, string> }) {
  const { search } = useLocation()
  const have = new URLSearchParams(search)
  // The old query is kept as written (series=hrv,rhr keeps its comma); only what is missing is put in front of it.
  const extra = new URLSearchParams(Object.entries(add ?? {}).filter(([k]) => !have.has(k))).toString()
  const qs = [extra, search.replace(/^\?/, '')].filter(Boolean).join('&')
  return <Navigate to={`${to}${qs ? `?${qs}` : ''}`} replace />
}

/**
 * A redirect from an old section whose tab is in the path (#/lab/<tab>, #/setup/<tab>) to the tab's new place, query kept. `where`
 * maps an old tab to its new path; a tab it does not know goes to `fallback`.
 */
export function RedirectTab({ where, fallback }: { where: (tab: string) => string | null; fallback: string }) {
  const { tab = '' } = useParams()
  return <RedirectKeep to={where(tab) ?? fallback} />
}
