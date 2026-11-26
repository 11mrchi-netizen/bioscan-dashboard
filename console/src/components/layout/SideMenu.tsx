// The side menu (web redesign, follow-up): the navigation pills of the old tab rows, as a tree. The active section opens to its
// tabs and the active tab to its views (lib/nav.ts). On a wide screen it can be hidden with the top bar's button; below 1280 px it is
// a drawer over the page. A link that changes only the tab or the view keeps the page's other parameters (range, a comparison).
import { Link, useLocation } from 'react-router-dom'
import { crumbs, locate, NAV, navTo, type NavTarget } from '../../lib/nav'

export function SideMenu({ email, onSignOut, narrow }: { email?: string | null; onSignOut: () => void; narrow: boolean }) {
  const { pathname, search } = useLocation()
  const loc = locate(pathname, search)
  const to = (t: NavTarget) => navTo(t, pathname, search)
  return (
    <nav className="side-menu ft-skin" aria-label="Navigation">
      <div className="menu-brand mono"><img className="menu-mark" src="/mark.svg" alt="" width={18} height={18} />FIELD TERMINAL</div>
      <ul className="menu-list">
        {NAV.map((s) => {
          const here = loc.section?.id === s.id
          return (
            <li key={s.id} className={`menu-sec ${s.id === 'setup' ? 'menu-foot-sec' : ''}`}>
              <Link to={to({ section: s })} data-nav={s.id} className={`menu-item level-1 mono ${here && !s.tabs ? 'on' : ''} ${here ? 'open' : ''}`} aria-current={here && !s.tabs ? 'page' : undefined}>
                {s.id === 'setup' ? '⚙ ' : ''}{s.label}
              </Link>
              {here && s.tabs && (
                <ul className="menu-sub">
                  {s.tabs.map((t) => {
                    const tabOn = loc.tab?.id === t.id
                    return (
                      <li key={t.id}>
                        <Link to={to({ section: s, tab: t })} data-nav={`${s.id}/${t.id}`} className={`menu-item level-2 mono ${tabOn && !t.views ? 'on' : ''} ${tabOn ? 'open' : ''}`} aria-current={tabOn && !t.views ? 'page' : undefined}>
                          {t.label}
                        </Link>
                        {tabOn && t.views && (
                          <ul className="menu-sub menu-views">
                            {t.views.map((v) => {
                              const viewOn = loc.view?.id === v.id
                              return (
                                <li key={v.id}>
                                  <Link to={to({ section: s, tab: t, view: v })} data-nav={`${s.id}/${t.id}/${v.id}`} className={`menu-item level-3 mono ${viewOn ? 'on' : ''}`} aria-current={viewOn ? 'page' : undefined}>
                                    {v.label}
                                  </Link>
                                </li>
                              )
                            })}
                          </ul>
                        )}
                      </li>
                    )
                  })}
                </ul>
              )}
            </li>
          )
        })}
      </ul>
      {narrow && (
        <div className="menu-account">
          {email && <span className="mono user-label">{email}</span>}
          <button className="btn-ghost mono" onClick={onSignOut}>SIGN OUT</button>
        </div>
      )}
    </nav>
  )
}

/** The words of the top bar for an address. */
export function crumbWords(pathname: string, search: string, pageTitle: string | null): string[] {
  const loc = locate(pathname, search)
  if (loc.section) return crumbs(loc)
  if (pathname.startsWith('/m/')) return pageTitle ? ['Metric', pageTitle] : ['Metric']
  return ['Field Terminal']
}
