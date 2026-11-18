// PLAN BLOCK: the long-form training block planner (owner's request, Nov 2026: the web is where the next block is
// prepared, with more detail and control than the phone). Same engine as the Android planner
// (supabase/functions/_shared/training-core, parity-tested against the Kotlin one), same rows written: a block created
// here is on the phone at once. Six steps, free to move between; the preview on the right regenerates on every change,
// and the draft is kept in this browser until the block is created.

import { useEffect, useMemo, useState } from 'react'
import { useLoadEffect } from '../lib/useLoadEffect'
import {
  blockLength, buildableEvents, buildToward, canCreate, changeStart, changeWeeks, choiceFor, dayPositions, exercisesInPlay, generate,
  missingMaxes, newDraft, startAfterBlocks, pickProtocol, pickRoute, pickStrength, pickTemplate, type PlannerCatalog, type PlannerDraft, protocolOf, setDay, setWeek,
  strengthModules, strengthOf, templateOf, updateChoice, weekdayFor, weekStats, weekWarnings, withLayoutReset, withTimelineReset,
} from '../lib/blockPlanner'
import { type BlockForEnding, createBlock, endBlockEarly, type ExistingBlock, loadBlockForEnding, loadExistingBlocks, loadPlannerCatalog, saveSettingsAsDefault, updateBlockAccessories } from '../lib/blockPlannerData'
import {
  type ConditioningProtocolDef, type ConditioningSessionDef, countedWeeks, type DayPlan, type GeneratedBlock, type GeneratedSession, isCluster,
  isMainProtocol, itemText, loadText, localToday, type NumRange, plusDays, plusWeekday, rangeText, slotsToChoose, STRENGTH_GROUPS, type StrengthModuleDef,
  type TemplateDef, templateVariables, versionsOf, WEEK_KINDS, type WeekKind, weekdayOf, weekdayShort, type Weekday, progressionIncrementKg, loadStepKg,
  fmt, kgToLb, endEarlyPreview, stepUpMaxes, movementKey, type Accessory, DEFAULT_EQUIPMENT,
} from '../../../supabase/functions/_shared/training-core/index.ts'
import { AccessoryTables } from './AccessoryWork'
import { accessorySummary, cleanAccessories } from '../lib/accessoryWork'
import './PlanBlock.css'

type Step = 'when' | 'program' | 'week' | 'weeks' | 'details' | 'review'
const STEPS: { id: Step; label: string }[] = [
  { id: 'when', label: 'WHEN' }, { id: 'program', label: 'PROGRAM' }, { id: 'week', label: 'THE WEEK' },
  { id: 'weeks', label: 'THE WEEKS' }, { id: 'details', label: 'DETAILS' }, { id: 'review', label: 'REVIEW' },
]
const DRAFT_KEY = 'ft.planBlock.draft.v1'
const PLATE_SIZES = [0.5, 1, 1.25, 2, 2.5, 5, 10, 15, 20, 25]

function readDraft(): PlannerDraft | null {
  try { const s = localStorage.getItem(DRAFT_KEY); return s ? (JSON.parse(s) as PlannerDraft) : null } catch { return null }
}
function writeDraft(d: PlannerDraft | null): void {
  try { if (d) localStorage.setItem(DRAFT_KEY, JSON.stringify(d)); else localStorage.removeItem(DRAFT_KEY) } catch { /* storage unavailable: the draft lives for this visit */ }
}

const dateLong = (d: string) => new Date(`${d}T12:00:00`).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
const dateShort = (d: string) => new Date(`${d}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })
const domainClass = (s: GeneratedSession) => (s.workKind === 'test' ? 'test' : s.workKind === 'deload' || s.domain === 'recovery' ? 'deload' : s.conditioning ? 'cond' : 'strength')
const rangeOrDash = (r: NumRange | null | undefined) => (r ? rangeText(r) : '—')

export default function PlanBlock() {
  const [cat, setCat] = useState<PlannerCatalog | null>(null)
  const [existing, setExisting] = useState<ExistingBlock[]>([])
  const [draft, setDraftState] = useState<PlannerDraft | null>(null)
  const [step, setStep] = useState<Step>('when')
  const [selected, setSelected] = useState<number | null>(null)
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<{ id: number; name: string } | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const status = useLoadEffect(async (isCurrent) => {
    const [c, blocks] = await Promise.all([loadPlannerCatalog(), loadExistingBlocks().catch(() => [] as ExistingBlock[])])
    if (!isCurrent()) return
    setCat(c)
    setExisting(blocks)
    setDraftState((d) => d ?? readDraft() ?? newDraft(c.settings, localToday(), c.bodyweightKg, startAfterBlocks(blocks, localToday())))
  }, [])

  const setDraft = (f: (d: PlannerDraft) => PlannerDraft) => setDraftState((d) => (d ? f(d) : d))
  useEffect(() => { if (draft) writeDraft(draft) }, [draft])

  const gen = useMemo(() => (draft && cat ? generate(draft, cat) : { block: null, error: null }), [draft, cat])
  const block = gen.block

  if (status.loading) return <div className="state-loading">LOADING THE PROGRAM CATALOGUE…</div>
  if (status.error || !cat || !draft) return <div className="state-error">COULD NOT LOAD: {status.error}<button className="btn-ghost mono" onClick={status.retry}>RETRY</button></div>
  if (cat.index.all.length === 0) {
    return <div className="state-empty">NO PROGRAM DEFINITIONS YET. IMPORT THEM ON THE PHONE (SETUP › TRAINING › IMPORT), THEN COME BACK.</div>
  }

  const steps = draft.route === 'published' ? STEPS.filter((s) => s.id !== 'weeks') : STEPS
  const current = steps.some((s) => s.id === step) ? step : 'when'

  const create = async () => {
    if (!block) return
    setCreating(true); setMessage(null)
    try {
      const id = await createBlock(draft, cat, block)
      setCreated({ id, name: draft.name.trim() || 'the block' })
      writeDraft(null)
      setExisting(await loadExistingBlocks().catch(() => existing))
    } catch (e) {
      setMessage(`Could not create the block: ${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setCreating(false)
    }
  }

  const startOver = () => {
    writeDraft(null)
    setCreated(null)
    setSelected(null)
    setStep('when')
    setDraftState(newDraft(cat.settings, localToday(), cat.bodyweightKg, startAfterBlocks(existing, localToday())))
  }

  return (
    <div className="pb">
      <nav className="pb-steps" aria-label="Planner steps">
        <h2 className="pb-title mono">PLAN A BLOCK</h2>
        {steps.map((s, i) => (
          <button key={s.id} className={`pb-step ${current === s.id ? 'active' : ''}`} onClick={() => setStep(s.id)}>
            <span className="pb-step-n mono">{i + 1}</span>
            <span className="pb-step-label mono">{s.label}</span>
            <span className="pb-step-sum">{stepSummary(s.id, draft, cat, block)}</span>
          </button>
        ))}
        <div className="pb-steps-foot">
          <button className="btn-ghost mono" onClick={startOver}>START OVER</button>
          <p className="pb-hint">The draft is kept in this browser until the block is created.</p>
          {cat.unreadable.length > 0 && <p className="pb-warn">{cat.unreadable.length} definition(s) could not be read: {cat.unreadable[0]}</p>}
        </div>
      </nav>

      <main className="pb-main">
        {current === 'when' && <RunningBlocks blocks={existing} cat={cat} onEnded={async (nextStart) => {
          setExisting(await loadExistingBlocks().catch(() => existing))
          setDraft((x) => changeStart(x, cat.index, nextStart, cat.events))
        }} />}
        {current === 'when' && <WhenStep d={draft} cat={cat} set={setDraft} />}
        {current === 'program' && <ProgramStep d={draft} cat={cat} set={setDraft} />}
        {current === 'week' && <WeekStep d={draft} cat={cat} set={setDraft} />}
        {current === 'weeks' && <WeeksStep d={draft} cat={cat} set={setDraft} />}
        {current === 'details' && <DetailsStep d={draft} cat={cat} set={setDraft} />}
        {current === 'review' && <ReviewStep d={draft} block={block} existing={existing} onSelect={setSelected} selected={selected} />}
        <div className="pb-nav">
          {steps.findIndex((s) => s.id === current) > 0 && (
            <button className="btn-ghost mono" onClick={() => setStep(steps[steps.findIndex((s) => s.id === current) - 1].id)}>BACK</button>
          )}
          {current !== 'review' && (
            <button className="pb-primary mono" onClick={() => setStep(steps[steps.findIndex((s) => s.id === current) + 1].id)}>NEXT</button>
          )}
        </div>
      </main>

      <aside className="pb-preview">
        <Preview d={draft} cat={cat} block={block} genError={gen.error} existing={existing} selected={selected} onSelect={setSelected}
          creating={creating} created={created} message={message} onCreate={create} onStartOver={startOver} />
      </aside>
    </div>
  )
}

