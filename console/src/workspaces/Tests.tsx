// LAB › TESTS (web transition plan, phase 4): every lab draw with its results, newest first; a marker opens its history
// across draws. Read-only: draws are entered on the phone (Lab › TESTS). The judgement against your own variation (RCV)
// is on the Dashboard's Me › Bloodwork, as the phone works it out.
import { useMemo, useState } from 'react'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { dashboardHref } from '../lib/surfaces'
import './PlanBlock.css'
import './Pages.css'

interface Draw { id: number; draw_date: string; draw_time: string | null; lab_name: string | null; fasted: boolean | null; hours_since_training: number | null }
interface Result { id: number; draw_id: number; marker_name: string; value: number | null; value_text: string | null; unit: string | null; ref_low: number | null; ref_high: number | null; flag: string | null }

const range = (r: Result) => r.ref_low != null && r.ref_high != null ? `${r.ref_low}–${r.ref_high}` : r.ref_low != null ? `≥ ${r.ref_low}` : r.ref_high != null ? `≤ ${r.ref_high}` : ''
const outside = (r: Result) => r.value != null && ((r.ref_low != null && r.value < r.ref_low) || (r.ref_high != null && r.value > r.ref_high))

export default function Tests() {
  const [draws, setDraws] = useState<Draw[]>([])
  const [results, setResults] = useState<Result[]>([])
  const [marker, setMarker] = useState<string | null>(null)
  const [q, setQ] = useState('')
  const st = useLoadEffect(async (cur) => {
    const [d, r] = await Promise.all([
      supabase.from('lab_draws').select('id,draw_date,draw_time,lab_name,fasted,hours_since_training').order('draw_date', { ascending: false }),
      supabase.from('lab_results').select('id,draw_id,marker_name,value,value_text,unit,ref_low,ref_high,flag').order('marker_name').limit(5000),
    ])
    if (d.error) throw new Error(`lab_draws: ${d.error.message}`)
    if (r.error) throw new Error(`lab_results: ${r.error.message}`)
    if (cur()) { setDraws((d.data ?? []) as Draw[]); setResults((r.data ?? []) as Result[]) }
  }, [])
  const byDraw = useMemo(() => {
    const m = new Map<number, Result[]>()
    for (const r of results) m.set(r.draw_id, [...(m.get(r.draw_id) ?? []), r])
    return m
  }, [results])
  const dateOf = useMemo(() => new Map(draws.map((d) => [d.id, d.draw_date])), [draws])
  const history = marker ? results.filter((r) => r.marker_name === marker).sort((a, b) => (dateOf.get(b.draw_id) ?? '').localeCompare(dateOf.get(a.draw_id) ?? '')) : []
  const match = (r: Result) => !q || r.marker_name.toLowerCase().includes(q.toLowerCase())

  return (
    <div className="pg">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">TESTS</h2>
          <p className="pg-sub">Every blood draw and its results. Tap a marker for its history across draws. How a change compares with your own variation is on the <a href={dashboardHref('#/me?tab=BLOODWORK')}>Dashboard's Bloodwork</a>.</p>
        </div>
        <input className="pb-input" placeholder="Find a marker" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Find a marker" />
      </header>
      {st.error && <p className="pb-warn">Could not load: {st.error}</p>}
      {st.loading && draws.length === 0 && <p className="state-loading">LOADING…</p>}
      {!st.loading && draws.length === 0 && !st.error && <p className="state-empty">NO DRAWS YET.</p>}

      {marker && (
        <section className="pg-card wide">
          <h3 className="pg-card-title mono"><span>{marker.toUpperCase()} · {history.length} DRAW{history.length === 1 ? '' : 'S'}</span><button className="pb-chip" onClick={() => setMarker(null)}>CLOSE</button></h3>
          <ul className="pg-rows">{history.map((r) => (
            <li key={r.id} className="pg-row"><span className="pg-row-title mono">{dateOf.get(r.draw_id)}</span>
              <span className={`pg-row-value ${outside(r) ? 'pg-warn' : ''}`}>{r.value ?? r.value_text ?? '–'} {r.unit ?? ''}</span>
              {range(r) && <span className="pg-row-sub">reference {range(r)}{r.flag ? ` · ${r.flag}` : ''}</span>}</li>
          ))}</ul>
        </section>
      )}

      <div className="pg-grid">
        {draws.map((d) => {
          const rows = (byDraw.get(d.id) ?? []).filter(match)
          if (q && rows.length === 0) return null
          return (
            <section key={d.id} className="pg-card">
              <h3 className="pg-card-title mono"><span>{d.draw_date}{d.draw_time ? ` · ${d.draw_time.slice(0, 5)}` : ''}</span><span>{d.lab_name ?? ''}</span></h3>
              <p className="pg-note">{[d.fasted != null && (d.fasted ? 'fasted' : 'not fasted'), d.hours_since_training != null && `${d.hours_since_training} h after training`, `${(byDraw.get(d.id) ?? []).length} results`].filter(Boolean).join(' · ')}</p>
              <ul className="pg-rows">{rows.map((r) => (
                <li key={r.id} className="pg-row">
                  <button className="pg-row-title pg-link" onClick={() => setMarker(r.marker_name)}>{r.marker_name}</button>
                  <span className={`pg-row-value ${outside(r) ? 'pg-warn' : ''}`}>{r.value ?? r.value_text ?? '–'} {r.unit ?? ''}</span>
                  {(range(r) || r.flag) && <span className="pg-row-sub">{range(r) && `reference ${range(r)}`}{r.flag ? ` · ${r.flag}` : ''}</span>}
                </li>
              ))}</ul>
            </section>
          )
        })}
      </div>
    </div>
  )
}
