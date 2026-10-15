// The evidence board (analysis review phase D; was the confirmatory card grid of Analysis Layer 3, Phase 3). Every
// question of the fixed catalogue, drawn to scale and grouped by outcome, in the catalogue's order and never sorted by p:
// the dot is the estimate, the line its 95% interval, the grey block the no-effect zone, the ticks the smallest effect the
// data could detect. The verdict compares the interval with the zone (CLEAR, NEGLIGIBLE, UNCLEAR, NOT YET); every row is
// on the ASSOCIATED rung of the evidence ladder. A row opens its sentence, adjustments, checks, event curve and lag curve.
// Below the board: what came before off mornings (time-stratified case-crossover), marked exploratory.

import { LoadBanner } from '../components/shared/LoadBanner'
import { Fragment, useCallback, useEffect, useState } from 'react'
import { supabase } from '../lib/supabase'
import { warnOnError } from '../lib/schema'
import './Confirmatory.css'

type Verdict = 'clear' | 'negligible' | 'unclear' | 'not_yet'

interface Effect { value: number; lo: number; hi: number; unit: string }
interface Payload {
  id: string
  label: string
  outcome: 'hrv' | 'rhr' | 'sleep' | 'energy' | ''
  rung: string
  summary: string
  reason: string | null
  n: number
  nOn: number | null
  nOff: number | null
  effect: Effect | null
  noEffect: number | null
  mde: number | null
  p: number | null
  pAdjusted: number | null
  eValue: { point: number; ci: number } | null
  negativeControl: (Effect & { clear: boolean }) | null
  readiness: { have: number; need: number; unit: string } | null
  adjustments: string[]
  notes: string[]
  familySize: number
  catalogueVersion: string
  window: { start: string; end: string }
  // Analysis review phase E.
  verdictInterval: { lo: number; hi: number; boundary: number; information: number } | null
  pooled: { family: string; members: number; value: number; lo: number; hi: number; unit: string; probabilityBeyondZone: number } | null
  registeredOn?: string
  daysSince?: number
}
interface LedgerRow { question_id: string; as_of: string; verdict: string }
interface Row { key: string; as_of: string; state: string; calculated_at: string; payload: Payload }

interface CurveDay { k: number; mean: number | null; lo: number | null; hi: number | null; chanceLo: number | null; chanceHi: number | null; n: number }
interface EventCurve { id: string; state: string; unit: string; events: number; days: CurveDay[]; summary: string }
interface LagCurve { id: string; state: string; unit: string; perText: string; lags: { lag: number; value: number; lo: number; hi: number }[]; dose: { group: string; n: number; mean: number; lo: number; hi: number }[] | null; summary: string }
interface CrossoverEntry { exposure: string; label: string; cases: number; need: number; or: number | null; lo: number | null; hi: number | null; verdict: string }
interface Crossover { outcome: string; text: string; caseDays: number; rows: CrossoverEntry[] }

// The catalogue's order (supabase/functions/_shared/analysis-core/hypotheses.ts).
const CATALOGUE = [
  'alcohol_hrv', 'alcohol_rhr', 'alcohol_sleep', 'caffeine_pm_sleep', 'late_meal_sleep', 'late_meal_hrv', 'stress_sleep',
  'evening_training_sleep', 'training_hrv', 'recovery_hrv', 'sleep_hrv', 'steps_hrv', 'sleep_energy',
]
const GROUPS: [Payload['outcome'] | 'registered', string][] = [['hrv', 'HRV'], ['rhr', 'RESTING HEART RATE'], ['sleep', 'SLEEP'], ['energy', 'ENERGY'], ['registered', 'QUESTIONS YOU REGISTERED']]

// "CLEAR since 2026-10-13 (2 nights) · UNCLEAR 2026-10-10 to 2026-10-12 (3 nights)" from the ledger, newest first.
function historyLine(entries: LedgerRow[]): string | null {
  const sorted = [...entries].sort((a, b) => a.as_of.localeCompare(b.as_of))
  const runs: { verdict: string; from: string; to: string; nights: number }[] = []
  for (const e of sorted) {
    const last = runs[runs.length - 1]
    if (last && last.verdict === e.verdict) { last.to = e.as_of; last.nights++ } else runs.push({ verdict: e.verdict, from: e.as_of, to: e.as_of, nights: 1 })
  }
  if (runs.length === 0) return null
  return runs.reverse().slice(0, 4).map((r, i) => `${WORD[asVerdict(r.verdict)]} ${i === 0 ? `since ${r.from}` : `${r.from} to ${r.to}`} (${r.nights} night${r.nights === 1 ? '' : 's'})`).join(' · ')
}
const WORD: Record<Verdict, string> = { clear: 'CLEAR', negligible: 'NEGLIGIBLE', unclear: 'UNCLEAR', not_yet: 'NOT YET' }
const asVerdict = (s: string): Verdict => (s === 'clear' || s === 'negligible' || s === 'unclear' ? s : 'not_yet')

