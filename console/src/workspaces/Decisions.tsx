// Evidence > DECISIONS (analysis hub phase D): from question to evidence to decision.
//   Queue      every evidence item in one of five states (analysis-core/evidenceItems.ts place): needs data, under
//              observation, ready to test, decision review, decided. No composite score: each item's place comes from
//              its own verdict and its own decision history.
//   Workbench  the selected item: verdict, effect against its no-effect zone, sample, readiness, engine version, what
//              this class of evidence can and cannot say, the verdict history (evidence ledger) and links to the result
//              and to the outcome in Explore.
//   Record     a decision (adopt, reject, continue testing, inconclusive) with threshold, rationale and review
//              condition; adopt and reject need a settled verdict. The row keeps a snapshot of the evidence it rested on
//              (append-only `decisions`), so the record stays readable when later results change.
// Deep links: #/evidence?question=<catalogue id | reg_N> and #/evidence?result=<kind>&key=<key> select an item.

import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { supabase } from '../lib/supabase'
import { localToday, localDaysAgo } from '../lib/dates'
import { hrefFor } from '../lib/links'
import { LoadBanner } from '../components/shared/LoadBanner'
import { HYPOTHESES } from '../../../supabase/functions/_shared/analysis-core/hypotheses.ts'
import {
  CLASS_LIMITS,
  allowedDecisions,
  decisionRow,
  evidenceItems,
  latestDecisions,
  place,
  type DecisionKind,
  type DecisionRecord,
  type EvidenceClass,
  type EvidenceItem,
  type QueueState,
  type ResultRow,
} from '../../../supabase/functions/_shared/analysis-core/evidenceItems.ts'
import './Decisions.css'

const KINDS = ['relationship', 'registered_question', 'experiment', 'intervention_its']
const STATES: { key: QueueState; label: string; hint: string }[] = [
  { key: 'decision_review', label: 'Decision review', hint: 'settled, changed, or due for review' },
  { key: 'ready_to_test', label: 'Ready to test', hint: 'unclear, and you can switch it on and off' },
  { key: 'under_observation', label: 'Under observation', hint: 'collecting, or not under your control' },
  { key: 'needs_data', label: 'Needs data', hint: 'blocked until there is enough' },
  { key: 'decided', label: 'Decided', hint: 'adopted or rejected, still standing' },
]
const CLASS_LABEL: Record<EvidenceClass, string> = { association: 'association', experiment: 'experiment', interrupted_time_series: 'time series' }
const DECISION_LABEL: Record<DecisionKind, string> = { adopt: 'Adopt', reject: 'Reject', continue_testing: 'Continue testing', inconclusive: 'Inconclusive' }
// outcome group or metric -> the series of metric-series.json to open in Explore
const OUTCOME_SERIES: Record<string, string> = { hrv: 'hrv', rhr: 'rhr', sleep: 'sleep_hours', sleep_hours: 'sleep_hours', energy: 'energy' }

interface LedgerRow { as_of: string; verdict: string; estimate: number | null; lo: number | null; hi: number | null; unit: string | null; engine_version: string }

const fmt = (v: number | null | undefined) => (v === null || v === undefined ? '—' : Math.abs(v) >= 10 ? v.toFixed(1) : v.toFixed(2))

function ledgerId(item: EvidenceItem): string | null {
  if (item.id.startsWith('q:')) return item.id.slice(2)
  if (item.id.startsWith('reg:')) return `reg_${item.id.slice(4)}`
  return null
}

