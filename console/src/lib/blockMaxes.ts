// Changing the maxes of a block that is already planned (owner's request, Nov 2026: "I want to modify the exercises maxes").
// A planned session stores its loads already worked out (kilograms, or "needs a max"), so a new max means working them out
// again. The block is generated afresh from what it was planned with (its frozen definitions, its stored choices and its
// start), the same deterministic engine the planner used, with the new maxes; each session still to come then takes the
// regenerated load of each of its lifts. Everything else in the session stays as it is: its sets, reps, notes, date and time,
// and any load the person set by hand in the session editor (a fixed weight or an RPE where the program asked for a
// percentage). Before anything is changed the regenerated block must line up with the stored one, session by session and
// lift by lift; when it does not (a block planned by an older version of the planner, a session reshaped by hand) the
// sessions concerned are left alone and the page says why. No IO here: the reads and writes are in blockPlannerData.ts.

import {
  type BlockBlueprint, type DayPlan, type Definition, DefinitionIndex, DEFAULT_EQUIPMENT, generateMergedBlock, generateTemplateBlock, type GenChoices,
  type GeneratedBlock, type GeneratedItem, type GenEquipment, liftJson, loadJson, type MaxEntry, type ModuleChoice, movementKey, parseDefinition,
  type SlotTime, type Weekday, type WeekKind, WEEK_KINDS, WEEKDAY_NAMES, accessoriesFrom,
} from '../../../supabase/functions/_shared/training-core/index.ts'

type Json = Record<string, unknown>

/** The stored block, as much of it as regenerating needs. */
export interface StoredBlock {
  start_date: string
  template_key: string | null
  components: unknown
  choices: Json | null
  definition_snapshot: { template?: unknown; definitions?: Record<string, unknown> } | null
}

/** A planned session of the block (the newest revision of each, superseded rows left out). */
export interface StoredSession { id: number; sequence_no: number; title: string; status: string; scheduled_date: string; exercise_session_id: number | null; prescription: unknown }

export interface Regenerable {
  run: (maxes: (name: string) => MaxEntry | null | undefined) => GeneratedBlock
}

const WD: Record<string, Weekday> = Object.fromEntries(WEEKDAY_NAMES.map((n, i) => [n.slice(0, 3).toLowerCase(), (i + 1) as Weekday]))
const num = (x: unknown): number | null => (typeof x === 'number' && Number.isFinite(x) ? x : typeof x === 'string' && x.trim() !== '' && Number.isFinite(Number(x)) ? Number(x) : null)

/**
 * How to generate this block again, from its own record. `fallback` gives what a block planned on the phone does not store
 * (its equipment and bodyweight come from the training settings and the latest weigh-in, as on the phone).
 */
