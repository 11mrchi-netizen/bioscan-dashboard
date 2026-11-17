// The web block planner's state (docs/analysis-console: planning, Nov 2026). A pure model of the Android planner
// (ui/screens/training/TrainingPlannerState.kt) over the shared engine (supabase/functions/_shared/training-core), with
// more of the engine's inputs exposed: the equipment, session times and bodyweight for this block, max overrides
// (1RM and max reps), the conditioning rotation per kind, and the week-by-week timeline. No IO here: the workspace
// loads the catalogue and saves the block (blockPlannerData.ts); everything in between is these functions.

import {
  type BlockBlueprint, type ConditioningProtocolDef, countedWeeks, cycleWeekKind, type DayPlan, DEFAULT_EQUIPMENT, defaultTimeline, type Definition,
  DefinitionIndex, generateMergedBlock, generateTemplateBlock, type GenChoices, type GeneratedBlock, type GenEquipment, isCluster, layoutWarnings,
  type LocalDate, type MaxEntry, type ModuleChoice, movementKey, nextOrSame, plusWeekday, resizeTimeline, type ResolvedMax, slotsToChoose,
  type SlotTime, type StrengthModuleDef, suggestLayout, type TemplateDef, templateDayPositions, templateModuleKeys, type WeekKind, weekdayOf,
  weeksForBlocks, type Weekday, withTaper, daysBetween, WEEKDAY_NAMES,
} from '../../../supabase/functions/_shared/training-core/index.ts'

export type Route = 'build' | 'published'

export interface ManualMax { oneRmKg?: number | null; maxReps?: number | null }

export interface PlannerDraft {
  v: 1
  route: Route
  name: string
  startDate: LocalDate
  targetEventId: number | null
  weeks: number
  lengthChosen: boolean
  startWithTest: boolean
  strengthKey: string | null
  protocolKey: string | null
  templateKey: string | null
  variables: Record<string, string>
  weekdays: Record<number, Weekday>
  layout: DayPlan[]
  timeline: WeekKind[]
  modules: Record<string, ModuleChoice>
  conditioning: Record<string, string[]>
  conditioningPerWeek: number | null
  manualMaxes: Record<string, ManualMax>
  projectProgression: boolean
  slotTimes: Record<1 | 2, SlotTime>
  equipment: GenEquipment
  bodyweightKg: number | null
}

export interface TrainingSettings {
  plates_kg: number[]
  default_start_time: string
  default_duration_min: number
  second_start_time: string
  second_duration_min: number
  weighted_percent_base: string
}

export const DEFAULT_SETTINGS: TrainingSettings = {
  plates_kg: [1.25, 2.5, 5, 10, 15, 20], default_start_time: '07:00:00', default_duration_min: 60,
  second_start_time: '20:00:00', second_duration_min: 120, weighted_percent_base: 'added',
}

export interface PlannerEvent { id: number; name: string; priority: string; eventDate: LocalDate; plannedDurationMin: number | null }

/** Everything loaded once: the catalogue, the settings, the maxes, the events, and the latest bodyweight. */
export interface PlannerCatalog {
  index: DefinitionIndex
  raw: Record<string, unknown> // key -> stored body of the version in the index (frozen into the block as is)
  unreadable: string[]
  settings: TrainingSettings
  maxes: Record<string, ResolvedMax> // by movement key
  events: PlannerEvent[]
  bodyweightKg: number | null
}

const hm = (t: string): string => t.slice(0, 5)

