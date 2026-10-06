// Data Explorer workspace — virtualized table over canonical Supabase tables.
// Inspector "VIEW RAW RECORDS →" deep-links here via ?table=X&date=Y.
// ponytail: native HTML table + useVirtualizer; skipped TanStack Table v9 (breaking API change).

import { useEffect, useState, useRef, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useVirtualizer } from '@tanstack/react-virtual'
import { supabase } from '../lib/supabase'
import './DataExplorer.css'

const KNOWN_TABLES = [
  'runs', 'sleep_daily', 'wearable_daily', 'wellbeing_daily',
  'meals', 'body_metrics', 'injuries', 'illnesses', 'rest_days', 'lab_draws',
]

const DATE_COLUMN: Record<string, string> = {
  runs: 'date', sleep_daily: 'date', wearable_daily: 'date',
  wellbeing_daily: 'date', meals: 'date', body_metrics: 'date',
  injuries: 'start_date', illnesses: 'start_date', rest_days: 'date',
  lab_draws: 'date',
}

type Row = Record<string, unknown>

async function fetchRows(table: string, dateFilter: string | null): Promise<Row[]> {
  let q = supabase.from(table).select('*').order(DATE_COLUMN[table] ?? 'id', { ascending: false }).limit(500)
  if (dateFilter) q = q.eq(DATE_COLUMN[table] ?? 'date', dateFilter)
  const { data, error } = await q
  if (error) throw error
  return (data ?? []) as Row[]
}

export function DataExplorer() {
  const [params, setParams] = useSearchParams()

  const [selectedTable, setSelectedTable] = useState<string>(params.get('table') ?? KNOWN_TABLES[0])
  const [dateFilter, setDateFilter]       = useState<string>(params.get('date') ?? '')
  const [rows, setRows]                   = useState<Row[]>([])
  const [columns, setColumns]             = useState<string[]>([])
  const [loading, setLoading]             = useState(false)
  const [error, setError]                 = useState<string | null>(null)
  const [sortKey, setSortKey]             = useState<string | null>(null)
  const [sortDir, setSortDir]             = useState<'asc' | 'desc'>('asc')

  useEffect(() => {
    setLoading(true)
    setError(null)
    fetchRows(selectedTable, dateFilter || null)
      .then(r => {
        setRows(r)
        setColumns(r.length ? Object.keys(r[0]) : [])
        setLoading(false)
      })
      .catch(e => { setError(String((e as Error)?.message ?? e)); setLoading(false) })
  }, [selectedTable, dateFilter])

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
    setParams(next)
  }

  return (
    <div className="de">
      <div className="de-toolbar">
        <h2 className="de-title">Data Explorer</h2>
        <div className="de-controls">
          <select className="de-select mono" value={selectedTable} onChange={e => setSelectedTable(e.target.value)}>
            {KNOWN_TABLES.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
          <input
            className="de-input mono"
            type="date"
            value={dateFilter}
            onChange={e => setDateFilter(e.target.value)}
          />
          <button className="de-btn mono" onClick={applyFilters}>APPLY</button>
          {dateFilter && <button className="de-btn-ghost mono" onClick={() => setDateFilter('')}>CLEAR DATE</button>}
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
