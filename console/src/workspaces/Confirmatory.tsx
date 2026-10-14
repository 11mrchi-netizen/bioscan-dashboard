// Confirmatory relationships (Analysis Layer 3, Phase 3): the published results of a fixed catalogue of hypotheses, each
// tested once, adjusted for day of week, corrected across the catalogue. There is deliberately no "moderate" tier: a link is
// supported after correction, not distinguishable from noise (with the smallest effect the data could have detected), or
// not testable yet (with the reason).

import { LoadBanner } from '../components/shared/LoadBanner'
import { useCallback, useEffect, useState } from 'react'
import { supabase } from '../lib/supabase'
import { warnOnError } from '../lib/schema'
import './Confirmatory.css'

type State = 'supported' | 'not_distinguishable' | 'insufficient'

interface Effect { value: number; lo: number; hi: number; unit: string }
interface Payload {
  label: string
  summary: string
  reason: string | null
  n: number
  nOn: number | null
  nOff: number | null
  effect: Effect | null
  mde: number | null
  p: number | null
  pAdjusted: number | null
  direction: 'positive' | 'negative' | null
  signMatchesPrior: boolean | null
  notes: string[]
  familySize: number
  catalogueVersion: string
  window: { start: string; end: string }
}
interface Row { key: string; as_of: string; state: State; calculated_at: string; payload: Payload }

const ORDER: Record<State, number> = { supported: 0, not_distinguishable: 1, insufficient: 2 }
const STATE_LABEL: Record<State, string> = {
  supported: 'SUPPORTED AFTER CORRECTION',
  not_distinguishable: 'NOT DISTINGUISHABLE FROM NOISE',
  insufficient: 'NOT TESTABLE YET',
}

const fmt = (v: number, unit: string) => `${v >= 0 ? '+' : '−'}${Math.abs(v).toFixed(unit === '%' ? 1 : 2)}${unit === '%' ? '%' : ` ${unit}`}`

// Interval on a number line: the detectable band (±MDE) is shaded, the 95 % interval is the bar, the estimate the tick.
export function IntervalBar({ effect, mde }: { effect: Effect; mde: number }) {
  const reach = Math.max(Math.abs(effect.lo), Math.abs(effect.hi), mde) * 1.15 || 1
  const pos = (v: number) => `${((v + reach) / (2 * reach)) * 100}%`
  return (
    <div className="conf-bar" role="img" aria-label={`Estimate ${fmt(effect.value, effect.unit)}, interval ${fmt(effect.lo, effect.unit)} to ${fmt(effect.hi, effect.unit)}`}>
      <div className="conf-bar-mde" style={{ left: pos(-mde), width: `${(mde / reach) * 100}%` }} />
      <div className="conf-bar-zero" />
      <div className="conf-bar-ci" style={{ left: pos(effect.lo), width: `${((effect.hi - effect.lo) / (2 * reach)) * 100}%` }} />
      <div className="conf-bar-est" style={{ left: pos(effect.value) }} />
    </div>
  )
}

export function Confirmatory() {
  const [rows, setRows] = useState<Row[]>([])
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  const [loadError, setLoadError] = useState<string | null>(null)

  const loadInner = useCallback(async () => {
    const { data, error } = await supabase
      .from('analysis_results')
      .select('key, as_of, state, calculated_at, payload')
      .eq('kind', 'relationship')
    warnOnError('confirmatory relationships', error)
    const list = ((data ?? []) as unknown as Row[]).sort((a, b) => ORDER[a.state] - ORDER[b.state] || a.key.localeCompare(b.key))
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

  if (loading) return <div className="state-loading mono">LOADING RELATIONSHIPS…</div>
  if (loadError) return <LoadBanner error={loadError} onRetry={() => { setLoading(true); void load() }} />

  const tested = rows.filter(r => r.state !== 'insufficient').length
  const first = rows[0]?.payload

  return (
    <div className="conf">
      <div className="conf-head">
        <p className="conf-note">
          {rows.length} fixed questions, each tested once. Both sides are adjusted for day of week, and the p-values are corrected across the {tested} that had enough data.
          An effect is shown only if it survives that correction; otherwise the card says how large an effect would have had to be to be seen.
          {first ? ` Window ${first.window.start} to ${first.window.end} · catalogue v${first.catalogueVersion}.` : ''}
        </p>
        <button className="btn-ghost mono" disabled={running} onClick={() => void run()}>{running ? 'RUNNING…' : 'RUN ANALYSIS'}</button>
      </div>
      {message && <div className="conf-message mono">{message}</div>}
      {rows.length === 0 && <div className="state-empty mono">NO RESULTS YET · RUN ANALYSIS</div>}

      <div className="conf-grid">
        {rows.map(r => {
          const p = r.payload
          return (
            <div key={r.key} className={`card conf-card conf-${r.state}`}>
              <div className="conf-card-head">
                <span className="conf-title">{p.label}</span>
                <span className={`conf-state mono conf-state-${r.state}`}>{STATE_LABEL[r.state]}</span>
              </div>
              <p className="conf-summary">{p.summary}</p>
              {p.effect && p.mde !== null && <IntervalBar effect={p.effect} mde={p.mde} />}
              {p.effect && p.mde !== null && (
                <div className="conf-legend mono">
                  <span>interval {fmt(p.effect.lo, p.effect.unit)} … {fmt(p.effect.hi, p.effect.unit)}</span>
                  <span>shaded: smaller than ±{p.mde.toFixed(p.effect.unit === '%' ? 1 : 2)} is below what {p.n} days can detect</span>
                </div>
              )}
              <div className="conf-stats mono">
                <span>n {p.n}</span>
                {p.nOn !== null && <span>{p.nOn} with · {p.nOff} without</span>}
                {p.p !== null && <span>p {p.p.toFixed(4)}</span>}
                {p.pAdjusted !== null && <span>adjusted {p.pAdjusted.toFixed(4)}</span>}
              </div>
              {p.notes.map((n, i) => <p key={i} className={`conf-footnote ${n.includes('opposite') ? 'conf-warn' : ''}`}>{n}</p>)}
              <div className="conf-foot mono">calculated {r.calculated_at.slice(0, 16).replace('T', ' ')}</div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default Confirmatory