const fmt = (v: number, unit: string) =>
  `${v >= 0 ? '+' : '−'}${Math.abs(v).toFixed(unit === '%' ? 1 : unit === 'min' ? 0 : unit === 'bpm' ? 1 : 2)}${unit === '%' ? '%' : ` ${unit}`}`

// Interval on a number line: the detectable band (±MDE) is shaded, the 95 % interval is the bar, the estimate the tick.
// Kept for the experiment and intervention views.
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

// One forest-plot row on its group's shared axis: zone, zero, detectable-effect ticks, interval, estimate.
function ForestRow({ p, scale }: { p: Payload; scale: number }) {
  const e = p.effect
  if (!e) return null
  const x = (v: number) => 50 + Math.max(-52, Math.min(52, (v / scale) * 48))
  const zone = p.noEffect ?? 0
  return (
    <svg className="ev-forest" viewBox="0 0 100 10" preserveAspectRatio="none" role="img"
      aria-label={`${fmt(e.value, e.unit)}, 95% interval ${fmt(e.lo, e.unit)} to ${fmt(e.hi, e.unit)}, no-effect zone ±${zone}`}>
      {zone > 0 && <rect x={x(-zone)} y={0} width={x(zone) - x(-zone)} height={10} className="ev-zone" />}
      <line x1={50} x2={50} y1={0} y2={10} className="ev-zero" />
      {p.mde !== null && [-p.mde, p.mde].map((m) => <line key={m} x1={x(m)} x2={x(m)} y1={7.5} y2={10} className="ev-mde" />)}
      <line x1={x(e.lo)} x2={x(e.hi)} y1={5} y2={5} className="ev-ci" />
      <circle cx={x(e.value)} cy={5} r={1.6} className="ev-est" />
    </svg>
  )
}

