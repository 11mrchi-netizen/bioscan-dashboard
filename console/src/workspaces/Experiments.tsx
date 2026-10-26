// N-of-1 experiments (Analysis Layer 3, Phase 5; randomised since analysis review phase F): the pre-registered plan and
// the published result of each experiment. A plan is registered in the Android app (Supplements > TEST IT, or SET UP A
// RANDOMISED TEST from an evidence-board row) and cannot be changed afterwards; this view is read-only. While an experiment
// runs its numbers are interim and carry no verdict; a randomised design then answers in four words against the zone set
// at registration. A deleted experiment (soft delete in the app) is left out.

import { LoadBanner } from '../components/shared/LoadBanner'
import { useCallback, useEffect, useState } from 'react'
import { supabase } from '../lib/supabase'
import { warnOnError } from '../lib/schema'
import { IntervalBar } from './Confirmatory'
import './Confirmatory.css'

type FourWord = 'clear_benefit' | 'clear_harm' | 'negligible' | 'unclear'
type Verdict = 'effect_detected' | 'no_detectable_effect' | 'insufficient' | 'running' | FourWord
type State = Verdict | 'before'

interface Diff { value: number; lo: number; hi: number; unit: string }
interface MetricResult {
  metric: string
  label: string
  role: 'primary' | 'secondary'
  verdict: Verdict
  n0: number
  n1: number
  diff: Diff | null
  mde: number | null
  preTrendPerWeek: number | null
  trendWarning: boolean
  replicated: boolean | null
  summary: string
  notes: string[]
  zone?: number | null
  randomizationP?: number | null
}
interface Protocol {
  design: string
  phases: { label: string; start: string; end: string; pair?: number }[]
  washoutDays: number
  minAdherence: number
  version?: number
  zone?: number
  blinded?: boolean
  assignments?: { date: string; arm: 'A' | 'B' }[]
  lag?: number
  behaviour?: { series: string; withValue: number; label: string }
}
interface Payload {
  name: string
  stage: 'before' | 'running' | 'complete'
  status: string
  adherence: { interventionDays: number; interventionLogged: number; rate: number | null; baselineLogged: number; ok: boolean | null }
  metrics: MetricResult[]
  protocol: Protocol
  preregisteredAt: string
}
interface Row { key: string; as_of: string; state: State; calculated_at: string; payload: Payload }

const LABEL: Record<State, string> = {
  effect_detected: 'EFFECT SEEN',
  no_detectable_effect: 'NO DETECTABLE EFFECT',
  insufficient: 'NOT ENOUGH DAYS',
  running: 'INTERIM, NO VERDICT YET',
  before: 'NOT STARTED',
  clear_benefit: 'CLEAR BENEFIT',
  clear_harm: 'CLEAR HARM',
  negligible: 'NO MEANINGFUL EFFECT',
  unclear: 'UNCLEAR',
}

const DESIGN: Record<string, string> = { AB: 'A, B', ABAB: 'A, B, A, B', randomized_pairs: 'PERIODS IN RANDOM ORDER', micro_randomized: 'DAYS IN RANDOM ORDER' }
const firstDay = (p: Protocol | undefined) => p?.phases?.[0]?.start ?? p?.assignments?.[0]?.date ?? ''
const cardClass = (s: State) => (s === 'effect_detected' || s === 'clear_benefit' || s === 'clear_harm' ? 'supported' : s === 'no_detectable_effect' || s === 'negligible' ? 'not_distinguishable' : 'insufficient')
const zoneText = (zone: number, unit: string) => (unit === '%' ? `±${zone}%` : `±${zone} ${unit}`)

// The schedule as registered: the periods in their drawn order (hidden when blinded), or the daily draw in brief.
function Schedule({ p }: { p: Protocol }) {
  if (p.design === 'micro_randomized') {
    const days = p.assignments ?? []
    const withDays = days.filter((d) => d.arm === 'B').length
    return (
      <div className="conf-stats mono">
        <span>{days[0]?.date} → {days[days.length - 1]?.date}</span>
        <span>{withDays} of {days.length} days drawn for the rule{p.behaviour ? `: ${p.behaviour.label}` : ''}</span>
        <span>outcome read {p.lag ?? 1} day(s) later</span>
      </div>
    )
  }
  return (
    <div className="conf-stats mono">
      {p.blinded
        ? <span>blinded: {p.phases.length} periods, {p.phases[0]?.start} → {p.phases[p.phases.length - 1]?.end}, order hidden</span>
        : p.phases.map((ph, i) => <span key={i}>{ph.label === 'B' ? 'with' : 'without'} {ph.start} → {ph.end}</span>)}
      {p.washoutDays ? <span>washout {p.washoutDays} d{p.version === 2 ? ' at the start of every period' : ''}</span> : null}
    </div>
  )
}

