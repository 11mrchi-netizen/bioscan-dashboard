// PLAN › GEAR (#/plan/gear): when you use straps, a belt and the rest, and what they do to your lifts. The rules ("straps on deadlifts
// from 140 kg") pre-fill the gear of a set on this page's sister, the recorded session, and on the phone's log; the comparison reads the
// sets where gear was RECORDED (a set from before gear existed is not "no gear", it is unknown). Rules: supabase gear_rules; shared
// arithmetic: supabase/functions/_shared/training-core/gear.ts.
import { useMemo, useState } from 'react'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { deleteGearRule, loadGearRules, saveGearRule } from '../lib/gearData'
import {
  GEAR_LABEL, GEAR_TAGS, MIN_SETS_PER_SIDE, gearComparisons, movementKey as keyOf, type GearComparison, type GearRule, type GearSetRow, type GearTag,
} from '../../../supabase/functions/_shared/training-core/index.ts'
import './PlanBlock.css'
import './Pages.css'

interface Loaded { rules: GearRule[]; sets: GearSetRow[]; names: string[]; recorded: number; total: number }

async function loadSets(): Promise<{ sets: GearSetRow[]; names: string[]; recorded: number; total: number }> {
  const sets: GearSetRow[] = []
  const names = new Set<string>()
  let recorded = 0, total = 0
  for (let from = 0; ; from += 500) {
    const r = await supabase.from('exercise_sessions').select('start_time,exercises:details->exercises').eq('type', 'strength').order('start_time').range(from, from + 499)
    if (r.error) throw new Error(`exercise_sessions: ${r.error.message}`)
    const rows = (r.data ?? []) as unknown as Array<{ start_time: string; exercises: Array<{ name?: string; sets?: Array<{ reps?: number; weight_kg?: number; rpe?: number | null; gear?: unknown }> }> | null }>
    for (const row of rows) {
      for (const e of Array.isArray(row.exercises) ? row.exercises : []) {
        if (!e?.name) continue
        names.add(e.name)
        for (const s of e.sets ?? []) {
          total++
          if (Array.isArray(s.gear)) recorded++
          if (typeof s.reps === 'number' && typeof s.weight_kg === 'number') sets.push({ name: e.name, reps: s.reps, weight_kg: s.weight_kg, rpe: s.rpe ?? null, gear: s.gear, date: row.start_time.slice(0, 10) })
        }
      }
    }
    if (rows.length < 500) break
  }
  return { sets, names: [...names].sort(), recorded, total }
}

export default function Gear() {
  const [data, setData] = useState<Loaded | null>(null)
  const st = useLoadEffect(async (cur) => {
    const [rules, s] = await Promise.all([loadGearRules(), loadSets()])
    if (cur()) setData({ rules, ...s })
  }, [])
  if (st.error) return <div className="pg"><p className="pb-warn">Could not load: {st.error} <button className="btn-ghost mono" onClick={st.retry}>RETRY</button></p></div>
  if (!data) return <div className="pg"><p className="state-loading mono">LOADING…</p></div>
  return <Loaded d={data} onChange={(rules) => setData({ ...data, rules })} />
}

const nm = (x: number | null, d = 1) => (x == null ? '–' : x.toFixed(d))