export function Decisions() {
  const { user } = useAuth()
  const [params, setParams] = useSearchParams()
  const [rows, setRows] = useState<ResultRow[]>([])
  const [decisions, setDecisions] = useState<DecisionRecord[]>([])
  const [regCause, setRegCause] = useState<Map<string, string>>(new Map())
  const [names, setNames] = useState<Map<string, string>>(new Map())
  const [error, setError] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [classFilter, setClassFilter] = useState<EvidenceClass | 'all'>('all')
  const [selected, setSelected] = useState<string | null>(null)

  useEffect(() => {
    if (!user) return
    let current = true
    Promise.all([
      supabase.from('analysis_results').select('kind, key, as_of, engine_version, inputs_hash, state, payload, calculated_at').in('kind', KINDS).order('as_of', { ascending: false }).limit(1000),
      supabase.from('decisions').select('*').order('decided_at', { ascending: false }),
      supabase.from('registered_questions').select('id, exposure'),
      supabase.from('interventions').select('id, name'),
    ]).then(([res, dec, reg, ints]) => {
      if (!current) return
      const e = res.error ?? dec.error
      if (e) { setError(e.message); return }
      setRows((res.data ?? []) as ResultRow[])
      setDecisions((dec.data ?? []) as DecisionRecord[])
      setRegCause(new Map(((reg.data ?? []) as { id: number; exposure: string }[]).map((r) => [`reg_${r.id}`, r.exposure])))
      setNames(new Map(((ints.data ?? []) as { id: string; name: string }[]).map((r) => [String(r.id), r.name])))
      setError(null)
      setLoaded(true)
    }).catch((e: unknown) => { if (current) setError(e instanceof Error ? e.message : String(e)) })
    return () => { current = false }
  }, [user, attempt])

  const today = localToday()
  const items = useMemo(() => {
    const causeOf = (id: string) => HYPOTHESES.find((h) => h.id === id)?.cause ?? regCause.get(id) ?? null
    return evidenceItems(rows, { causeOf, interventionName: (id) => names.get(id) ?? null })
  }, [rows, regCause, names])
  const latest = useMemo(() => latestDecisions(decisions), [decisions])
  const placed = useMemo(() => items.map((item) => ({ item, ...place(item, latest.get(item.id), today) })), [items, latest, today])

  // Deep links from lib/links.ts (result and question references).
  useEffect(() => {
    const q = params.get('question')
    const kind = params.get('result')
    const key = params.get('key')
    let id: string | null = null
    if (q) id = q.startsWith('reg_') ? `reg:${q.slice(4)}` : `q:${q}`
    else if (kind && key) id = kind === 'relationship' ? `q:${key}` : kind === 'registered_question' ? `reg:${key.replace(/^reg_/, '')}` : kind === 'experiment' ? `exp:${key}` : kind === 'intervention_its' ? `its:${key}` : null
    if (id) setSelected(id)
  }, [params])

  const select = (id: string | null) => {
    setSelected(id)
    if (params.get('question') || params.get('result')) setParams(new URLSearchParams())
  }

  if (error) return <LoadBanner error={error} onRetry={() => setAttempt((a) => a + 1)} />
  if (!loaded) return <div className="state-loading mono">LOADING THE EVIDENCE…</div>

  const visible = placed.filter((p) => classFilter === 'all' || p.item.evidenceClass === classFilter)
  const current = placed.find((p) => p.item.id === selected) ?? null

  return (
    <div className="dec">
      <div className="card dec-note">
        <p>
          Every question, experiment and intervention read on one scale. An item&apos;s place comes from its own verdict and its own
          decision history, never from a combined score. Associations can say what goes together for you; only an experiment
          tests what a change does. Adopt and reject need a settled verdict; every decision keeps the evidence it rested on.
        </p>
        <div className="dec-filters mono">
          {(['all', 'association', 'experiment', 'interrupted_time_series'] as const).map((c) => (
            <button key={c} className={`dec-chip ${classFilter === c ? 'on' : ''}`} onClick={() => setClassFilter(c)}>
              {c === 'all' ? `ALL (${placed.length})` : `${CLASS_LABEL[c].toUpperCase()} (${placed.filter((p) => p.item.evidenceClass === c).length})`}
            </button>
          ))}
        </div>
      </div>

      <div className="dec-board">
        {STATES.map((s) => {
          const list = visible.filter((p) => p.state === s.key)
          return (
            <section key={s.key} className="dec-col" aria-label={s.label}>
              <div className="dec-col-head mono">{s.label.toUpperCase()} · {list.length}<span className="dec-muted"> {s.hint}</span></div>
              {list.length === 0 && <div className="dec-empty mono">—</div>}
              {list.map(({ item, reason }) => (
                <button key={item.id} className={`dec-card ${selected === item.id ? 'on' : ''}`} onClick={() => select(item.id)}>
                  <span className="dec-card-title">{item.title}</span>
                  <span className="dec-card-meta mono">
                    <span className={`dec-verdict v-${item.verdict}`}>{item.verdict.replace('_', ' ')}</span> · {CLASS_LABEL[item.evidenceClass]}
                  </span>
                  <span className="dec-card-reason">{reason}</span>
                </button>
              ))}
            </section>
          )
        })}
      </div>

      {current && (
        <Workbench
          key={current.item.id}
          item={current.item}
          state={current.state}
          reason={current.reason}
          history={decisions.filter((d) => d.item_id === current.item.id)}
          onClose={() => select(null)}
          onRecorded={(d) => setDecisions([d, ...decisions])}
        />
      )}
    </div>
  )
}