export function Experiments() {
  const [rows, setRows] = useState<Row[]>([])
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  const [loadError, setLoadError] = useState<string | null>(null)

  const loadInner = useCallback(async () => {
    const [res, exp] = await Promise.all([
      supabase.from('analysis_results').select('key, as_of, state, calculated_at, payload').eq('kind', 'experiment'),
      // Every column, so this keeps working before the phase F migration adds deleted_at.
      supabase.from('experiments').select('*'),
    ])
    warnOnError('experiments', res.error)
    const deleted = new Set(((exp.data ?? []) as { id: string; deleted_at?: string | null }[]).filter((e) => e.deleted_at).map((e) => e.id))
    const list = ((res.data ?? []) as unknown as Row[])
      .filter((r) => !deleted.has(r.key))
      .sort((a, b) => firstDay(b.payload.protocol).localeCompare(firstDay(a.payload.protocol)))
    setRows(list)
    setLoading(false)
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

  const run = useCallback(async () => {
    setRunning(true)
    setMessage(null)
    const { error } = await supabase.functions.invoke('analysis-run', { body: { action: 'run' } })
    if (error) setMessage(`Analysis failed: ${error.message}`)
    await load()
    setRunning(false)
  }, [load])

  if (loading) return <div className="state-loading mono">LOADING EXPERIMENTS…</div>
  if (loadError) return <LoadBanner error={loadError} onRetry={() => { setLoading(true); void load() }} />

  return (
    <div className="conf">
      <div className="conf-head">
        <p className="conf-note">
          Randomised self-experiments: periods (or days) with and without, in an order drawn when the plan was registered, so a trend or a bad week cannot line up with it.
          Each answer is judged against the smallest change that would matter, set at registration: CLEAR BENEFIT, CLEAR HARM, NO MEANINGFUL EFFECT or UNCLEAR. It is the
          TESTED rung of the evidence ladder, for this person under the conditions tested. Older fixed A-then-B plans keep their original reading.
        </p>
        <button className="btn-ghost mono" disabled={running} onClick={() => void run()}>{running ? 'RUNNING…' : 'RUN ANALYSIS'}</button>
      </div>
      {message && <div className="conf-message mono">{message}</div>}
      {rows.length === 0 && <div className="state-empty mono">NO EXPERIMENTS YET · START ONE FROM A SUPPLEMENT IN THE APP</div>}

      <div className="conf-grid">
        {rows.map(r => {
          const p = r.payload
          return (
            <div key={r.key} className={`card conf-card conf-${cardClass(r.state)}`}>
              <div className="conf-card-head">
                <span className="conf-title">{p.name} · {DESIGN[p.protocol?.design] ?? p.protocol?.design}</span>
                <span className={`conf-state mono conf-state-${cardClass(r.state)}`}>{LABEL[r.state] ?? r.state}</span>
              </div>
              {p.protocol && <Schedule p={p.protocol} />}
              {p.metrics.map(m => (
                <div key={m.metric} className="conf-metric">
                  <div className="conf-title">{m.label}{m.role === 'primary' ? ' (registered primary)' : ''}</div>
                  <p className="conf-summary">{m.summary}</p>
                  {m.diff && m.zone ? <IntervalBar effect={m.diff} mde={m.zone} /> : m.diff && m.mde !== null && <IntervalBar effect={m.diff} mde={m.mde} />}
                  {m.diff && (m.zone || m.mde !== null) && (
                    <div className="conf-legend mono">
                      <span>{m.n0} days without · {m.n1} days with</span>
                      {m.zone
                        ? <span>shaded: the {zoneText(m.zone, m.diff.unit)} no-effect zone set at registration</span>
                        : <span>shaded: smaller than ±{(m.mde ?? 0).toFixed(m.diff.unit === '%' ? 1 : 2)} is below what this design can detect</span>}
                      {m.randomizationP != null && <span>exact randomisation p {m.randomizationP}</span>}
                      {m.replicated !== null && <span>{m.replicated ? 'repeated in both cycles' : 'not repeated across cycles'}</span>}
                    </div>
                  )}
                  {m.notes.map((n, i) => <p key={i} className={`conf-footnote ${m.trendWarning && n.includes('drifting') ? 'conf-warn' : ''}`}>{n}</p>)}
                </div>
              ))}
              {p.adherence.rate !== null && (
                <div className="conf-stats mono">
                  <span>{p.protocol?.design === 'micro_randomized' ? 'rule kept' : 'logged'} on {p.adherence.interventionLogged} of {p.adherence.interventionDays} with-{p.protocol?.design === 'micro_randomized' ? 'days' : 'phase days'} ({Math.round(p.adherence.rate * 100)}%)</span>
                  {p.adherence.baselineLogged > 0 && <span>{p.adherence.baselineLogged} slip(s) in the without-phase</span>}
                </div>
              )}
              <div className="conf-foot mono">registered {p.preregisteredAt?.slice(0, 16).replace('T', ' ')} · calculated {r.calculated_at.slice(0, 16).replace('T', ' ')}</div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default Experiments
