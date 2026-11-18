// ACCESSORY WORK (Nov 2026): exercises added to a strength module's sessions on top of the book's slots, edited in a
// table per session ("every session" first). Used by the planner's DETAILS step and by a block under way (BLOCKS UNDER
// WAY › ACCESSORIES). Same rules as the phone (training-core/accessories.ts): full in normal and peak weeks, half the sets
// in deload and taper weeks, none in test weeks.

import { type Accessory, ALL_SESSIONS, type LoadDto } from '../../../supabase/functions/_shared/training-core/index.ts'
import { accessoryOk, COMMON_ACCESSORIES, loadValue } from '../lib/accessoryWork'

const LOAD_KINDS: { kind: string; label: string }[] = [
  { kind: 'rpe', label: 'RPE' }, { kind: 'pct_1rm', label: '% of 1RM' }, { kind: 'fixed', label: 'kg' }, { kind: 'bodyweight', label: 'bodyweight' }, { kind: 'none', label: 'no load' },
]

function withLoad(kind: string, v: number | undefined): LoadDto {
  if (kind === 'rpe') return { kind, rpe: v ?? 8 }
  if (kind === 'pct_1rm') return { kind, value: v ?? 65 }
  if (kind === 'fixed') return { kind, kg: v ?? 10 }
  return { kind }
}
const NEW_ONE: Accessory = { exercise: '', sets: 3, reps: { min: 10, max: 12 }, load: { kind: 'rpe', rpe: 8 }, restS: null, note: null }

export function AccessoryTables({ sessions, value, onChange }: { sessions: { id: string; label: string }[]; value: Record<string, Accessory[]>; onChange: (next: Record<string, Accessory[]>) => void }) {
  const all = [{ id: ALL_SESSIONS, label: 'Every session' }, ...sessions]
  const put = (id: string, list: Accessory[]) => onChange({ ...value, [id]: list })
  return (
    <div className="pb-group">
      <datalist id="pb-accessory-names">{COMMON_ACCESSORIES.map((n) => <option key={n} value={n} />)}</datalist>
      {all.map((s) => {
        const list = value[s.id] ?? []
        return (
          <div key={s.id} className="pb-acc">
            <div className="pb-row tight" style={{ justifyContent: 'space-between' }}>
              <span className="pb-label">{s.label.toUpperCase()}{s.id !== ALL_SESSIONS ? ` · ${s.id}` : ''}</span>
              <button className="pb-chip small" onClick={() => put(s.id, [...list, { ...NEW_ONE }])}>+ ADD AN EXERCISE</button>
            </div>
            {list.length > 0 && (
              <table className="pb-table">
                <thead><tr><th>EXERCISE</th><th>SETS</th><th>REPS / SECONDS</th><th>LOAD</th><th>NOTE</th><th /></tr></thead>
                <tbody>
                  {list.map((a, i) => {
                    const upd = (f: (x: Accessory) => Accessory) => put(s.id, list.map((x, j) => (j === i ? f(x) : x)))
                    const v = loadValue(a.load)
                    return (
                      <tr key={i} className={accessoryOk(a) ? '' : 'pb-missing'}>
                        <td><input className="pb-input" style={{ width: 150 }} list="pb-accessory-names" placeholder="Exercise" value={a.exercise} onChange={(e) => upd((x) => ({ ...x, exercise: e.target.value }))} /></td>
                        <td><input className="pb-input" type="number" min={1} max={10} style={{ width: 56 }} value={a.sets} onChange={(e) => upd((x) => ({ ...x, sets: Math.max(1, Math.round(Number(e.target.value) || 1)) }))} /></td>
                        <td>
                          <span className="pb-row tight">
                            <input className="pb-input" type="number" min={1} style={{ width: 52 }} value={a.reps?.min ?? ''} placeholder="—"
                              onChange={(e) => { const n = Number(e.target.value); upd((x) => ({ ...x, reps: e.target.value === '' ? null : { min: n, max: Math.max(n, x.reps?.max ?? n) } })) }} />
                            <span className="pb-unit">to</span>
                            <input className="pb-input" type="number" min={1} style={{ width: 52 }} value={a.reps?.max ?? ''} placeholder="—"
                              onChange={(e) => { const n = Number(e.target.value); upd((x) => ({ ...x, reps: x.reps ? { min: x.reps.min, max: e.target.value === '' ? x.reps.min : n } : null })) }} />
                          </span>
                        </td>
                        <td>
                          <span className="pb-row tight">
                            <select className="pb-input" value={a.load.kind} onChange={(e) => upd((x) => ({ ...x, load: withLoad(e.target.value, undefined) }))}>
                              {LOAD_KINDS.map((k) => <option key={k.kind} value={k.kind}>{k.label}</option>)}
                            </select>
                            {v !== undefined && (
                              <input className="pb-input" type="number" step={a.load.kind === 'fixed' ? 0.5 : a.load.kind === 'rpe' ? 0.5 : 1} style={{ width: 64 }} value={v}
                                onChange={(e) => upd((x) => ({ ...x, load: withLoad(x.load.kind, e.target.value === '' ? undefined : Number(e.target.value)) }))} />
                            )}
                          </span>
                        </td>
                        <td><input className="pb-input" style={{ width: 120 }} placeholder="tempo, superset…" value={a.note ?? ''} onChange={(e) => upd((x) => ({ ...x, note: e.target.value }))} /></td>
                        <td><button className="pb-chip small" onClick={() => put(s.id, list.filter((_, j) => j !== i))}>REMOVE</button></td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            )}
          </div>
        )
      })}
    </div>
  )
}

