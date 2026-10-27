// Data Explorer workspace — virtualized table over canonical Supabase tables.
// Inspector "VIEW RAW RECORDS →" deep-links here via ?table=X&date=Y; a record reference (lib/links.ts) via ?table=X&id=N.
// ponytail: native HTML table + useVirtualizer; skipped TanStack Table v9 (breaking API change).

import { useEffect, useState, useRef, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useVirtualizer } from '@tanstack/react-virtual'
import { supabase } from '../lib/supabase'
import { dayBounds, LIVE_COLUMNS } from '../lib/schema'
import './DataExplorer.css'

// `runs` was replaced by exercise_sessions; meals and sessions are timestamped, lab draws use draw_date (lib/schema.ts).
const KNOWN_TABLES = [
  'exercise_sessions', 'sleep_daily', 'wearable_daily', 'wellbeing_daily',
  'meals', 'body_metrics', 'hydration_daily', 'injuries', 'illnesses', 'rest_days', 'lab_draws',
]

const DATE_COLUMN: Record<string, string> = {
  exercise_sessions: 'start_time', sleep_daily: 'date', wearable_daily: 'date',
  wellbeing_daily: 'date', meals: 'logged_at', body_metrics: 'date', hydration_daily: 'date',
  injuries: 'start_date', illnesses: 'start_date', rest_days: 'date',
  lab_draws: 'draw_date',
}

const TIMESTAMP_COLUMNS = new Set(['start_time', 'logged_at'])

type Row = Record<string, unknown>

// Large JSON columns (about 50 kB per day for hr_timeseries) are not worth shipping to a 500-row table view that truncates
// them in the cell anyway. For a table whose columns are known, list them minus the heavy ones; otherwise select everything.
const HEAVY_COLUMNS = new Set(['hr_timeseries', 'raw_body', 'details'])
function explorerColumns(table: string): string {
  const known = LIVE_COLUMNS[table]
  return known ? known.filter(c => !HEAVY_COLUMNS.has(c)).join(', ') : '*'
}

async function fetchRows(table: string, dateFilter: string | null, idFilter: string | null): Promise<Row[]> {
  let q = supabase.from(table).select(explorerColumns(table)).order(DATE_COLUMN[table] ?? 'id', { ascending: false }).limit(500)
  if (idFilter) q = q.eq('id', idFilter)
  if (dateFilter) {
    const col = DATE_COLUMN[table] ?? 'date'
    if (TIMESTAMP_COLUMNS.has(col)) {
      const { from, to } = dayBounds(dateFilter, dateFilter)
      q = q.gte(col, from).lte(col, to)
    } else {
      q = q.eq(col, dateFilter)
    }
  }
  const { data, error } = await q
  if (error) throw error
  return (data ?? []) as unknown as Row[]
}

export function DataExplorer() {
  const [params, setParams] = useSearchParams()

  const [selectedTable, setSelectedTable] = useState<string>(params.get('table') ?? KNOWN_TABLES[0])
  const [dateFilter, setDateFilter]       = useState<string>(params.get('date') ?? '')
  const [idFilter, setIdFilter]           = useState<string>(params.get('id') ?? '')
  const [rows, setRows]                   = useState<Row[]>([])
  const [columns, setColumns]             = useState<string[]>([])
  const [loading, setLoading]             = useState(false)
  const [error, setError]                 = useState<string | null>(null)
  const [sortKey, setSortKey]             = useState<string | null>(null)
  const [sortDir, setSortDir]             = useState<'asc' | 'desc'>('asc')

  useEffect(() => {
    setLoading(true)
    setError(null)
    fetchRows(selectedTable, dateFilter || null, idFilter || null)
      .then(r => {
        setRows(r)
        setColumns(r.length ? Object.keys(r[0]) : [])
        setLoading(false)
      })
      .catch(e => { setError(String((e as Error)?.message ?? e)); setLoading(false) })
  }, [selectedTable, dateFilter, idFilter])

  const sortedRows = useMemo(() => {
    if (!sortKey) return rows
    return [...rows].sort((a, b) => {
      const av = a[sortKey] ?? ''
      const bv = b[sortKey] ?? ''
      const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true })
      return sortDir === 'asc' ? cmp : -cmp
    })
  }, [rows, sortKey, sortDir])

  const parentRef = useRef<HTMLDivElement>(null)
  const virtualizer = useVirtualizer({
    count: sortedRows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 36,
    overscan: 20,
  })
  const virtualItems = virtualizer.getVirtualItems()
  const totalSize   = virtualizer.getTotalSize()

  function toggleSort(col: string) {
    if (sortKey === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortKey(col); setSortDir('asc') }
  }

  function applyFilters() {
    const next = new URLSearchParams()
    if (selectedTable) next.set('table', selectedTable)
    if (dateFilter)    next.set('date',  dateFilter)
    if (idFilter)      next.set('id',    idFilter)
    setParams(next)
  }

  return (
    <div className="de">
      <div className="de-toolbar">
        <h2 className="de-title">Data Explorer</h2>
        <div className="de-controls">
          <select className="de-select mono" value={selectedTable} onChange={e => setSelectedTable(e.target.value)}>
            {(KNOWN_TABLES.includes(selectedTable) ? KNOWN_TABLES : [selectedTable, ...KNOWN_TABLES]).map(t => <option key={t} value={t}>{t}</option>)}
          </select>
          <input
            className="de-input mono"
            type="date"
            value={dateFilter}
            onChange={e => setDateFilter(e.target.value)}
          />
          <button className="de-btn mono" onClick={applyFilters}>APPLY</button>
          {dateFilter && <button className="de-btn-ghost mono" onClick={() => setDateFilter('')}>CLEAR DATE</button>}
          {idFilter && <button className="de-btn-ghost mono" onClick={() => setIdFilter('')}>RECORD {idFilter} ✕</button>}
        </div>
        <div className="de-meta mono">
          {!loading && !error && `${rows.length} rows${rows.length === 500 ? ' (capped)' : ''}`}
        </div>
      </div>

      {loading && <div className="state-loading mono">LOADING…</div>}
      {error   && <div className="state-error mono">ERROR: {error}</div>}
      {!loading && !error && rows.length === 0 && <div className="state-empty mono">NO ROWS</div>}

      {!loading && !error && rows.length > 0 && (
        <div className="de-table-wrap" ref={parentRef}>
          <table className="de-table">
            <thead>
              <tr>
                {columns.map(col => (
                  <th key={col} className="de-th mono" onClick={() => toggleSort(col)}>
                    {col}
                    {sortKey === col ? (sortDir === 'asc' ? ' ▲' : ' ▼') : ''}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody style={{ height: totalSize, position: 'relative', display: 'block' }}>
              {virtualItems.map(vr => {
                const row = sortedRows[vr.index]
                return (
                  <tr
                    key={vr.index}
                    className="de-tr"
                    style={{ position: 'absolute', top: vr.start, width: '100%', display: 'flex' }}
                  >
                    {columns.map(col => {
                      const v = row[col]
                      const s = v === null || v === undefined ? null : String(v)
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
        </div>
      )}
    </div>
  )
}

export default DataExplorer