export function regenerable(b: StoredBlock, fallback: { equipment: GenEquipment; bodyweightKg: number | null }): Regenerable | string {
  const defs: Definition[] = []
  for (const raw of Object.values(b.definition_snapshot?.definitions ?? {})) {
    try { defs.push(parseDefinition(raw)) } catch (e) { return `a program definition frozen into the block cannot be read (${e instanceof Error ? e.message : String(e)})` }
  }
  let template = null
  if (b.definition_snapshot?.template) {
    try { const t = parseDefinition(b.definition_snapshot.template); if (t.kind === 'template') { template = t; defs.push(t) } } catch { /* reported below */ }
  }
  if (defs.length === 0) return 'the block has no program frozen into it'
  const idx = new DefinitionIndex(defs)
  const c = b.choices ?? {}

  const clusters = (c.clusters ?? {}) as Record<string, Json>
  const modules: Record<string, ModuleChoice> = {}
  for (const [key, cl] of Object.entries(clusters)) {
    const exercises: Record<string, string[]> = {}
    for (const [k, v] of Object.entries(cl ?? {})) if (k !== 'variant' && k !== 'block_length' && k !== 'accessories' && Array.isArray(v)) exercises[k] = v.map(String)
    modules[key] = {
      variant: typeof cl?.variant === 'string' ? cl.variant : null, blockLength: num(cl?.block_length), exercises, accessories: accessoriesFrom(cl?.accessories),
    }
  }
  const weekdays: Record<number, Weekday> = {}
  for (const [pos, w] of Object.entries((c.weekday_map ?? {}) as Record<string, string>)) if (WD[String(w).slice(0, 3).toLowerCase()]) weekdays[Number(pos)] = WD[String(w).slice(0, 3).toLowerCase()]
  const slotTimes: Record<number, SlotTime> = {}
  for (const [k, v] of Object.entries((c.slot_times ?? {}) as Record<string, { start?: string; durationMin?: number }>)) if (v?.start) slotTimes[Number(k)] = { start: v.start.slice(0, 5), durationMin: num(v.durationMin) ?? 60 }
  const eqStored = (c.equipment ?? null) as { bar_kg?: number; trap_bar_kg?: number; plates_kg?: number[]; added_step_kg?: number } | null
  const equipment: GenEquipment = {
    barKg: num(eqStored?.bar_kg) ?? fallback.equipment.barKg ?? DEFAULT_EQUIPMENT.barKg,
    trapBarKg: num(eqStored?.trap_bar_kg) ?? fallback.equipment.trapBarKg ?? DEFAULT_EQUIPMENT.trapBarKg,
    platesKg: Array.isArray(eqStored?.plates_kg) && eqStored.plates_kg.length > 0 ? eqStored.plates_kg.map(Number) : fallback.equipment.platesKg,
    addedStepKg: num(eqStored?.added_step_kg) ?? fallback.equipment.addedStepKg,
    weightedBase: c.weighted_percent_base === 'total' ? 'total' : c.weighted_percent_base === 'added' ? 'added' : fallback.equipment.weightedBase,
  }
  const choices: GenChoices = {
    startDate: b.start_date, weekdays, slotTimes: slotTimes as GenChoices['slotTimes'], variables: (c.variables ?? {}) as Record<string, string>, modules,
    conditioning: (c.conditioning ?? {}) as Record<string, string[]>, projectProgression: c.project_progression !== false,
    bodyweightKg: c.bodyweight_kg !== undefined ? num(c.bodyweight_kg) : fallback.bodyweightKg,
  }

  if (c.path === 'published' || (template && c.path !== 'build')) {
    if (!template) return 'its published program is not frozen into the block'
    const t = template
    return { run: (maxes) => generateTemplateBlock(t, idx, choices, equipment, maxes) }
  }
  const comps = (Array.isArray(b.components) ? b.components : []) as { role?: string; ref?: { key?: string } }[]
  const strengthKey = comps.find((x) => x.role === 'strength')?.ref?.key ?? null
  const protocolKey = comps.find((x) => x.role === 'conditioning')?.ref?.key ?? null
  if (!Array.isArray(c.layout)) return 'the block does not store its week layout'
  const layout: DayPlan[] = []
  for (const p of c.layout as { weekday?: string; strength_session?: number; conditioning?: string }[]) {
    const w = WD[String(p.weekday ?? '').slice(0, 3).toLowerCase()]
    if (!w) return 'the block\'s week layout cannot be read'
    layout.push({ weekday: w, strengthSession: num(p.strength_session), conditioning: p.conditioning ? { kind: p.conditioning } : null })
  }
  const timeline = (Array.isArray(c.timeline) ? c.timeline : []).map(String).filter((k): k is WeekKind => (WEEK_KINDS as string[]).includes(k))
  if (timeline.length === 0) return 'the block does not store its week-by-week timeline'
  const bp: BlockBlueprint = { strengthKey, protocolKey, layout, timeline }
  return { run: (maxes) => generateMergedBlock(bp, idx, choices, equipment, maxes) }
}

/** Loads that come from a max: these are the ones a new max changes. */
const FROM_A_MAX = new Set(['pct_1rm', 'added', 'pct_max_reps', 'unresolved'])
/** A load set by hand where the program asked for a max-based one: the person's choice, left alone. */
const BY_HAND = new Set(['fixed', 'rpe'])

const lifts = (p: unknown): Json[] => (Array.isArray(p) ? (p as Json[]) : []).filter((e) => e?.type === 'lift')
const sameLoad = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)

