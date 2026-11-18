// The two web surfaces (docs: Dashboard and Console Transition Plan, Nov 2026). Deployed, the Dashboard is the site root and
// the Console lives in /console/; under the dev server they are dashboard.html and app.html side by side.
const deployed = (): boolean => !/\/(app|dashboard)\.html$/.test(window.location.pathname)
const inConsole = (): boolean => /\/console\/?/.test(window.location.pathname)

/** The Dashboard, optionally at a hash route such as `#/today?day=2026-11-17`. */
export function dashboardHref(hash = ''): string {
  if (!deployed()) return `./dashboard.html${hash}`
  return `${inConsole() ? '../' : './'}${hash}`
}

/** The Console, optionally at a hash route such as `#/data?table=meals`. */
export function consoleHref(hash = ''): string {
  if (!deployed()) return `./app.html${hash}`
  return `${inConsole() ? './' : './console/'}${hash}`
}

/** The previous dashboard, kept at /legacy/ until it is retired (plan phase 5). */
export function legacyHref(): string {
  if (!deployed()) return '../legacy/'
  return inConsole() ? '../legacy/' : './legacy/'
}
