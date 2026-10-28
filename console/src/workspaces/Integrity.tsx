// Integrity workspace (analysis hub phase C; replaces the Data Quality page). Answers at any time: is the data arriving,
// is it complete enough to trust, are the values plausible and consistent? It runs the same engine as the nightly job
// (analysis-core/integrity.ts through runIntegrity.ts buildIntegrity) live, over the selected range, with the signed-in
// client (row-level security keeps it to the user's own rows).
//
//   A  Health overview  coverage of due days, freshness of due streams, problems by severity, readiness for analysis
//   B  Source matrix    one row per series, grouped by table; selecting a row filters the issues
//   C  Issues           every problem with its days and the records behind it (Data or Explore links); series-break
//                       candidates join the list with the existing review (source change / real change / ignore)
// Collectors: the newest heartbeat per collector and the newest write per writer of daily_field_provenance.
//
// Coverage rules are per series (metric-series.json `expect`): a manual log or an event stream has no due days, so its
// absent days are unknown, never missing. The wear signal (days with minute heart rate) decides when band data was due.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { LoadBanner } from '../components/shared/LoadBanner'
import { useRange } from '../lib/analysisStore'
import { supabase } from '../lib/supabase'
import { SERIES_FILE, seriesById } from '../lib/schema'
import { hrefFor } from '../lib/links'
import { buildIntegrity } from '../../../supabase/functions/_shared/analysis-core/runIntegrity.ts'
import type { IntegrityReport, Issue, Severity, StreamStatus } from '../../../supabase/functions/_shared/analysis-core/integrity.ts'
import type { DataRef } from '../../../supabase/functions/_shared/analysis-core/dataRef.ts'
import './Integrity.css'

// --- Series-break review (Analysis Layer 3, R1), carried over from the Data Quality page ---

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
interface BreakResult { key: string; as_of: string; payload: { deviceSensitive: boolean; breaks: BreakRow[] } }
type Decision = 'source_change' | 'real_change' | 'ignore'

// series_break metric keys -> series ids of metric-series.json
const BREAK_SERIES: Record<string, string> = {
  hrv: 'hrv', rhr: 'rhr', steps: 'steps', sleep_hours: 'sleep_hours',
  deep_min: 'sleep_deep_min', rem_min: 'sleep_rem_min', light_min: 'sleep_light_min',
}

// Which analyses read each series (downstream impact). Everything is also in Explore.
const USED_BY: Record<string, string> = {
  hrv: 'series breaks, expected bands, typicality, evidence board',
  rhr: 'series breaks, expected bands, typicality, evidence board',
  sleep_hours: 'series breaks, expected bands, typicality, evidence board',
  steps: 'series breaks, typicality',
  sleep_deep_min: 'series breaks, typicality', sleep_rem_min: 'series breaks, typicality', sleep_light_min: 'series breaks',
  run_duration: 'training load, fitness-fatigue', strength_duration: 'training load', session_rpe: 'training load',
  run_avg_hr: 'training load (TRIMP)', energy: 'evidence board (morning outcomes)', mood: 'evidence board', stress: 'evidence board',
}

const fmt1 = (v: number) => (Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(1))
function describeBreak(b: BreakRow): string {
  const parts = [`median ${fmt1(b.before.median)} → ${fmt1(b.after.median)}`, `spread ${fmt1(b.before.robustSd)} → ${fmt1(b.after.robustSd)}`]
  if (Math.abs(b.after.integerShare - b.before.integerShare) >= 0.2) parts.push(`whole-number readings ${Math.round(b.before.integerShare * 100)}% → ${Math.round(b.after.integerShare * 100)}%`)
  return parts.join(' · ')
}

interface Collectors {
  heartbeats: { stream: string; ts: string }[]
  writers: { writer: string; origin: string; written_at: string }[]
}