function stepSummary(s: Step, d: PlannerDraft, cat: PlannerCatalog, block: GeneratedBlock | null): string {
  const idx = cat.index
  switch (s) {
    case 'when': return `${dateShort(d.startDate)}${d.route === 'build' ? ` · ${d.weeks} wk` : ''}`
    case 'program':
      if (d.route === 'published') return templateOf(d, idx)?.title ?? 'choose a program'
      return [strengthOf(d, idx)?.title, protocolOf(d, idx)?.title].filter(Boolean).join(' + ') || 'choose'
    case 'week': {
      if (d.route === 'published') return `${dayPositions(d, idx).length} training days`
      const n = weekWarnings(d, idx).length
      return `${d.layout.filter((p) => p.strengthSession != null || p.conditioning).length} days${n > 0 ? ` · ${n} warning${n === 1 ? '' : 's'}` : ''}`
    }
    case 'weeks': return `${countedWeeks(d.timeline)} counted of ${d.timeline.length}`
    case 'details': { const m = missingMaxes(d, cat).length; return m > 0 ? `${m} max${m === 1 ? '' : 'es'} missing` : 'every max known' }
    case 'review': return block ? `${block.sessions.length} sessions · ${block.warnings.length} warnings` : '—'
  }
}

// ---------------- shared bits ----------------

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="pb-field">
      <span className="pb-label mono">{label}</span>
      {children}
      {hint && <span className="pb-hint">{hint}</span>}
    </label>
  )
}

function Num({ value, onChange, step = 1, min, max, width = 80, placeholder }: { value: number | null | undefined; onChange: (v: number | null) => void; step?: number; min?: number; max?: number; width?: number; placeholder?: string }) {
  return (
    <input className="pb-input" type="number" style={{ width }} step={step} min={min} max={max} placeholder={placeholder}
      value={value ?? ''} onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))} />
  )
}

function Section({ title, info, children }: { title: string; info?: string; children: React.ReactNode }) {
  return (
    <section className="pb-section">
      <h3 className="pb-section-title mono">{title}</h3>
      {info && <p className="pb-info">{info}</p>}
      {children}
    </section>
  )
}

function Warnings({ list }: { list: string[] }) {
  if (list.length === 0) return <p className="pb-ok mono">NO WARNINGS</p>
  return <ul className="pb-warnings">{list.map((w) => <li key={w}>{w}</li>)}</ul>
}

type Setter = (f: (d: PlannerDraft) => PlannerDraft) => void

// ---------------- 1 · WHEN ----------------

function WhenStep({ d, cat, set }: { d: PlannerDraft; cat: PlannerCatalog; set: Setter }) {
  const idx = cat.index
  const events = buildableEvents(cat.events, d.startDate)
  const [saved, setSaved] = useState<string | null>(null)
  const end = plusDays(d.startDate, 7 * Math.max(1, d.route === 'build' ? d.weeks : templateOf(d, idx)?.grid.length ?? 1) - 1)
  return (
    <>
      <Section title="NAME AND DATES" info="Start on the day the first session falls; the week's layout follows the start weekday.">
        <div className="pb-row">
          <Field label="NAME"><input className="pb-input" style={{ width: 280 }} value={d.name} placeholder="e.g. Winter strength" onChange={(e) => set((x) => ({ ...x, name: e.target.value }))} /></Field>
          <Field label="START" hint={dateLong(d.startDate)}>
            <input className="pb-input" type="date" value={d.startDate} onChange={(e) => e.target.value && set((x) => changeStart(x, idx, e.target.value, cat.events))} />
          </Field>
          <Field label="QUICK">
            <div className="pb-chips">
              {[0, 7, 14].map((n) => {
                const mon = plusDays(localToday(), ((1 - weekdayOf(localToday()) + 7) % 7) + n)
                return <button key={n} className="pb-chip" onClick={() => set((x) => changeStart(x, idx, mon, cat.events))}>MON {dateShort(mon)}</button>
              })}
            </div>
          </Field>
        </div>
        {d.route === 'build' && (
          <div className="pb-row">
            <Field label="LENGTH, WEEKS" hint={`Ends ${dateLong(end)}`}>
              <div className="pb-stepper">
                <button className="pb-chip" onClick={() => set((x) => changeWeeks(x, idx, x.weeks - 1))}>−</button>
                <Num value={d.weeks} min={1} max={52} width={64} onChange={(v) => v != null && set((x) => changeWeeks(x, idx, v))} />
                <button className="pb-chip" onClick={() => set((x) => changeWeeks(x, idx, x.weeks + 1))}>+</button>
              </div>
            </Field>
            <Field label="BUILD TOWARD AN EVENT" hint="Ends in the event's week; the last week or two taper (A race or longer than 90 minutes: two).">
              <select className="pb-input" value={d.targetEventId ?? ''} onChange={(e) => {
                const ev = cat.events.find((x) => x.id === Number(e.target.value)) ?? null
                set((x) => buildToward(x, idx, ev, cat.events))
              }}>
                <option value="">No event</option>
                {events.map((e) => <option key={e.id} value={e.id}>{e.name} · {e.priority} · {dateShort(e.eventDate)}</option>)}
              </select>
            </Field>
          </div>
        )}
      </Section>

      <Section title="SESSION TIMES" info="Every planned session gets the first time; a second session on the same day gets the second. Moving one later is done on the phone or in the calendar.">
        <div className="pb-row">
          {([1, 2] as const).map((n) => (
            <Field key={n} label={n === 1 ? 'FIRST SESSION' : 'SECOND SESSION OF A DAY'}>
              <div className="pb-row tight">
                <input className="pb-input" type="time" value={d.slotTimes[n].start} onChange={(e) => set((x) => ({ ...x, slotTimes: { ...x.slotTimes, [n]: { ...x.slotTimes[n], start: e.target.value } } }))} />
                <Num value={d.slotTimes[n].durationMin} min={10} max={600} step={5} onChange={(v) => v != null && set((x) => ({ ...x, slotTimes: { ...x.slotTimes, [n]: { ...x.slotTimes[n], durationMin: v } } }))} />
                <span className="pb-unit">min</span>
              </div>
            </Field>
          ))}
        </div>
      </Section>

      <Section title="EQUIPMENT AND BODYWEIGHT" info="Loads round to what the bar and plates can make (ties round down). Weighted pull-ups and dips follow the percent base.">
        <div className="pb-row">
          <Field label="BAR, KG"><Num value={d.equipment.barKg} step={0.5} onChange={(v) => v != null && set((x) => ({ ...x, equipment: { ...x.equipment, barKg: v } }))} /></Field>
          <Field label="TRAP BAR, KG" hint="Used for any exercise named 'trap'."><Num value={d.equipment.trapBarKg} step={0.5} onChange={(v) => v != null && set((x) => ({ ...x, equipment: { ...x.equipment, trapBarKg: v } }))} /></Field>
          <Field label="BELT STEP, KG" hint="Smallest plate on a dip belt or vest."><Num value={d.equipment.addedStepKg} step={0.25} onChange={(v) => v != null && v > 0 && set((x) => ({ ...x, equipment: { ...x.equipment, addedStepKg: v } }))} /></Field>
          <Field label="BODYWEIGHT, KG" hint="Latest weigh-in; used for the total load of weighted calisthenics."><Num value={d.bodyweightKg} step={0.1} onChange={(v) => set((x) => ({ ...x, bodyweightKg: v }))} /></Field>
        </div>
        <Field label="PLATES (PER SIDE)" hint={`Smallest jump on the bar: ${fmt(loadStepKg(d.equipment.platesKg))} kg.`}>
          <div className="pb-chips">
            {PLATE_SIZES.map((p) => {
              const on = d.equipment.platesKg.includes(p)
              return (
                <button key={p} className={`pb-chip ${on ? 'on' : ''}`} onClick={() => set((x) => {
                  const next = on ? x.equipment.platesKg.filter((q) => q !== p) : [...x.equipment.platesKg, p].sort((a, b) => a - b)
                  return next.length === 0 ? x : { ...x, equipment: { ...x.equipment, platesKg: next } }
                })}>{fmt(p)}</button>
              )
            })}
          </div>
        </Field>
        <Field label="WEIGHTED CALISTHENICS: THE PERCENT IS OF">
          <div className="pb-chips">
            {(['added', 'total'] as const).map((b) => (
              <button key={b} className={`pb-chip ${d.equipment.weightedBase === b ? 'on' : ''}`} onClick={() => set((x) => ({ ...x, equipment: { ...x.equipment, weightedBase: b } }))}>
                {b === 'added' ? 'THE ADDED WEIGHT (YOUR LOGS)' : 'THE TOTAL WITH BODYWEIGHT (THE BOOK)'}
              </button>
            ))}
          </div>
        </Field>
        <div className="pb-row">
          <button className="btn-ghost mono" onClick={async () => {
            try { await saveSettingsAsDefault(d); setSaved('Saved as the defaults for new blocks (phone and web).') } catch (e) { setSaved(e instanceof Error ? e.message : String(e)) }
          }}>SAVE TIMES AND EQUIPMENT AS MY DEFAULTS</button>
          {saved && <span className="pb-hint">{saved}</span>}
        </div>
      </Section>
    </>
  )
}

// ---------------- 2 · PROGRAM ----------------

function budgetLines(p: ConditioningProtocolDef): string[] {
  const b = p.budget
  if (!b) return []
  const out: string[] = []
  if (b.low_intensity_minutes_per_week) out.push(`Low intensity ${rangeText(b.low_intensity_minutes_per_week)} min a week${b.session_min_minutes ? `, at least ${b.session_min_minutes} min a session` : ''}`)
  if (b.high_intensity_per_week) out.push(`High intensity ${rangeText(b.high_intensity_per_week)} a week`)
  if (b.high_intensity_every_n_weeks) out.push(`High intensity every ${b.high_intensity_every_n_weeks} weeks`)
  if (b.sessions_per_week) out.push(`${rangeText(b.sessions_per_week)} conditioning sessions a week`)
  if (b.min_conditioning_sessions_per_week) out.push(`At least ${b.min_conditioning_sessions_per_week} conditioning sessions a week`)
  if (b.min_rest_days_per_week) out.push(`At least ${b.min_rest_days_per_week} rest day(s)`)
  for (const a of p.week_adjustments) out.push(`${a.when === 'week_has_high_intensity' ? 'Weeks with' : 'Weeks without'} high intensity: ${rangeText(a.low_intensity_minutes_per_week)} min low intensity`)
  return out
}