export function newDraft(settings: TrainingSettings, today: LocalDate, bodyweightKg: number | null): PlannerDraft {
  return {
    v: 1, route: 'build', name: '', startDate: nextOrSame(today, 1), targetEventId: null, weeks: 8, lengthChosen: false, startWithTest: false,
    strengthKey: null, protocolKey: null, templateKey: null, variables: {}, weekdays: {}, layout: [], timeline: Array.from({ length: 8 }, () => 'normal'),
    modules: {}, conditioning: {}, conditioningPerWeek: null, manualMaxes: {}, projectProgression: true,
    slotTimes: { 1: { start: hm(settings.default_start_time), durationMin: settings.default_duration_min }, 2: { start: hm(settings.second_start_time), durationMin: settings.second_duration_min } },
    equipment: { ...DEFAULT_EQUIPMENT, platesKg: settings.plates_kg.length > 0 ? settings.plates_kg : DEFAULT_EQUIPMENT.platesKg, weightedBase: settings.weighted_percent_base === 'total' ? 'total' : 'added' },
    bodyweightKg,
  }
}

// ---- the parts of the program ----

export const strengthOf = (d: PlannerDraft, idx: DefinitionIndex): StrengthModuleDef | null => (d.strengthKey ? idx.strength(d.strengthKey) ?? null : null)
export const protocolOf = (d: PlannerDraft, idx: DefinitionIndex): ConditioningProtocolDef | null => (d.protocolKey ? idx.protocol(d.protocolKey) ?? null : null)
export const templateOf = (d: PlannerDraft, idx: DefinitionIndex): TemplateDef | null => (d.templateKey ? idx.template(d.templateKey) ?? null : null)

/** The strength modules in play: the chosen one on the build route, the template's (variables resolved) on the published one. */
export function strengthModules(d: PlannerDraft, idx: DefinitionIndex): StrengthModuleDef[] {
  if (d.route === 'build') { const m = strengthOf(d, idx); return m ? [m] : [] }
  const t = templateOf(d, idx)
  return t ? templateModuleKeys(t, d.variables).map((k) => idx.strength(k)).filter((m): m is StrengthModuleDef => !!m) : []
}

export const choiceFor = (d: PlannerDraft, key: string): ModuleChoice => d.modules[key] ?? {}

export function blockLength(d: PlannerDraft, idx: DefinitionIndex): number {
  const m = strengthOf(d, idx)
  if (!m) return 3
  const c = choiceFor(d, m.key)
  const variant = m.variants.find((v) => v.key === c.variant) ?? m.variants[0]
  const n = variant?.weeks.length ?? 1
  return Math.min(n, Math.max(1, c.blockLength ?? m.block_lengths[m.block_lengths.length - 1] ?? n))
}

export const dayPositions = (d: PlannerDraft, idx: DefinitionIndex): number[] => {
  const t = d.route === 'published' ? templateOf(d, idx) : null
  return t ? templateDayPositions(t) : []
}

export const weekdayFor = (d: PlannerDraft, position: number): Weekday => d.weekdays[position] ?? plusWeekday(weekdayOf(d.startDate), position - 1)

// ---- edits (each returns a new draft) ----

export function withLayoutReset(d: PlannerDraft, idx: DefinitionIndex): PlannerDraft {
  return { ...d, layout: suggestLayout(strengthOf(d, idx), protocolOf(d, idx), weekdayOf(d.startDate), d.conditioningPerWeek) }
}

export function withTimelineReset(d: PlannerDraft, idx: DefinitionIndex, events: PlannerEvent[] = []): PlannerDraft {
  const base: WeekKind[] = strengthOf(d, idx) ? defaultTimeline(blockLength(d, idx), d.weeks, d.startWithTest) : Array.from({ length: d.weeks }, () => 'normal')
  const e = events.find((x) => x.id === d.targetEventId)
  return { ...d, timeline: e ? withTaper(base, taperWeeks(e)) : base }
}

export function pickRoute(d: PlannerDraft, route: Route): PlannerDraft {
  if (route === d.route) return d
  return {
    ...d, route, variables: {}, modules: {}, conditioning: {}, weekdays: {}, manualMaxes: {}, strengthKey: null, protocolKey: null, templateKey: null,
    layout: [], startWithTest: false, timeline: route === 'build' ? Array.from({ length: d.weeks }, () => 'normal') : [],
  }
}