/** The exercises whose loads in the block come from a max (the program's own lifts and any accessory loaded by %), in order of first use. */
export function exercisesFromMaxes(gen: GeneratedBlock): string[] {
  const out: string[] = []
  for (const s of gen.sessions) for (const i of s.items) {
    const k = i.load.kind
    const fromMax = k === 'barbell' || k === 'added' || k === 'max_reps_fraction' || k === 'unresolved' || (i.role === 'accessory' && /% of the 1RM/.test(i.note ?? ''))
    if (fromMax && !out.includes(i.exercise)) out.push(i.exercise)
  }
  return out
}

export interface MaxRevision { id: number; date: string; title: string; after: Json[]; changes: { exercise: string; before: Json; after: Json }[] }
export interface MaxRevisionPlan { revisions: MaxRevision[]; skipped: { date: string; title: string; why: string }[] }

/**
 * The sessions whose loads change once the block is regenerated as `gen`, from `today` on: still planned, not linked to a
 * recording. Sessions are matched by their place in the block (sequence_no), lifts by slot and exercise in order. Only
 * lifts of exercises in `only` (by movement key) are touched when it is given.
 */
export function maxRevisions(rows: StoredSession[], gen: GeneratedBlock, today: string, only?: Set<string>): MaxRevisionPlan {
  const bySeq = new Map(gen.sessions.map((s) => [s.sequenceNo, s]))
  const revisions: MaxRevision[] = []
  const skipped: MaxRevisionPlan['skipped'] = []
  for (const row of rows) {
    if (row.status !== 'planned' || row.exercise_session_id != null || row.scheduled_date < today) continue
    const stored = lifts(row.prescription)
    if (stored.length === 0) continue
    const g = bySeq.get(row.sequence_no)
    if (!g || g.title !== row.title) { skipped.push({ date: row.scheduled_date, title: row.title, why: 'it no longer matches the program it was planned from' }); continue }
    const pool = new Map<string, GeneratedItem[]>()
    for (const i of g.items) { const k = `${i.slot}|${movementKey(i.exercise)}`; pool.set(k, [...(pool.get(k) ?? []), i]) }
    const changes: MaxRevision['changes'] = []
    const after = (row.prescription as Json[]).map((e) => {
      if (e?.type !== 'lift') return e
      const name = String(e.exercise ?? '')
      const match = pool.get(`${String(e.slot)}|${movementKey(name)}`)?.shift()
      if (!match) return e
      if (only && !only.has(movementKey(name))) return e
      const now = (e.load ?? {}) as Json
      const next = loadJson(match.load)
      if (BY_HAND.has(String(now.kind)) && FROM_A_MAX.has(String(next.kind))) return e
      if (sameLoad(now, next)) return e
      // an accessory loaded by % keeps its own note: only its kilograms move
      changes.push({ exercise: name, before: now, after: next })
      const nextLift = liftJson(match)
      const out: Json = { ...e, load: next }
      if (nextLift.projected) out.projected = true; else delete out.projected
      return out
    })
    if (changes.length > 0) revisions.push({ id: row.id, date: row.scheduled_date, title: row.title, after, changes })
  }
  return { revisions, skipped }
}

/** A load as a few words, for the preview (the stored JSON, not the engine's object). */
export function storedLoadText(l: Json): string {
  const r = (x: unknown) => { const n = num(x); return n == null ? '?' : String(Math.round(n * 10) / 10) }
  switch (l.kind) {
    case 'pct_1rm': return `${r(l.loadable_kg ?? l.target_kg)} kg (${r(l.pct)}%)`
    case 'added': return `+${r(l.added_kg)} kg (${r(l.pct)}%)`
    case 'pct_max_reps': return `${r(l.reps)} reps (${r(l.pct)}%)`
    case 'fixed': return `${r(l.loadable_kg ?? l.kg)} kg`
    case 'rpe': return `RPE ${r(l.rpe)}`
    case 'unresolved': return 'needs a max'
    case 'bodyweight': return 'bodyweight'
    default: return String(l.kind ?? '')
  }
}

// ---- swapping an exercise for the rest of the block ----

