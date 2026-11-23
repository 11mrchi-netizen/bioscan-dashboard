// Web redesign, phase 1: the Dashboard and the Console are one app with one router, so both hrefs are plain hash links.
// Kept only so the call sites need no change in this phase; they are replaced by <Link> and deleted in the cleanup phase.
/** A page of the app, optionally at a hash route such as `#/today?day=2026-11-17`. */
export function dashboardHref(hash = ''): string {
  return hash || '#/'
}

/** A page of the app, optionally at a hash route such as `#/data?table=meals`. */
export function consoleHref(hash = ''): string {
  return hash || '#/'
}
