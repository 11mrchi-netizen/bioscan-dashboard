// Endurance Fueling & Personal Experiments (06.3.7): a read-only view of fueling products/recipes and, for a
// selected activity, its intake-event timeline and summary. Computes the summary with the exact same
// `summariseFueling` the mobile app and any future server-side job would use (supabase/functions/_shared/
// analysis-core/fuelingSummary.ts) -- not a second, console-only reimplementation of the same arithmetic, per
// 06.3.4's "a single, stable contract... rather than calculating metrics independently in multiple screens."
// Same read-only, direct-Supabase-read pattern as Interventions.tsx (08.9).

import { LoadBanner } from '../components/shared/LoadBanner'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { supabase } from '../lib/supabase'
import { warnOnError } from '../lib/schema'
import { summariseFueling } from '../../../supabase/functions/_shared/analysis-core/fuelingSummary.ts'
import type { FuelingEvent, FuelingEventStatus, IntakeGap } from '../../../supabase/functions/_shared/analysis-core/fuelingSummary.ts'
import './Confirmatory.css'
import './Fueling.css'

interface ProductRow {
  id: number
  name: string
  kind: string
  brand: string | null
}

interface RecipeVersionRow {
  product_id: number
  version: number
  serving_size: number | null
  serving_unit: string | null
  carbs_g: number | null
  fluid_ml: number | null
  sodium_mg: number | null
  caffeine_mg: number | null
}

interface SessionRow {
  id: number
  type: string
  start_time: string
  duration_min: number | null
}

interface IntakeLogRow {
  id: number
  status: FuelingEventStatus
  elapsed_seconds: number
  logged_at: string | null
  description: string | null
  quantity: number | null
  quantity_unit: string | null
  carbs_g: number | null
  fluid_ml: number | null
  sodium_mg: number | null
  caffeine_mg: number | null
}

const STATUS_LABEL: Record<FuelingEventStatus, string> = { planned: 'PLANNED', consumed: 'CONSUMED', skipped: 'SKIPPED', partial: 'PARTIAL' }

function fmt(v: number | null, unit: string): string {
  return v === null ? 'unknown' : `${v.toFixed(unit === 'g/h' || unit === 'ml/h' || unit === 'mg/h' ? 0 : 1)} ${unit}`
}