export interface SwapSlot { moduleKey: string; moduleTitle: string; slotId: string; role: string; current: string; alternates: string[] }

/** The single-exercise slots of the block's strength programs, with the exercise each holds now and the program's alternates. */
export function swapSlots(b: StoredBlock): SwapSlot[] {
  const clusters = (b.choices?.clusters ?? {}) as Record<string, Record<string, unknown>>
  const out: SwapSlot[] = []
  for (const raw of Object.values(b.definition_snapshot?.definitions ?? {})) {
    let m
    try { m = parseDefinition(raw) } catch { continue }
    if (m.kind !== 'strength_module') continue
    const used = new Set([...m.sessions.flatMap((s) => s.slots), ...m.variants.flatMap((v) => v.weeks.flatMap((w) => w.sessions.flatMap((s) => s.items.map((i) => i.slot))))])
    for (const slot of m.slots) {
      if (!used.has(slot.id) || slot.pick != null || m.domain === 'strength_endurance') continue // clusters hold several exercises: not swapped here
      const chosen = clusters[m.key]?.[slot.id]
      const current = Array.isArray(chosen) && chosen.length > 0 ? String(chosen[0]) : slot.standard
      out.push({ moduleKey: m.key, moduleTitle: m.title, slotId: slot.id, role: slot.role, current, alternates: [slot.standard, ...slot.alternates].filter((n, i, a) => a.indexOf(n) === i && n !== current) })
    }
  }
  return out
}

/** The block's choices with one slot's exercise replaced (what is saved, and what the block is regenerated from). */
export function choicesWithSwap(choices: Json | null, moduleKey: string, slotId: string, exercise: string): Json {
  const c = { ...(choices ?? {}) }
  const clusters = { ...((c.clusters ?? {}) as Record<string, Json>) }
  clusters[moduleKey] = { ...(clusters[moduleKey] ?? {}), [slotId]: [exercise] }
  return { ...c, clusters }
}

/**
 * The sessions still to come where the slot's exercise becomes the new one, regenerated as `gen` (already built with the swap):
 * the lift's name, and its load worked out from the new exercise's max. Sets, reps and notes stay; a load set by hand stays too.
 * Only lifts of the slot that still hold the old exercise are changed (a session where it was swapped by hand is left as it is).
 */
export function swapRevisions(rows: StoredSession[], gen: GeneratedBlock, today: string, slotId: string, from: string): MaxRevisionPlan {
  const bySeq = new Map(gen.sessions.map((s) => [s.sequenceNo, s]))
  const revisions: MaxRevision[] = []
  const skipped: MaxRevisionPlan['skipped'] = []
  const fromKey = movementKey(from)
  for (const row of rows) {
    if (row.status !== 'planned' || row.exercise_session_id != null || row.scheduled_date < today) continue
    const hits = lifts(row.prescription).filter((e) => String(e.slot) === slotId && movementKey(String(e.exercise ?? '')) === fromKey)
    if (hits.length === 0) continue
    const g = bySeq.get(row.sequence_no)
    if (!g || g.title !== row.title) { skipped.push({ date: row.scheduled_date, title: row.title, why: 'it no longer matches the program it was planned from' }); continue }
    const pool = g.items.filter((i) => i.slot === slotId)
    const changes: MaxRevision['changes'] = []
    const after = (row.prescription as Json[]).map((e) => {
      if (e?.type !== 'lift' || String(e.slot) !== slotId || movementKey(String(e.exercise ?? '')) !== fromKey) return e
      const match = pool.shift()
      if (!match) return e
      const now = (e.load ?? {}) as Json
      const next = BY_HAND.has(String(now.kind)) ? now : loadJson(match.load)
      changes.push({ exercise: match.exercise, before: now, after: next })
      const out: Json = { ...e, exercise: match.exercise, load: next }
      if (match.projected && !BY_HAND.has(String(now.kind))) out.projected = true; else delete out.projected
      return out
    })
    if (changes.length > 0) revisions.push({ id: row.id, date: row.scheduled_date, title: row.title, after, changes })
  }
  return { revisions, skipped }
}
