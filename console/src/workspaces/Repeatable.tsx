// Repeatable interventions (20261113_intervention_events.sql): a read-only view of the informal N-of-1 layer. An
// intervention you try now and then (nasal strips, a lactose meal) is declared in the app with the outcome windows it is
// expected to change; each event is read in its window against the same window on days without it. The label climbs with
// the number of events that have data and the wording is "consistent with", never "caused": the step after READY TO TEST
// PROPERLY is a pre-registered randomised experiment (EXPERIMENTS). Rows exist once analysis-run runs runEventEvidence.

import { LoadBanner } from '../components/shared/LoadBanner'
import { useCallback, useEffect, useState } from 'react'
import { supabase } from '../lib/supabase'
import { warnOnError } from '../lib/schema'
import './Confirmatory.css'
import './Interventions.css'

type Ladder = 'anecdote' | 'early_signal' | 'emerging' | 'ready'

interface IvRow { id: string; name: string; instructions: string | null; lifecycle_state: string }
interface ExpRow { intervention_id: string; outcome_key: string; role: string; direction: 'up' | 'down' | 'none'; window_start_h: number; window_end_h: number; exploratory: boolean }
interface EventRow { id: string; intervention_id: string; local_date: string; adhered: string; feeling: number | null; confounders: string[] | null; note: string | null }
interface ExpResult { label: string; role: string; exploratory: boolean; nWithData: number; nReference: number; diff: number | null; lo: number | null; hi: number | null; unit: string | null; observed: string; summary: string; randomised?: { nDo: number; nSkip: number; diff: number | null; lo: number | null; hi: number | null } | null }
interface EvidenceRow {
  key: string
  calculated_at: string
  payload: { nEvents: number; nUsed: number; ladder: Ladder; assignment: string; feeling: { n: number; mean: number | null }; draws?: { nDo: number; nSkip: number; kept: number; missed: number; broken: number } | null; expectations: ExpResult[]; notes: string[] }
}

const LADDER_LABEL: Record<Ladder, string> = { anecdote: 'ANECDOTE', early_signal: 'EARLY SIGNAL', emerging: 'EMERGING', ready: 'READY TO TEST PROPERLY' }
const ARROW = { up: '↑', down: '↓', none: '=' } as const

/** How far the next rung is, so a single night never reads as an answer. */
function ladderHint(ladder: Ladder, nWithData: number): string {
  if (ladder === 'ready') return 'Enough events to test it properly with a randomised experiment.'
  if (ladder === 'emerging') return `${15 - nWithData} more with data before a randomised test is worth setting up.`
  if (ladder === 'early_signal') return `${7 - nWithData} more with data before this is more than an early signal.`
  return `${3 - Math.min(3, nWithData)} more with data before anything can be read from it.`
}

function windowText(startH: number, endH: number): string {
  return startH === 0 ? `within ${endH} h` : `+${startH} to +${endH} h`
}

