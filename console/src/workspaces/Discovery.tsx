// Evidence > DISCOVERY (analysis hub phase C2): the corrected discovery screen that replaces the retired exploratory scan.
// The engine (analysis-core/discovery.ts) scans the registrable questions (six exposures x HRV, resting HR and sleep, lags
// 0-3) on the older two-thirds of the history with weekday and trend adjustment, Box-Jenkins prewhitening, Bartlett
// effective sample sizes and a Benjamini-Hochberg false-discovery rate of 10%. A flagged cell is a hint: REGISTER turns
// it into a question that the nightly job tests only on days after today (registered_questions, evidence ledger).

import { useEffect, useMemo, useState } from 'react'
import { supabase } from '../lib/supabase'
import { useAuth } from '../context/AuthContext'
import { localToday } from '../lib/dates'
import { LoadBanner } from '../components/shared/LoadBanner'
import { loadFrame, type LoadedFrame } from '../../../supabase/functions/_shared/analysis-core/runRelationships.ts'
import { DEFAULT_NO_EFFECT, DISCOVERY, discover, type DiscoveryCell } from '../../../supabase/functions/_shared/analysis-core/discovery.ts'
import { REGISTRABLE_EXPOSURES } from '../../../supabase/functions/_shared/analysis-core/hypotheses.ts'
import './Discovery.css'

const OUTCOME_LABEL = { hrv: 'HRV', rhr: 'resting HR', sleep_hours: 'sleep' } as const
const UNIT = { hrv: '%', rhr: 'bpm', sleep_hours: 'min' } as const
const LAG_TEXT = ['same day', 'next day', '2 days later', '3 days later']

interface Live { exposure: string; outcome: string; lag: number }

