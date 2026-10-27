// Data workspace (rebuilt in the analysis hub's phase B): raw rows of the canonical tables.
// - Filters live in the URL (table, from, to or date, id, source, a column that is or is not empty), so issues, evidence
//   and the Inspector link straight to a filtered view (lib/links.ts: #/data?table=meals&id=42).
// - The count of matching rows is exact; rows load newest first in pages of 200 with keyset paging (time column, id), so
//   nothing is cut silently and LOAD MORE never repeats or skips a row.
// - Large JSON columns stay out of the table; selecting a row loads the whole record, those columns included.
// Times are shown as stored: a date filter on a timestamp table matches the stored day (local wall time for sessions and
// meals since 2026-09-17; seriesContract.ts has the per-series rules).
// ponytail: native HTML table + useVirtualizer; skipped TanStack Table v9 (breaking API change).

import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useVirtualizer } from '@tanstack/react-virtual'
import { supabase } from '../lib/supabase'
import { dayBounds, LIVE_COLUMNS, SERIES } from '../lib/schema'
import { HEAVY_COLUMNS } from '../lib/seriesQuery'
import { keysetFilter, type Cursor } from '../lib/pager'
import { hrefFor } from '../lib/links'
import './DataExplorer.css'

// Time column of each table; a table without one is ordered by id. Every table listed here has an id primary key, which
// keyset paging needs (daily_field_provenance and wearable_hr_daily are keyed by user and date, so they are not listed).
const TIME_COLUMN: Record<string, string> = {
  exercise_sessions: 'start_time', sleep_daily: 'date', wearable_daily: 'date', wellbeing_daily: 'date',
  meals: 'logged_at', body_metrics: 'date', hydration_daily: 'date', injuries: 'start_date', illnesses: 'start_date',
  rest_days: 'date', lab_draws: 'draw_date', evening_checkins: 'date', supplement_log: 'taken_at',
}
const DATE_ONLY = new Set(['date', 'start_date', 'draw_date'])
const TABLES = [...new Set([...SERIES.map(s => s.table), ...Object.keys(TIME_COLUMN)])].sort()
const PAGE = 200

type Row = Record<string, unknown>

interface Filters {
  table: string
  from: string
  to: string
  id: string
  source: string
  col: string
  empty: '' | 'null' | 'notnull'
}

function readFilters(p: URLSearchParams): Filters {
  const date = p.get('date') ?? ''
  return {
    table: p.get('table') ?? 'wearable_daily',
    from: p.get('from') ?? date,
    to: p.get('to') ?? date,
    id: p.get('id') ?? '',
    source: p.get('source') ?? '',
    col: p.get('col') ?? '',
    empty: (p.get('empty') as Filters['empty']) ?? '',
  }
}

function listColumns(table: string): string {
  const known = LIVE_COLUMNS[table]
  return known ? known.filter(c => !HEAVY_COLUMNS.has(c)).join(', ') : '*'
}

// supabase-js builders are thenables with filter methods; typed loosely so one helper serves count and page queries.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function applyFilters(q: any, f: Filters): any {
  const tcol = TIME_COLUMN[f.table]
  if (tcol && (f.from || f.to)) {
    if (DATE_ONLY.has(tcol)) {
      if (f.from) q = q.gte(tcol, f.from)
      if (f.to) q = q.lte(tcol, f.to)
    } else {
      const b = dayBounds(f.from || '0001-01-01', f.to || '9999-12-31')
      if (f.from) q = q.gte(tcol, b.from)
      if (f.to) q = q.lte(tcol, b.to)
    }
  }
  if (f.id) q = q.eq('id', f.id)
  if (f.source) q = q.eq('source', f.source)
  if (f.col && f.empty === 'null') q = q.is(f.col, null)
  if (f.col && f.empty === 'notnull') q = q.not(f.col, 'is', null)
  return q
}

