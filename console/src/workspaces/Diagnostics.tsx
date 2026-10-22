// Analysis review phase G (P12): diagnostics of the engines that judge each morning, none of which the console showed
// before. The expected bands (how often the 80% and 99% bands actually held on the person's history, the current regime,
// recent flags), the series breaks that start a regime, the typicality engine with its scorecard against logged illness,
// and the personal fitness-fatigue fit. The app's composite scorecards are worked out on the phone (Today, DO THE SUMMARIES
// EARN THEIR PLACE?) from the same history.

import { useCallback, useEffect, useState } from 'react'
import { LoadBanner } from '../components/shared/LoadBanner'
import { newest } from './Confirmatory'
import './Confirmatory.css'

interface BandPoint { date: string; value: number | null; flag: 'extreme' | 'run' | null; side: 'low' | 'high' | null }
interface BandPayload {
  metric: string
  unit: string
  state: string
  regimeStart: string | null
  have: number
  need: number
  params: { alpha: number; gamma: number; sigma: number; df: number }
  calibration: { days: number; inside80: number | null; inside99: number | null }
  recent: BandPoint[]
  ewma: { value: number | null; runsActive: boolean }
}
interface BreakPayload { lastConfirmed?: string | null; breaks?: { date: string; status?: string; direction?: string }[] }
interface TypicalityPayload {
  baselineDays: number
  baselineNeed: number
  thresholds: { elevated: number; unusual: number } | null
  looUnusualShare: number | null
  skipped: string[]
  scorecard?: { verdict: string; onsets: number; caught: number; flags: number; flagsBeforeOnset: number; precision: number | null; recall: number | null; baseRate: number | null; sentence: string }
}
interface FitnessFatiguePayload {
  state: string
  standard: { ctl: number; atl: number }
  personal: { ctl: number; atl: number } | null
  adopted: boolean
  heldOutDays: number
  difference: number | null
  lo: number | null
  hi: number | null
  loadAdds: { difference: number; lo: number; hi: number } | null
  summary: string
}

const METRIC_LABEL: Record<string, string> = { hrv: 'HRV', rhr: 'Resting heart rate', sleep_hours: 'Sleep duration' }
const pct = (v: number | null | undefined) => (v === null || v === undefined ? '–' : `${Math.round(v * 100)}%`)

