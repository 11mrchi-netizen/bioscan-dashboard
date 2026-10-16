// Intervention & Protocol Intelligence (08.9): a read-only view of the generalized interventions/protocols layer
// (08.1-08.8) alongside the existing Experiments tab. An intervention/protocol is created in the app (not yet built
// for Android as of this pass, see android-app/.../ui/screens/status/InterventionsScreen.kt); this view reads
// whatever exists. intervention_response and protocol_audit rows only exist once analysis-run is redeployed with
// runInterventionResponse.ts wired in (docs/intervention-protocol-intelligence/README.md's "Still open") -- until
// then this tab correctly shows interventions/protocols with no response/audit section, not an error.
// Analysis review phase F adds intervention_its: each intervention read once, on its evaluation date, against a forecast
// from the eight weeks before it started (the OBSERVED rung: what happened, not why).

import { LoadBanner } from '../components/shared/LoadBanner'
import { useCallback, useEffect, useState } from 'react'
import { supabase } from '../lib/supabase'
import { warnOnError } from '../lib/schema'
import { IntervalBar } from './Confirmatory'
import './Confirmatory.css'
import './Interventions.css'

type LifecycleState = 'proposed' | 'baseline' | 'active' | 'evaluating' | 'validated' | 'maintained' | 'modified' | 'stopped'
type ResponseVerdict = 'signal_observed' | 'no_signal' | 'insufficient'
type AuditSeverity = 'info' | 'low' | 'moderate' | 'high'

interface InterventionRow {
  id: string
  domain: string
  name: string
  lifecycle_state: LifecycleState
  baseline_started_at: string | null
  started_at: string | null
  ended_at: string | null
  intended_outcomes: string[]
}

interface ProtocolRow {
  id: string
  name: string
  objective_outcome_key: string
  lifecycle_state: 'proposed' | 'active' | 'evaluating' | 'completed' | 'stopped'
  template_key: string | null
}

interface Diff { value: number; lo: number; hi: number; unit: string }
interface MetricResponse { metric: string; label: string; verdict: ResponseVerdict; n0: number; n1: number; diff: Diff | null; mde: number | null; summary: string; notes: string[] }
interface ResponsePayload { interventionId: string; asOf: string; metrics: MetricResponse[] }
interface ResponseRow { key: string; state: string; calculated_at: string; payload: ResponsePayload }

type ItsState = 'collecting' | 'evaluated' | 'insufficient'
type ItsVerdict = 'clear' | 'negligible' | 'unclear'
interface ItsMetric { metric: string; label: string; state: ItsState; verdict: ItsVerdict | null; gap: Diff | null; zone: number; preDays: number; activeDays: number; evaluateOn: string; summary: string }
interface ItsRow { key: string; calculated_at: string; payload: { preStart: string; activeStart: string; evaluateOn: string; state: ItsState; metrics: ItsMetric[] } }
const ITS_LABEL: Record<ItsVerdict, string> = { clear: 'CLEAR', negligible: 'NEGLIGIBLE', unclear: 'UNCLEAR' }

interface AuditFinding { code: string; severity: AuditSeverity; message: string; interventionIds: string[] }
interface AuditRow { key: string; calculated_at: string; payload: { findings: AuditFinding[] } }

const CARD_CLASS: Record<ResponseVerdict, string> = { signal_observed: 'supported', no_signal: 'not_distinguishable', insufficient: 'insufficient' }
const VERDICT_LABEL: Record<ResponseVerdict, string> = { signal_observed: 'SIGNAL OBSERVED', no_signal: 'NO DETECTABLE SIGNAL', insufficient: 'NOT ENOUGH DAYS YET' }
const LIFECYCLE_LABEL: Record<LifecycleState, string> = {
  proposed: 'PROPOSED', baseline: 'BASELINE', active: 'ACTIVE', evaluating: 'EVALUATING', validated: 'VALIDATED', maintained: 'MAINTAINED', modified: 'MODIFIED', stopped: 'STOPPED',
}
const SEVERITY_CLASS: Record<AuditSeverity, string> = { high: 'iv-sev-high', moderate: 'iv-sev-moderate', low: 'iv-sev-low', info: 'iv-sev-info' }