export function Repeatable() {
  const [ivs, setIvs] = useState<IvRow[]>([])
  const [exps, setExps] = useState<ExpRow[]>([])
  const [events, setEvents] = useState<EventRow[]>([])
  const [evidence, setEvidence] = useState<Map<string, EvidenceRow>>(new Map())
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const loadInner = useCallback(async () => {
    const [ivRes, expRes, evRes, resRes] = await Promise.all([
      supabase.from('interventions').select('id, name, instructions, lifecycle_state').eq('mode', 'episodic').order('created_at', { ascending: false }),
      supabase.from('intervention_expectations').select('intervention_id, outcome_key, role, direction, window_start_h, window_end_h, exploratory'),
      supabase.from('intervention_events').select('id, intervention_id, local_date, adhered, feeling, confounders, note').order('occurred_at', { ascending: false }).limit(300),
      supabase.from('analysis_results').select('key, calculated_at, payload').eq('kind', 'event_evidence'),
    ])
    warnOnError('interventions', ivRes.error)
    warnOnError('intervention_expectations', expRes.error)
    warnOnError('intervention_events', evRes.error)
    warnOnError('event_evidence', resRes.error)
    setIvs((ivRes.data ?? []) as unknown as IvRow[])
    setExps((expRes.data ?? []) as unknown as ExpRow[])
    setEvents((evRes.data ?? []) as unknown as EventRow[])
    const byKey = new Map<string, EvidenceRow>()
    for (const r of (resRes.data ?? []) as unknown as EvidenceRow[]) {
      const existing = byKey.get(r.key)
      if (!existing || r.calculated_at > existing.calculated_at) byKey.set(r.key, r)
    }
    setEvidence(byKey)
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

  if (loading) return <div className="state-loading mono">LOADING REPEATABLE INTERVENTIONS…</div>
  if (loadError) return <LoadBanner error={loadError} onRetry={() => { setLoading(true); void load() }} />

  return (
    <div className="conf">
      <div className="conf-head">
        <p className="conf-note">
          Interventions you repeat now and then, each event read in the window you declared for its outcome and compared with the same window on days without it. One try is never an answer: the label only climbs as events with data accumulate, and the wording stays “consistent with”. Added in the app (Lab, N=1).
        </p>
      </div>
      {ivs.length === 0 && <div className="state-empty mono">NO REPEATABLE INTERVENTIONS YET</div>}
      <div className="conf-grid">
        {ivs.map((iv) => {
          const ev = evidence.get(iv.id)
          const mine = events.filter((e) => e.intervention_id === iv.id)
          const primary = ev?.payload.expectations.find((x) => x.role === 'primary' && !x.exploratory) ?? ev?.payload.expectations[0]
          return (
            <div key={iv.id} className={`card conf-card ${ev?.payload.ladder === 'ready' ? 'conf-supported' : ''}`}>
              <div className="conf-card-head">
                <span className="conf-title">{iv.name}</span>
                <span className="conf-state mono">{ev ? LADDER_LABEL[ev.payload.ladder] : 'NOT ANALYSED YET'}</span>
              </div>
              <div className="conf-stats mono">
                {exps.filter((x) => x.intervention_id === iv.id).map((x, i) => (
                  <span key={i}>{ARROW[x.direction]} {x.outcome_key} ({windowText(Number(x.window_start_h), Number(x.window_end_h))}){x.exploratory ? ' · exploratory' : x.role === 'watch' ? ' · watching' : ''}</span>
                ))}
                <span>{mine.length} logged</span>
                {ev && <span>{ev.payload.nUsed} used</span>}
                {ev?.payload.draws && <span>{ev.payload.draws.nDo} do / {ev.payload.draws.nSkip} skip draws · {ev.payload.draws.kept} kept{ev.payload.draws.missed > 0 ? ` · ${ev.payload.draws.missed} not logged` : ''}{ev.payload.draws.broken > 0 ? ` · ${ev.payload.draws.broken} done on a skip day` : ''}</span>}
                {ev && ev.payload.feeling.n > 0 && <span>felt {ev.payload.feeling.mean?.toFixed(1) ?? '–'} on average ({ev.payload.feeling.n} rated)</span>}
              </div>
              {ev ? (
                <>
                  <p className="conf-footnote mono">{ladderHint(ev.payload.ladder, primary?.nWithData ?? 0)}</p>
                  {ev.payload.expectations.map((x, i) => (
                    <div key={i} className="conf-metric">
                      <p className="conf-summary">{x.summary}</p>
                      {x.randomised && x.randomised.diff !== null && (
                        <p className="conf-footnote mono">drawn days only ({x.randomised.nDo} do, {x.randomised.nSkip} skip): {x.randomised.diff.toFixed(2)}{x.unit ? ` ${x.unit}` : ''}{x.randomised.lo !== null && x.randomised.hi !== null ? ` (${x.randomised.lo.toFixed(2)} to ${x.randomised.hi.toFixed(2)})` : ''}</p>
                      )}
                    </div>
                  ))}
                  {ev.payload.notes.map((n, i) => <p key={i} className="conf-footnote">{n}</p>)}
                </>
              ) : (
                <p className="conf-footnote mono">no analysis yet: it is published nightly once events have data</p>
              )}
              {mine.length > 0 && (
                <ul className="iv-findings">
                  {mine.slice(0, 8).map((e) => (
                    <li key={e.id} className="iv-finding iv-sev-info">
                      <span className="iv-finding-severity mono">{e.local_date}</span>
                      <span className="iv-finding-message">
                        {e.adhered === 'yes' ? 'as planned' : e.adhered === 'partial' ? 'partly' : 'not done'}
                        {e.feeling !== null ? ` · felt ${e.feeling}/10` : ''}
                        {e.confounders && e.confounders.length > 0 ? ` · ${e.confounders.join(', ')}` : ''}
                        {e.note ? ` · ${e.note}` : ''}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