export function pickStrength(d: PlannerDraft, idx: DefinitionIndex, key: string | null, events: PlannerEvent[] = []): PlannerDraft {
  const modules = { ...d.modules }
  if (key) delete modules[key]
  let next: PlannerDraft = { ...d, strengthKey: key, modules }
  if (!next.lengthChosen && key) next = { ...next, weeks: weeksForBlocks(2, blockLength(next, idx)) }
  return withTimelineReset(withLayoutReset(next, idx), idx, events)
}

export function pickProtocol(d: PlannerDraft, idx: DefinitionIndex, key: string | null): PlannerDraft {
  return withLayoutReset({ ...d, protocolKey: key, conditioning: {} }, idx)
}

export function pickTemplate(d: PlannerDraft, key: string | null): PlannerDraft {
  return { ...d, templateKey: key, variables: {}, modules: {}, weekdays: {} }
}

export function changeStart(d: PlannerDraft, idx: DefinitionIndex, start: LocalDate, events: PlannerEvent[]): PlannerDraft {
  let next = withLayoutReset({ ...d, startDate: start }, idx)
  const e = events.find((x) => x.id === d.targetEventId)
  if (e) next = buildToward(next, idx, e, events)
  return next
}

export function changeWeeks(d: PlannerDraft, idx: DefinitionIndex, n: number): PlannerDraft {
  const weeks = Math.min(52, Math.max(1, Math.round(n)))
  return { ...d, weeks, lengthChosen: true, timeline: strengthOf(d, idx) ? resizeTimeline(d.timeline, blockLength(d, idx), weeks) : Array.from({ length: weeks }, (_, i) => d.timeline[i] ?? 'normal') }
}

export const weeksToEvent = (start: LocalDate, eventDate: LocalDate): number => (eventDate < start ? 0 : Math.floor(daysBetween(start, eventDate) / 7) + 1)
export const taperWeeks = (e: PlannerEvent): number => (e.priority === 'C' ? 0 : e.priority === 'A' || (e.plannedDurationMin ?? 0) > 90 ? 2 : 1)
export const buildableEvents = (events: PlannerEvent[], start: LocalDate): PlannerEvent[] =>
  events.filter((e) => e.priority !== 'C' && weeksToEvent(start, e.eventDate) >= 3).sort((a, b) => (a.eventDate < b.eventDate ? -1 : 1))

export function buildToward(d: PlannerDraft, idx: DefinitionIndex, e: PlannerEvent | null, events: PlannerEvent[]): PlannerDraft {
  if (!e) return { ...d, targetEventId: null }
  let next: PlannerDraft = { ...d, targetEventId: e.id, lengthChosen: true, weeks: Math.min(52, Math.max(1, weeksToEvent(d.startDate, e.eventDate))) }
  next = withTimelineReset(next, idx, events)
  if (next.name.trim() === '') next = { ...next, name: `Toward ${e.name}` }
  return next
}

export const toggleWeek = (d: PlannerDraft, i: number): PlannerDraft => ({ ...d, timeline: d.timeline.map((k, j) => (j === i ? cycleWeekKind(k) : k)) })
export const setWeek = (d: PlannerDraft, i: number, k: WeekKind): PlannerDraft => ({ ...d, timeline: d.timeline.map((x, j) => (j === i ? k : x)) })
export const setDay = (d: PlannerDraft, w: Weekday, f: (p: DayPlan) => DayPlan): PlannerDraft => ({ ...d, layout: d.layout.map((p) => (p.weekday === w ? f(p) : p)) })
export const updateChoice = (d: PlannerDraft, key: string, f: (c: ModuleChoice) => ModuleChoice): PlannerDraft => ({ ...d, modules: { ...d.modules, [key]: f(choiceFor(d, key)) } })

// ---- what the engine is given ----

export function maxEntry(d: PlannerDraft, cat: PlannerCatalog, name: string): MaxEntry | null {
  const k = movementKey(name)
  const base = cat.maxes[k]?.entry ?? null
  const manual = d.manualMaxes[k]
  if (!manual) return base
  return { ...(base ?? {}), ...(manual.oneRmKg != null ? { oneRmKg: manual.oneRmKg } : {}), ...(manual.maxReps != null ? { maxReps: manual.maxReps } : {}) }
}

