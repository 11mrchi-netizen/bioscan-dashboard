// Integrity workspace. For now the data-quality view of Analysis Layer 3, R1; Phase C of the analysis hub
// (docs/analysis-console/PLAN.md) rebuilds it as health overview, source matrix and issue investigation.
// Runs the analysis-run Edge Function and shows the published series-break results. A detected break is only a candidate;
// here the user says whether it was a device/source change (restarts personal baselines) or a real physiological change
// (dismissed). Decisions go to series_break_decisions.

import { LoadBanner } from '../components/shared/LoadBanner'
import { useCallback, useEffect, useState } from 'react'
import { supabase } from '../lib/supabase'
import { warnOnError } from '../lib/schema'
import './DataQuality.css'

interface SegmentStats { n: number; median: number; robustSd: number; integerShare: number }
interface BreakRow {
  date: string
  kind: 'level' | 'dispersion' | 'both'
  score: number
  status: 'candidate' | 'confirmed' | 'dismissed'
  reason: 'origin_change' | 'user_decision' | null
  before: SegmentStats
  after: SegmentStats
}
interface ResultRow {
  key: string
  as_of: string
  state: string
  confidence_have: number | null
  confidence_need: number | null
  calculated_at: string
  payload: { deviceSensitive: boolean; breaks: BreakRow[]; lastConfirmed: string | null }
}

type Decision = 'source_change' | 'real_change' | 'ignore'

const fmt = (v: number) => (Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(1))

function describe(b: BreakRow): string {
  const parts = [`median ${fmt(b.before.median)} → ${fmt(b.after.median)}`, `spread ${fmt(b.before.robustSd)} → ${fmt(b.after.robustSd)}`]
  if (Math.abs(b.after.integerShare - b.before.integerShare) >= 0.2) parts.push(`whole-number readings ${Math.round(b.before.integerShare * 100)}% → ${Math.round(b.after.integerShare * 100)}%`)
  return parts.join(' · ')
}

export function DataQuality() {
  const [rows, setRows] = useState<ResultRow[]>([])
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  const [loadError, setLoadError] = useState<string | null>(null)

  const loadInner = useCallback(async () => {
    const { data, error } = await supabase
      .from('analysis_results')
      .select('key, as_of, state, confidence_have, confidence_need, calculated_at, payload')
      .eq('kind', 'series_break')
      .order('key')
    warnOnError('data quality results', error)
    setRows((data ?? []) as unknown as ResultRow[])
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

  const decide = useCallback(async (metric: string, breakDate: string, decision: Decision) => {
    const { data: { user } } = await supabase.auth.getUser()
    if (!user) { setMessage('Not signed in'); return }
    const { error } = await supabase
      .from('series_break_decisions')
      .upsert({ user_id: user.id, metric, break_date: breakDate, decision }, { onConflict: 'user_id,metric,break_date' })
    if (error) { setMessage(`Could not save decision: ${error.message}`); return }
    await run()
  }, [run])

  if (loading) return <div className="state-loading mono">LOADING DATA QUALITY…</div>
  if (loadError) return <LoadBanner error={loadError} onRetry={() => { setLoading(true); void load() }} />

  return (
    <div className="dq">
      <div className="dq-header">
        <h2 className="dq-title">Integrity · series breaks</h2>
        <button className="btn-ghost mono" disabled={running} onClick={() => void run()}>{running ? 'RUNNING…' : 'RUN ANALYSIS'}</button>
      </div>
      <p className="dq-note">
        A series break is a date where a metric changes level or noise. It might be a different device, source or classifier — or a real change in you. Nothing restarts a baseline until you confirm.
      </p>
      {message && <div className="dq-message mono">{message}</div>}

      {rows.length === 0 && <div className="state-empty mono">NO RESULTS YET · RUN ANALYSIS</div>}

      <div className="dq-grid">
        {rows.map(r => (
          <div key={r.key} className="card dq-card">
            <div className="dq-card-head">
              <span className="dq-metric mono">{r.key}</span>
              <span className={`dq-state mono dq-state-${r.state.toLowerCase()}`}>{r.state}</span>
              {r.payload.deviceSensitive && <span className="dq-tag mono">device-sensitive</span>}
              <span className="dq-meta mono">{r.confidence_have}/{r.confidence_need} readings · through {r.as_of}</span>
            </div>
            {r.payload.breaks.length === 0 && <div className="dq-none mono">no break detected</div>}
            {r.payload.breaks.map(b => (
              <div key={b.date} className={`dq-break dq-break-${b.status}`}>
                <div className="dq-break-head">
                  <span className="mono">{b.date}</span>
                  <span className="dq-kind mono">{b.kind} · score {b.score.toFixed(1)}</span>
                  <span className="dq-status mono">{b.status}{b.reason ? ` (${b.reason.replace('_', ' ')})` : ''}</span>
                </div>
                <div className="dq-break-detail mono">{describe(b)}</div>
                {b.status === 'candidate' && (
                  <div className="dq-actions">
                    <span className="dq-question">Did your device or data source change around then?</span>
                    <button className="btn-ghost mono" disabled={running} onClick={() => void decide(r.key, b.date, 'source_change')}>YES, SOURCE CHANGED</button>
                    <button className="btn-ghost mono" disabled={running} onClick={() => void decide(r.key, b.date, 'real_change')}>NO, REAL CHANGE</button>
                    <button className="btn-ghost mono" disabled={running} onClick={() => void decide(r.key, b.date, 'ignore')}>IGNORE</button>
                  </div>
                )}
              </div>
            ))}
            <div className="dq-foot mono">calculated {r.calculated_at.slice(0, 16).replace('T', ' ')}</div>
          </div>
        ))}
      </div>
    </div>
  )
}

export default DataQuality