function StrengthCard({ m, selected, onPick, hint }: { m: StrengthModuleDef; selected: boolean; onPick: () => void; hint?: boolean }) {
  return (
    <button className={`pb-card ${selected ? 'on' : ''}`} onClick={onPick}>
      <span className="pb-card-title">{m.title}{hint && <span className="pb-tag mono">PAIRS</span>}</span>
      <span className="pb-card-meta mono">{m.sessions_per_week} A WEEK · DAYS {m.day_positions.join(' ')} · BLOCKS OF {m.block_lengths.join('/')} WK · {m.domain.replaceAll('_', ' ').toUpperCase()}</span>
      {m.notes[0] && <span className="pb-card-note">{m.notes[0]}</span>}
    </button>
  )
}

function ProtocolCard({ p, selected, onPick, hint }: { p: ConditioningProtocolDef; selected: boolean; onPick: () => void; hint?: boolean }) {
  return (
    <button className={`pb-card ${selected ? 'on' : ''}`} onClick={onPick}>
      <span className="pb-card-title">{p.title}{hint && <span className="pb-tag mono">PAIRS</span>}</span>
      <span className="pb-card-meta mono">{p.protocol_type.toUpperCase()}{p.oa_type ? ` · ${p.oa_type.toUpperCase()}` : ''}{p.default_layout.length > 0 ? ` · ${p.default_layout.filter((x) => x.kind !== 'rest' && x.kind !== 'strength').length} SET DAYS` : ''}</span>
      {budgetLines(p).slice(0, 3).map((l) => <span key={l} className="pb-card-note">{l}</span>)}
    </button>
  )
}

function ProgramStep({ d, cat, set }: { d: PlannerDraft; cat: PlannerCatalog; set: Setter }) {
  const idx = cat.index
  const strengths = idx.ofKind('strength_module')
  const protocols = idx.ofKind('conditioning_protocol')
  const templates = idx.ofKind('template')
  const [moreProtocols, setMoreProtocols] = useState(false)
  // With none of the main templates in the catalogue, every template is a main one (nothing hides behind a button).
  const isMain = protocols.some(isMainProtocol) ? isMainProtocol : () => true
  const s = strengthOf(d, idx)
  const p = protocolOf(d, idx)
  const pairsProtocol = (x: ConditioningProtocolDef) => !!s && (s.compatible_conditioning.includes(x.key) || x.pairs_well_with.includes(s.key))
  const pairsStrength = (m: StrengthModuleDef) => !!p && (m.compatible_conditioning.includes(p.key) || p.pairs_well_with.includes(m.key))
  return (
    <>
      <Section title="ROUTE">
        <div className="pb-chips">
          <button className={`pb-chip ${d.route === 'build' ? 'on' : ''}`} onClick={() => set((x) => pickRoute(x, 'build'))}>BUILD YOUR OWN: STRENGTH + CONDITIONING</button>
          <button className={`pb-chip ${d.route === 'published' ? 'on' : ''}`} onClick={() => set((x) => pickRoute(x, 'published'))}>A PUBLISHED WEEK-BY-WEEK PROGRAM</button>
        </div>
      </Section>
      {d.route === 'build' ? (
        <>
          <Section title="STRENGTH" info="The main programs first; each has its versions. PAIRS marks those the chosen conditioning recommends.">
            <button className={`pb-card slim ${d.strengthKey == null ? 'on' : ''}`} onClick={() => set((x) => pickStrength(x, idx, null, cat.events))}>
              <span className="pb-card-title">No strength in this block</span>
            </button>
            {STRENGTH_GROUPS.map((g) => {
              const versions = versionsOf(g, strengths)
              if (versions.length === 0) return null
              return (
                <div key={g.id} className="pb-group">
                  <span className="pb-label mono">{g.label}</span>
                  <div className="pb-cards">
                    {versions.map((m) => <StrengthCard key={m.key} m={m} selected={d.strengthKey === m.key} hint={pairsStrength(m)} onPick={() => set((x) => pickStrength(x, idx, m.key, cat.events))} />)}
                  </div>
                </div>
              )
            })}
          </Section>
          <Section title="CONDITIONING" info="Budgets, not grids: the week's layout comes from the template's own days, then you move them.">
            <button className={`pb-card slim ${d.protocolKey == null ? 'on' : ''}`} onClick={() => set((x) => pickProtocol(x, idx, null))}>
              <span className="pb-card-title">No conditioning in this block</span>
            </button>
            <div className="pb-cards">
              {protocols.filter((x) => isMain(x) || x.key === d.protocolKey).map((x) => <ProtocolCard key={x.key} p={x} selected={d.protocolKey === x.key} hint={pairsProtocol(x)} onPick={() => set((y) => pickProtocol(y, idx, x.key))} />)}
            </div>
            {protocols.some((x) => !isMain(x)) && (
              <button className="btn-ghost mono" onClick={() => setMoreProtocols((v) => !v)}>{moreProtocols ? 'HIDE' : 'SHOW'} OTHER TEMPLATES ({protocols.filter((x) => !isMain(x)).length})</button>
            )}
            {moreProtocols && <div className="pb-cards">{protocols.filter((x) => !isMain(x) && x.key !== d.protocolKey).map((x) => <ProtocolCard key={x.key} p={x} selected={false} hint={pairsProtocol(x)} onPick={() => set((y) => pickProtocol(y, idx, x.key))} />)}</div>}
            {p && <ProtocolDetail p={p} idx={cat.index} />}
          </Section>
        </>
      ) : (
        <Section title="PUBLISHED PROGRAMS" info="A fixed week-by-day grid: its length and weeks come with it.">
          <div className="pb-cards">
            {templates.map((t) => (
              <button key={t.key} className={`pb-card ${d.templateKey === t.key ? 'on' : ''}`} onClick={() => set((x) => pickTemplate(x, t.key))}>
                <span className="pb-card-title">{t.title}</span>
                <span className="pb-card-meta mono">{t.grid.length} WEEKS · {t.domains.map((x) => x.replaceAll('_', ' ')).join(', ').toUpperCase()}</span>
                {t.minimums.slice(0, 2).map((m) => <span key={m} className="pb-card-note">{m}</span>)}
              </button>
            ))}
          </div>
          {templateOf(d, idx) && <TemplateVariables t={templateOf(d, idx)!} d={d} cat={cat} set={set} />}
        </Section>
      )}
    </>
  )
}

function ProtocolDetail({ p, idx }: { p: ConditioningProtocolDef; idx: PlannerCatalog['index'] }) {
  const lines = budgetLines(p)
  const named = (keys: string[]) => keys.map((k) => idx.session(k)?.title ?? k).join(', ') || '—'
  return (
    <div className="pb-detail">
      <span className="pb-label mono">{p.title.toUpperCase()}</span>
      {lines.map((l) => <span key={l} className="pb-info">{l}</span>)}
      <span className="pb-info">Suggested: LIC {named(p.suggested.lic)} · HIC {named(p.suggested.hic)}{p.suggested.wc.length > 0 ? ` · WC ${named(p.suggested.wc)}` : ''}</span>
      {p.notes.map((n) => <span key={n} className="pb-hint">{n}</span>)}
    </div>
  )
}

function TemplateVariables({ t, d, cat, set }: { t: TemplateDef; d: PlannerDraft; cat: PlannerCatalog; set: Setter }) {
  const vars = templateVariables(t)
  if (vars.length === 0) return <p className="pb-info">This program has nothing to choose before it runs.</p>
  return (
    <div className="pb-detail">
      <span className="pb-label mono">CHOICES THIS PROGRAM ASKS FOR</span>
      {vars.map((v) => {
        const strengthKind = v.kind === 'strength_module' || v.kind === 'se_module'
        const options = v.options.length > 0 ? v.options
          : strengthKind ? cat.index.ofKind('strength_module').map((m) => m.key) : cat.index.ofKind('conditioning_session').map((s) => s.key)
        const chosen = (d.variables[v.name] ?? '').split(',').map((x) => x.trim()).filter(Boolean)
        const label = (k: string) => cat.index.any(k)?.title ?? k
        return (
          <div key={v.name} className="pb-var">
            <span className="pb-card-title">${v.name}</span>
            {v.note && <span className="pb-hint">{v.note}</span>}
            {strengthKind ? (
              <select className="pb-input" value={chosen[0] ?? ''} onChange={(e) => set((x) => ({ ...x, variables: { ...x.variables, [v.name]: e.target.value } }))}>
                <option value="">Choose…</option>
                {options.map((o) => <option key={o} value={o}>{label(o)}</option>)}
              </select>
            ) : (
              <>
                <span className="pb-hint">Several sessions rotate, in this order.</span>
                <div className="pb-chips">
                  {options.map((o) => {
                    const on = chosen.includes(o)
                    return <button key={o} className={`pb-chip ${on ? 'on' : ''}`} onClick={() => set((x) => {
                      const next = on ? chosen.filter((c) => c !== o) : [...chosen, o]
                      return { ...x, variables: { ...x.variables, [v.name]: next.join(',') } }
                    })}>{label(o)}{on && chosen.length > 1 ? ` · ${chosen.indexOf(o) + 1}` : ''}</button>
                  })}
                </div>
              </>
            )}
          </div>
        )
      })}
    </div>
  )
}

