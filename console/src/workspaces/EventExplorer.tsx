// Analysis review phase G (P12): the event explorer. Any logged exposure against any of the three morning outcomes, drawn
// as the days around each first evening of a run against comparable days without it, with the chance band. It runs in
// the browser on the same engine and the same daily frame the nightly analysis uses (analysis-core evidence.ts and
// runRelationships.ts loadFrame, reading only the signed-in person's own rows), so a curve here is the curve the
// nightly run would draw for that pair. Exploratory: a shape to look at, not a verdict.

import { useEffect, useMemo, useState } from 'react'
import { supabase } from '../lib/supabase'
import { loadFrame } from '../../../supabase/functions/_shared/analysis-core/runRelationships.ts'
import type { LoadedFrame } from '../../../supabase/functions/_shared/analysis-core/runRelationships.ts'
import { loadConfirmedBreaks } from '../../../supabase/functions/_shared/analysis-core/runExpectedBands.ts'
import { analyseEventCurve, bandModel } from '../../../supabase/functions/_shared/analysis-core/evidence.ts'
import type { EventCurveResult } from '../../../supabase/functions/_shared/analysis-core/evidence.ts'
import { CurveChart } from './Confirmatory'
import './Confirmatory.css'

const EXPOSURES = [
  { key: 'alcohol_any', text: 'evenings with alcohol' },
  { key: 'caffeine_pm_any', text: 'days with caffeine after 14:00' },
  { key: 'late_meal', text: 'evenings with a meal after 21:00' },
  { key: 'stress_high', text: 'high-stress days' },
  { key: 'evening_training', text: 'days with training after 19:00' },
  { key: 'training_day', text: 'training days' },
  { key: 'recovery_any', text: 'days with a recovery session' },
]
const OUTCOMES = [
  { key: 'hrv', text: 'HRV' },
  { key: 'rhr', text: 'resting heart rate' },
  { key: 'sleep_hours', text: 'sleep' },
] as const

type Outcome = (typeof OUTCOMES)[number]['key']

export function EventExplorer() {
  const [loaded, setLoaded] = useState<LoadedFrame | null>(null)
  const [breaks, setBreaks] = useState<Map<string, string[]>>(new Map())
  const [error, setError] = useState<string | null>(null)
  const [exposure, setExposure] = useState(EXPOSURES[0].key)
  const [outcome, setOutcome] = useState<Outcome>('hrv')

  useEffect(() => {
    let live = true
    void (async () => {
      const { data } = await supabase.auth.getUser()
      const userId = data.user?.id
      if (!userId) {
        if (live) setError('Not signed in.')
        return
      }
      const [frame, confirmed] = await Promise.all([loadFrame(supabase, userId), loadConfirmedBreaks(supabase, userId)])
      if (!live) return
      if ('error' in frame) setError(frame.error)
      else setLoaded(frame)
      if (!('error' in confirmed)) setBreaks(confirmed)
    })().catch((e) => { if (live) setError(e instanceof Error ? e.message : String(e)) })
    return () => { live = false }
  }, [])

  const result: EventCurveResult | string | null = useMemo(() => {
    if (!loaded) return null
    const m = bandModel(loaded.frame, outcome, breaks.get(outcome) ?? [])
    if (!m) return 'Not enough mornings of this outcome to build its expected band yet.'
    const ex = EXPOSURES.find((e) => e.key === exposure)!
    const outcomeText = OUTCOMES.find((o) => o.key === outcome)!.text
    return analyseEventCurve(
      { id: `${exposure}_${outcome}`, exposure, outcome, label: `${outcomeText} around ${ex.text}`, eventText: ex.text, outcomeText },
      loaded.frame,
      m,
    )
  }, [loaded, breaks, exposure, outcome])

  return (
    <div className="conf">
      <div className="conf-head">
        <p className="conf-note">
          The days around each first evening of a run of an exposure, against comparable days of the same weekday without it, with a
          chance band that holds 95% of the time when nothing happens. Computed here on your own last year with the nightly engine.
          Exploratory: a shape to look at; the evidence board holds the questions that were fixed in advance.
        </p>
      </div>
      <div className="conf-stats mono">
        <label>EVENT{' '}
          <select value={exposure} onChange={(e) => setExposure(e.target.value)}>
            {EXPOSURES.map((e) => <option key={e.key} value={e.key}>{e.text}</option>)}
          </select>
        </label>
        <label>OUTCOME{' '}
          <select value={outcome} onChange={(e) => setOutcome(e.target.value as Outcome)}>
            {OUTCOMES.map((o) => <option key={o.key} value={o.key}>{o.text}</option>)}
          </select>
        </label>
      </div>
      {error && <div className="state-error mono">{error}</div>}
      {!error && !loaded && <div className="state-loading mono">LOADING YOUR DAILY FRAME…</div>}
      {typeof result === 'string' && <div className="state-empty mono">{result}</div>}
      {result && typeof result !== 'string' && (
        <div className="card conf-card">
          <div className="conf-card-head">
            <span className="conf-title">{result.label}</span>
            <span className="conf-state mono">{result.state === 'ready' ? `${result.events} EVENTS` : `NOT YET · ${result.events} OF ${result.need} EVENTS`}</span>
          </div>
          {result.state === 'ready' && (
            <CurveChart
              points={result.days.map((d) => ({ x: d.k, mean: d.mean, lo: d.lo, hi: d.hi, chanceLo: d.chanceLo, chanceHi: d.chanceHi }))}
              unit={result.unit}
              label={(k) => (k === 0 ? 'EVENT' : k > 0 ? `+${k}` : `${k}`)}
              marker={0}
            />
          )}
          <p className="conf-summary">{result.summary}</p>
          {result.differentBefore && <p className="conf-footnote conf-warn">A day before the event already sat outside the chance band: something else may drive both.</p>}
          <p className="conf-footnote mono">line: difference from comparable days without it, with its 95% interval · grey: chance band</p>
        </div>
      )}
    </div>
  )
}

export default EventExplorer