function fmtDate(d: string | null): string {
  return d ?? '—'
}

export function Interventions() {
  const [interventions, setInterventions] = useState<InterventionRow[]>([])
  const [protocols, setProtocols] = useState<ProtocolRow[]>([])
  const [responses, setResponses] = useState<Map<string, ResponseRow>>(new Map())
  const [audits, setAudits] = useState<Map<string, AuditRow>>(new Map())
  const [itsReads, setItsReads] = useState<Map<string, ItsRow>>(new Map())
  const [loading, setLoading] = useState(true)

  const [loadError, setLoadError] = useState<string | null>(null)

  const loadInner = useCallback(async () => {
    const [ivRes, protoRes, respRes, auditRes, itsRes] = await Promise.all([
      supabase.from('interventions').select('id, domain, name, lifecycle_state, baseline_started_at, started_at, ended_at, intended_outcomes').order('created_at', { ascending: false }),
      supabase.from('protocols').select('id, name, objective_outcome_key, lifecycle_state, template_key').order('created_at', { ascending: false }),
      supabase.from('analysis_results').select('key, state, calculated_at, payload').eq('kind', 'intervention_response'),
      supabase.from('analysis_results').select('key, calculated_at, payload').eq('kind', 'protocol_audit'),
      supabase.from('analysis_results').select('key, calculated_at, payload').eq('kind', 'intervention_its'),
    ])
    warnOnError('interventions', ivRes.error)
    warnOnError('protocols', protoRes.error)
    warnOnError('intervention_response', respRes.error)
    warnOnError('protocol_audit', auditRes.error)
    warnOnError('intervention_its', itsRes.error)

    setInterventions((ivRes.data ?? []) as unknown as InterventionRow[])
    setProtocols((protoRes.data ?? []) as unknown as ProtocolRow[])
    // Keep only the latest as_of per key: the table has no uniqueness guarantee from the client's point of view.
    const respByKey = new Map<string, ResponseRow>()
    for (const r of (respRes.data ?? []) as unknown as ResponseRow[]) {
      const existing = respByKey.get(r.key)
      if (!existing || r.calculated_at > existing.calculated_at) respByKey.set(r.key, r)
    }
    setResponses(respByKey)
    const auditByKey = new Map<string, AuditRow>()
    for (const r of (auditRes.data ?? []) as unknown as AuditRow[]) {
      const existing = auditByKey.get(r.key)
      if (!existing || r.calculated_at > existing.calculated_at) auditByKey.set(r.key, r)
    }
    setAudits(auditByKey)
    const itsByKey = new Map<string, ItsRow>()
    for (const r of (itsRes.data ?? []) as unknown as ItsRow[]) {
      const existing = itsByKey.get(r.key)
      if (!existing || r.calculated_at > existing.calculated_at) itsByKey.set(r.key, r)
    }
    setItsReads(itsByKey)
  }, [])

  const load = useCallback(async () => {
    try {
      await loadInner()
      setLoadError(null)
    } catch (e) {
      console.error('[BioScan] load failed:', e)
      setLoadError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [loadInner])

  useEffect(() => { void load() }, [load])

  if (loading) return <div className="state-loading mono">LOADING INTERVENTIONS…</div>
  if (loadError) return <LoadBanner error={loadError} onRetry={() => { setLoading(true); void load() }} />

  return (
    <div className="conf">
      <div className="conf-head">
        <p className="conf-note">
          Interventions (supplement, nutrition, training, sleep or lifestyle changes) and the protocols that group them around an objective. Response and audit results below are observational, not a pre-registered experiment -- see the EXPERIMENTS tab for pre-registered supplement N-of-1 tests.
        </p>
      </div>

      {protocols.length === 0 && interventions.length === 0 && (
        <div className="state-empty mono">NO INTERVENTIONS OR PROTOCOLS YET</div>
      )}

      {protocols.length > 0 && (
        <>
          <h3 className="iv-section-title mono">PROTOCOLS</h3>
          <div className="conf-grid">
            {protocols.map((p) => {
              const audit = audits.get(p.id)
              return (
                <div key={p.id} className="card conf-card">
                  <div className="conf-card-head">
                    <span className="conf-title">{p.name}</span>
                    <span className="conf-state mono">{p.lifecycle_state.toUpperCase()}</span>
                  </div>
                  <div className="conf-stats mono">
                    <span>objective: {p.objective_outcome_key}</span>
                    {p.template_key && <span>template: {p.template_key}</span>}
                  </div>
                  {audit && audit.payload.findings.length > 0 ? (
                    <ul className="iv-findings">
                      {audit.payload.findings.map((f, i) => (
                        <li key={i} className={`iv-finding ${SEVERITY_CLASS[f.severity]}`}>
                          <span className="iv-finding-severity mono">{f.severity.toUpperCase()}</span>
                          <span className="iv-finding-message">{f.message}</span>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="conf-footnote mono">no audit findings{audit ? '' : ' yet'}</p>
                  )}
                </div>
              )
            })}
          </div>
        </>
      )}

      <h3 className="iv-section-title mono">INTERVENTIONS</h3>
      <div className="conf-grid">
        {interventions.map((iv) => {
          const response = responses.get(iv.id)
          const cardState = response ? CARD_CLASS[response.state as ResponseVerdict] : null
          return (
            <div key={iv.id} className={`card conf-card ${cardState ? `conf-${cardState}` : ''}`}>
              <div className="conf-card-head">
                <span className="conf-title">{iv.name} · {iv.domain}</span>
                <span className="conf-state mono">{LIFECYCLE_LABEL[iv.lifecycle_state]}</span>
              </div>
              <div className="conf-stats mono">
                <span>baseline {fmtDate(iv.baseline_started_at)}</span>
                <span>started {fmtDate(iv.started_at)}</span>
                <span>ended {fmtDate(iv.ended_at)}</span>
              </div>
              {response ? (
                response.payload.metrics.map((m) => (
                  <div key={m.metric} className="conf-metric">
                    <div className="conf-card-head">
                      <span className="conf-title">{m.label}</span>
                      <span className={`conf-state mono conf-state-${CARD_CLASS[m.verdict]}`}>{VERDICT_LABEL[m.verdict]}</span>
                    </div>
                    <p className="conf-summary">{m.summary}</p>
                    {m.diff && m.mde !== null && <IntervalBar effect={m.diff} mde={m.mde} />}
                    {m.diff && m.mde !== null && (
                      <div className="conf-legend mono">
                        <span>{m.n0} baseline days · {m.n1} days since starting</span>
                        <span>shaded: smaller than ±{m.mde.toFixed(m.diff.unit === '%' ? 1 : 2)} is below what this comparison can detect</span>
                      </div>
                    )}
                    {m.notes.map((n, i) => <p key={i} className="conf-footnote">{n}</p>)}
                  </div>
                ))
              ) : (
                <p className="conf-footnote mono">no response analysis yet</p>
              )}
              <ItsSection read={itsReads.get(iv.id)} />
            </div>
          )
        })}
      </div>
    </div>
  )
}

// The interrupted-time-series read: once, on its date, against the forecast from before the start. Its interval is drawn
// against the no-effect zone (shaded).
function ItsSection({ read }: { read: ItsRow | undefined }) {
  if (!read) return null
  const p = read.payload
  return (
    <div className="conf-metric">
      <div className="conf-title">OBSERVED AGAINST THE FORECAST · read {p.state === 'collecting' ? `on ${p.evaluateOn}` : `${p.evaluateOn}`}</div>
      <p className="conf-footnote mono">forecast from {p.preStart} to the day before {p.activeStart}; what happened, not why</p>
      {p.metrics.map((m) => (
        <div key={m.metric}>
          <div className="conf-card-head">
            <span className="conf-title">{m.label}</span>
            <span className="conf-state mono">{m.verdict ? ITS_LABEL[m.verdict] : m.state.toUpperCase()}</span>
          </div>
          <p className="conf-summary">{m.summary}</p>
          {m.gap && <IntervalBar effect={m.gap} mde={m.zone} />}
          {m.gap && (
            <div className="conf-legend mono">
              <span>{m.preDays} days before · {m.activeDays} days since the start</span>
              <span>shaded: the ±{m.zone}{m.gap.unit === '%' ? '%' : ` ${m.gap.unit}`} no-effect zone</span>
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

export default Interventions
