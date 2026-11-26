// Me: the phone's eight tabs in the phone's order, each drawn from what the phone worked out (me_views,
// meView.ts), with when that was. Body adds the 3D body. Tabs the phone does not send yet say so.
import { lazy, Suspense, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { localDaysAgo } from '../lib/dates'
import { MeSections } from './MeView'
import { loadMeView, ME_TABS, type MeRow, type MeTab, type MeViewRow } from './meData'
import type { Concern } from './Body3D'

const Body3D = lazy(() => import('./Body3D').then((m) => ({ default: m.Body3D })))
// The console's deeper reads, below the phone's own picture of the tab (web redesign, phase 5): the scale's impedance on BODY, exposures and risks on EXPOSURES.
const BodyAnalysis = lazy(() => import('../workspaces/Body'))
const RiskAnalysis = lazy(() => import('../workspaces/Risk'))
// INTIMACY is the one tab the web draws itself, from the tables (the phone sends no me_view for it).
const IntimacyPage = lazy(() => import('../workspaces/Intimacy'))

/** The tabs the phone sends so far (phase 3 builds them one by one). */

const RANGES = [{ id: '28D', days: 28 }, { id: '90D', days: 90 }, { id: '1Y', days: 365 }, { id: 'ALL', days: 0 }]

export function Me() {
  const [params, setParams] = useSearchParams()
  const raw = (params.get('tab') ?? 'BODY').toUpperCase()
  const tab: MeTab = (ME_TABS as readonly string[]).includes(raw) ? (raw as MeTab) : 'BODY'
  const range = RANGES.find((r) => r.id === params.get('range')) ?? RANGES[1]
  const set = (k: string, v: string) => { const n = new URLSearchParams(params); n.set(k, v); setParams(n, { replace: true }) }
  const [row, setRow] = useState<MeViewRow | null>(null)
  const [concerns, setConcerns] = useState<Concern[]>([])
  const status = useLoadEffect(async (isCurrent) => {
    setRow(null)
    if (tab === 'INTIMACY') return
    const v = await loadMeView(tab)
    if (!isCurrent()) return
    setRow(v)
    if (tab === 'BODY') {
      const c = await supabase.from('health_concerns').select('title,kind,category,regions').is('archived_at', null)
      if (isCurrent() && !c.error) setConcerns(((c.data ?? []) as Concern[]).map((x) => ({ ...x, regions: Array.isArray(x.regions) ? x.regions : [] })))
    }
  }, [tab])

  const rows: MeRow[] = (row?.view.sections ?? []).flatMap((s) => s.items).filter((i): i is MeRow => i.kind === 'row')
  const since = range.days ? localDaysAgo(range.days - 1) : null
  const builtAt = row ? new Date(row.builtAt).toLocaleString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : null

  if (tab === 'INTIMACY') {
    return <div className="me"><Suspense fallback={<p className="me-note muted">Loading intimacy…</p>}><IntimacyPage /></Suspense></div>
  }

  return (
    <div className="me">
      <div className="me-head">
        {row?.view.range
          ? <span className="me-fixed-range mono">LAST {row.view.range.replace(/^(\d+)D$/, '$1 DAYS')}</span>
          : <div className="me-ranges">{RANGES.map((r) => <button key={r.id} className="ft-chip" aria-current={r.id === range.id} onClick={() => set('range', r.id)}>{r.id}</button>)}</div>}
        {builtAt && <span className="me-fresh">As the phone worked it out · {builtAt}</span>}
      </div>

      {tab === 'BODY' && (
        <Suspense fallback={<section className="ft-card body3d-loading">Loading the 3D body…</section>}>
          <Body3D rows={rows} concerns={concerns} />
        </Suspense>
      )}

      {status.loading && !row ? <p className="me-note muted">Loading {tab.toLowerCase()}…</p>
        : status.error ? <p className="me-note">Could not load: {status.error} <button className="dash-link" onClick={status.retry}>RETRY</button></p>
        : row ? <MeSections sections={row.view.sections} sinceDay={since} tab={tab} />
        : (
          <section className="ft-card me-soon">
            <p>The phone has not sent {tab} yet. It does when the tab is opened on the phone, and every few hours in the background once the updated app is installed.</p>
          </section>
        )}

      {tab === 'BODY' && <Suspense fallback={null}><section className="me-deeper ft-skin" aria-label="The scale in depth"><BodyAnalysis /></section></Suspense>}
      {tab === 'EXPOSURES' && <Suspense fallback={null}><section className="me-deeper ft-skin" aria-label="Exposures and risks in depth"><RiskAnalysis embedded /></section></Suspense>}
    </div>
  )
}
