// A long session's FUEL SCHEDULE on the web: what to take and when, as blocks (a product, what one intake holds, when the first is, how often, how many), kept in
// fuel_schedules (the phone's table, so the phone shows and debriefs the same schedule). Starts: from the planner's plan, from a STANDARD, or empty. Standards are
// reusable ways of fuelling ("a gel every 30 minutes from the 30th") applied to a session of any length; FILL adds one rule in a step. Totals, per-hour rates and
// warnings follow every edit.
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  applyStandard, describeRule, expandSchedule, FUEL_FORMS, type FuelBlock, type FuelProductOption, type PlannedItem, roundToInt, ruleProblems, scheduleTotals, scheduleWarnings,
  standardFromBlocks, type StandardRule,
} from '../../../supabase/functions/_shared/fuel-core/index.ts'
import { scheduleKey } from '../lib/fuelScheduleKey'
import { cleanBlock, deleteSchedule, deleteStandard, loadCatalog, loadSchedule, loadStandards, saveSchedule, saveStandard, seedScheduleFor, type StandardsResult } from '../lib/fuelScheduleData'

const g = (x: number) => String(roundToInt(x))
const clock = (min: number) => (min < 0 ? `-${Math.abs(min)} min` : min >= 60 ? `${Math.trunc(min / 60)} h ${String(min % 60).padStart(2, '0')}` : `${min} min`)
const newId = () => (globalThis.crypto?.randomUUID?.() ?? String(Math.random()).slice(2)).slice(0, 8)
const byStart = (bs: FuelBlock[]) => bs.map((b, i) => [b, i] as [FuelBlock, number]).sort((a, b) => a[0].startMin - b[0].startMin || a[1] - b[1]).map((x) => x[0])
const num = (s: string) => { const v = Number(s.replace(',', '.')); return s.trim() === '' || !Number.isFinite(v) ? 0 : v }

const PRESETS: FuelProductOption[] = [
  { id: null, name: 'Water', kind: 'water', carbsG: 0, fluidMl: 250, sodiumMg: 0, caffeineMg: 0, fructoseG: null },
  { id: null, name: 'Water with electrolytes', kind: 'electrolyte_drink', carbsG: 0, fluidMl: 250, sodiumMg: 100, caffeineMg: 0, fructoseG: null },
  { id: null, name: 'Gel (25 g, generic)', kind: 'gel', carbsG: 25, fluidMl: 0, sodiumMg: 50, caffeineMg: 0, fructoseG: null },
]

/** For a long session of the plan: where its schedule is kept, and the plan to start from. */
export function PlannedScheduleEditor({ item, weightKg, zone }: { item: PlannedItem; weightKg: number; zone: string }) {
  const key = scheduleKey(item)
  const seed = useCallback(() => seedScheduleFor(item, weightKg, zone), [item, weightKg, zone])
  if (!key) return null
  return <ScheduleEditor title={item.title} kind={key[0]} refKey={key[1]} durationMin={item.durationMin} seed={seed} />
}

/**
 * The editor for any schedule: kind and refKey say where it is kept (the phone's fuel_schedules: 'event' on the event id, 'planned' on the plan item, 'session' on a recording),
 * seed builds the generated plan as blocks to start from.
 */