export function genChoices(d: PlannerDraft): GenChoices {
  return {
    startDate: d.startDate, weekdays: d.weekdays, slotTimes: d.slotTimes, variables: d.variables, modules: d.modules, conditioning: d.conditioning,
    projectProgression: d.projectProgression, bodyweightKg: d.bodyweightKg, conditioningPerWeek: d.conditioningPerWeek,
  }
}

export const blueprint = (d: PlannerDraft): BlockBlueprint => ({ strengthKey: d.strengthKey, protocolKey: d.protocolKey, layout: d.layout, timeline: d.timeline })

/** The block as it stands, or null while there is nothing to generate yet. Errors in a definition come back as a message. */
export function generate(d: PlannerDraft, cat: PlannerCatalog): { block: GeneratedBlock | null; error: string | null } {
  try {
    const lookup = (name: string) => maxEntry(d, cat, name)
    if (d.route === 'published') {
      const t = templateOf(d, cat.index)
      return { block: t ? generateTemplateBlock(t, cat.index, genChoices(d), d.equipment, lookup) : null, error: null }
    }
    if (!d.strengthKey && !d.protocolKey) return { block: null, error: null }
    return { block: generateMergedBlock(blueprint(d), cat.index, genChoices(d), d.equipment, lookup), error: null }
  } catch (e) {
    return { block: null, error: e instanceof Error ? e.message : String(e) }
  }
}

export const weekWarnings = (d: PlannerDraft, idx: DefinitionIndex): string[] =>
  d.route === 'build' && (d.strengthKey || d.protocolKey) ? layoutWarnings(d.layout, strengthOf(d, idx), protocolOf(d, idx)) : []

/** The exercises in play (the picks, or each slot's standard) that have no 1RM yet. */
export function missingMaxes(d: PlannerDraft, cat: PlannerCatalog): string[] {
  const names = strengthModules(d, cat.index).flatMap((m) => {
    const c = choiceFor(d, m.key)
    return slotsToChoose(m).flatMap((slot) => {
      const chosen = c.exercises?.[slot.id] ?? []
      if (isCluster(m, slot) && slot.pick != null) return []
      if (isCluster(m, slot)) return chosen.length > 0 ? chosen : [slot.standard]
      return [chosen[0] ?? slot.standard]
    })
  })
  return [...new Set(names)].filter((n) => maxEntry(d, cat, n)?.oneRmKg == null)
}

/** Every exercise the block will load, with the max it uses and where it came from. */
export function exercisesInPlay(d: PlannerDraft, cat: PlannerCatalog): { name: string; key: string; entry: MaxEntry | null; source: string; basedOn: string; manual: boolean }[] {
  const names = new Set<string>()
  for (const m of strengthModules(d, cat.index)) {
    const c = choiceFor(d, m.key)
    for (const slot of slotsToChoose(m)) {
      const chosen = c.exercises?.[slot.id] ?? []
      if (isCluster(m, slot)) (chosen.length > 0 ? chosen : slot.pick == null ? [slot.standard] : []).forEach((n) => names.add(n))
      else names.add(chosen[0] ?? slot.standard)
    }
  }
  return [...names].map((name) => {
    const key = movementKey(name)
    const r = cat.maxes[key]
    return { name, key, entry: maxEntry(d, cat, name), source: r?.source ?? 'none', basedOn: r?.basedOn ?? 'no data', manual: d.manualMaxes[key] != null }
  })
}

export function canCreate(d: PlannerDraft, block: GeneratedBlock | null): string | null {
  if (!block) return d.route === 'build' ? 'Choose a strength program or a conditioning template.' : 'Choose a published program.'
  if (block.sessions.length === 0) return 'The block has no sessions.'
  return null
}

// ---- what is saved with the block (training_blocks) ----