// ---------------- 3 · THE WEEK ----------------

function WeekStep({ d, cat, set }: { d: PlannerDraft; cat: PlannerCatalog; set: Setter }) {
  const idx = cat.index
  if (d.route === 'published') {
    const t = templateOf(d, idx)
    if (!t) return <p className="state-empty">CHOOSE A PROGRAM FIRST.</p>
    const positions = dayPositions(d, idx)
    return (
      <Section title="WHICH WEEKDAY EACH TRAINING DAY FALLS ON" info="The program numbers its days; put each on a weekday. Unset days follow the start date.">
        <div className="pb-row">
          {positions.map((pos) => (
            <Field key={pos} label={`DAY ${pos}`}>
              <select className="pb-input" value={weekdayFor(d, pos)} onChange={(e) => set((x) => ({ ...x, weekdays: { ...x.weekdays, [pos]: Number(e.target.value) as Weekday } }))}>
                {[1, 2, 3, 4, 5, 6, 7].map((w) => <option key={w} value={w}>{weekdayShort(w as Weekday)}</option>)}
              </select>
            </Field>
          ))}
        </div>
      </Section>
    )
  }
  const s = strengthOf(d, idx)
  const p = protocolOf(d, idx)
  if (!s && !p) return <p className="state-empty">CHOOSE A STRENGTH PROGRAM OR A CONDITIONING TEMPLATE FIRST.</p>
  const warnings = weekWarnings(d, idx)
  const placed = new Map(d.layout.filter((x) => x.strengthSession != null).map((x) => [x.strengthSession!, x.weekday]))
  return (
    <>
      <Section title="HOW THE TWO SHARE THE WEEK" info="Each day holds at most one strength session and one conditioning session; both on one day is a two-a-day (the conditioning takes the second time). Warnings explain clashes; they never block.">
        <div className="pb-week">
          {d.layout.map((day) => (
            <DayColumn key={day.weekday} day={day} s={s} p={p} placed={placed} onChange={(f) => set((x) => setDay(x, day.weekday, f))} />
          ))}
        </div>
        <div className="pb-row">
          <button className="btn-ghost mono" onClick={() => set((x) => withLayoutReset(x, idx))}>RESET TO THE SUGGESTED WEEK</button>
          {p && p.default_layout.length === 0 && (
            <Field label="CONDITIONING DAYS TO SUGGEST">
              <Num value={d.conditioningPerWeek} min={0} max={7} width={64} placeholder="auto" onChange={(v) => set((x) => withLayoutReset({ ...x, conditioningPerWeek: v }, idx))} />
            </Field>
          )}
        </div>
      </Section>
      <Section title="CHECKS"><Warnings list={warnings} /></Section>
    </>
  )
}

function DayColumn({ day, s, p, placed, onChange }: { day: DayPlan; s: StrengthModuleDef | null; p: ConditioningProtocolDef | null; placed: Map<number, Weekday>; onChange: (f: (d: DayPlan) => DayPlan) => void }) {
  const kinds = ['lic', 'hic', 'wc']
  const two = day.strengthSession != null && day.conditioning != null
  return (
    <div className={`pb-day ${day.strengthSession == null && !day.conditioning ? 'rest' : ''}`}>
      <span className="pb-day-name mono">{weekdayShort(day.weekday).toUpperCase()}</span>
      {s && (
        <Field label="STRENGTH">
          <select className="pb-input" value={day.strengthSession ?? ''} onChange={(e) => onChange((x) => ({ ...x, strengthSession: e.target.value === '' ? null : Number(e.target.value) }))}>
            <option value="">—</option>
            {s.sessions.map((sd, i) => (
              <option key={sd.id} value={i}>{sd.label}{placed.has(i) && placed.get(i) !== day.weekday ? ` (on ${weekdayShort(placed.get(i)!)})` : ''}</option>
            ))}
          </select>
        </Field>
      )}
      {s && day.strengthSession != null && <span className="pb-hint">{s.sessions[day.strengthSession]?.slots.map((id) => s.slots.find((x) => x.id === id)?.standard ?? id).join(' · ')}</span>}
      {p && (
        <Field label="CONDITIONING">
          <select className="pb-input" value={day.conditioning?.kind ?? ''} onChange={(e) => onChange((x) => ({ ...x, conditioning: e.target.value === '' ? null : { kind: e.target.value, minutes: x.conditioning?.minutes ?? null } }))}>
            <option value="">—</option>
            {kinds.map((k) => <option key={k} value={k}>{k.toUpperCase()}</option>)}
          </select>
        </Field>
      )}
      {day.conditioning && (
        <Field label="MINUTES" hint="Empty: the template's budget share.">
          <Num value={day.conditioning.minutes?.min ?? null} min={5} max={600} step={5} width={72} placeholder="auto"
            onChange={(v) => onChange((x) => ({ ...x, conditioning: x.conditioning ? { ...x.conditioning, minutes: v == null ? null : { min: v, max: v } } : null }))} />
        </Field>
      )}
      <span className="pb-day-foot mono">{two ? 'TWO-A-DAY' : day.strengthSession == null && !day.conditioning ? 'REST' : ''}</span>
    </div>
  )
}

// ---------------- 4 · THE WEEKS ----------------

interface WeekPlanRow { week: number; kind: WeekKind; start: string; label: string; progresses: boolean }

/** Where each calendar week sits in the strength cycle: block number, week within the block, and where the maxes step up. */
function cycleRows(d: PlannerDraft, len: number, hasStrength: boolean): WeekPlanRow[] {
  let blockPos = 0
  let block = 1
  let skip = false
  let lead = 0
  return d.timeline.map((k, i) => {
    const start = plusDays(d.startDate, 7 * i)
    if (!hasStrength) return { week: i + 1, kind: k, start, label: k === 'normal' ? '' : k.toUpperCase(), progresses: false }
    if (k === 'normal') {
      let progresses = false
      if (blockPos === len) { blockPos = 0; block++; progresses = !skip }
      skip = false
      blockPos++
      return { week: i + 1, kind: k, start, label: `BLOCK ${block} · WEEK ${blockPos} OF ${len}`, progresses: progresses && d.projectProgression }
    }
    if (k === 'lead') { lead++; return { week: i + 1, kind: k, start, label: `EARLY: OPENING WEEK ${((lead - 1) % Math.max(1, len - 1)) + 1} AGAIN, BEFORE THE FULL CYCLE`, progresses: false } }
    if (k === 'test') skip = true
    return { week: i + 1, kind: k, start, label: k === 'deload' ? 'DELOAD: NOT COUNTED, PAUSES THE CYCLE' : 'TEST: MAXES, NO PROGRESSION AFTER', progresses: false }
  })
}

function WeeksStep({ d, cat, set }: { d: PlannerDraft; cat: PlannerCatalog; set: Setter }) {
  const idx = cat.index
  const s = strengthOf(d, idx)
  const len = blockLength(d, idx)
  const rows = cycleRows(d, len, !!s)
  const event = cat.events.find((e) => e.id === d.targetEventId)
  return (
    <>
      <Section title="WHAT EACH WEEK IS" info={s ? `${s.title} runs in blocks of ${len} weeks. A deload week is invisible: it does not count and pauses the cycle. After a test week the maxes are not stepped up. An early week repeats the cycle's opening weeks before the first full cycle: toward a race, the weeks that do not make a whole cycle become early weeks, so the last cycle peaks right before the taper.` : 'Without a strength program every week is conditioning; a deload eases the low-intensity minutes and drops hard sessions.'}>
        <table className="pb-table">
          <thead><tr><th>WEEK</th><th>FROM</th><th>KIND</th><th>IN THE CYCLE</th><th /></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.week} className={`pb-week-${r.kind}`}>
                <td className="mono">{r.week}</td>
                <td>{dateShort(r.start)}</td>
                <td>
                  <div className="pb-chips">
                    {WEEK_KINDS.map((k) => <button key={k} className={`pb-chip small ${r.kind === k ? 'on' : ''}`} onClick={() => set((x) => setWeek(x, r.week - 1, k))}>{k === 'lead' ? 'EARLY' : k.toUpperCase()}</button>)}
                  </div>
                </td>
                <td className="mono pb-muted">{r.label}</td>
                <td className="mono">{r.progresses ? <span className="pb-tag">MAXES STEP UP</span> : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="pb-row">
          <Field label="LENGTH, WEEKS"><Num value={d.weeks} min={1} max={52} width={64} onChange={(v) => v != null && set((x) => changeWeeks(x, idx, v))} /></Field>
          <label className="pb-check"><input type="checkbox" checked={d.startWithTest} onChange={(e) => set((x) => withTimelineReset({ ...x, startWithTest: e.target.checked }, idx, cat.events))} /> Start with a test week</label>
          <button className="btn-ghost mono" onClick={() => set((x) => withTimelineReset(x, idx, cat.events))}>RESET TO THE DEFAULT RHYTHM</button>
        </div>
        <p className="pb-info">{countedWeeks(d.timeline)} counted weeks over {d.timeline.length} calendar weeks{event ? `, tapering into ${event.name} (${dateShort(event.eventDate)})` : ''}.</p>
      </Section>
    </>
  )
}