export default function ScheduleEditor({ title, kind, refKey, durationMin, seed, toleratedGPerHour = 60 }: { title: string; kind: string; refKey: string; durationMin: number; seed: () => Promise<FuelBlock[]>; toleratedGPerHour?: number }) {
  const key: [string, string] = [kind, refKey]
  const duration = Math.round(durationMin)
  const [loaded, setLoaded] = useState(false)
  const [blocks, setBlocks] = useState<FuelBlock[]>([])
  const [saved, setSaved] = useState('')
  const [exists, setExists] = useState(false)
  const [seededFrom, setSeededFrom] = useState<string | null>(null)
  const [catalog, setCatalog] = useState<FuelProductOption[]>([])
  const [stds, setStds] = useState<StandardsResult>({ standards: [], missing: false })
  const [note, setNote] = useState<{ text: string; bad: boolean } | null>(null)
  const [busy, setBusy] = useState(false)
  const [pickedStd, setPickedStd] = useState('')
  const [stdName, setStdName] = useState('')
  const [fill, setFill] = useState({ product: '0', every: '30', from: '30', stop: '0' })
  const options = useMemo(() => [...PRESETS, ...catalog], [catalog])

  useEffect(() => {
    let live = true
    ;(async () => {
      try {
        const [row, cat, st] = await Promise.all([key ? loadSchedule(key[0], key[1]) : Promise.resolve(null), loadCatalog().catch(() => [] as FuelProductOption[]), loadStandards()])
        if (!live) return
        if (row) { setBlocks(row.blocks); setSaved(JSON.stringify(row.blocks)); setExists(true); setSeededFrom(row.seeded_from) }
        setCatalog(cat); setStds(st)
      } catch (e) { if (live) setNote({ text: `Could not load the schedule: ${(e as Error).message}`, bad: true }) }
      if (live) setLoaded(true)
    })()
    return () => { live = false }
  }, [kind, refKey]) // eslint-disable-line react-hooks/exhaustive-deps

  const dirty = JSON.stringify(blocks) !== (saved || '[]')
  const totals = scheduleTotals(blocks, duration)
  const warnings = scheduleWarnings(blocks, duration, toleratedGPerHour)
  const std = stds.standards.find((s) => String(s.id) === pickedStd)

  const run = async (what: () => Promise<void>) => {
    setBusy(true); setNote(null)
    try { await what() } catch (e) { setNote({ text: (e as Error).message, bad: true }) } finally { setBusy(false) }
  }
  const persist = (next: FuelBlock[], from: string | null) => run(async () => {
    const clean = byStart(next.map(cleanBlock))
    await saveSchedule(key[0], key[1], duration, clean, from ?? seededFrom)
    setBlocks(clean); setSaved(JSON.stringify(clean)); setExists(true); if (from) setSeededFrom(from)
    setNote({ text: 'Saved. The phone shows the same schedule.', bad: false })
  })
  const set = (id: string, patch: Partial<FuelBlock>) => setBlocks((bs) => bs.map((b) => (b.id === id ? { ...b, ...patch } : b)))
  const pickProduct = (id: string, optKey: string) => {
    const p = options[Number(optKey)]
    if (p) set(id, { label: p.name, form: p.kind, carbsG: p.carbsG, fluidMl: p.fluidMl ?? 0, sodiumMg: p.sodiumMg ?? 0, caffeineMg: p.caffeineMg ?? 0, fuelingProductId: p.id })
  }
  const ruleOf = (p: FuelProductOption, o: { every: number; from: number; stop: number }): StandardRule => ({
    label: p.name, form: p.kind, fuelingProductId: p.id, carbsG: p.carbsG, fluidMl: p.fluidMl ?? 0, sodiumMg: p.sodiumMg ?? 0, caffeineMg: p.caffeineMg ?? 0,
    startMin: o.from, mode: 'every', everyMin: o.every, stopBeforeEndMin: o.stop,
  })
  const doFill = () => {
    const p = options[Number(fill.product)]
    const rule = ruleOf(p, { every: num(fill.every), from: num(fill.from), stop: num(fill.stop) })
    const bad = ruleProblems(rule)
    if (bad.length) return setNote({ text: bad.join(' '), bad: true })
    const add = applyStandard({ name: 'fill', rules: [rule] }, duration, `f${newId()}`)
    if (!add.length) return setNote({ text: 'Nothing fits: the first one would come after the finish.', bad: true })
    setBlocks((bs) => byStart([...bs, ...add]))
  }
  const useStandard = (mode: 'replace' | 'add') => {
    if (!std) return
    const add = applyStandard(std, duration, `s${newId()}`)
    if (!add.length) return setNote({ text: 'That standard gives nothing for a session this short.', bad: true })
    setBlocks((bs) => byStart(mode === 'replace' ? add : [...bs, ...add]))
    if (mode === 'replace') setSeededFrom('manual')
  }

  return (
    <section className="pg-card wide fp-sched" data-testid="fuel-schedule">
      <h3 className="pg-card-title mono"><span>YOUR FUEL SCHEDULE · {title.toUpperCase()}</span><span>{clock(duration)}{dirty ? ' · UNSAVED' : exists ? ' · SAVED' : ''}</span></h3>
      {!loaded && <p className="pg-note">Loading…</p>}
      {loaded && !exists && blocks.length === 0 && (
        <div className="fs-start">
          <p className="pg-note">What you will take and when: gels, drink mixes, water, electrolytes. Start from the plan, from one of your standards, or empty; edit anything after.</p>
          <div className="pg-actions">
            <button className="pb-chip on" disabled={busy} onClick={() => run(async () => { const b = await seed(); setBlocks(byStart(b.map(cleanBlock))); setSeededFrom('plan'); setNote({ text: 'The plan as blocks. Save to keep it.', bad: false }) })}>START FROM THE PLAN</button>
            <button className="pb-chip" disabled={busy} onClick={() => { setBlocks([]); setSeededFrom('manual'); setExists(true) }}>START EMPTY</button>
          </div>
        </div>
      )}
      {loaded && (exists || blocks.length > 0) && (
        <>
          <div className="pg-table-wrap">
            <table className="rf-table fs-table">
              <thead><tr><th>NAME</th><th>FORM</th><th>FROM</th><th>EVERY</th><th>×</th><th>CARBS g</th><th>FLUID ml</th><th>Na mg</th><th>CAFF mg</th><th>FILL FROM</th><th /></tr></thead>
              <tbody>
                {blocks.map((b) => (
                  <tr key={b.id}>
                    <td><input aria-label={`${b.label || 'block'} name`} value={b.label} onChange={(e) => set(b.id, { label: e.target.value })} /></td>
                    <td><select aria-label={`${b.label || 'block'} form`} value={b.form} onChange={(e) => set(b.id, { form: e.target.value })}>{FUEL_FORMS.map((f) => <option key={f.key} value={f.key}>{f.label}</option>)}</select></td>
                    <td><input className="n" inputMode="numeric" aria-label={`${b.label || 'block'} first at`} value={b.startMin} onChange={(e) => set(b.id, { startMin: Math.round(num(e.target.value)) })} /></td>
                    <td><input className="n" inputMode="numeric" aria-label={`${b.label || 'block'} every`} value={b.intervalMin} disabled={b.count <= 1} onChange={(e) => set(b.id, { intervalMin: Math.round(num(e.target.value)) })} /></td>
                    <td><input className="n" inputMode="numeric" aria-label={`${b.label || 'block'} how many`} value={b.count} onChange={(e) => set(b.id, { count: Math.max(1, Math.round(num(e.target.value))) })} /></td>
                    <td><input className="n" inputMode="decimal" aria-label={`${b.label || 'block'} carbs`} value={b.carbsG} onChange={(e) => set(b.id, { carbsG: num(e.target.value) })} /></td>
                    <td><input className="n" inputMode="decimal" aria-label={`${b.label || 'block'} fluid`} value={b.fluidMl} onChange={(e) => set(b.id, { fluidMl: num(e.target.value) })} /></td>
                    <td><input className="n" inputMode="decimal" aria-label={`${b.label || 'block'} sodium`} value={b.sodiumMg} onChange={(e) => set(b.id, { sodiumMg: num(e.target.value) })} /></td>
                    <td><input className="n" inputMode="decimal" aria-label={`${b.label || 'block'} caffeine`} value={b.caffeineMg} onChange={(e) => set(b.id, { caffeineMg: num(e.target.value) })} /></td>
                    <td><select aria-label={`${b.label || 'block'} product`} value="" onChange={(e) => pickProduct(b.id, e.target.value)}><option value="">…</option>{options.map((p, i) => <option key={`${p.id}-${p.name}`} value={i}>{p.name}</option>)}</select></td>
                    <td><button className="pb-chip small" aria-label={`remove ${b.label || 'block'}`} onClick={() => setBlocks((bs) => bs.filter((x) => x.id !== b.id))}>×</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {blocks.length === 0 && <p className="pg-note">No blocks yet: fill one below, or add one.</p>}
          <p className="pg-note mono" data-testid="schedule-totals">
            TOTAL {g(totals.carbsG)} G CARBS{totals.carbsPerHour != null && ` (${g(totals.carbsPerHour)} G/H)`} · {(totals.fluidMl / 1000).toFixed(1)} L · {g(totals.sodiumMg)} MG NA{totals.caffeineMg > 0 && ` · ${g(totals.caffeineMg)} MG CAFFEINE`} · {expandSchedule(blocks).length} INTAKES OVER {clock(duration).toUpperCase()}
          </p>
          {warnings.map((w) => <p key={w} className="pg-note pg-warn">· {w}</p>)}

          <div className="fs-fill">
            <span className="mono fs-label">FILL</span>
            one serving of
            <select aria-label="fill product" value={fill.product} onChange={(e) => setFill({ ...fill, product: e.target.value })}>{options.map((p, i) => <option key={`${p.id}-${p.name}`} value={i}>{p.name}{p.carbsG ? ` (${g(p.carbsG)} g)` : ''}</option>)}</select>
            every <input className="n" aria-label="fill every" inputMode="numeric" value={fill.every} onChange={(e) => setFill({ ...fill, every: e.target.value })} /> min, from <input className="n" aria-label="fill from" inputMode="numeric" value={fill.from} onChange={(e) => setFill({ ...fill, from: e.target.value })} /> min,
            none in the last <input className="n" aria-label="fill stop" inputMode="numeric" value={fill.stop} onChange={(e) => setFill({ ...fill, stop: e.target.value })} /> min
            <button className="pb-chip" onClick={doFill}>FILL</button>
            <button className="pb-chip" onClick={() => setBlocks((bs) => [...bs, cleanBlock({ id: newId(), label: '', form: 'gel', startMin: 30, intervalMin: 30, count: 1 })])}>ADD A BLOCK</button>
          </div>

          <div className="fs-std">
            <span className="mono fs-label">STANDARDS</span>
            {stds.missing ? (
              <span className="pg-note">Standards need a one-time database update (fuel_standards): not applied yet.</span>
            ) : (
              <>
                <select aria-label="standard" value={pickedStd} onChange={(e) => setPickedStd(e.target.value)}><option value="">{stds.standards.length ? 'pick a standard…' : 'none saved yet'}</option>{stds.standards.map((s) => <option key={s.id} value={String(s.id)}>{s.name}</option>)}</select>
                <button className="pb-chip" disabled={!std} onClick={() => useStandard('replace')}>USE FOR THIS SESSION</button>
                <button className="pb-chip" disabled={!std} onClick={() => useStandard('add')}>ADD ITS RULES</button>
                <button className="pb-chip" disabled={!std?.id || busy} onClick={() => run(async () => { await deleteStandard(std?.id as number); setStds(await loadStandards()); setPickedStd('') })}>DELETE</button>
                <input aria-label="standard name" placeholder="name this schedule as a standard" value={stdName} onChange={(e) => setStdName(e.target.value)} />
                <button className="pb-chip" disabled={busy || !stdName.trim() || blocks.length === 0} onClick={() => run(async () => { await saveStandard(standardFromBlocks(stdName, blocks, duration)); setStds(await loadStandards()); setNote({ text: `Saved as the standard “${stdName.trim()}”: its rules fit any length.`, bad: false }); setStdName('') })}>SAVE AS A STANDARD</button>
                {std && <ul className="fp-why fs-rules">{std.rules.map((r, i) => <li key={i} className="fp-note">{describeRule(r)}</li>)}</ul>}
              </>
            )}
          </div>

          <div className="pg-actions">
            <button className="pb-chip on" disabled={busy || !dirty} onClick={() => persist(blocks, seededFrom)}>SAVE THE SCHEDULE</button>
            <button className="pb-chip" disabled={busy || !dirty || !saved} onClick={() => { setBlocks(JSON.parse(saved)) }}>DISCARD CHANGES</button>
            {saved && <button className="pb-chip" disabled={busy} onClick={() => { if (window.confirm('Delete this schedule? The phone loses it too.')) run(async () => { await deleteSchedule(key[0], key[1]); setBlocks([]); setSaved(''); setExists(false); setSeededFrom(null) }) }}>DELETE THE SCHEDULE</button>}
          </div>
        </>
      )}
      {note && <p className={`pg-note ${note.bad ? 'pg-warn' : 'pg-ok'}`} role="status">{note.text}</p>}
    </section>
  )
}
