// Analysis review phase G (P12): the ledger view. Every nightly verdict of every question is kept in evidence_ledger
// (phase E, append-only); this view shows each question's verdict over time and every night a verdict changed, with the
// estimate, the engine version and how much information the verdict had. A change of verdict is visible with its date,
// never silently overwritten.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { supabase } from '../lib/supabase'
import { warnOnError } from '../lib/schema'
import { LoadBanner } from '../components/shared/LoadBanner'
import { HYPOTHESES } from '../../../supabase/functions/_shared/analysis-core/hypotheses.ts'
import './Confirmatory.css'

interface LedgerRow {
  question_id: string
  source: string
  as_of: string
  engine_version: string
  verdict: string
  estimate: number | null
  lo: number | null
  hi: number | null
  unit: string | null
  information: number | null
}

const WORD: Record<string, string> = {
  clear: 'CLEAR', negligible: 'NEGLIGIBLE', unclear: 'UNCLEAR', not_yet: 'NOT YET',
  // repeatable interventions (source 'event'): the ladder decides the word, not significance
  insufficient: 'TOO FEW EVENTS', possible: 'POSSIBLE', observed: 'OBSERVED',
}
const LABEL = new Map(HYPOTHESES.map((h) => [h.id, h.label]))
const labelOf = (id: string) => LABEL.get(id) ?? (id.startsWith('reg_') ? `Registered question ${id.slice(4)}` : id.startsWith('event_') ? `Repeatable intervention ${id.slice(6, 14)}` : id)
const fmt = (v: number | null, unit: string | null) =>
  v === null ? '–' : `${v >= 0 ? '+' : ''}${v.toFixed(unit === '%' ? 1 : 2)}${unit === '%' ? '%' : unit ? ` ${unit}` : ''}`

interface Run { verdict: string; from: string; to: string; nights: number }

function runsOf(rows: LedgerRow[]): Run[] {
  const out: Run[] = []
  for (const r of rows) {
    const last = out[out.length - 1]
    if (last && last.verdict === r.verdict) {
      last.to = r.as_of
      last.nights++
    } else {
      out.push({ verdict: r.verdict, from: r.as_of, to: r.as_of, nights: 1 })
    }
  }
  return out
}

export function Ledger() {
  const [rows, setRows] = useState<LedgerRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [question, setQuestion] = useState<string>('all')

  const load = useCallback(async () => {
    try {
      const all: LedgerRow[] = []
      // Pages of 1,000 (PostgREST's cap), oldest first, up to a few years of nights.
      for (let page = 0; page < 20; page++) {
        const { data, error: e } = await supabase
          .from('evidence_ledger')
          .select('question_id, source, as_of, engine_version, verdict, estimate, lo, hi, unit, information')
          .order('as_of', { ascending: true })
          .order('question_id', { ascending: true })
          .range(page * 1000, page * 1000 + 999)
        warnOnError('evidence ledger', e)
        if (e) throw new Error(e.message)
        all.push(...((data ?? []) as LedgerRow[]))
        if (!data || data.length < 1000) break
      }
      setRows(all)
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [])
  useEffect(() => { void load() }, [load])

  const byQuestion = useMemo(() => {
    const m = new Map<string, LedgerRow[]>()
    for (const r of rows) {
      const list = m.get(r.question_id)
      if (list) list.push(r)
      else m.set(r.question_id, [r])
    }
    return m
  }, [rows])

  // Every night a question's verdict differed from the night before, newest first.
  const changes = useMemo(() => {
    const out: { row: LedgerRow; from: string }[] = []
    byQuestion.forEach((list) => {
      for (let i = 1; i < list.length; i++) if (list[i].verdict !== list[i - 1].verdict) out.push({ row: list[i], from: list[i - 1].verdict })
    })
    return out.filter((c) => question === 'all' || c.row.question_id === question).sort((a, b) => b.row.as_of.localeCompare(a.row.as_of))
  }, [byQuestion, question])

  if (loading) return <div className="state-loading mono">LOADING THE LEDGER…</div>
  if (error) return <LoadBanner error={error} onRetry={() => { setLoading(true); void load() }} />
  if (rows.length === 0) return <div className="state-empty mono">NO VERDICTS IN THE LEDGER YET · THE NIGHTLY ANALYSIS APPENDS THEM</div>

  const questions = [...byQuestion.keys()].sort((a, b) => labelOf(a).localeCompare(labelOf(b)))
  return (
    <div className="conf">
      <div className="conf-head">
        <p className="conf-note">
          Every nightly verdict of every question, kept as it was published (append-only). A verdict that changes is shown with the
          night it changed, the estimate behind it and the engine version, so nothing is quietly rewritten.
        </p>
      </div>
      <div className="conf-stats mono">
        <label>QUESTION{' '}
          <select value={question} onChange={(e) => setQuestion(e.target.value)}>
            <option value="all">all questions</option>
            {questions.map((q) => <option key={q} value={q}>{labelOf(q)}</option>)}
          </select>
        </label>
      </div>
      <div className="conf-grid">
        {questions.filter((q) => question === 'all' || q === question).map((q) => {
          const list = byQuestion.get(q) ?? []
          const last = list[list.length - 1]
          const runs = runsOf(list).slice(-4).reverse()
          return (
            <div key={q} className="card conf-card">
              <div className="conf-card-head">
                <span className="conf-title">{labelOf(q)}</span>
                <span className="conf-state mono">{WORD[last.verdict] ?? last.verdict.toUpperCase()}</span>
              </div>
              <div className="conf-stats mono">
                <span>{fmt(last.estimate, last.unit)} ({fmt(last.lo, last.unit)} to {fmt(last.hi, last.unit)})</span>
                {last.information !== null && <span>information {Math.round(last.information * 100)}%</span>}
                <span>{list.length} nights · engine {last.engine_version}</span>
              </div>
              {runs.map((r, i) => (
                <p key={i} className="conf-footnote mono">{WORD[r.verdict] ?? r.verdict} {r.from === r.to ? r.from : `${r.from} → ${r.to}`} ({r.nights} night{r.nights === 1 ? '' : 's'})</p>
              ))}
            </div>
          )
        })}
      </div>
      <h3 className="iv-section-title mono">CHANGES OF VERDICT</h3>
      {changes.length === 0 && <div className="state-empty mono">NO VERDICT HAS CHANGED YET</div>}
      {changes.slice(0, 200).map((c, i) => (
        <p key={i} className="conf-footnote mono">
          {c.row.as_of} · {labelOf(c.row.question_id)} · {WORD[c.from] ?? c.from} → {WORD[c.row.verdict] ?? c.row.verdict} · {fmt(c.row.estimate, c.row.unit)} ({fmt(c.row.lo, c.row.unit)} to {fmt(c.row.hi, c.row.unit)}) · engine {c.row.engine_version}
        </p>
      ))}
    </div>
  )
}

export default Ledger