export function Diagnostics() {
  const [bands, setBands] = useState<{ key: string; state: string; payload: BandPayload }[]>([])
  const [breaks, setBreaks] = useState<{ key: string; state: string; payload: BreakPayload }[]>([])
  const [typicality, setTypicality] = useState<{ key: string; state: string; payload: TypicalityPayload } | null>(null)
  const [ff, setFf] = useState<{ key: string; state: string; payload: FitnessFatiguePayload } | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const [b, s, t, f] = await Promise.all([
        newest<BandPayload>('expected_band'),
        newest<BreakPayload>('series_break'),
        newest<TypicalityPayload>('typicality'),
        newest<FitnessFatiguePayload>('fitness_fatigue'),
      ])
      setBands(b)
      setBreaks(s.sort((x, y) => x.key.localeCompare(y.key)))
      setTypicality(t[0] ?? null)
      setFf(f[0] ?? null)
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [])
  useEffect(() => { void load() }, [load])

  if (loading) return <div className="state-loading mono">LOADING DIAGNOSTICS…</div>
  if (error) return <LoadBanner error={error} onRetry={() => { setLoading(true); void load() }} />

  return (
    <div className="conf">
      <div className="conf-head">
        <p className="conf-note">
          How the engines behind each morning are doing on your own history: whether the expected bands held as often as they claim,
          where a device change started a new regime, and whether the typicality flag and the personal load spans earn their place.
        </p>
      </div>

      <h3 className="iv-section-title mono">EXPECTED BANDS</h3>
      {bands.length === 0 && <div className="state-empty mono">NOT PUBLISHED YET · THE NIGHTLY ANALYSIS WRITES THESE</div>}
      <div className="conf-grid">
        {bands.map(({ key, payload: p }) => {
          const flags = p.recent.filter((d) => d.flag)
          return (
            <div key={key} className="card conf-card">
              <div className="conf-card-head">
                <span className="conf-title">{METRIC_LABEL[p.metric] ?? p.metric}</span>
                <span className="conf-state mono">{p.state.toUpperCase()}</span>
              </div>
              <div className="conf-stats mono">
                <span>80% band held {pct(p.calibration.inside80)} of {p.calibration.days} mornings</span>
                <span>99% band held {pct(p.calibration.inside99)}</span>
              </div>
              <div className="conf-stats mono">
                <span>regime from {p.regimeStart ?? 'the start'}</span>
                <span>{p.have} of {p.need} errors</span>
                <span>alpha {p.params.alpha} · gamma {p.params.gamma}</span>
                {p.ewma.runsActive && <span>run chart active</span>}
              </div>
              {flags.length === 0
                ? <p className="conf-footnote mono">no flags in the last four weeks</p>
                : flags.map((d) => <p key={d.date} className="conf-footnote mono">{d.date} · {d.flag === 'extreme' ? 'beyond the 99% band' : 'run on one side'} · {d.side}</p>)}
            </div>
          )
        })}
      </div>

      <h3 className="iv-section-title mono">SERIES BREAKS</h3>
      {breaks.length === 0 && <div className="state-empty mono">NO SERIES-BREAK RESULTS YET</div>}
      <div className="conf-grid">
        {breaks.map(({ key, payload: p }) => (
          <div key={key} className="card conf-card">
            <div className="conf-card-head">
              <span className="conf-title">{key}</span>
              <span className="conf-state mono">{p.lastConfirmed ? `REGIME FROM ${p.lastConfirmed}` : 'ONE REGIME'}</span>
            </div>
            {(p.breaks ?? []).length === 0
              ? <p className="conf-footnote mono">no breaks detected</p>
              : (p.breaks ?? []).map((b) => <p key={b.date} className="conf-footnote mono">{b.date} · {b.status ?? 'candidate'}{b.direction ? ` · ${b.direction}` : ''}</p>)}
          </div>
        ))}
      </div>

      <h3 className="iv-section-title mono">IS TODAY TYPICAL? (EXPERIMENTAL)</h3>
      {!typicality ? <div className="state-empty mono">NOT PUBLISHED YET</div> : (
        <div className="card conf-card">
          <div className="conf-card-head">
            <span className="conf-title">Typicality</span>
            <span className="conf-state mono">{typicality.state.toUpperCase()}</span>
          </div>
          <div className="conf-stats mono">
            <span>baseline {typicality.payload.baselineDays} of {typicality.payload.baselineNeed} days</span>
            {typicality.payload.thresholds && <span>thresholds {typicality.payload.thresholds.elevated} / {typicality.payload.thresholds.unusual}</span>}
            <span>leave-one-out unusual share {pct(typicality.payload.looUnusualShare)} (nominal 2.5%)</span>
          </div>
          {typicality.payload.scorecard && (
            <>
              <p className="conf-summary">SCORECARD · {typicality.payload.scorecard.verdict.replace(/_/g, ' ').toUpperCase()}</p>
              <p className="conf-footnote">{typicality.payload.scorecard.sentence}</p>
              <div className="conf-stats mono">
                <span>precision {pct(typicality.payload.scorecard.precision)}</span>
                <span>recall {pct(typicality.payload.scorecard.recall)}</span>
                <span>base rate {pct(typicality.payload.scorecard.baseRate)}</span>
              </div>
            </>
          )}
        </div>
      )}

      <h3 className="iv-section-title mono">PERSONAL FITNESS AND FATIGUE SPANS</h3>
      {!ff ? <div className="state-empty mono">NOT PUBLISHED YET</div> : (
        <div className="card conf-card">
          <div className="conf-card-head">
            <span className="conf-title">Spans fitted to tomorrow's HRV</span>
            <span className="conf-state mono">{ff.payload.adopted ? 'PREDICTIVE · IN USE' : ff.state.replace(/_/g, ' ').toUpperCase()}</span>
          </div>
          <div className="conf-stats mono">
            <span>standard {ff.payload.standard.ctl} / {ff.payload.standard.atl} days</span>
            {ff.payload.personal && <span>yours {ff.payload.personal.ctl} / {ff.payload.personal.atl} days</span>}
            <span>{ff.payload.heldOutDays} held-out mornings</span>
            {ff.payload.difference !== null && <span>log score {ff.payload.difference >= 0 ? '+' : ''}{ff.payload.difference} ({ff.payload.lo} to {ff.payload.hi}) per morning</span>}
          </div>
          <p className="conf-footnote">{ff.payload.summary}</p>
        </div>
      )}
    </div>
  )
}

export default Diagnostics