function EffectBar({ item }: { item: EvidenceItem }) {
  const e = item.effect
  if (!e) return <div className="dec-muted mono">no estimate yet</div>
  const zone = item.noEffect ?? 0
  const span = Math.max(Math.abs(e.lo), Math.abs(e.hi), zone * 1.5, 1e-9) * 1.15
  const x = (v: number) => 50 + (v / span) * 50
  return (
    <svg className="dec-effect" viewBox="0 0 100 18" preserveAspectRatio="none" role="img" aria-label={`effect ${fmt(e.value)} ${e.unit}, interval ${fmt(e.lo)} to ${fmt(e.hi)}, no-effect zone ±${zone}`}>
      {zone > 0 && <rect x={x(-zone)} y="2" width={x(zone) - x(-zone)} height="14" className="dec-zone" />}
      <line x1="50" x2="50" y1="0" y2="18" className="dec-zero" />
      <line x1={x(e.lo)} x2={x(e.hi)} y1="9" y2="9" className="dec-ci" />
      <circle cx={x(e.value)} cy="9" r="2.2" className="dec-point" />
    </svg>
  )
}

function Workbench({ item, state, reason, history, onClose, onRecorded }: {
  item: EvidenceItem
  state: QueueState
  reason: string
  history: DecisionRecord[]
  onClose: () => void
  onRecorded: (d: DecisionRecord) => void
}) {
  const [ledger, setLedger] = useState<LedgerRow[] | null>(null)
  const allowed = allowedDecisions(item)
  const [kind, setKind] = useState<DecisionKind>(allowed[0])
  const [threshold, setThreshold] = useState('')
  const [rationale, setRationale] = useState('')
  const [reviewOn, setReviewOn] = useState('')
  const [reviewCondition, setReviewCondition] = useState('')
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    const qid = ledgerId(item)
    if (!qid) { setLedger([]); return }
    let current = true
    supabase.from('evidence_ledger').select('as_of, verdict, estimate, lo, hi, unit, engine_version').eq('question_id', qid).order('as_of', { ascending: false }).limit(200)
      .then(({ data }) => { if (current) setLedger((data ?? []) as LedgerRow[]) })
    return () => { current = false }
  }, [item])

  // Only the nights the verdict changed, newest first.
  const changes = useMemo(() => {
    if (!ledger) return []
    const asc = [...ledger].reverse()
    return asc.filter((r, i) => i === 0 || r.verdict !== asc[i - 1].verdict).reverse()
  }, [ledger])

  const record = async () => {
    setMessage(null)
    const built = decisionRow(item, { decision: kind, rationale, threshold, reviewOn: reviewOn || undefined, reviewCondition, supersedes: history[0]?.id ?? null }, localToday())
    if ('error' in built) { setMessage(built.error); return }
    setSaving(true)
    const { data, error } = await supabase.from('decisions').insert(built.row).select('*').single()
    setSaving(false)
    if (error) { setMessage(`Not recorded: ${error.message}`); return }
    onRecorded(data as DecisionRecord)
    setRationale(''); setThreshold(''); setReviewOn(''); setReviewCondition('')
    setMessage('Recorded with a snapshot of the evidence above.')
  }

  const series = OUTCOME_SERIES[item.outcome]
  const resultHref = hrefFor(item.result)
  const exploreHref = series ? hrefFor({ kind: 'period', series, start: localDaysAgo(180), end: localToday() }) : null

  return (
    <section className="card dec-bench" aria-label="Evidence workbench">
      <div className="dec-bench-head">
        <div>
          <div className="dec-eyebrow mono">{CLASS_LABEL[item.evidenceClass].toUpperCase()} · {state.replace('_', ' ').toUpperCase()}</div>
          <h3 className="dec-bench-title">{item.title}</h3>
          <div className="dec-muted">{reason}</div>
        </div>
        <button className="btn-ghost mono" onClick={onClose}>✕</button>
      </div>

      <div className="dec-bench-grid">
        <div>
          <div className="dec-label mono">VERDICT</div>
          <div className={`dec-verdict-big v-${item.verdict}`}>{item.verdict.replace('_', ' ')}</div>
          <div className="dec-muted mono">engine: {item.engineState || '—'}</div>
        </div>
        <div className="dec-bench-effect">
          <div className="dec-label mono">EFFECT AGAINST THE NO-EFFECT ZONE</div>
          <EffectBar item={item} />
          {item.effect && (
            <div className="mono dec-small">
              {fmt(item.effect.value)} {item.effect.unit} (interval {fmt(item.effect.lo)} to {fmt(item.effect.hi)}) · zone ±{item.noEffect ?? '—'} {item.effect.unit}
            </div>
          )}
        </div>
        <div>
          <div className="dec-label mono">DATA</div>
          <div className="mono dec-small">{item.n ?? '—'} {item.evidenceClass === 'interrupted_time_series' ? 'active days' : 'days'}</div>
          {item.readiness && <div className="mono dec-small dec-muted">{item.readiness.have} of about {item.readiness.need} {item.readiness.unit}</div>}
          <div className="mono dec-small dec-muted">as of {item.asOf} · engine {item.engineVersion}</div>
        </div>
      </div>

      {item.summary && <p className="dec-summary">{item.summary}</p>}
      <p className="dec-limits">{CLASS_LIMITS[item.evidenceClass]}</p>
      <div className="dec-links mono">
        {exploreHref && <a href={exploreHref}>OUTCOME IN EXPLORE (180 D) →</a>}
        {resultHref && item.evidenceClass === 'association' && <a href="#/evidence">EVIDENCE BOARD →</a>}
      </div>

      {changes.length > 0 && (
        <div className="dec-history">
          <div className="dec-label mono">VERDICT HISTORY (CHANGES ONLY)</div>
          {changes.slice(0, 10).map((r) => (
            <div key={`${r.as_of}|${r.engine_version}`} className="mono dec-small">
              {r.as_of} · <span className={`v-${r.verdict}`}>{r.verdict.replace('_', ' ')}</span>
              {r.estimate !== null && ` · ${fmt(r.estimate)} ${r.unit ?? ''} (${fmt(r.lo)} to ${fmt(r.hi)})`} · engine {r.engine_version}
            </div>
          ))}
        </div>
      )}

      <div className="dec-form">
        <div className="dec-label mono">RECORD A DECISION</div>
        <div className="dec-kinds">
          {(['adopt', 'reject', 'continue_testing', 'inconclusive'] as const).map((k) => (
            <label key={k} className={`dec-kind ${allowed.includes(k) ? '' : 'off'}`} title={allowed.includes(k) ? '' : 'needs a settled verdict (clear or negligible)'}>
              <input type="radio" name="decision" disabled={!allowed.includes(k)} checked={kind === k} onChange={() => setKind(k)} /> {DECISION_LABEL[k]}
            </label>
          ))}
        </div>
        {item.evidenceClass === 'association' && (kind === 'adopt' || kind === 'reject') && (
          <div className="dec-warn">This rests on an association. An experiment would test what the change itself does.</div>
        )}
        <input className="de-input" placeholder="Threshold: what change would matter to you (e.g. 3% HRV)" value={threshold} onChange={(e) => setThreshold(e.target.value)} />
        <textarea className="de-input dec-text" placeholder="Rationale: why this decision" value={rationale} onChange={(e) => setRationale(e.target.value)} />
        <div className="dec-row">
          <label className="dec-small">Review on <input className="de-input" type="date" value={reviewOn} onChange={(e) => setReviewOn(e.target.value)} /></label>
          <input className="de-input dec-grow" placeholder="Or revisit when… (e.g. 30 more days with it)" value={reviewCondition} onChange={(e) => setReviewCondition(e.target.value)} />
          <button className="btn-ghost mono" disabled={saving} onClick={() => void record()}>{saving ? 'RECORDING…' : 'RECORD'}</button>
        </div>
        {message && <div className="mono dec-small dec-message">{message}</div>}
      </div>

      {history.length > 0 && (
        <div className="dec-records">
          <div className="dec-label mono">DECISION RECORDS</div>
          {history.map((d) => {
            const changed = d.evidence.verdict !== item.verdict
            return (
              <div key={d.id} className="dec-record">
                <div className="mono dec-small">
                  <b>{DECISION_LABEL[d.decision]}</b> · {d.decided_at.slice(0, 10)}{d.review_on ? ` · review ${d.review_on}` : ''}{d.supersedes ? ` · supersedes #${d.supersedes}` : ''}
                </div>
                <div className="dec-small">{d.rationale}</div>
                {(d.threshold || d.review_condition) && <div className="dec-small dec-muted">{d.threshold ? `Threshold: ${d.threshold}. ` : ''}{d.review_condition ? `Revisit: ${d.review_condition}` : ''}</div>}
                <div className={`mono dec-small ${changed ? 'dec-changed' : 'dec-muted'}`}>
                  evidence then: {d.evidence.verdict}{d.evidence.effect ? ` ${fmt(d.evidence.effect.value)} ${d.evidence.effect.unit} (${fmt(d.evidence.effect.lo)} to ${fmt(d.evidence.effect.hi)})` : ''}, as of {d.evidence.asOf}, engine {d.evidence.engineVersion}
                  {changed ? ` · now: ${item.verdict}` : ' · unchanged'}
                </div>
              </div>
            )
          })}
        </div>
      )}
    </section>
  )
}

export default Decisions