// ---------------- 5 · DETAILS ----------------

function DetailsStep({ d, cat, set }: { d: PlannerDraft; cat: PlannerCatalog; set: Setter }) {
  const idx = cat.index
  const modules = strengthModules(d, idx)
  const p = d.route === 'build' ? protocolOf(d, idx) : null
  const exercises = exercisesInPlay(d, cat)
  return (
    <>
      {modules.length === 0 && !p && <p className="state-empty">NOTHING TO SET YET: CHOOSE A PROGRAM.</p>}
      {modules.map((m) => <ModuleDetails key={m.key} m={m} d={d} set={set} weeks={d.route === 'build' ? d.weeks : 99} idx={idx} />)}
      {exercises.length > 0 && (
        <Section title="MAXES" info="The newest of a recorded max and an estimate from your logged sets (the last 12 weeks) is used. A value typed here wins for this block and is recorded as a manual max when the block is created.">
          <table className="pb-table">
            <thead><tr><th>EXERCISE</th><th>1RM</th><th>FROM</th><th>OVERRIDE 1RM</th><th>MAX REPS</th><th>OVERRIDE REPS</th><th>TRAINING MAX</th></tr></thead>
            <tbody>
              {exercises.map((e) => {
                const manual = d.manualMaxes[e.key] ?? {}
                const missing = e.entry?.oneRmKg == null
                return (
                  <tr key={e.key} className={missing ? 'pb-missing' : ''}>
                    <td>{e.name}</td>
                    <td className="mono">{e.entry?.oneRmKg != null ? `${fmt(Math.round(e.entry.oneRmKg * 10) / 10)} kg` : 'none'}</td>
                    <td className="pb-muted">{e.manual ? `typed here (was: ${e.basedOn})` : e.basedOn}</td>
                    <td><Num value={manual.oneRmKg ?? null} step={0.5} width={80} placeholder="kg" onChange={(v) => set((x) => ({ ...x, manualMaxes: cleanMax({ ...x.manualMaxes, [e.key]: { ...x.manualMaxes[e.key], oneRmKg: v } }, e.key) }))} /></td>
                    <td className="mono">{e.entry?.maxReps ?? '—'}</td>
                    <td><Num value={manual.maxReps ?? null} step={1} width={64} placeholder="reps" onChange={(v) => set((x) => ({ ...x, manualMaxes: cleanMax({ ...x.manualMaxes, [e.key]: { ...x.manualMaxes[e.key], maxReps: v } }, e.key) }))} /></td>
                    <td className="mono">{e.entry?.trainingMaxKg != null ? `${fmt(e.entry.trainingMaxKg)} kg` : '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </Section>
      )}
      {modules.length > 0 && (
        <Section title="PROGRESSION" info="Between blocks of the same program the maxes step up by the program's own increments (the bottom of the book's range, on your plate step, never below one step).">
          <div className="pb-chips">
            <button className={`pb-chip ${d.projectProgression ? 'on' : ''}`} onClick={() => set((x) => ({ ...x, projectProgression: true }))}>PROJECT MAX INCREASES</button>
            <button className={`pb-chip ${!d.projectProgression ? 'on' : ''}`} onClick={() => set((x) => ({ ...x, projectProgression: false }))}>USE TODAY'S MAXES THROUGHOUT</button>
          </div>
          {modules.filter((m) => m.progression).map((m) => {
            const step = loadStepKg(d.equipment.platesKg)
            const pr = m.progression!
            return <p key={m.key} className="pb-info">{m.title}: upper body +{fmt(progressionIncrementKg(pr.upper, pr.unit, step))} kg, lower body +{fmt(progressionIncrementKg(pr.lower, pr.unit, step))} kg a block (book: {rangeText(pr.upper)} / {rangeText(pr.lower)} {pr.unit}).</p>
          })}
        </Section>
      )}
      {p && <ConditioningRotation p={p} d={d} set={set} idx={idx} />}
    </>
  )
}

function cleanMax(all: PlannerDraft['manualMaxes'], key: string): PlannerDraft['manualMaxes'] {
  const m = all[key]
  if (m && m.oneRmKg == null && m.maxReps == null) { const next = { ...all }; delete next[key]; return next }
  return all
}

function ModuleDetails({ m, d, set, weeks, idx }: { m: StrengthModuleDef; d: PlannerDraft; set: Setter; weeks: number; idx: PlannerCatalog['index'] }) {
  const c = choiceFor(d, m.key)
  const variant = m.variants.find((v) => v.key === c.variant) ?? m.variants[0]
  const lengths = m.block_lengths.filter((n) => n <= (variant?.weeks.length ?? 99))
  const slots = slotsToChoose(m)
  return (
    <Section title={m.title.toUpperCase()} info={m.notes.join(' ') || undefined}>
      <div className="pb-row">
        {m.variants.length > 1 && (
          <Field label="VERSION">
            <select className="pb-input" value={variant?.key} onChange={(e) => set((x) => updateChoice(x, m.key, (cc) => ({ ...cc, variant: e.target.value })))}>
              {m.variants.map((v) => <option key={v.key} value={v.key}>{v.title} ({v.weeks.length} wk)</option>)}
            </select>
          </Field>
        )}
        {lengths.length > 1 && (
          <Field label="BLOCK LENGTH" hint={weeks < 99 ? 'The weeks step keeps its pattern; reset it there to follow a new length.' : undefined}>
            <select className="pb-input" value={c.blockLength ?? lengths[lengths.length - 1]} onChange={(e) => set((x) => updateChoice(x, m.key, (cc) => ({ ...cc, blockLength: Number(e.target.value) })))}>
              {lengths.map((n) => <option key={n} value={n}>{n} weeks</option>)}
            </select>
          </Field>
        )}
      </div>
      {variant?.note && <p className="pb-info">{variant.note}</p>}
      {m.options.length > 0 && (
        <details className="pb-more"><summary className="mono">THE PROGRAM'S OPTIONS ({m.options.length})</summary>
          {m.options.map((o) => <p key={o.id} className="pb-info"><b>{o.title}</b>: {o.description}</p>)}
        </details>
      )}
      <table className="pb-table">
        <thead><tr><th>SLOT</th><th>ROLE</th><th>EXERCISE</th></tr></thead>
        <tbody>
          {slots.map((slot) => {
            const chosen = c.exercises?.[slot.id] ?? []
            const cluster = isCluster(m, slot)
            const options = [...new Set([slot.standard, ...slot.alternates, ...chosen])]
            const setChosen = (names: string[]) => set((x) => updateChoice(x, m.key, (cc) => ({ ...cc, exercises: { ...(cc.exercises ?? {}), [slot.id]: names } })))
            return (
              <tr key={slot.id}>
                <td className="mono">{slot.id}{slot.optional ? ' · optional' : ''}</td>
                <td className="pb-muted">{slot.role}{slot.weighted_calisthenics ? ' · weighted' : ''}{slot.pick ? ` · pick ${rangeText(slot.pick)}` : ''}</td>
                <td>
                  {cluster ? (
                    <div className="pb-chips">
                      {options.map((o) => {
                        const on = chosen.includes(o)
                        return <button key={o} className={`pb-chip small ${on ? 'on' : ''}`} onClick={() => setChosen(on ? chosen.filter((x) => x !== o) : [...chosen, o])}>{o}</button>
                      })}
                      <CustomExercise onAdd={(n) => setChosen([...chosen, n])} />
                    </div>
                  ) : (
                    <div className="pb-row tight">
                      <select className="pb-input" value={chosen[0] ?? slot.standard} onChange={(e) => setChosen(e.target.value === slot.standard ? [] : [e.target.value])}>
                        {options.map((o) => <option key={o} value={o}>{o}{o === slot.standard ? ' (the book)' : ''}</option>)}
                      </select>
                      <CustomExercise onAdd={(n) => setChosen([n])} />
                    </div>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <div className="pb-detail">
        <span className="pb-label">ACCESSORY WORK</span>
        <p className="pb-info">Exercises added after the program's own, in every session or in one. Full in normal and peak weeks, half the sets in deload and taper weeks, left out of test weeks. A load as a % of the 1RM needs a max (MAXES, below).</p>
        <AccessoryTables sessions={m.sessions.map((x) => ({ id: x.id, label: x.label }))} value={c.accessories ?? {}}
          onChange={(acc) => set((x) => updateChoice(x, m.key, (cc) => ({ ...cc, accessories: acc })))} />
      </div>
      <WeekByWeek m={m} variantKey={variant?.key} idx={idx} />
    </Section>
  )
}

function CustomExercise({ onAdd }: { onAdd: (name: string) => void }) {
  const [v, setV] = useState('')
  return (
    <span className="pb-row tight">
      <input className="pb-input" style={{ width: 130 }} placeholder="Another exercise" value={v} onChange={(e) => setV(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter' && v.trim()) { onAdd(v.trim()); setV('') } }} />
      <button className="pb-chip small" disabled={!v.trim()} onClick={() => { onAdd(v.trim()); setV('') }}>USE</button>
    </span>
  )
}

/** The version's week-by-week prescription, as written in the program (before maxes). */
function WeekByWeek({ m, variantKey, idx }: { m: StrengthModuleDef; variantKey?: string; idx: PlannerCatalog['index'] }) {
  void idx
  const v = m.variants.find((x) => x.key === variantKey) ?? m.variants[0]
  if (!v) return null
  const loadWords = (l: { kind: string; value?: number; min_rm?: number; max_rm?: number; kg?: number; rpe?: number }) =>
    l.kind === 'pct_1rm' ? `${fmt(l.value ?? 0)}%` : l.kind === 'pct_tm' ? `${fmt(l.value ?? 0)}% TM` : l.kind === 'pct_max_reps' ? `${fmt(l.value ?? 0)}% max reps`
      : l.kind === 'work_up_rm' ? `work up to ${l.min_rm}-${l.max_rm}RM` : l.kind === 'fixed' ? `${fmt(l.kg ?? 0)} kg` : l.kind === 'rpe' ? `RPE ${fmt(l.rpe ?? 0)}` : l.kind === 'bodyweight' ? 'bodyweight' : ''
  return (
    <details className="pb-more"><summary className="mono">THE VERSION WEEK BY WEEK ({v.weeks.length} WEEKS)</summary>
      <table className="pb-table">
        <thead><tr><th>WEEK</th>{m.sessions.map((s) => <th key={s.id}>{s.label.toUpperCase()}</th>)}</tr></thead>
        <tbody>
          {v.weeks.map((w) => (
            <tr key={w.week}>
              <td className="mono">{w.week}{w.kind !== 'normal' ? ` · ${w.kind}` : ''}</td>
              {m.sessions.map((s) => {
                const ws = w.sessions.find((x) => x.session === s.id)
                return <td key={s.id} className="pb-muted">{ws ? ws.items.map((it) => `${it.slot} ${[it.sets, it.reps].filter(Boolean).map((r) => rangeText(r as NumRange)).join('x')} ${loadWords(it.load)}`).join(' · ') : '—'}</td>
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </details>
  )
}

function ConditioningRotation({ p, d, set, idx }: { p: ConditioningProtocolDef; d: PlannerDraft; set: Setter; idx: PlannerCatalog['index'] }) {
  const kinds = [...new Set(d.layout.map((x) => x.conditioning?.kind).filter((k): k is string => !!k))]
  const all = idx.ofKind('conditioning_session')
  const describe = (s: ConditioningSessionDef) => [
    s.prescription.minutes ? `${rangeText(s.prescription.minutes)} min` : null, s.prescription.rounds ? `${rangeText(s.prescription.rounds)} rounds` : null,
    s.prescription.distance_km ? `${rangeText(s.prescription.distance_km)} km` : null, s.structure.type, s.intensity?.cue ?? null,
  ].filter(Boolean).join(' · ')
  return (
    <Section title="CONDITIONING SESSIONS" info="Each kind rotates through its list week by week, in this order. Empty: the template's own suggestions.">
      {kinds.length === 0 && <p className="pb-info">No conditioning days in the week yet.</p>}
      {kinds.map((k) => {
        const own = d.conditioning[k] ?? []
        const list = own.length > 0 ? own : (k === 'lic' ? p.suggested.lic : k === 'hic' ? p.suggested.hic : p.suggested.wc)
        const setList = (l: string[]) => set((x) => ({ ...x, conditioning: { ...x.conditioning, [k]: l } }))
        const candidates = all.filter((s) => s.category === k || (k === 'wc' && s.doubles_as_wc)).filter((s) => !list.includes(s.key))
        const others = all.filter((s) => !candidates.includes(s) && !list.includes(s.key))
        return (
          <div key={k} className="pb-detail">
            <span className="pb-label mono">{k.toUpperCase()}{own.length === 0 ? ' · THE TEMPLATE\'S SUGGESTIONS' : ''}</span>
            <ol className="pb-rotation">
              {list.map((key, i) => {
                const s = idx.session(key)
                return (
                  <li key={key + i}>
                    <span className="pb-card-title">{s?.title ?? key}</span>
                    <span className="pb-hint">{s ? describe(s) : 'not in the catalogue'}</span>
                    <span className="pb-row tight">
                      <button className="pb-chip small" disabled={i === 0} onClick={() => { const l = [...list]; [l[i - 1], l[i]] = [l[i], l[i - 1]]; setList(l) }}>↑</button>
                      <button className="pb-chip small" disabled={i === list.length - 1} onClick={() => { const l = [...list]; [l[i + 1], l[i]] = [l[i], l[i + 1]]; setList(l) }}>↓</button>
                      <button className="pb-chip small" onClick={() => setList(list.filter((_, j) => j !== i))}>REMOVE</button>
                    </span>
                  </li>
                )
              })}
            </ol>
            <select className="pb-input" value="" onChange={(e) => e.target.value && setList([...list, e.target.value])}>
              <option value="">Add a session…</option>
              <optgroup label={k.toUpperCase()}>{candidates.map((s) => <option key={s.key} value={s.key}>{s.title}</option>)}</optgroup>
              {others.length > 0 && <optgroup label="OTHER KINDS">{others.map((s) => <option key={s.key} value={s.key}>{s.title} ({s.category.toUpperCase()})</option>)}</optgroup>}
            </select>
            {own.length > 0 && <button className="btn-ghost mono" onClick={() => set((x) => { const c = { ...x.conditioning }; delete c[k]; return { ...x, conditioning: c } })}>BACK TO THE TEMPLATE'S SUGGESTIONS</button>}
          </div>
        )
      })}
    </Section>
  )
}

// ---------------- 6 · REVIEW ----------------

type ReviewView = 'calendar' | 'sessions' | 'weeks' | 'maxes' | 'warnings'

function ReviewStep({ d, block, existing, onSelect, selected }: { d: PlannerDraft; block: GeneratedBlock | null; existing: ExistingBlock[]; onSelect: (n: number | null) => void; selected: number | null }) {
  const [view, setView] = useState<ReviewView>('calendar')
  const [filter, setFilter] = useState<string>('all')
  if (!block) return <p className="state-empty">NOTHING TO REVIEW YET.</p>
  const overlaps = existing.filter((b) => b.start_date <= block.endDate && b.end_date >= d.startDate)
  const views: { id: ReviewView; label: string }[] = [
    { id: 'calendar', label: 'CALENDAR' }, { id: 'sessions', label: `SESSIONS · ${block.sessions.length}` }, { id: 'weeks', label: 'WEEK BY WEEK' },
    { id: 'maxes', label: `MAX INCREASES · ${block.progressions.length}` }, { id: 'warnings', label: `WARNINGS · ${block.warnings.length}` },
  ]
  const startW = weekdayOf(d.startDate)
  return (
    <>
      {overlaps.length > 0 && <p className="pb-warn">Overlaps {overlaps.map((b) => `${b.name} (${dateShort(b.start_date)} → ${dateShort(b.end_date)})`).join(', ')}: both blocks' sessions would be planned.</p>}
      <div className="pb-chips">{views.map((v) => <button key={v.id} className={`pb-chip ${view === v.id ? 'on' : ''}`} onClick={() => setView(v.id)}>{v.label}</button>)}</div>
      {view === 'calendar' && (
        <div className="pb-cal">
          <div className="pb-cal-row head"><span />{[0, 1, 2, 3, 4, 5, 6].map((i) => <span key={i} className="mono">{weekdayShort(plusWeekday(startW, i)).toUpperCase()}</span>)}</div>
          {Array.from({ length: block.calendarWeeks }, (_, w) => {
            const kind = d.route === 'build' ? d.timeline[w] : undefined
            return (
              <div key={w} className={`pb-cal-row ${kind ? `pb-week-${kind}` : ''}`}>
                <span className="mono pb-cal-week">{w + 1}{kind && kind !== 'normal' ? ` ${kind[0].toUpperCase()}` : ''}<br /><span className="pb-muted">{dateShort(plusDays(d.startDate, 7 * w))}</span></span>
                {[0, 1, 2, 3, 4, 5, 6].map((i) => {
                  const date = plusDays(d.startDate, 7 * w + i)
                  const here = block.sessions.filter((s) => s.date === date)
                  return (
                    <span key={i} className="pb-cal-cell">
                      {here.map((s) => (
                        <button key={s.sequenceNo} className={`pb-sess ${domainClass(s)} ${selected === s.sequenceNo ? 'on' : ''}`} onClick={() => onSelect(s.sequenceNo)} title={s.title}>
                          {s.title.replace(/^[^:]+: /, '')}
                        </button>
                      ))}
                    </span>
                  )
                })}
              </div>
            )
          })}
          <p className="pb-hint">Click a session for its full prescription. Colours: strength, conditioning, test, deload.</p>
        </div>
      )}
      {view === 'sessions' && (
        <>
          <div className="pb-chips">
            {['all', ...new Set(block.sessions.map((s) => s.domain))].map((f) => <button key={f} className={`pb-chip small ${filter === f ? 'on' : ''}`} onClick={() => setFilter(f)}>{f.replaceAll('_', ' ').toUpperCase()}</button>)}
          </div>
          <table className="pb-table">
            <thead><tr><th>#</th><th>WEEK</th><th>DATE</th><th>TIME</th><th>SESSION</th><th>PRESCRIPTION</th></tr></thead>
            <tbody>
              {block.sessions.filter((s) => filter === 'all' || s.domain === filter).map((s) => (
                <tr key={s.sequenceNo} className={selected === s.sequenceNo ? 'pb-sel' : ''} onClick={() => onSelect(s.sequenceNo)}>
                  <td className="mono">{s.sequenceNo}</td><td className="mono">{s.weekIndex}</td><td>{dateShort(s.date)}</td>
                  <td className="mono">{s.startTime ?? ''}</td><td>{s.title}</td>
                  <td className="pb-muted">{s.items.length > 0 ? s.items.map(itemText).join(' · ') : s.conditioning ? `${s.conditioning.label}${s.conditioning.minutes ? ` · ${rangeText(s.conditioning.minutes)} min` : ''}` : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      {view === 'weeks' && (
        <table className="pb-table">
          <thead><tr><th>WEEK</th><th>FROM</th><th>KIND</th><th>SESSIONS</th><th>STRENGTH</th><th>CONDITIONING MIN</th><th>TESTS</th></tr></thead>
          <tbody>
            {weekStats(block, d.timeline).map((w) => (
              <tr key={w.week} className={`pb-week-${w.kind}`}>
                <td className="mono">{w.week}</td><td>{dateShort(plusDays(d.startDate, 7 * (w.week - 1)))}</td><td className="mono">{w.kind.toUpperCase()}</td>
                <td className="mono">{w.sessions}</td><td className="mono">{w.strength}</td><td className="mono">{w.conditioningMin || '—'}</td><td className="mono">{w.tests || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {view === 'maxes' && (
        block.progressions.length === 0 ? <p className="pb-info">No projected increases (one block, progression off, or no progression in the program).</p> : (
          <table className="pb-table">
            <thead><tr><th>FROM WEEK</th><th>EXERCISE</th><th>1RM</th><th>BECOMES</th><th /></tr></thead>
            <tbody>
              {block.progressions.map((p, i) => (
                <tr key={i}><td className="mono">{p.weekIndex}</td><td>{p.exercise}</td><td className="mono">{fmt(Math.round(p.fromKg * 10) / 10)} kg</td><td className="mono">{fmt(Math.round(p.toKg * 10) / 10)} kg</td><td className="pb-muted">+{fmt(Math.round((p.toKg - p.fromKg) * 100) / 100)} kg ({fmt(Math.round(kgToLb(p.toKg - p.fromKg) * 10) / 10)} lb)</td></tr>
              ))}
            </tbody>
          </table>
        )
      )}
      {view === 'warnings' && <Warnings list={block.warnings} />}
    </>
  )
}

// ---------------- the preview ----------------

function Preview({ d, cat, block, genError, existing, selected, onSelect, creating, created, message, onCreate, onStartOver }: {
  d: PlannerDraft; cat: PlannerCatalog; block: GeneratedBlock | null; genError: string | null; existing: ExistingBlock[]; selected: number | null
  onSelect: (n: number | null) => void; creating: boolean; created: { id: number; name: string } | null; message: string | null; onCreate: () => void; onStartOver: () => void
}) {
  const idx = cat.index
  const blocker = canCreate(d, block)
  const missing = missingMaxes(d, cat)
  const session = block?.sessions.find((s) => s.sequenceNo === selected) ?? null
  const domains = useMemo(() => {
    if (!block) return []
    const counts = new Map<string, number>()
    for (const s of block.sessions) if (s.domain !== 'recovery' && s.workKind !== 'test') counts.set(s.domain, (counts.get(s.domain) ?? 0) + 1)
    return [...counts].sort((a, b) => b[1] - a[1])
  }, [block])
  if (created) {
    return (
      <div className="pb-pv">
        <h3 className="pb-section-title mono">CREATED</h3>
        <p className="pb-info">{created.name} is planned: its sessions are on the phone's Plan › Training now, and on the calendar once the phone syncs.</p>
        <button className="pb-primary mono" onClick={onStartOver}>PLAN ANOTHER BLOCK</button>
      </div>
    )
  }
  return (
    <div className="pb-pv">
      <h3 className="pb-section-title mono">{(d.name.trim() || (d.route === 'published' ? templateOf(d, idx)?.title : [strengthOf(d, idx)?.title, protocolOf(d, idx)?.title].filter(Boolean).join(' + ')) || 'NEW BLOCK').toUpperCase()}</h3>
      {genError && <p className="pb-warn">The engine could not build this: {genError}</p>}
      {block ? (
        <>
          <div className="pb-tiles">
            <div className="pb-tile"><span className="pb-label mono">DATES</span><span>{dateShort(d.startDate)} → {dateShort(block.endDate)}</span></div>
            <div className="pb-tile"><span className="pb-label mono">WEEKS</span><span>{block.calendarWeeks} · {block.countedWeeks} counted</span></div>
            <div className="pb-tile"><span className="pb-label mono">SESSIONS</span><span>{block.sessions.length}</span></div>
            <div className="pb-tile"><span className="pb-label mono">STEP-UPS</span><span>{block.progressions.length}</span></div>
          </div>
          <div className="pb-domains">
            {domains.map(([dom, n], i) => <span key={dom} className="pb-dom"><b className="mono">{i === 0 ? 'PRIMARY' : i === 1 ? 'SECONDARY' : 'ALSO'}</b> {dom.replaceAll('_', ' ')} · {n}</span>)}
          </div>
          <MiniWeeks block={block} timeline={d.route === 'build' ? d.timeline : []} />
          {missing.length > 0 && <p className="pb-warn">No 1RM for {missing.join(', ')}: their loads stay "needs a max" (DETAILS › MAXES).</p>}
          {block.warnings.length > 0 && <p className="pb-warn">{block.warnings.length} warning{block.warnings.length === 1 ? '' : 's'}: {block.warnings[0]}</p>}
        </>
      ) : <p className="pb-info">Choose a program to see the block.</p>}

      {session && <SessionDetail s={session} onClose={() => onSelect(null)} />}

      <div className="pb-create">
        {blocker && <p className="pb-hint">{blocker}</p>}
        {message && <p className="pb-warn">{message}</p>}
        <button className="pb-primary mono" disabled={!!blocker || creating} onClick={onCreate}>{creating ? 'CREATING…' : 'CREATE BLOCK'}</button>
        <p className="pb-hint">Writes the block and its {block?.sessions.length ?? 0} planned sessions, exactly as the phone does. {(() => { const n = existing.filter((b) => b.end_date >= localToday()).length; return `${n} block${n === 1 ? '' : 's'} planned or under way.` })()}</p>
      </div>
    </div>
  )
}

function MiniWeeks({ block, timeline }: { block: GeneratedBlock; timeline: WeekKind[] }) {
  const stats = weekStats(block, timeline)
  const most = Math.max(1, ...stats.map((s) => s.sessions))
  return (
    <div className="pb-mini" aria-label="Sessions per week">
      {stats.map((s) => (
        <span key={s.week} className={`pb-mini-bar pb-week-${s.kind}`} title={`Week ${s.week}: ${s.sessions} sessions (${s.kind})`} style={{ height: `${Math.max(8, (s.sessions / most) * 48)}px` }} />
      ))}
    </div>
  )
}

function SessionDetail({ s, onClose }: { s: GeneratedSession; onClose: () => void }) {
  return (
    <div className="pb-sdetail">
      <div className="pb-row tight"><span className="pb-label mono">SESSION {s.sequenceNo} · WEEK {s.weekIndex}</span><button className="pb-chip small" onClick={onClose}>CLOSE</button></div>
      <span className="pb-card-title">{s.title}</span>
      <span className="pb-hint">{dateLong(s.date)}{s.startTime ? ` · ${s.startTime}` : ''}{s.durationMin ? ` · ${s.durationMin} min` : ''} · {s.domain.replaceAll('_', ' ')} · {s.workKind}</span>
      {s.items.length > 0 && (
        <table className="pb-table">
          <thead><tr><th>EXERCISE</th><th>SETS × REPS</th><th>LOAD</th></tr></thead>
          <tbody>
            {s.items.map((i, n) => (
              <tr key={n}>
                <td>{i.exercise}{i.optional ? ' (optional)' : ''}<br /><span className="pb-muted mono">{i.slot} · {i.role}{i.technique ? ` · ${i.technique}` : ''}</span></td>
                <td className="mono">{rangeOrDash(i.sets)} × {rangeOrDash(i.reps)}</td>
                <td className="mono">{loadText(i.load) || '—'}{i.load.kind === 'barbell' ? <><br /><span className="pb-muted">{fmt(i.load.pct)}% → {fmt(Math.round(i.load.targetKg * 10) / 10)} kg</span></> : null}{i.projected ? <><br /><span className="pb-tag">PROJECTED MAX</span></> : null}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {s.items.some((i) => i.note) && s.items.filter((i) => i.note).map((i, n) => <span key={n} className="pb-hint">{i.exercise}: {i.note}</span>)}
      {s.conditioning && (
        <span className="pb-info">{s.conditioning.label}{s.conditioning.category ? ` · ${s.conditioning.category.toUpperCase()}` : ''}{s.conditioning.minutes ? ` · ${rangeText(s.conditioning.minutes)} min` : ''}</span>
      )}
      {s.notes.map((n) => <span key={n} className="pb-hint">{n}</span>)}
    </div>
  )
}

// ---------------- blocks under way: end one early ----------------

function RunningBlocks({ blocks, cat, onEnded }: { blocks: ExistingBlock[]; cat: PlannerCatalog; onEnded: (nextStart: string) => void }) {
  const today = localToday()
  const running = blocks.filter((b) => (b.status === 'scheduled' || b.status === 'active') && b.end_date > today)
  const [open, setOpen] = useState<{ id: number; what: 'end' | 'accessories' } | null>(null)
  const [done, setDone] = useState<string | null>(null)
  if (running.length === 0) return null
  return (
    <Section title="BLOCKS UNDER WAY" info="The block you are training now. A new block starts the day after the latest one ends. To change course (more training now, the peak nearer the race), end the current block early: its later sessions are set aside, not deleted. ACCESSORIES adds extra work to the sessions still ahead.">
      {running.map((b) => (
        <div key={b.id} className="pb-detail">
          <div className="pb-row tight" style={{ justifyContent: 'space-between' }}>
            <span><b>{b.name}</b> <span className="pb-muted mono">{dateShort(b.start_date)} → {dateShort(b.end_date)} · {b.status.toUpperCase()}{b.origin && b.origin !== 'generated' ? ' · RECORDED' : ''}</span></span>
            {open?.id !== b.id && (
              <span className="pb-row tight">
                {b.origin === 'generated' && <button className="btn-ghost mono" onClick={() => { setDone(null); setOpen({ id: b.id, what: 'accessories' }) }}>ACCESSORIES</button>}
                <button className="btn-ghost mono" onClick={() => { setDone(null); setOpen({ id: b.id, what: 'end' }) }}>END EARLY</button>
              </span>
            )}
          </div>
          {open?.id === b.id && open.what === 'end' && <EndEarly id={b.id} cat={cat} onCancel={() => setOpen(null)} onEnded={(last) => { setOpen(null); onEnded(plusDays(last, 1)) }} />}
          {open?.id === b.id && open.what === 'accessories' && <BlockAccessories id={b.id} cat={cat} onCancel={() => setOpen(null)} onDone={(n) => { setOpen(null); setDone(`${b.name}: accessory work applied to ${n} session${n === 1 ? '' : 's'} from today.`) }} />}
        </div>
      ))}
      {done && <p className="pb-ok">{done}</p>}
    </Section>
  )
}

function BlockAccessories({ id, cat, onCancel, onDone }: { id: number; cat: PlannerCatalog; onCancel: () => void; onDone: (sessions: number) => void }) {
  const [b, setB] = useState<BlockForEnding | null>(null)
  const [acc, setAcc] = useState<Record<string, Accessory[]>>({})
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => {
    let live = true
    loadBlockForEnding(id).then((x) => { if (live) { setB(x); setAcc(x.choice.accessories ?? {}) } }).catch((e) => setErr(e instanceof Error ? e.message : String(e)))
    return () => { live = false }
  }, [id])
  if (!b) return <p className="pb-hint">{err ?? 'Loading the block…'}</p>
  const m = b.module
  if (!m) return <div className="pb-detail"><p className="pb-info">This block has no strength program, so there is nothing to add accessory work to.</p><button className="btn-ghost mono" onClick={onCancel}>CLOSE</button></div>
  const today = localToday()
  const ahead = b.sessions.filter((x) => x.status === 'planned' && x.date >= today).length
  const clean = cleanAccessories(acc)
  const lines = accessorySummary(acc)
  return (
    <div className="pb-detail">
      <p className="pb-info">Extra work on the {m.title} sessions still planned from today ({ahead} planned session{ahead === 1 ? '' : 's'} ahead, strength and conditioning). Past and confirmed sessions stay as they were; the program's own exercises are never touched. Half the sets in deload weeks.</p>
      <AccessoryTables sessions={m.sessions.map((x) => ({ id: x.id, label: x.label }))} value={acc} onChange={setAcc} />
      {lines.length > 0 && <p className="pb-hint mono">{lines.join('   ')}</p>}
      {err && <p className="pb-warn">{err}</p>}
      <div className="pb-row">
        <button className="pb-primary mono" disabled={busy} onClick={async () => {
          setBusy(true); setErr(null)
          try {
            const eq = { ...DEFAULT_EQUIPMENT, platesKg: cat.settings.plates_kg, weightedBase: cat.settings.weighted_percent_base === 'total' ? 'total' as const : 'added' as const }
            onDone(await updateBlockAccessories(b, clean, today, eq, (n) => cat.maxes[movementKey(n)]?.entry ?? null))
          } catch (e) { setErr(e instanceof Error ? e.message : String(e)) } finally { setBusy(false) }
        }}>{busy ? 'APPLYING…' : 'APPLY FROM TODAY'}</button>
        <button className="btn-ghost mono" onClick={onCancel}>CANCEL</button>
      </div>
    </div>
  )
}

function EndEarly({ id, cat, onCancel, onEnded }: { id: number; cat: PlannerCatalog; onCancel: () => void; onEnded: (lastDay: string) => void }) {
  const today = localToday()
  const sunday = plusDays(today, (7 - weekdayOf(today)) % 7)
  const [b, setB] = useState<BlockForEnding | null>(null)
  const [lastDay, setLastDay] = useState(plusDays(sunday, 7))
  const [stepUp, setStepUp] = useState(false)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => {
    let live = true
    loadBlockForEnding(id).then((x) => {
      if (!live) return
      setB(x)
      setLastDay((d) => (d < x.row.start_date ? x.row.start_date : d >= x.row.end_date ? plusDays(x.row.end_date, -1) : d))
    }).catch((e) => setErr(e instanceof Error ? e.message : String(e)))
    return () => { live = false }
  }, [id])
  if (!b) return <p className="pb-hint">{err ?? 'Loading the block…'}</p>
  const valid = lastDay >= b.row.start_date && lastDay < b.row.end_date
  const preview = endEarlyPreview(b.sessions, lastDay)
  const steps = b.module ? stepUpMaxes(b.module, b.choice, (n) => cat.maxes[movementKey(n)]?.entry.oneRmKg, loadStepKg(cat.settings.plates_kg)) : []
  return (
    <div className="pb-detail">
      <div className="pb-row">
        <Field label="LAST DAY OF THE BLOCK">
          <div className="pb-chips">
            {[[sunday, 'END OF THIS WEEK'], [plusDays(sunday, 7), 'END OF NEXT WEEK']].filter(([d]) => d >= b.row.start_date && d < b.row.end_date).map(([d, l]) => (
              <button key={d} className={`pb-chip ${lastDay === d ? 'on' : ''}`} onClick={() => setLastDay(d)}>{l} · {dateShort(d)}</button>
            ))}
            <input className="pb-input" type="date" value={lastDay} min={b.row.start_date} max={plusDays(b.row.end_date, -1)} onChange={(e) => e.target.value && setLastDay(e.target.value)} />
          </div>
        </Field>
      </div>
      {!valid && <p className="pb-warn">Choose a day from {dateShort(b.row.start_date)} to {dateShort(plusDays(b.row.end_date, -1))}.</p>}
      {b.sessions.length === 0 ? <p className="pb-info">{b.row.origin === 'generated' ? 'No planned sessions left.' : 'A block recorded outside the planner: it has no planned sessions here, so only its end date moves'} (from {dateLong(b.row.end_date)} to {dateLong(lastDay)}).</p> : <>
      <p className="pb-info">Keeps {preview.kept} session{preview.kept === 1 ? '' : 's'}; sets aside the {preview.dropped} still planned after {dateLong(lastDay)}{preview.heaviestDroppedPct != null ? `, the heaviest at ${fmt(preview.heaviestDroppedPct)}% of the 1RM` : ''}.</p>
      {preview.droppedWeeks.length > 0 && <p className="pb-hint mono">{preview.droppedWeeks.map((w) => `W${w.weekIndex}: ${w.sessions}${w.topPct != null ? ` · ≤${fmt(w.topPct)}%` : ''}`).join('   ')}</p>}
      </>}
      {b.module && <Field label="MAXES FOR THE NEXT BLOCK" hint="The program steps maxes up after a completed cycle; this one stops before its end.">
        <div className="pb-chips">
          <button className={`pb-chip ${!stepUp ? 'on' : ''}`} onClick={() => setStepUp(false)}>KEEP TODAY'S MAXES (RECOMMENDED)</button>
          <button className={`pb-chip ${stepUp ? 'on' : ''}`} onClick={() => setStepUp(true)}>STEP THEM UP AS IF THE CYCLE WAS DONE</button>
        </div>
      </Field>}
      {b.module && stepUp && (steps.length === 0 ? <p className="pb-hint">Nothing to step up: no progression in the program, or no max recorded.</p>
        : <p className="pb-hint mono">{steps.map((st) => `${st.exercise} ${fmt(Math.round(st.fromKg * 10) / 10)} → ${fmt(Math.round(st.toKg * 10) / 10)} kg`).join(' · ')}</p>)}
      {err && <p className="pb-warn">{err}</p>}
      <div className="pb-row">
        <button className="pb-primary mono" disabled={!valid || busy} onClick={async () => {
          setBusy(true); setErr(null)
          try { await endBlockEarly(b, lastDay, stepUp ? steps : []); onEnded(lastDay) } catch (e) { setErr(e instanceof Error ? e.message : String(e)) } finally { setBusy(false) }
        }}>{busy ? 'ENDING…' : `END ON ${dateShort(lastDay).toUpperCase()}`}</button>
        <button className="btn-ghost mono" onClick={onCancel}>CANCEL</button>
      </div>
      <p className="pb-hint">The new block then starts {dateLong(plusDays(lastDay, 1))}.</p>
    </div>
  )
}
