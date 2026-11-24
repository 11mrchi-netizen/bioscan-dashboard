// A redirect that keeps what the old address carried: its query string, plus any parameters the new place needs (web redesign,
// phase 4). `<Navigate to="/lab/evidence">` alone would drop ?question=… and ?series=…; links in saved views, notes and the
// phone's pages would then land on the right page with none of the filters.
import { Navigate, useLocation } from 'react-router-dom'

export function RedirectKeep({ to, add }: { to: string; add?: Record<string, string> }) {
  const { search } = useLocation()
  const have = new URLSearchParams(search)
  // The old query is kept as written (series=hrv,rhr keeps its comma); only what is missing is put in front of it.
  const extra = new URLSearchParams(Object.entries(add ?? {}).filter(([k]) => !have.has(k))).toString()
  const qs = [extra, search.replace(/^\?/, '')].filter(Boolean).join('&')
  return <Navigate to={`${to}${qs ? `?${qs}` : ''}`} replace />
}