const wd = (w: Weekday) => WEEKDAY_NAMES[w - 1].slice(0, 3).toLowerCase()

export function componentsJson(d: PlannerDraft): unknown[] {
  if (d.route === 'build') {
    return [
      ...(d.strengthKey ? [{ role: 'strength', ref: { kind: 'strength_module', key: d.strengthKey } }] : []),
      ...(d.protocolKey ? [{ role: 'conditioning', ref: { kind: 'conditioning_protocol', key: d.protocolKey } }] : []),
    ]
  }
  return d.templateKey ? [{ role: 'template', ref: { kind: 'template', key: d.templateKey } }] : []
}

export function choicesJson(d: PlannerDraft, idx: DefinitionIndex): Record<string, unknown> {
  const clusters: Record<string, unknown> = {}
  for (const [module, c] of Object.entries(d.modules)) {
    clusters[module] = { ...(c.variant ? { variant: c.variant } : {}), ...(c.blockLength ? { block_length: c.blockLength } : {}), ...(c.exercises ?? {}) }
  }
  return {
    path: d.route === 'build' ? 'build' : 'published',
    weekday_map: Object.fromEntries(dayPositions(d, idx).map((p) => [String(p), wd(weekdayFor(d, p))])),
    variables: d.variables, clusters, conditioning: d.conditioning,
    ...(d.route === 'build' ? {
      layout: d.layout.map((p) => ({ weekday: wd(p.weekday), ...(p.strengthSession != null ? { strength_session: p.strengthSession } : {}), ...(p.conditioning ? { conditioning: p.conditioning.kind } : {}) })),
      timeline: d.timeline, start_with_test: d.startWithTest,
    } : {}),
    weighted_percent_base: d.equipment.weightedBase, project_progression: d.projectProgression,
    // Web-only inputs, kept so the block says how its loads were worked out.
    equipment: { bar_kg: d.equipment.barKg, trap_bar_kg: d.equipment.trapBarKg, plates_kg: d.equipment.platesKg, added_step_kg: d.equipment.addedStepKg },
    bodyweight_kg: d.bodyweightKg, slot_times: d.slotTimes, planned_on: 'web',
  }
}

/** The program and every module, protocol and session its sessions refer to, frozen into the block. */
export function definitionsUsed(d: PlannerDraft, idx: DefinitionIndex, block: GeneratedBlock): Definition[] {
  const keys = new Set<string>()
  strengthModules(d, idx).forEach((m) => keys.add(m.key))
  if (d.route === 'build' && d.protocolKey) keys.add(d.protocolKey)
  for (const s of block.sessions) {
    const k = s.moduleRef.includes(':') ? s.moduleRef.slice(s.moduleRef.indexOf(':') + 1) : ''
    if (k !== '' && !k.startsWith('$')) keys.add(k)
    if (s.conditioning?.sessionKey) keys.add(s.conditioning.sessionKey)
  }
  return [...keys].map((k) => idx.any(k)).filter((x): x is Definition => !!x)
}

// ---- summaries for the step rail and the preview ----

export interface WeekStat { week: number; kind: string; sessions: number; strength: number; conditioningMin: number; tests: number }

export function weekStats(block: GeneratedBlock, timeline: WeekKind[]): WeekStat[] {
  return Array.from({ length: block.calendarWeeks }, (_, i) => {
    const s = block.sessions.filter((x) => x.weekIndex === i + 1)
    return {
      week: i + 1, kind: timeline[i] ?? (s.some((x) => !x.countsTowardBlock) ? 'deload' : 'normal'), sessions: s.length,
      strength: s.filter((x) => x.items.length > 0 && x.workKind !== 'test').length,
      conditioningMin: s.reduce((m, x) => m + (x.conditioning?.minutes?.min ?? 0), 0),
      tests: s.filter((x) => x.workKind === 'test').length,
    }
  })
}

export const counted = (d: PlannerDraft): number => countedWeeks(d.timeline)