const pct = (x: number | null) => (x === null ? '—' : `${Math.round(x * 100)}%`)
const ago = (iso: string) => {
  const h = (Date.now() - Date.parse(iso)) / 3_600_000
  return h < 1 ? `${Math.max(1, Math.round(h * 60))} min ago` : h < 48 ? `${Math.round(h)} h ago` : `${Math.round(h / 24)} d ago`
}
const RULE = { daily: 'every day', worn: 'when worn', none: 'not due' } as const
const COLLECTOR: Record<string, string> = { health_connect_sync: 'Health Connect sync', zepp_sync: 'Zepp sync', location: 'location', phone_usage: 'phone use' }

export function Integrity() {
  const { user } = useAuth()
  const range = useRange()
  const [report, setReport] = useState<IntegrityReport | null>(null)
  const [breaks, setBreaks] = useState<BreakResult[]>([])
  const [collectors, setCollectors] = useState<Collectors | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [ms, setMs] = useState<number | null>(null)
  // The focused series lives in the URL (#/integrity?series=hrv), so the Decisions workbench and saved views can open it.
  const [params, setParams] = useSearchParams()
  const focusParam = params.get('series')
  const focus = focusParam && seriesById(focusParam) ? focusParam : null
  const setFocus = (id: string | null) => setParams(id ? { series: id } : {})
  const [show, setShow] = useState<Record<Severity, boolean>>({ critical: true, warning: true, info: false })
  const [running, setRunning] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  const loadBreaks = useCallback(async () => {
    const { data, error: e } = await supabase.from('analysis_results').select('key, as_of, payload').eq('kind', 'series_break').order('key')
    if (e) throw new Error(e.message)
    setBreaks((data ?? []) as unknown as BreakResult[])
  }, [])

  useEffect(() => {
    if (!user) return
    let current = true
    const t0 = performance.now()
    setBusy(true)
    Promise.all([
      buildIntegrity(supabase, user.id, SERIES_FILE, new Date(), range),
      loadBreaks(),
      Promise.all([
        supabase.from('collector_heartbeats').select('stream, ts').order('ts', { ascending: false }).limit(500),
        supabase.from('daily_field_provenance').select('writer, origin, written_at').order('written_at', { ascending: false }).limit(500),
      ]),
    ])
      .then(([r, , [hb, prov]]) => {
        if (!current) return
        const latest = <T,>(rows: T[], key: (x: T) => string) => [...new Map(rows.map((x) => [key(x), x] as const)).values()]
        // rows come newest first, so the first per key is the newest: keep it
        const firstPer = <T,>(rows: T[], key: (x: T) => string) => latest([...rows].reverse(), key)
        setReport(r)
        setCollectors({
          heartbeats: firstPer((hb.data ?? []) as Collectors['heartbeats'], (x) => x.stream),
          writers: firstPer((prov.data ?? []) as Collectors['writers'], (x) => `${x.writer}|${x.origin}`),
        })
        setMs(Math.round(performance.now() - t0))
        setError(null)
        setBusy(false)
      })
      .catch((e: unknown) => {
        if (!current) return
        setError(e instanceof Error ? e.message : String(e))
        setBusy(false)
      })
    return () => { current = false }
  }, [user, range.start, range.end, attempt, loadBreaks]) // eslint-disable-line react-hooks/exhaustive-deps

  const runAnalysis = useCallback(async () => {
    setRunning(true)
    setMessage(null)
    const { error: e } = await supabase.functions.invoke('analysis-run', { body: { action: 'run' } })
    if (e) setMessage(`Analysis failed: ${e.message}`)
    await loadBreaks().catch((x: unknown) => setMessage(String(x)))
    setRunning(false)
  }, [loadBreaks])

  const decide = useCallback(async (metric: string, breakDate: string, decision: Decision) => {
    if (!user) return
    const { error: e } = await supabase
      .from('series_break_decisions')
      .upsert({ user_id: user.id, metric, break_date: breakDate, decision }, { onConflict: 'user_id,metric,break_date' })
    if (e) { setMessage(`Could not save decision: ${e.message}`); return }
    await runAnalysis()
  }, [user, runAnalysis])

  const candidates = useMemo(() => breaks.flatMap((b) => b.payload.breaks.filter((x) => x.status === 'candidate').map((x) => ({ metric: b.key, as_of: b.as_of, b: x }))), [breaks])

  const overview = useMemo(() => {
    if (!report) return null
    const judged = report.streams.filter((s) => s.coverage !== null)
    const due = judged.reduce((a, s) => a + s.daysDue, 0)
    const seen = judged.reduce((a, s) => a + s.daysObserved, 0)
    const fresh = { fresh: 0, late: 0, stale: 0, never: 0 }
    for (const s of report.streams) if (s.freshness !== 'not_judged') fresh[s.freshness]++
    const ready = judged.filter((s) => (s.coverage ?? 0) >= 0.8).length
    return { judged: judged.length, notJudged: report.streams.length - judged.length, due, seen, fresh, ready }
  }, [report])

  const groups = useMemo(() => {
    const m = new Map<string, StreamStatus[]>()
    for (const s of report?.streams ?? []) m.set(s.table, [...(m.get(s.table) ?? []), s])
    return [...m.entries()]
  }, [report])

  const issues = useMemo(() => (report?.issues ?? []).filter((i) => show[i.severity] && (!focus || i.seriesId === focus)), [report, show, focus])

  return (
    <div className="ig">
      <div className="ig-header">
        <h2 className="ig-title">Integrity</h2>
        <span className="ig-window mono">{report ? `${report.start} → ${report.end}` : `${range.start} → ${range.end}`}{busy ? ' · checking…' : ms !== null ? ` · ${ms} ms` : ''}</span>
        <button className="btn-ghost mono" disabled={busy} onClick={() => setAttempt((a) => a + 1)}>RECHECK</button>
        <button className="btn-ghost mono" disabled={running} onClick={() => void runAnalysis()}>{running ? 'RUNNING…' : 'RUN SERIES BREAKS'}</button>
      </div>

      {error && <LoadBanner error={error} onRetry={() => setAttempt((a) => a + 1)} />}
      {message && <div className="ig-message mono">{message}</div>}
      {!report && !error && <div className="state-loading mono">CHECKING EVERY STREAM…</div>}

      {report && overview && (
        <>
          <section className="ig-tiles" aria-label="Health overview">
            <div className="card ig-tile">
              <div className="ig-tile-label mono">CAPTURE COVERAGE</div>
              <div className="ig-tile-value">{overview.due ? pct(overview.seen / overview.due) : '—'}</div>
              <div className="ig-tile-sub mono">{overview.seen.toLocaleString()} of {overview.due.toLocaleString()} due days observed · {overview.judged} streams judged, {overview.notJudged} not due (manual logs, events)</div>
            </div>
            <div className="card ig-tile">
              <div className="ig-tile-label mono">SOURCE FRESHNESS</div>
              <div className="ig-tile-value">{overview.fresh.fresh}<span className="ig-tile-of"> / {overview.fresh.fresh + overview.fresh.late + overview.fresh.stale + overview.fresh.never} fresh</span></div>
              <div className="ig-tile-sub mono">{overview.fresh.late} late (&gt;2 d) · {overview.fresh.stale} stale (&gt;7 d) · {overview.fresh.never} never</div>
            </div>
            <div className="card ig-tile">
              <div className="ig-tile-label mono">DATA ANOMALIES</div>
              <div className="ig-tile-value">
                <span className="sev-critical">{report.totals.critical}</span> · <span className="sev-warning">{report.totals.warning}</span> · <span className="sev-info">{report.totals.info}</span>
              </div>
              <div className="ig-tile-sub mono">critical · warning · info{candidates.length ? ` · ${candidates.length} series break${candidates.length === 1 ? '' : 's'} to review` : ''}</div>
            </div>
            <div className="card ig-tile">
              <div className="ig-tile-label mono">ANALYSIS READINESS</div>
              <div className="ig-tile-value">{overview.ready}<span className="ig-tile-of"> / {overview.judged} streams</span></div>
              <div className="ig-tile-sub mono">with at least 80% of due days observed{report.wearSignal ? '' : ' · no wear signal: band streams cannot be judged'}</div>
            </div>
          </section>

          {collectors && (
            <section className="card ig-collectors mono" aria-label="Collectors">
              <span className="ig-collectors-label">COLLECTORS</span>
              {['health_connect_sync', 'zepp_sync', 'location', 'phone_usage'].map((stream) => {
                const hb = collectors.heartbeats.find((h) => h.stream === stream)
                return <span key={stream} className={hb ? '' : 'ig-muted'}>{COLLECTOR[stream]}: {hb ? ago(hb.ts) : 'no heartbeat'}</span>
              })}
              {collectors.writers.map((w) => <span key={`${w.writer}|${w.origin}`}>{w.writer} ({w.origin}): {ago(w.written_at)}</span>)}
            </section>
          )}

          <section className="card ig-matrix" aria-label="Source matrix">
            <table className="ig-table">
              <thead>
                <tr className="mono">
                  <th>SERIES</th><th>DUE</th><th>NEWEST</th><th>COVERAGE</th><th>MISSING · INVALID · CONFLICT · UNKNOWN</th><th>SOURCES</th><th>ISSUES</th>
                </tr>
              </thead>
              {groups.map(([table, list]) => (
                <tbody key={table}>
                  <tr className="ig-group mono"><td colSpan={7}>{table}</td></tr>
                  {list.map((s) => (
                    <tr key={s.seriesId} className={`ig-row ${focus === s.seriesId ? 'on' : ''}`} onClick={() => setFocus(focus === s.seriesId ? null : s.seriesId)}>
                      <td className="ig-name" title={`Used by: ${USED_BY[s.seriesId] ?? 'Explore'}`}>{s.label}</td>
                      <td className="mono">{RULE[s.expect]}</td>
                      <td className="mono">
                        <span className={`fr fr-${s.freshness}`}>{s.lastSeen ?? 'never'}</span>
                        {s.lagDays !== null && s.lagDays > 0 && <span className="ig-muted"> · {s.lagDays} d</span>}
                      </td>
                      <td className="mono">
                        {s.coverage === null
                          ? <span className="ig-muted">{s.daysWithValue} d logged</span>
                          : <span className="ig-cov"><span className="ig-cov-track"><i style={{ width: `${Math.round(s.coverage * 100)}%` }} /></span>{pct(s.coverage)} <span className="ig-muted">{s.daysObserved}/{s.daysDue}</span></span>}
                      </td>
                      <td className="mono">{s.states.missing} · {s.states.invalid} · {s.states.conflict} · <span className="ig-muted">{s.states.unknown}</span></td>
                      <td className="mono ig-muted">{s.sources.join(', ') || '—'}</td>
                      <td className="mono">{s.issues || ''}</td>
                    </tr>
                  ))}
                </tbody>
              ))}
            </table>
          </section>

          <section className="ig-issues" aria-label="Issues">
            <div className="ig-issues-head mono">
              <span>ISSUES{focus ? ` · ${seriesById(focus)?.label} · used by ${USED_BY[focus] ?? 'Explore only'}` : ''}</span>
              {focus && <button className="btn-ghost mono" onClick={() => setFocus(null)}>ALL SERIES</button>}
              {(['critical', 'warning', 'info'] as const).map((sev) => (
                <label key={sev} className={`ig-sev-toggle sev-${sev}`}>
                  <input type="checkbox" checked={show[sev]} onChange={(e) => setShow({ ...show, [sev]: e.target.checked })} /> {sev} ({report.totals[sev]})
                </label>
              ))}
            </div>

            {candidates.filter((c) => !focus || BREAK_SERIES[c.metric] === focus).map((c) => (
              <div key={`${c.metric}|${c.b.date}`} className="card ig-issue sev-border-warning">
                <div className="ig-issue-head">
                  <span className="ig-pill sev-warning mono">review</span>
                  <span className="ig-kind mono">series break</span>
                  <b>{seriesById(BREAK_SERIES[c.metric])?.label ?? c.metric}: level or noise changed on {c.b.date}</b>
                </div>
                <div className="ig-issue-detail mono">{describeBreak(c.b)} · {c.b.kind} · score {c.b.score.toFixed(1)}</div>
                <div className="ig-actions">
                  <span className="ig-question">Did your device or data source change around then? Nothing restarts a baseline until you confirm.</span>
                  <button className="btn-ghost mono" disabled={running} onClick={() => void decide(c.metric, c.b.date, 'source_change')}>YES, SOURCE CHANGED</button>
                  <button className="btn-ghost mono" disabled={running} onClick={() => void decide(c.metric, c.b.date, 'real_change')}>NO, REAL CHANGE</button>
                  <button className="btn-ghost mono" disabled={running} onClick={() => void decide(c.metric, c.b.date, 'ignore')}>IGNORE</button>
                  {BREAK_SERIES[c.metric] && <a className="ig-link mono" href={hrefFor({ kind: 'period', series: BREAK_SERIES[c.metric], start: shiftDays(c.b.date, -60), end: shiftDays(c.b.date, 60) }) ?? '#'}>SEE IT IN EXPLORE →</a>}
                </div>
              </div>
            ))}

            {issues.length === 0 && <div className="state-empty mono">NO ISSUES AT THE SELECTED SEVERITIES</div>}
            {issues.map((i) => <IssueCard key={i.key} issue={i} />)}
          </section>
        </>
      )}
    </div>
  )
}

