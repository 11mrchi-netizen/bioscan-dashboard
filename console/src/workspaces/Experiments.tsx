// Supplement N-of-1 experiments (Analysis Layer 3, Phase 5): the pre-registered plan and the published result of each
// experiment. A plan is registered in the Android app (Supplements > TEST IT) and cannot be changed afterwards; this view is
// read-only. While an experiment runs its numbers are interim and carry no verdict.

import { useCallback, useEffect, useState } from 'react'
import { supabase } from '../lib/supabase'
import { warnOnError } from '../lib/schema'
import { IntervalBar } from './Confirmatory'
import './Confirmatory.css'

type Verdict = 'effect_detected' | 'no_detectable_effect' | 'insufficient' | 'running'
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
}
interface Payload {
  name: string
  stage: 'before' | 'running' | 'complete'
  status: string
  adherence: { interventionDays: number; interventionLogged: number; rate: number | null; baselineLogged: number; ok: boolean | null }
  metrics: MetricResult[]
  protocol: { design: string; phases: { label: string; start: string; end: string }[]; washoutDays: number; minAdherence: number }
  preregisteredAt: string
}
interface Row { key: string; as_of: string; state: State; calculated_at: string; payload: Payload }

const LABEL: Record<State, string> = {
  effect_detected: 'EFFECT SEEN',
  no_detectable_effect: 'NO DETECTABLE EFFECT',
  insufficient: 'NOT ENOUGH DAYS',
  running: 'INTERIM, NO VERDICT YET',
  before: 'NOT STARTED',
}

export function Experiments() {
  const [rows, setRows] = useState<Row[]>([])
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  const load = useCallback(async () => {
    const { data, error } = await supabase.from('analysis_results').select('key, as_of, state, calculated_at, payload').eq('kind', 'experiment')
    warnOnError('experiments', error)
    const list = ((data ?? []) as unknown as Row[]).sort((a, b) => (b.payload.protocol?.phases[0]?.start ?? '').localeCompare(a.payload.protocol?.phases[0]?.start ?? ''))
    setRows(list)
    setLoading(false)
  }, [])

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

  return (
    <div className="conf">
      <div className="conf-head">
        <p className="conf-note">
          Pre-registered supplement tests: you without it (A) against you with it (B), adjusted for day of week, with the smallest difference each design could have seen.
          Plans are registered in the app (Supplements, TEST IT) and locked; the result is analysed exactly as registered. This compares your own phases and cannot show cause.
        </p>
        <button className="btn-ghost mono" disabled={running} onClick={() => void run()}>{running ? 'RUNNING…' : 'RUN ANALYSIS'}</button>
      </div>
      {message && <div className="conf-message mono">{message}</div>}
      {rows.length === 0 && <div className="state-empty mono">NO EXPERIMENTS YET · START ONE FROM A SUPPLEMENT IN THE APP</div>}

      <div className="conf-grid">
        {rows.map(r => {
          const p = r.payload
          const phases = p.protocol?.phases ?? []
          return (
            <div key={r.key} className={`card conf-card conf-${r.state === 'effect_detected' ? 'supported' : r.state === 'no_detectable_effect' ? 'not_distinguishable' : 'insufficient'}`}>
              <div className="conf-card-head">
                <span className="conf-title">{p.name} · {p.protocol?.design}</span>
                <span className={`conf-state mono conf-state-${r.state === 'effect_detected' ? 'supported' : r.state === 'no_detectable_effect' ? 'not_distinguishable' : 'insufficient'}`}>{LABEL[r.state] ?? r.state}</span>
              </div>
              <div className="conf-stats mono">
                {phases.map((ph, i) => <span key={i}>{ph.label} {ph.start} → {ph.end}</span>)}
                {p.protocol?.washoutDays ? <span>washout {p.protocol.washoutDays} d</span> : null}
              </div>
              {p.metrics.map(m => (
                <div key={m.metric} className="conf-metric">
                  <div className="conf-title">{m.label}{m.role === 'primary' ? ' (registered primary)' : ''}</div>
                  <p className="conf-summary">{m.summary}</p>
                  {m.diff && m.mde !== null && <IntervalBar effect={m.diff} mde={m.mde} />}
                  {m.diff && m.mde !== null && (
                    <div className="conf-legend mono">
                      <span>{m.n0} days without · {m.n1} days with</span>
                      <span>shaded: smaller than ±{m.mde.toFixed(m.diff.unit === '%' ? 1 : 2)} is below what this design can detect</span>
                      {m.replicated !== null && <span>{m.replicated ? 'repeated in both cycles' : 'not repeated across cycles'}</span>}
                    </div>
                  )}
                  {m.notes.map((n, i) => <p key={i} className={`conf-footnote ${m.trendWarning && n.includes('drifting') ? 'conf-warn' : ''}`}>{n}</p>)}
                </div>
              ))}
              {p.adherence.rate !== null && (
                <div className="conf-stats mono">
                  <span>logged on {p.adherence.interventionLogged} of {p.adherence.interventionDays} with-phase days ({Math.round(p.adherence.rate * 100)}%)</span>
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