// Days around an event, or lags after an exposure, on a labelled y axis: chance band, 95% interval, estimate.
function CurveChart({ points, unit, label, marker }: {
  points: { x: number; mean: number | null; lo: number | null; hi: number | null; chanceLo?: number | null; chanceHi?: number | null }[]
  unit: string
  label: (x: number) => string
  marker?: number
}) {
  const vals = points.flatMap((p) => [p.mean, p.lo, p.hi, p.chanceLo ?? null, p.chanceHi ?? null]).filter((v): v is number => v !== null)
  if (vals.length === 0) return null
  const lo = Math.min(0, ...vals)
  const hi = Math.max(0, ...vals)
  const pad = (hi - lo) * 0.1 || 1
  const y0 = lo - pad
  const y1 = hi + pad
  const W = 320
  const H = 120
  const L = 34
  const xs = points.map((p) => p.x)
  const px = (v: number) => L + ((v - Math.min(...xs)) / Math.max(1, Math.max(...xs) - Math.min(...xs))) * (W - L - 8)
  const py = (v: number) => 6 + (1 - (v - y0) / (y1 - y0)) * (H - 22)
  const ticks = [y0 + pad, (y0 + y1) / 2, y1 - pad].map((t) => Math.round(t * 10) / 10)
  const band = (lowOf: (p: (typeof points)[number]) => number | null | undefined, highOf: (p: (typeof points)[number]) => number | null | undefined) => {
    const pts = points.filter((p) => lowOf(p) != null && highOf(p) != null)
    if (pts.length < 2) return ''
    return [...pts.map((p) => `${px(p.x)},${py(highOf(p) as number)}`), ...pts.slice().reverse().map((p) => `${px(p.x)},${py(lowOf(p) as number)}`)].join(' ')
  }
  const line = points.filter((p) => p.mean !== null)
  return (
    <svg className="ev-curve" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Curve in ${unit}`}>
      {ticks.map((t) => (
        <g key={t}>
          <line x1={L} x2={W} y1={py(t)} y2={py(t)} className="ev-grid" />
          <text x={L - 4} y={py(t) + 3} className="ev-tick" textAnchor="end">{t}</text>
        </g>
      ))}
      <line x1={L} x2={W} y1={py(0)} y2={py(0)} className="ev-zero-line" />
      {marker !== undefined && <line x1={px(marker)} x2={px(marker)} y1={0} y2={H - 16} className="ev-marker" />}
      <polygon points={band((p) => p.chanceLo, (p) => p.chanceHi)} className="ev-chance" />
      <polygon points={band((p) => p.lo, (p) => p.hi)} className="ev-ci-band" />
      <polyline points={line.map((p) => `${px(p.x)},${py(p.mean as number)}`).join(' ')} className="ev-line" />
      {line.map((p) => <circle key={p.x} cx={px(p.x)} cy={py(p.mean as number)} r={2.6} className="ev-dot" />)}
      {points.map((p) => <text key={`l${p.x}`} x={px(p.x)} y={H - 3} className="ev-tick" textAnchor="middle">{label(p.x)}</text>)}
      <text x={2} y={10} className="ev-tick">{unit}</text>
    </svg>
  )
}

async function newest<T>(kind: string): Promise<{ key: string; state: string; payload: T }[]> {
  const { data, error } = await supabase
    .from('analysis_results')
    .select('key, as_of, state, calculated_at, payload')
    .eq('kind', kind)
    .order('as_of', { ascending: false })
    .order('calculated_at', { ascending: false })
  warnOnError(`evidence ${kind}`, error)
  if (error) throw new Error(error.message)
  const seen = new Set<string>()
  return ((data ?? []) as unknown as { key: string; state: string; payload: T }[]).filter((r) => (seen.has(r.key) ? false : (seen.add(r.key), true)))
}

export function Confirmatory() {
  const [rows, setRows] = useState<Row[]>([])
  const [curves, setCurves] = useState<Map<string, EventCurve>>(new Map())
  const [lags, setLags] = useState<Map<string, LagCurve>>(new Map())
  const [crossover, setCrossover] = useState<Crossover[]>([])
  const [open, setOpen] = useState<string | null>(null)
  const [registered, setRegistered] = useState<Row[]>([])
  const [history, setHistory] = useState<Map<string, string | null>>(new Map())
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      // Analysis review A8: newest row per key (latest as_of, then latest run). A failed read is an error, not an empty board.
      const [rel, ev, lg, cc, reg] = await Promise.all([
        newest<Payload>('relationship'), newest<EventCurve>('event_curve'), newest<LagCurve>('lag_curve'), newest<Crossover>('case_crossover'),
        newest<Payload>('registered_question'),
      ])
      setRows(rel as unknown as Row[])
      setRegistered(reg as unknown as Row[])
      setCurves(new Map(ev.map((r) => [r.key, r.payload])))
      setLags(new Map(lg.map((r) => [r.key, r.payload])))
      setCrossover(cc.map((r) => r.payload))
      setLoadError(null)
    } catch (e) {
      console.error('[BioScan] load failed:', e)
      setLoadError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  // Phase E: the open question's verdict history from the append-only ledger (loaded once per question).
  useEffect(() => {
    if (!open || history.has(open)) return
    void (async () => {
      const { data, error } = await supabase.from('evidence_ledger').select('question_id, as_of, verdict').eq('question_id', open).order('as_of', { ascending: false }).limit(180)
      warnOnError('evidence ledger', error)
      setHistory((h) => new Map(h).set(open, error ? null : historyLine((data ?? []) as LedgerRow[])))
    })()
  }, [open, history])

  const run = useCallback(async () => {
    setRunning(true)
    setMessage(null)
    const { error } = await supabase.functions.invoke('analysis-run', { body: { action: 'run' } })
    if (error) setMessage(`Analysis failed: ${error.message}`)
    await load()
    setRunning(false)
  }, [load])

  if (loading) return <div className="state-loading mono">LOADING THE EVIDENCE BOARD…</div>
  if (loadError) return <LoadBanner error={loadError} onRetry={() => { setLoading(true); void load() }} />

  const order = (k: string) => (CATALOGUE.indexOf(k) < 0 ? 999 : CATALOGUE.indexOf(k))
  const sorted = [...rows].sort((a, b) => order(a.key) - order(b.key) || a.key.localeCompare(b.key))
  const first = sorted[0]?.payload
  const tally = (['clear', 'negligible', 'unclear', 'not_yet'] as Verdict[])
    .map((v) => [v, sorted.filter((r) => asVerdict(r.state) === v).length] as const)
    .filter(([, n]) => n > 0)
    .map(([v, n]) => `${n} ${WORD[v].toLowerCase()}`)
    .join(' · ')

  return (
    <div className="conf">
      <div className="conf-head">
        <p className="conf-note">
          {rows.length} questions fixed in advance, each with a no-effect zone (HRV 2%, resting heart rate 1 bpm, sleep 10 minutes, energy half a point), re-tested every night on the last year.
          One regression per question, adjusted for weekday, a slow trend, the outcome before the exposure and the exposure before that, with Newey-West intervals.
          CLEAR: the 95% interval lies outside the zone. NEGLIGIBLE: inside it. UNCLEAR: across an edge. NOT YET: too few days. Every row is ASSOCIATED, not a cause.
          {first?.window ? ` Window ${first.window.start} to ${first.window.end} · catalogue v${first.catalogueVersion}.` : ''}
        </p>
        <button className="btn-ghost mono" disabled={running} onClick={() => void run()}>{running ? 'RUNNING…' : 'RUN ANALYSIS'}</button>
      </div>
      {message && <div className="conf-message mono">{message}</div>}
      {rows.length === 0 && <div className="state-empty mono">NO RESULTS YET · RUN ANALYSIS</div>}
      {tally && <div className="ev-tally mono">{tally}</div>}

      {GROUPS.map(([g, title]) => {
        const groupRows = g === 'registered' ? registered : sorted.filter((r) => r.payload.outcome === g)
        if (groupRows.length === 0) return null
        const scale = Math.max(
          ...groupRows.flatMap((r) => [r.payload.effect ? Math.abs(r.payload.effect.lo) : 0, r.payload.effect ? Math.abs(r.payload.effect.hi) : 0, r.payload.mde ?? 0, (r.payload.noEffect ?? 0) * 1.5]),
          (Math.max(...groupRows.map((r) => r.payload.noEffect ?? 0)) || 1) * 3,
        ) * 1.1
        const unit = groupRows.find((r) => r.payload.effect)?.payload.effect?.unit ?? ''
        return (
          <section key={g} className="card ev-group">
            <div className="ev-group-head mono">{title}<span className="ev-axis">axis ±{scale.toFixed(scale >= 10 ? 0 : 1)} {unit}</span></div>
            {groupRows.map((r) => {
              const p = r.payload
              const v = asVerdict(r.state)
              const isOpen = open === r.key
              const curve = curves.get(r.key)
              const lag = lags.get(r.key)
              return (
                <Fragment key={r.key}>
                  <button className="ev-row" onClick={() => setOpen(isOpen ? null : r.key)} aria-expanded={isOpen}>
                    <span className="ev-label">{p.label}</span>
                    <span className="ev-chips mono"><span className={`ev-verdict ev-${v}`}>{WORD[v]}</span><span className="ev-rung">ASSOCIATED</span></span>
                    <span className="ev-plot">{v !== 'not_yet' && <ForestRow p={p} scale={scale} />}</span>
                    <span className="ev-size mono">
                      {v !== 'not_yet' && p.effect
                        ? `${fmt(p.effect.value, p.effect.unit)} (${fmt(p.effect.lo, p.effect.unit)} to ${fmt(p.effect.hi, p.effect.unit)}) · ${p.nOn !== null ? `${p.nOn} with, ${p.nOff} without` : `${p.n} days`}`
                        : p.readiness ? `${p.readiness.have} of about ${p.readiness.need} ${p.readiness.unit}` : p.reason ?? ''}
                    </span>
                  </button>
                  {isOpen && (
                    <div className="ev-detail">
                      <p className="conf-summary">{p.summary}</p>
                      {p.registeredOn && <p className="conf-footnote">Registered {p.registeredOn}; tested only on the {p.daysSince ?? 0} days since.</p>}
                      {p.verdictInterval && p.effect && (p.verdictInterval.lo < p.effect.lo || p.verdictInterval.hi > p.effect.hi) && (
                        <p className="conf-footnote">
                          The verdict uses a wider interval ({fmt(p.verdictInterval.lo, p.effect.unit)} to {fmt(p.verdictInterval.hi, p.effect.unit)}, boundary {p.verdictInterval.boundary.toFixed(2)}) because the question is re-checked every night; {Math.round(p.verdictInterval.information * 100)}% of a full year's information so far.
                        </p>
                      )}
                      {p.adjustments?.length > 0 && <p className="conf-footnote">Adjusted for {p.adjustments.join(', ')}.</p>}
                      {p.pooled && (
                        <p className="conf-footnote">
                          Pooled with {p.pooled.members - 1} related question{p.pooled.members === 2 ? '' : 's'} about the same exposure: {fmt(p.pooled.value, p.pooled.unit)} ({fmt(p.pooled.lo, p.pooled.unit)} to {fmt(p.pooled.hi, p.pooled.unit)}); about {Math.round(p.pooled.probabilityBeyondZone * 100)}% likely beyond the zone under that model.
                        </p>
                      )}
                      {history.get(r.key) && <p className="conf-footnote mono">History: {history.get(r.key)}</p>}
                      {v === 'unclear' && p.readiness && <p className="conf-footnote">About {Math.max(0, p.readiness.need - p.readiness.have)} more {p.readiness.unit} would settle it.</p>}
                      <div className="conf-stats mono">
                        {p.mde !== null && p.effect && <span>detectable ±{p.mde.toFixed(p.effect.unit === '%' ? 1 : 2)}{p.effect.unit === '%' ? '%' : ` ${p.effect.unit}`}</span>}
                        {p.eValue && <span>E-value {p.eValue.point.toFixed(2)} (edge {p.eValue.ci.toFixed(2)})</span>}
                        {p.p !== null && <span>p {p.p.toFixed(4)}</span>}
                        {p.pAdjusted !== null && <span>BH-adjusted {p.pAdjusted.toFixed(4)}</span>}
                      </div>
                      {p.negativeControl && (
                        <p className={`conf-footnote ${p.negativeControl.clear ? 'conf-warn' : ''}`}>
                          Negative control (the outcome before the exposure): {fmt(p.negativeControl.value, p.negativeControl.unit)} ({fmt(p.negativeControl.lo, p.negativeControl.unit)} to {fmt(p.negativeControl.hi, p.negativeControl.unit)}){p.negativeControl.clear ? ': it already differed, so something else may drive both.' : '.'}
                        </p>
                      )}
                      {curve && curve.state === 'ready' && curve.days.length > 0 && (
                        <div className="ev-chart">
                          <div className="ev-chart-label mono">THE DAYS AROUND IT · {curve.events} EVENTS</div>
                          <CurveChart points={curve.days.map((d) => ({ x: d.k, mean: d.mean, lo: d.lo, hi: d.hi, chanceLo: d.chanceLo, chanceHi: d.chanceHi }))} unit={curve.unit} label={(k) => (k === 0 ? 'event' : k > 0 ? `+${k}` : `${k}`)} marker={0} />
                          <p className="conf-footnote">{curve.summary} Line: difference from comparable days without it, with its 95% interval; grey: simultaneous chance band.</p>
                        </div>
                      )}
                      {lag && lag.state === 'ready' && lag.lags.length > 0 && (
                        <div className="ev-chart">
                          <div className="ev-chart-label mono">OVER THE WEEK AFTER · {lag.perText.toUpperCase()}</div>
                          <CurveChart points={lag.lags.map((l) => ({ x: l.lag, mean: l.value, lo: l.lo, hi: l.hi }))} unit={lag.unit} label={(l) => (l === 0 ? 'same day' : `+${l}`)} />
                          <p className="conf-footnote">{lag.summary}</p>
                          {lag.dose?.map((d) => <p key={d.group} className="conf-footnote">{d.group} drinks: {fmt(d.mean, lag.unit)} from expected ({fmt(d.lo, lag.unit)} to {fmt(d.hi, lag.unit)}), {d.n} mornings</p>)}
                        </div>
                      )}
                      {p.notes.map((n, i) => <p key={i} className={`conf-footnote ${n.startsWith('Warning') ? 'conf-warn' : ''}`}>{n}</p>)}
                    </div>
                  )}
                </Fragment>
              )
            })}
          </section>
        )
      })}

      {crossover.length > 0 && (
        <section className="card ev-group">
          <div className="ev-group-head mono">WHAT CAME BEFORE OFF MORNINGS<span className="ev-axis">EXPLORATORY · a hint of what to test next</span></div>
          <p className="conf-footnote">Each morning outside its expected range on the unfavourable side against reference days on the same weekday in the same month (time-stratified case-crossover); odds ratio with its 95% interval.</p>
          {crossover.map((c) => (
            <div key={c.outcome} className="ev-cc">
              <div className="ev-label">{c.text} · {c.caseDays}</div>
              {c.rows.map((e) => (
                <div key={e.exposure} className="ev-cc-row mono">
                  <span>{e.label}</span>
                  <span>{e.or !== null && e.lo !== null && e.hi !== null ? `${e.or.toFixed(1)}× (${e.lo.toFixed(1)} to ${e.hi.toFixed(1)})` : `not yet: ${e.cases} of ${e.need}`}</span>
                  <span className={e.verdict === 'more_often' || e.verdict === 'less_often' ? 'ev-cc-hit' : ''}>
                    {e.verdict === 'more_often' ? 'MORE OFTEN BEFORE' : e.verdict === 'less_often' ? 'LESS OFTEN BEFORE' : e.verdict === 'no_clear_difference' ? 'NO CLEAR DIFFERENCE' : 'NOT YET'}
                  </span>
                </div>
              ))}
            </div>
          ))}
        </section>
      )}
    </div>
  )
}

export default Confirmatory
