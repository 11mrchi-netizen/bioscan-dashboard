// Dashboard › Me: the phone's six tabs for reading arrive in phase 3 of the transition plan (with the 3D body in Body).
import { legacyHref } from '../lib/surfaces'

const TABS = ['BODY', 'PERFORMANCE', 'HEALTH', 'BLOODWORK', 'HEALTHSPAN', 'ROUTINE']

export function Me() {
  return (
    <section className="ft-card me-soon">
      <div className="me-tabs">{TABS.map((t) => <span key={t} className="ft-chip">{t}</span>)}</div>
      <p>Me comes next: the same six tabs as on the phone, in the same order, for reading, with the 3D body in Body.</p>
      <p>Until then the 3D body and the charts are on the <a href={legacyHref()}>previous dashboard</a>.</p>
    </section>
  )
}