export function DataExplorer() {
  const [params, setParams] = useSearchParams()
  const applied = useMemo(() => readFilters(params), [params])
  const [draft, setDraft] = useState<Filters>(applied)
  useEffect(() => setDraft(applied), [applied])

  const [rows, setRows] = useState<Row[]>([])
  const [total, setTotal] = useState<number | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const [selected, setSelected] = useState<Row | null>(null)
  const [full, setFull] = useState<Row | null>(null)

  const tcol = TIME_COLUMN[applied.table]
  const hasSource = (LIVE_COLUMNS[applied.table] ?? []).includes('source')

  async function loadPage(after: Cursor | null, signal: AbortSignal): Promise<Row[]> {
    let q = applyFilters(supabase.from(applied.table).select(listColumns(applied.table)), applied)
    if (tcol) {
      if (after) q = q.or(keysetFilter(tcol, after, 'desc'))
      q = q.order(tcol, { ascending: false }).order('id', { ascending: false })
    } else {
      if (after) q = q.lt('id', after.id)
      q = q.order('id', { ascending: false })
    }
    const { data, error: e } = await q.limit(PAGE).abortSignal(signal)
    if (e) throw new Error(e.message)
    return (data ?? []) as Row[]
  }

  // First page and the exact count whenever the applied filters change.
  useEffect(() => {
    const ac = new AbortController()
    setLoading(true)
    setError(null)
    setSelected(null)
    const count = applyFilters(supabase.from(applied.table).select('id', { count: 'exact', head: true }), applied).abortSignal(ac.signal)
    Promise.all([loadPage(null, ac.signal), count])
      .then(([page, c]) => {
        if (ac.signal.aborted) return
        setRows(page)
        setDone(page.length < PAGE)
        setTotal((c as { count: number | null }).count ?? null)
        setLoading(false)
      })
      .catch((e: unknown) => {
        if (ac.signal.aborted) return
        setError(e instanceof Error ? e.message : String(e))
        setLoading(false)
      })
    return () => ac.abort()
  }, [applied]) // eslint-disable-line react-hooks/exhaustive-deps

  const loadMore = () => {
    const last = rows[rows.length - 1]
    if (!last) return
    setLoading(true)
    const after: Cursor = { time: tcol ? String(last[tcol]) : '', id: last.id as string | number }
    loadPage(after, new AbortController().signal)
      .then(page => { setRows(r => [...r, ...page]); setDone(page.length < PAGE); setLoading(false) })
      .catch((e: unknown) => { setError(e instanceof Error ? e.message : String(e)); setLoading(false) })
  }

  // The whole record (heavy columns included) for the selected row.
  useEffect(() => {
    setFull(null)
    if (!selected || selected.id === undefined) return
    const ac = new AbortController()
    supabase.from(applied.table).select('*').eq('id', selected.id as string).abortSignal(ac.signal).maybeSingle()
      .then(({ data }) => { if (!ac.signal.aborted) setFull((data as Row | null) ?? null) })
    return () => ac.abort()
  }, [selected, applied.table])

  const apply = (f: Filters) => {
    const next = new URLSearchParams()
    next.set('table', f.table)
    if (f.from) next.set('from', f.from)
    if (f.to) next.set('to', f.to)
    if (f.id) next.set('id', f.id)
    if (f.source) next.set('source', f.source)
    if (f.col && f.empty) { next.set('col', f.col); next.set('empty', f.empty) }
    setParams(next)
  }

  const columns = useMemo(() => (rows.length ? Object.keys(rows[0]) : []), [rows])
  const parentRef = useRef<HTMLDivElement>(null)
  const virtualizer = useVirtualizer({ count: rows.length, getScrollElement: () => parentRef.current, estimateSize: () => 36, overscan: 20 })
  const allColumns = LIVE_COLUMNS[draft.table] ?? []

  return (
    <div className="de">
      <div className="de-toolbar">
        <h2 className="de-title">Data</h2>
        <form className="de-controls" onSubmit={e => { e.preventDefault(); apply(draft) }}>
          <select className="de-select mono" value={draft.table} onChange={e => apply({ ...readFilters(new URLSearchParams()), table: e.target.value })} aria-label="Table">
            {(TABLES.includes(draft.table) ? TABLES : [draft.table, ...TABLES]).map(t => <option key={t} value={t}>{t}</option>)}
          </select>
          {TIME_COLUMN[draft.table] && <>
            <input className="de-input mono" type="date" value={draft.from} onChange={e => setDraft({ ...draft, from: e.target.value })} aria-label="From" />
            <input className="de-input mono" type="date" value={draft.to} onChange={e => setDraft({ ...draft, to: e.target.value })} aria-label="To" />
          </>}
          <input className="de-input mono de-narrow" placeholder="id" value={draft.id} onChange={e => setDraft({ ...draft, id: e.target.value.trim() })} aria-label="Record id" />
          {(LIVE_COLUMNS[draft.table] ?? []).includes('source') && (
            <input className="de-input mono de-narrow" placeholder="source" value={draft.source} onChange={e => setDraft({ ...draft, source: e.target.value.trim() })} aria-label="Source" />
          )}
          {allColumns.length > 0 && <>
            <select className="de-select mono" value={draft.col} onChange={e => setDraft({ ...draft, col: e.target.value })} aria-label="Column">
              <option value="">column…</option>
              {allColumns.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
            <select className="de-select mono" value={draft.empty} onChange={e => setDraft({ ...draft, empty: e.target.value as Filters['empty'] })} aria-label="Empty or not">
              <option value="">any</option>
              <option value="null">is empty</option>
              <option value="notnull">has a value</option>
            </select>
          </>}
          <button className="de-btn mono" type="submit">APPLY</button>
          <button className="de-btn-ghost mono" type="button" onClick={() => apply({ ...readFilters(new URLSearchParams()), table: draft.table })}>CLEAR</button>
        </form>
        <div className="de-meta mono">
          {!error && total !== null && `${rows.length.toLocaleString()} of ${total.toLocaleString()} matching rows`}
          {tcol ? ` · newest first by ${tcol}` : ' · by id'}{hasSource ? '' : ' · no source column'}
        </div>
      </div>

      {error && <div className="state-error mono">ERROR: {error}</div>}
      {!loading && !error && rows.length === 0 && <div className="state-empty mono">NO ROWS</div>}

      <div className="de-body">
        {rows.length > 0 && (
          <div className="de-table-wrap" ref={parentRef}>
            <table className="de-table">
              <thead>
                <tr>{columns.map(col => <th key={col} className="de-th mono">{col}</th>)}</tr>
              </thead>
              <tbody style={{ height: virtualizer.getTotalSize(), position: 'relative', display: 'block' }}>
                {virtualizer.getVirtualItems().map(vr => {
                  const row = rows[vr.index]
                  return (
                    <tr key={vr.index} className={`de-tr ${selected === row ? 'de-row-on' : ''}`} onClick={() => setSelected(row)}
                      style={{ position: 'absolute', top: vr.start, width: '100%', display: 'flex' }}>
                      {columns.map(col => {
                        const v = row[col]
                        const s = v === null || v === undefined ? null : typeof v === 'object' ? JSON.stringify(v) : String(v)
                        return (
                          <td key={col} className="de-td mono">
                            {s === null ? <span className="de-null">—</span> : s.length > 80 ? <span title={s}>{s.slice(0, 80)}…</span> : s}
                          </td>
                        )
                      })}
                    </tr>
                  )
                })}
              </tbody>
            </table>
            {!done && <button className="de-btn mono de-more" disabled={loading} onClick={loadMore}>{loading ? 'LOADING…' : `LOAD ${PAGE} MORE`}</button>}
          </div>
        )}

        {selected && (
          <aside className="de-detail">
            <div className="de-detail-head mono">
              <span>{applied.table} #{String(selected.id)}</span>
              <a className="de-link" href={hrefFor({ kind: 'record', table: applied.table, id: selected.id as string | number }) ?? '#'}>LINK</a>
              <button className="de-btn-ghost mono" onClick={() => setSelected(null)}>✕</button>
            </div>
            <pre className="de-json mono">{JSON.stringify(full ?? selected, null, 2)}</pre>
            {!full && <div className="de-meta mono">loading the full record…</div>}
          </aside>
        )}
      </div>
    </div>
  )
}

export default DataExplorer
