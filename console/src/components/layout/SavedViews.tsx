// Header control for named views (analysis hub phase E; IA.md §3.4 "Save workspace"): SAVE keeps the current workspace
// route with its filters and the date range in saved_views; opening a view restores both. Helpers in lib/savedViews.ts.
import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { supabase } from '../../lib/supabase'
import { useRange, useSetRange } from '../../lib/analysisStore'
import { openView, viewRow, type SavedView } from '../../lib/savedViews'
import './SavedViews.css'

export function SavedViews() {
  const location = useLocation()
  const navigate = useNavigate()
  const range = useRange()
  const setRange = useSetRange()
  const [open, setOpen] = useState(false)
  const [views, setViews] = useState<SavedView[] | null>(null)
  const [name, setName] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const box = useRef<HTMLDivElement>(null)

  const load = () => {
    supabase.from('saved_views').select('id, name, path, range').order('name')
      .then(({ data, error }) => {
        if (error) { setMessage(error.message); setViews([]); return }
        setViews((data ?? []) as SavedView[])
      })
  }

  useEffect(() => { if (open && views === null) load() }, [open]) // eslint-disable-line react-hooks/exhaustive-deps

  // A new route closes the panel (the header does not remount when the workspace changes).
  useEffect(() => { setOpen(false) }, [location.pathname, location.search])

  // Close on a click outside.
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [open])

  const save = async () => {
    setMessage(null)
    const row = viewRow(name, location.pathname, location.search, range)
    if ('error' in row) { setMessage(row.error); return }
    const { error } = await supabase.from('saved_views').upsert(row, { onConflict: 'user_id,name' })
    if (error) { setMessage(error.message); return }
    setName('')
    setMessage(`Saved “${row.name}”.`)
    load()
  }

  const go = (v: SavedView) => {
    const target = openView(v)
    if (!target) { setMessage('That view is not a console route.'); return }
    if (target.range) setRange(target.range)
    navigate(target.path)
    setOpen(false)
  }

  const remove = async (v: SavedView) => {
    if (!window.confirm(`Delete the view “${v.name}”?`)) return
    const { error } = await supabase.from('saved_views').delete().eq('id', v.id)
    if (error) { setMessage(error.message); return }
    setViews((views ?? []).filter((x) => x.id !== v.id))
  }

  return (
    <div className="sv" ref={box}>
      <button className="btn-ghost mono" onClick={() => setOpen(!open)} aria-expanded={open}>VIEWS</button>
      {open && (
        <div className="sv-panel">
          <form className="sv-save" onSubmit={(e) => { e.preventDefault(); void save() }}>
            <input className="sv-input" placeholder="Name this view" value={name} onChange={(e) => setName(e.target.value)} aria-label="View name" />
            <button className="btn-ghost mono" type="submit">SAVE</button>
          </form>
          {message && <div className="sv-message mono">{message}</div>}
          {views === null && <div className="sv-muted mono">loading…</div>}
          {views?.length === 0 && <div className="sv-muted mono">No saved views yet.</div>}
          {views?.map((v) => (
            <div key={v.id} className="sv-row">
              <button className="sv-open" onClick={() => go(v)} title={v.path}>
                <span>{v.name}</span>
                <span className="sv-muted mono">{v.path.split('?')[0].slice(1)} · {'days' in v.range ? `${v.range.days}D` : `${v.range.start} → ${v.range.end}`}</span>
              </button>
              <button className="sv-del mono" onClick={() => void remove(v)} aria-label={`Delete ${v.name}`}>✕</button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