function Loaded({ d, onChange }: { d: Loaded; onChange: (r: GearRule[]) => void }) {
  const [lift, setLift] = useState('')
  const [gear, setGear] = useState<GearTag>('straps')
  const [from, setFrom] = useState('')
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const comparisons = useMemo(() => gearComparisons(d.sets), [d.sets])
  // a readable lift name per movement key: the most recent spelling in the log
  const labelOf = useMemo(() => {
    const m = new Map<string, string>()
    for (const s of d.sets) m.set(keyOf(s.name), s.name)
    return (k: string) => m.get(k) ?? k
  }, [d.sets])
  const byLift = useMemo(() => {
    const m = new Map<string, GearRule[]>()
    for (const r of d.rules) m.set(r.movement_key, [...(m.get(r.movement_key) ?? []), r])
    return [...m.entries()]
  }, [d.rules])

  async function add() {
    try {
      const kg = from.trim() === '' ? null : Number(from)
      if (kg != null && !Number.isFinite(kg)) throw new Error('The weight is a number of kg, or empty for any load.')
      await saveGearRule(lift, gear, kg)
      onChange(await loadGearRules())
      setMessage({ ok: true, text: `Saved: ${GEAR_LABEL[gear].toLowerCase()} on ${lift.trim()}${kg == null ? '' : ` from ${kg} kg`}.` })
      setLift(''); setFrom('')
    } catch (e) { setMessage({ ok: false, text: e instanceof Error ? e.message : String(e) }) }
  }
  async function remove(r: GearRule) {
    try { await deleteGearRule(r.movement_key, r.gear); onChange(await loadGearRules()); setMessage({ ok: true, text: 'Rule removed.' }) } catch (e) { setMessage({ ok: false, text: e instanceof Error ? e.message : String(e) }) }
  }

  return (
    <div className="pg">
      <section className="pb-section">
        <h2 className="mono">MY GEAR RULES</h2>
        <p className="pg-note">When you use gear on a lift, and from what weight. A new set pre-fills from these as you type its weight, on the web and on the phone; you can always change it per set. A lift with no rule is left NOT RECORDED rather than logged as "no gear". Leave the weight empty for gear you always use on that lift.</p>
        {byLift.length === 0 && <p className="state-empty">NO RULES YET.</p>}
        {byLift.map(([key, rs]) => (
          <div key={key} className="pb-row" style={{ alignItems: 'center' }}>
            <b>{labelOf(key)}</b>
            {rs.map((r) => (
              <span key={r.gear} className="pb-chip on">{GEAR_LABEL[r.gear as GearTag] ?? r.gear}{r.from_kg == null ? '' : ` from ${r.from_kg} kg`} <button className="btn-ghost mono" aria-label={`Remove ${r.gear} on ${labelOf(key)}`} onClick={() => void remove(r)}>×</button></span>
            ))}
          </div>
        ))}
        <div className="pb-row" style={{ alignItems: 'flex-end' }}>
          <label className="sd-field"><span className="mono">LIFT</span>
            <input className="pb-input" list="gear-lifts" value={lift} onChange={(e) => setLift(e.target.value)} placeholder="Deadlift" />
            <datalist id="gear-lifts">{d.names.map((n) => <option key={n} value={n} />)}</datalist>
          </label>
          <label className="sd-field"><span className="mono">GEAR</span>
            <select className="pb-input" value={gear} onChange={(e) => setGear(e.target.value as GearTag)}>{GEAR_TAGS.map((g) => <option key={g} value={g}>{GEAR_LABEL[g]}</option>)}</select>
          </label>
          <label className="sd-field"><span className="mono">FROM KG</span>
            <input className="pb-input sd-num" inputMode="decimal" value={from} onChange={(e) => setFrom(e.target.value)} placeholder="any" />
          </label>
          <button className="pb-primary mono" disabled={lift.trim() === ''} onClick={() => void add()}>SAVE RULE</button>
          {message && <span className={message.ok ? 'pb-ok' : 'pb-warn'} role="status">{message.text}</span>}
        </div>
      </section>

      <section className="pb-section">
        <h2 className="mono">WITH AND WITHOUT</h2>
        <p className="pg-note">
          {d.recorded.toLocaleString()} of {d.total.toLocaleString()} logged sets have gear recorded; the others are unknown and are left out. Gear tends to go on at the heavy end, so a plain average would set heavy sets against light ones: each line compares only the weights you lifted both ways, and
          waits for {MIN_SETS_PER_SIDE} sets on each side before it reads anything. It shows what happened, not that the gear caused it.
        </p>
        {comparisons.length === 0 && <p className="state-empty">NOTHING TO COMPARE YET: A LIFT NEEDS SETS RECORDED BOTH WITH AND WITHOUT THE SAME GEAR.</p>}
        {comparisons.length > 0 && (
          <table className="sd-sets">
            <thead><tr><th>LIFT</th><th>GEAR</th><th>SETS WITH / WITHOUT</th><th>SHARED WEIGHTS</th><th>REPS</th><th>RPE</th><th>BEST E1RM, KG</th></tr></thead>
            <tbody>{comparisons.map((c) => <Row key={`${c.movement}|${c.gear}`} c={c} name={labelOf(c.movement)} />)}</tbody>
          </table>
        )}
      </section>
    </div>
  )
}

function Row({ c, name }: { c: GearComparison; name: string }) {
  const o = c.inOverlap
  const readable = c.enough && o
  return (
    <tr>
      <td>{name}</td>
      <td className="mono">{GEAR_LABEL[c.gear].toUpperCase()}</td>
      <td>{c.with.sets} / {c.without.sets}</td>
      <td>{c.overlapKg ? `${c.overlapKg[0]}–${c.overlapKg[1]} kg (${o?.with.sets} / ${o?.without.sets} sets)` : 'never at the same weight'}</td>
      <td>{readable ? `${nm(o.with.meanReps)} vs ${nm(o.without.meanReps)}` : <span className="sd-watch mono">TOO FEW SETS</span>}</td>
      <td>{readable ? `${nm(o.with.meanRpe)} vs ${nm(o.without.meanRpe)}` : ''}</td>
      <td>{readable ? `${nm(o.with.bestE1rmKg)} vs ${nm(o.without.bestE1rmKg)}` : ''}</td>
    </tr>
  )
}