function shiftDays(date: string, n: number): string {
  return new Date(Date.parse(`${date}T00:00:00Z`) + n * 86_400_000).toISOString().slice(0, 10)
}

const KIND = {
  stale: 'stale', missing: 'missing', invalid: 'invalid', conflict: 'conflict', duplicate: 'duplicate',
  suspect_zero: 'suspect 0', source_switch: 'source switch',
} as const

function refLabel(r: DataRef): string {
  if (r.kind === 'record') return `#${r.id}`
  if (r.kind === 'period') return `${r.start} → ${r.end}`
  if (r.kind === 'day') return r.date
  return r.kind
}

function IssueCard({ issue: i }: { issue: Issue }) {
  const s = seriesById(i.seriesId)
  const records = i.refs.filter((r) => r.kind === 'record')
  const others = i.refs.filter((r) => r.kind !== 'record')
  const dayLink = s && hrefFor({ kind: 'day', series: s.id, date: i.firstDate })
  return (
    <div className={`card ig-issue sev-border-${i.severity}`}>
      <div className="ig-issue-head">
        <span className={`ig-pill sev-${i.severity} mono`}>{i.severity}</span>
        <span className="ig-kind mono">{KIND[i.kind]}</span>
        <b>{i.title}</b>
        <span className="ig-dates mono">{i.firstDate === i.lastDate ? i.firstDate : `${i.firstDate} → ${i.lastDate}`} · {i.days} d</span>
      </div>
      <div className="ig-issue-detail">{i.detail}</div>
      <div className="ig-refs mono">
        {others.map((r, k) => { const h = hrefFor(r); return h ? <a key={k} className="ig-link" href={h}>EXPLORE {refLabel(r)} →</a> : null })}
        {records.slice(0, 8).map((r, k) => { const h = hrefFor(r); return h ? <a key={`r${k}`} className="ig-link" href={h}>{refLabel(r)}</a> : null })}
        {records.length > 8 && <span className="ig-muted">+{records.length - 8} more</span>}
        {records.length > 0 && dayLink && <a className="ig-link" href={dayLink}>DATA ON {i.firstDate} →</a>}
      </div>
    </div>
  )
}

export default Integrity