export function Fueling() {
  const [products, setProducts] = useState<ProductRow[]>([])
  const [latestVersions, setLatestVersions] = useState<Map<number, RecipeVersionRow>>(new Map())
  const [sessions, setSessions] = useState<SessionRow[]>([])
  const [selectedSessionId, setSelectedSessionId] = useState<number | null>(null)
  const [events, setEvents] = useState<IntakeLogRow[]>([])
  const [loading, setLoading] = useState(true)
  const [loadingEvents, setLoadingEvents] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)

  const loadProducts = useCallback(async () => {
    const [prodRes, verRes] = await Promise.all([
      supabase.from('fueling_products').select('id, name, kind, brand').order('created_at', { ascending: false }),
      supabase.from('fueling_recipe_versions').select('product_id, version, serving_size, serving_unit, carbs_g, fluid_ml, sodium_mg, caffeine_mg').order('version', { ascending: false }),
    ])
    warnOnError('fueling_products', prodRes.error)
    warnOnError('fueling_recipe_versions', verRes.error)
    setProducts((prodRes.data ?? []) as unknown as ProductRow[])
    const latest = new Map<number, RecipeVersionRow>()
    for (const v of (verRes.data ?? []) as unknown as RecipeVersionRow[]) {
      if (!latest.has(v.product_id)) latest.set(v.product_id, v) // first seen per product_id is the highest version, thanks to the order() above
    }
    setLatestVersions(latest)
  }, [])

  const loadSessions = useCallback(async () => {
    const { data, error } = await supabase
      .from('exercise_sessions')
      .select('id, type, start_time, duration_min')
      .in('type', ['run', 'hike', 'ride'])
      .order('start_time', { ascending: false })
      .limit(30)
    warnOnError('exercise_sessions', error)
    const rows = (data ?? []) as unknown as SessionRow[]
    setSessions(rows)
    if (rows.length > 0) setSelectedSessionId(rows[0].id)
  }, [])

  const loadAll = useCallback(() => {
    setLoading(true)
    setLoadError(null)
    void Promise.all([loadProducts(), loadSessions()])
      .catch((e: unknown) => { console.error('[BioScan] load failed:', e); setLoadError(e instanceof Error ? e.message : String(e)) })
      .finally(() => setLoading(false))
  }, [loadProducts, loadSessions])

  useEffect(() => { loadAll() }, [loadAll])

  useEffect(() => {
    if (selectedSessionId === null) return
    setLoadingEvents(true)
    void supabase
      .from('fueling_intake_log')
      .select('id, status, elapsed_seconds, logged_at, description, quantity, quantity_unit, carbs_g, fluid_ml, sodium_mg, caffeine_mg')
      .eq('exercise_session_id', selectedSessionId)
      .order('elapsed_seconds', { ascending: true })
      .then((res) => {
        warnOnError('fueling_intake_log', res.error)
        setEvents((res.data ?? []) as unknown as IntakeLogRow[])
        setLoadingEvents(false)
      }, (e: unknown) => {
        console.error('[BioScan] intake log load failed:', e)
        setEvents([])
        setLoadingEvents(false)
      })
  }, [selectedSessionId])

  const selectedSession = sessions.find((s) => s.id === selectedSessionId) ?? null

  const summary = useMemo(() => {
    if (!selectedSession) return null
    const fuelingEvents: FuelingEvent[] = events.map((e) => ({
      id: e.id, status: e.status, elapsedSeconds: e.elapsed_seconds,
      carbsG: e.carbs_g, fluidMl: e.fluid_ml, sodiumMg: e.sodium_mg, caffeineMg: e.caffeine_mg,
    }))
    const durationSeconds = selectedSession.duration_min !== null ? selectedSession.duration_min * 60 : null
    return summariseFueling(fuelingEvents, durationSeconds)
  }, [events, selectedSession])

  if (loading) return <div className="state-loading mono">LOADING FUELING DATA…</div>
  if (loadError) return <LoadBanner error={loadError} onRetry={loadAll} />

  return (
    <div className="conf">
      <div className="conf-head">
        <p className="conf-note">
          Fueling products/recipes and, for a selected run/hike/ride, its logged intake timeline and summary. Totals are computed by the same function the mobile app and any future server job use -- not recalculated independently here.
        </p>
      </div>

      <h3 className="iv-section-title mono">PRODUCTS &amp; RECIPES</h3>
      {products.length === 0 ? (
        <div className="state-empty mono">NO FUELING PRODUCTS YET</div>
      ) : (
        <div className="conf-grid">
          {products.map((p) => {
            const v = latestVersions.get(p.id)
            return (
              <div key={p.id} className="card conf-card">
                <div className="conf-card-head">
                  <span className="conf-title">{p.name}{p.brand ? ` · ${p.brand}` : ''}</span>
                  <span className="conf-state mono">{p.kind.toUpperCase()}</span>
                </div>
                {v ? (
                  <div className="conf-stats mono">
                    <span>v{v.version}</span>
                    {v.serving_size !== null && <span>{v.serving_size}{v.serving_unit} serving</span>}
                    <span>carbs {fmt(v.carbs_g, 'g')}</span>
                    <span>fluid {fmt(v.fluid_ml, 'ml')}</span>
                    <span>sodium {fmt(v.sodium_mg, 'mg')}</span>
                    <span>caffeine {fmt(v.caffeine_mg, 'mg')}</span>
                  </div>
                ) : (
                  <p className="conf-footnote mono">no recipe version yet</p>
                )}
              </div>
            )
          })}
        </div>
      )}

      <h3 className="iv-section-title mono">ACTIVITY INTAKE</h3>
      {sessions.length === 0 ? (
        <div className="state-empty mono">NO RUN/HIKE/RIDE ACTIVITIES YET</div>
      ) : (
        <>
          <select className="fuel-select mono" value={selectedSessionId ?? ''} onChange={(e) => setSelectedSessionId(Number(e.target.value))}>
            {sessions.map((s) => (
              <option key={s.id} value={s.id}>{s.start_time.slice(0, 16).replace('T', ' ')} · {s.type.toUpperCase()}{s.duration_min ? ` · ${Math.round(s.duration_min)} min` : ''}</option>
            ))}
          </select>

          {loadingEvents && <div className="state-loading mono">LOADING EVENTS…</div>}

          {!loadingEvents && summary && (
            <div className="card conf-card">
              <div className="conf-card-head">
                <span className="conf-title">Summary</span>
                <span className="conf-state mono">{summary.durationKnown ? 'DURATION KNOWN' : 'DURATION UNKNOWN'}</span>
              </div>
              <div className="conf-stats mono">
                <span>carbs {fmt(summary.actualTotals.carbsG, 'g')} ({fmt(summary.carbsPerHour, 'g/h')})</span>
                <span>fluid {fmt(summary.actualTotals.fluidMl, 'ml')} ({fmt(summary.fluidPerHour, 'ml/h')})</span>
                <span>sodium {fmt(summary.actualTotals.sodiumMg, 'mg')} ({fmt(summary.sodiumPerHour, 'mg/h')})</span>
                <span>{summary.eventCount} consumed/partial · {summary.plannedCount} planned · {summary.skippedCount} skipped</span>
              </div>
              {summary.gaps.length > 0 && (
                <p className="conf-footnote mono">
                  {summary.gaps.length} gap(s) with no logged intake: {summary.gaps.map((g: IntakeGap) => `${Math.round(g.startSeconds / 60)}-${Math.round(g.endSeconds / 60)}min`).join(', ')}
                </p>
              )}

              <div className="conf-metric">
                <div className="conf-title">Timeline (source events)</div>
                <ul className="fuel-timeline">
                  {events.map((e) => (
                    <li key={e.id} className="fuel-event mono">
                      <span className="fuel-event-time">{Math.round(e.elapsed_seconds / 60)} min</span>
                      <span className={`fuel-event-status fuel-status-${e.status}`}>{STATUS_LABEL[e.status]}</span>
                      <span className="fuel-event-desc">{e.description ?? (e.quantity ? `${e.quantity} ${e.quantity_unit ?? ''}` : '')}</span>
                      <span className="fuel-event-values">
                        {e.carbs_g !== null && `${e.carbs_g}g carbs `}
                        {e.fluid_ml !== null && `${e.fluid_ml}ml `}
                        {e.sodium_mg !== null && `${e.sodium_mg}mg Na `}
                        {e.caffeine_mg !== null && `${e.caffeine_mg}mg caff`}
                      </span>
                    </li>
                  ))}
                  {events.length === 0 && <li className="fuel-event mono">no intake logged for this activity</li>}
                </ul>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}

export default Fueling