export function Discovery() {
  const { user } = useAuth()
  const userId = user?.id ?? null
  const [loaded, setLoaded] = useState<LoadedFrame | null>(null)
  const [live, setLive] = useState<Live[]>([])
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [pick, setPick] = useState<DiscoveryCell | null>(null)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    let current = true
    void (async () => {
      const id = userId
      if (!id) return
      const [frame, reg] = await Promise.all([
        loadFrame(supabase, id),
        supabase.from('registered_questions').select('exposure, outcome, lag').is('withdrawn_at', null),
      ])
      if (!current) return
      if ('error' in frame) setError(frame.error)
      else { setLoaded(frame); setError(null) }
      setLive((reg.data ?? []) as Live[])
    })().catch((e) => { if (current) setError(e instanceof Error ? e.message : String(e)) })
    return () => { current = false }
  }, [attempt, userId])

  const result = useMemo(() => (loaded ? discover(loaded.frame) : null), [loaded])
  const isLive = (c: DiscoveryCell) => live.some((l) => l.exposure === c.exposure && l.outcome === c.outcome && l.lag === c.lag)

  const register = async (c: DiscoveryCell) => {
    if (!userId || !result) return
    setSaving(true)
    setMessage(null)
    const { error: e } = await supabase.from('registered_questions').insert({
      exposure: c.exposure, outcome: c.outcome, lag: c.lag, no_effect: DEFAULT_NO_EFFECT[c.outcome],
      registered_on: localToday(), source: 'discovery',
      note: `discovery ${result.version}: r ${c.r?.toFixed(3)}, q ${c.q?.toFixed(3)}, ${c.n} pairs, ${result.window.start}..${result.window.end}`,
    })
    setSaving(false)
    if (e) { setMessage(`Could not register: ${e.message}`); return }
    setLive([...live, { exposure: c.exposure, outcome: c.outcome, lag: c.lag }])
    setMessage('Registered. The nightly job tests it on days after today; its verdicts appear on the evidence board and in the ledger.')
  }

  if (error) return <LoadBanner error={error} onRetry={() => setAttempt((a) => a + 1)} />
  if (!result) return <div className="state-loading mono">SCANNING…</div>

  const rows = result.exposures.flatMap((e) => result.outcomes.map((o) => ({ e, o })))
  const cell = (e: string, o: string, lag: number) => result.cells.find((c) => c.exposure === e && c.outcome === o && c.lag === lag)!

  return (
    <div className="disc">
      <div className="card disc-note">
        <div className="disc-eyebrow mono">EXPLORATORY · DISCOVERY {result.version}</div>
        <p>
          Every question that can be registered, scanned on the older two-thirds of your history
          ({result.window.start} → {result.window.end}){result.heldOut ? `; ${result.heldOut.start} → ${result.heldOut.end} is held out` : ''}.
          Both series lose their weekday pattern and slow trend and are prewhitened; each correlation is judged on its
          effective sample size, and the {result.tests} tests are corrected together (false-discovery rate {DISCOVERY.fdr * 100}%).
        </p>
        <p className="disc-muted">
          {result.flagged
            ? `${result.flagged} cell${result.flagged === 1 ? '' : 's'} stand${result.flagged === 1 ? 's' : ''} out after correction. A flagged cell is a hint, not a finding: register it to test it on new days.`
            : 'Nothing stands out after correction. That is the expected result when there is no strong link, not a failure.'}
        </p>
      </div>

      <div className="card disc-grid-card">
        <table className="disc-grid">
          <thead>
            <tr className="mono"><th />{LAG_TEXT.map((t, l) => <th key={l}>lag {l}<br /><span className="disc-muted">{t}</span></th>)}</tr>
          </thead>
          <tbody>
            {rows.map(({ e, o }) => (
              <tr key={`${e}|${o}`}>
                <th className="disc-row">{REGISTRABLE_EXPOSURES[e]?.label ?? e} <span className="disc-muted">→ {OUTCOME_LABEL[o as keyof typeof OUTCOME_LABEL]}</span></th>
                {[0, 1, 2, 3].map((lag) => {
                  const c = cell(e, o, lag)
                  const strength = c.r === null || c.band === null ? 0 : Math.min(1, Math.abs(c.r) / (2 * c.band))
                  const outside = c.r !== null && c.band !== null && Math.abs(c.r) > c.band
                  const bg = c.r === null ? 'transparent' : c.r < 0 ? `rgba(96,165,250,${0.08 + 0.5 * strength})` : `rgba(245,158,11,${0.08 + 0.5 * strength})`
                  return (
                    <td key={lag}>
                      <button
                        className={`disc-cell mono ${c.status} ${outside ? '' : 'inside'} ${pick === c ? 'on' : ''}`}
                        style={{ background: bg }}
                        onClick={() => { setPick(c); setMessage(null) }}
                        title={c.status === 'too_few' ? `too few: ${c.n} pairs, ${c.exposed} exposed days` : `r ${c.r?.toFixed(3)} · chance band ±${c.band?.toFixed(3)} · q ${c.q?.toFixed(3)}`}
                      >
                        {c.status === 'too_few' ? '—' : (c.r as number).toFixed(2)}
                        {c.catalogued && <i className="disc-mark" title="in the fixed catalogue">◆</i>}
                        {isLive(c) && <i className="disc-mark" title="registered">●</i>}
                      </button>
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
        <div className="disc-legend mono">
          <span><i className="sw neg" /> lower outcome</span><span><i className="sw pos" /> higher outcome</span>
          <span><i className="sw flag" /> flagged (q &lt; {DISCOVERY.fdr})</span><span className="disc-muted">faint: inside the 95% chance band</span>
          <span>◆ in the catalogue · ● registered · — too few</span>
        </div>
      </div>

      {pick && (
        <div className="card disc-detail">
          <div className="disc-eyebrow mono">{pick.status === 'flagged' ? 'FLAGGED' : pick.status === 'too_few' ? 'TOO FEW DAYS' : 'WITHIN CHANCE'}</div>
          <b>{REGISTRABLE_EXPOSURES[pick.exposure]?.label ?? pick.exposure} and {OUTCOME_LABEL[pick.outcome]} {LAG_TEXT[pick.lag]}</b>
          {pick.status === 'too_few'
            ? <p className="disc-muted">{pick.n} pairs and {pick.exposed} days with the exposure; at least {DISCOVERY.minPairs} pairs and {DISCOVERY.minExposed} days with and without it are needed.</p>
            : <p className="mono disc-facts">r {pick.r?.toFixed(3)} · chance band ±{pick.band?.toFixed(3)} · {pick.n} pairs (effective {pick.nEff.toFixed(0)}) · {pick.exposed} exposed days · p {pick.p?.toExponential(1)} · q {pick.q?.toFixed(3)}</p>}
          <p className="disc-muted">
            The correlation is between the adjusted, prewhitened series: its sign says the direction, not the size of an effect.
            A registered question gets a size in {UNIT[pick.outcome]} with its own interval, on days after today only
            (no-effect zone ±{DEFAULT_NO_EFFECT[pick.outcome]} {UNIT[pick.outcome]}).
          </p>
          {pick.catalogued
            ? <p className="disc-muted">This question is already in the fixed catalogue; its verdict is on the evidence board.</p>
            : isLive(pick)
              ? <p className="disc-muted">Already registered: it is being tested on new days.</p>
              : <button className="btn-ghost mono" disabled={saving || pick.status === 'too_few'} onClick={() => void register(pick)}>
                  {saving ? 'REGISTERING…' : pick.status === 'flagged' ? 'REGISTER THIS QUESTION' : 'REGISTER ANYWAY (NOT FLAGGED)'}
                </button>}
          {message && <p className="mono disc-message">{message}</p>}
        </div>
      )}
    </div>
  )
}

export default Discovery
