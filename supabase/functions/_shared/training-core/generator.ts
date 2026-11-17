// Port of generate/BlockGenerator.kt and GenerationTypes.kt: deterministic block generation (architecture 3.3). A
// fixed-grid template or a merged strength + conditioning block expands into dated sessions with resolved loads. No
// clock, no IO: the same inputs always give the same block, and the phone's generator gives the same one (the parity
// fixtures in fixtures/ are dumped from the Kotlin tests).

import { type LocalDate, plusDays, plusWeekday, type Weekday, weekdayOf } from './dates.ts'
import {
  type Cell, type ConditioningProtocolDef, type ConditioningSessionDef, DefinitionIndex, type ItemDto, type LoadDto, type NumRange,
  rangeOf, type SlotDef, type StrengthModuleDef, type TemplateDef, toNumRange,
} from './definitions.ts'
import type { MaxEntry } from './maxes.ts'
import { lbToKg, loadStepKg, roundToStep } from './plates.ts'
import { type LoadSpec, type Missing, type PercentBase, type ResolvedLoad, resolveLoad } from './prescription.ts'
import { type BlockBlueprint, countsTowardBlock, type DayPlan, defaultTimeline, type WeekKind } from './timeline.ts'
import { suggestLayout } from './layout.ts'

export interface GenEquipment { barKg: number; trapBarKg: number; platesKg: number[]; addedStepKg: number; weightedBase: PercentBase }
export const DEFAULT_EQUIPMENT: GenEquipment = { barKg: 20, trapBarKg: 25, platesKg: [1.25, 2.5, 5, 10, 15, 20], addedStepKg: 1.25, weightedBase: 'added' }

export interface SlotTime { start: string; durationMin: number } // start 'HH:MM'
export interface ModuleChoice { variant?: string | null; blockLength?: number | null; exercises?: Record<string, string[]> }

export interface GenChoices {
  startDate: LocalDate
  weekdays?: Record<number, Weekday>
  slotTimes?: Record<number, SlotTime>
  variables?: Record<string, string>
  modules?: Record<string, ModuleChoice>
  conditioning?: Record<string, string[]>
  weeks?: number | null
  deloadAfterBlock?: boolean
  bodyweightKg?: number | null
  conditioningPerWeek?: number | null
  projectProgression?: boolean
}

export interface GeneratedItem {
  slot: string; exercise: string; role: string; sets: NumRange | null; reps: NumRange | null; load: ResolvedLoad
  technique: string | null; optional: boolean; note: string | null; projected: boolean
}
export interface GeneratedConditioning { sessionKey: string | null; label: string; category: string | null; params: Record<string, unknown> | null; minutes: NumRange | null }
export interface GeneratedSession {
  sequenceNo: number; weekIndex: number; countsTowardBlock: boolean; daySlot: number; slotInDay: number; date: LocalDate
  startTime: string | null; durationMin: number | null; domain: string; workKind: string; moduleRef: string; title: string
  items: GeneratedItem[]; conditioning: GeneratedConditioning | null; notes: string[]
}
export interface MaxProgression { weekIndex: number; moduleKey: string; exercise: string; fromKg: number; toKg: number }
export interface GeneratedBlock { sessions: GeneratedSession[]; calendarWeeks: number; countedWeeks: number; endDate: LocalDate; progressions: MaxProgression[]; warnings: string[] }

export type MaxLookup = (name: string) => MaxEntry | null | undefined

const DB_DOMAINS = new Set(['max_strength', 'hypertrophy', 'strength_endurance', 'power', 'aerobic_base', 'anaerobic_capacity', 'speed', 'work_capacity', 'specific_endurance', 'sport_skill', 'recovery'])

const categoryDomain = (category: string | null | undefined): string =>
  category === 'lic' ? 'aerobic_base' : category === 'hic' ? 'anaerobic_capacity' : category === 'wc' ? 'work_capacity'
    : category === 'power_hic' ? 'power' : category === 'core' ? 'strength_endurance' : 'aerobic_base'

const sessionDomain = (s: ConditioningSessionDef): string => s.domains.find((d) => DB_DOMAINS.has(d)) ?? categoryDomain(s.category)
const nameKey = (name: string): string => name.trim().toLowerCase()
const before = (s: string, ch: string) => (s.includes(ch) ? s.slice(0, s.indexOf(ch)) : s)
const after = (s: string, ch: string) => (s.includes(ch) ? s.slice(s.indexOf(ch) + 1) : '')
const clamp = (x: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, x))

/** A progression increment (a range in the definition's unit) as the kilograms added: the bottom of the range, on the loadable step, never below one step. */
export function progressionIncrementKg(range: NumRange, unit: string, stepKg: number): number {
  const kg = unit === 'lb' ? lbToKg(range.min) : range.min
  return Math.max(stepKg, roundToStep(kg, stepKg))
}

export function loadSpecOf(dto: LoadDto): LoadSpec {
  const need = (v: number | undefined, what: string): number => { if (v == null) throw new Error(`${dto.kind} needs ${what}`); return v }
  switch (dto.kind) {
    case 'pct_1rm': return { kind: 'pct_1rm', pct: need(dto.value, 'value') }
    case 'pct_tm': return { kind: 'pct_tm', pct: need(dto.value, 'value') }
    case 'pct_max_reps': return { kind: 'pct_max_reps', pct: need(dto.value, 'value') }
    case 'work_up_rm': return { kind: 'work_up_rm', minRm: need(dto.min_rm, 'min_rm'), maxRm: need(dto.max_rm, 'max_rm') }
    case 'fixed': return { kind: 'fixed', kg: need(dto.kg, 'kg') }
    case 'rpe': return { kind: 'rpe', rpe: need(dto.rpe, 'rpe') }
    case 'bodyweight': return { kind: 'bodyweight' }
    case 'none': return { kind: 'none' }
    default: throw new Error(`unknown load kind '${dto.kind}'`)
  }
}

/** A slot that holds several exercises: a declared cluster, or any slot of a strength-endurance module. */
export const isCluster = (m: StrengthModuleDef, slot: SlotDef): boolean => slot.pick != null || m.domain === 'strength_endurance'

const pickVariant = (m: StrengthModuleDef, choice: ModuleChoice) => m.variants.find((v) => v.key === choice.variant) ?? m.variants[0]

const MISSING_WORDS: Record<Missing, string> = { OneRm: 'a 1RM', TrainingMax: 'a training max', MaxReps: 'a max-reps test', Bodyweight: 'bodyweight' }

class Gen {
  sessions: GeneratedSession[] = []
  warnings: string[] = []
  progressions: MaxProgression[] = []
  private seq = 0
  private liveMax = new Map<string, number>()
  private warnedMissing = new Set<string>()
  private stepKg: number
  private varCounters = new Map<string, number>()
  idx: DefinitionIndex
  c: GenChoices
  eq: GenEquipment
  maxes: MaxLookup

  constructor(idx: DefinitionIndex, c: GenChoices, eq: GenEquipment, maxes: MaxLookup) {
    this.idx = idx; this.c = c; this.eq = eq; this.maxes = maxes
    this.stepKg = loadStepKg(eq.platesKg)
  }

  pickConditioningVariable(name: string): string | null {
    const list = (this.c.variables?.[name] ?? '').split(',').map((s) => s.trim()).filter((s) => s !== '')
    if (list.length === 0) return null
    const i = this.varCounters.get(name) ?? 0
    this.varCounters.set(name, i + 1)
    return list[i % list.length]
  }

  weekdayFor(day: number): Weekday { return this.c.weekdays?.[day] ?? plusWeekday(weekdayOf(this.c.startDate), day - 1) }

  dateFor(week: number, day: number): LocalDate {
    const offset = (this.weekdayFor(day) - weekdayOf(this.c.startDate) + 7) % 7
    return plusDays(this.c.startDate, 7 * (week - 1) + offset)
  }

  emit(week: number, counts: boolean, day: number, slotInDay: number, domain: string, workKind: string, moduleRef: string, title: string,
    items: GeneratedItem[] = [], conditioning: GeneratedConditioning | null = null, notes: string[] = []): void {
    const time = this.c.slotTimes?.[slotInDay]
    this.sessions.push({
      sequenceNo: ++this.seq, weekIndex: week, countsTowardBlock: counts, daySlot: day, slotInDay, date: this.dateFor(week, day),
      startTime: time?.start ?? null, durationMin: time?.durationMin ?? null, domain, workKind, moduleRef, title, items, conditioning, notes,
    })
  }

  private currentOneRm(name: string): number | null { return this.liveMax.get(nameKey(name)) ?? this.maxes(name)?.oneRmKg ?? null }

  resolveItems(m: StrengthModuleDef, choice: ModuleChoice, dtos: ItemDto[]): GeneratedItem[] {
    const out: GeneratedItem[] = []
    for (const dto of dtos) {
      const slot = m.slots.find((s) => s.id === dto.slot)
      if (!slot) continue
      const chosen = choice.exercises?.[slot.id] ?? []
      const names = slot.pick != null ? chosen : isCluster(m, slot) && chosen.length > 0 ? chosen : chosen.length > 0 ? [chosen[0]] : [slot.standard]
      if (names.length === 0) {
        if (!slot.optional && !dto.optional) this.warnings.push(`${m.title}: choose exercises for slot ${slot.id}`)
        continue
      }
      for (const name of names) {
        const entry = this.maxes(name)
        const load = resolveLoad(
          { slot: slot.id, load: loadSpecOf(dto.load), weightedCalisthenics: slot.weighted_calisthenics },
          {
            oneRmKg: this.currentOneRm(name), trainingMaxKg: entry?.trainingMaxKg ?? null, maxReps: entry?.maxReps ?? null, bodyweightKg: this.c.bodyweightKg ?? null,
            barKg: name.toLowerCase().includes('trap') ? this.eq.trapBarKg : this.eq.barKg, platesKg: this.eq.platesKg, addedStepKg: this.eq.addedStepKg, weightedBase: this.eq.weightedBase,
          },
        )
        if (load.kind === 'unresolved' && !this.warnedMissing.has(nameKey(name))) {
          this.warnedMissing.add(nameKey(name))
          this.warnings.push(`No ${MISSING_WORDS[load.missing]} for ${name}: its loads stay unresolved until one is recorded`)
        }
        out.push({
          slot: slot.id, exercise: name, role: dto.role, sets: dto.sets ?? null, reps: dto.reps ?? null, load,
          technique: dto.technique ?? null, optional: dto.optional, note: dto.note ?? null, projected: this.liveMax.has(nameKey(name)),
        })
      }
    }
    return out
  }

  /** Forced progression at the start of a new block of the same module (upper and lower body increments). */
  progress(m: StrengthModuleDef, choice: ModuleChoice, week: number): void {
    if (this.c.projectProgression === false) return
    const prog = m.progression
    if (!prog) return
    for (const slot of m.slots) {
      const chosen = choice.exercises?.[slot.id] ?? []
      let names = isCluster(m, slot) ? chosen : chosen.slice(0, 1)
      if (names.length === 0) names = slot.pick == null ? [slot.standard] : []
      const lower = slot.role === 'squat' || slot.role === 'hinge'
      for (const name of names) {
        const from = this.currentOneRm(name)
        if (from == null) continue
        const to = from + progressionIncrementKg(lower ? prog.lower : prog.upper, prog.unit, this.stepKg)
        this.liveMax.set(nameKey(name), to)
        this.progressions.push({ weekIndex: week, moduleKey: m.key, exercise: name, fromKg: from, toKg: to })
      }
    }
  }

  conditioningFor(key: string | null, label: string | null, category: string | null, params: Record<string, unknown> | null, defaultMinutes: NumRange | null = null): GeneratedConditioning {
    const def = key ? this.idx.session(key) : undefined
    const minutes = (params ? toNumRange(params.minutes) : null) ?? defaultMinutes ?? def?.prescription.minutes ?? null
    return { sessionKey: def?.key ?? key, label: label ?? def?.title ?? 'Conditioning', category: def?.category ?? category, params, minutes }
  }
}

interface Cycle { lastWeek: number; seg: number; seen: boolean }

// ---- fixed-grid templates ----
export function generateTemplateBlock(template: TemplateDef, idx: DefinitionIndex, c: GenChoices, eq: GenEquipment = DEFAULT_EQUIPMENT, maxes: MaxLookup = () => null): GeneratedBlock {
  const g = new Gen(idx, c, eq, maxes)
  const cycles = new Map<string, Cycle>()
  let counted = 0
  for (const gw of template.grid) {
    if (gw.counts_toward_block) counted++
    const perModuleCount = new Map<string, number>()
    const moduleWeekSeen = new Set<string>()
    for (const day of [...gw.days].sort((a, b) => a.day - b.day)) {
      day.cells.forEach((cell, cellIdx) => {
        const slotInDay = cellIdx + 1
        const kindRef = before(cell.ref, ':')
        const target = after(cell.ref, ':')
        switch (kindRef) {
          case 'rest': break
          case 'test': g.emit(gw.week, gw.counts_toward_block, day.day, slotInDay, 'max_strength', 'test', cell.ref, cell.label ?? `Test: ${target.replaceAll('_', ' ')}`); break
          case 'inline': g.emit(gw.week, gw.counts_toward_block, day.day, slotInDay, 'recovery', gw.kind === 'deload' || gw.kind === 'taper' ? 'deload' : 'recovery', cell.ref, cell.label ?? 'Session'); break
          case 'cond': {
            const key = target.startsWith('$') ? g.pickConditioningVariable(target.slice(1)) : target
            const def = key ? idx.session(key) : undefined
            if (!def) {
              g.warnings.push(`Week ${gw.week} day ${day.day}: choose a session for ${cell.label ?? cell.ref}`)
              g.emit(gw.week, gw.counts_toward_block, day.day, slotInDay, 'aerobic_base', 'progression', cell.ref, cell.label ?? 'Choose a session', [],
                g.conditioningFor(null, cell.label ?? 'Choose a session', null, cell.params ?? null))
            } else {
              g.emit(gw.week, gw.counts_toward_block, day.day, slotInDay, sessionDomain(def), gw.kind === 'deload' ? 'deload' : 'progression', `cond:${def.key}`, def.title, [],
                g.conditioningFor(def.key, null, null, cell.params ?? null))
            }
            break
          }
          case 'strength': case 'se':
            strengthCell(g, idx, c, cell, target, gw.week, gw.counts_toward_block, day.day, slotInDay, cycles, perModuleCount, moduleWeekSeen)
            break
          default: g.warnings.push(`Week ${gw.week} day ${day.day}: unknown cell '${cell.ref}'`)
        }
      })
    }
  }
  return finish(g, c, counted)
}

function strengthCell(g: Gen, idx: DefinitionIndex, c: GenChoices, cell: Cell, target: string, week: number, counts: boolean, day: number, slotInDay: number,
  cycles: Map<string, Cycle>, perModuleCount: Map<string, number>, moduleWeekSeen: Set<string>): void {
  const key = target.startsWith('$') ? before(c.variables?.[target.slice(1)] ?? '', ',') || null : target
  const strength = key ? idx.strength(key) : undefined
  const seMod = key ? idx.se(key) : undefined
  if (!strength && !seMod) {
    g.warnings.push(`Week ${week} day ${day}: choose a strength module for ${cell.label ?? cell.ref}`)
    g.emit(week, counts, day, slotInDay, 'max_strength', 'progression', cell.ref, cell.label ?? 'Choose a strength module')
    return
  }
  const moduleKey = (strength ?? seMod)!.key
  let cyc = cycles.get(moduleKey)
  if (!cyc) { cyc = { lastWeek: -10, seg: 0, seen: false }; cycles.set(moduleKey, cyc) }
  if (!moduleWeekSeen.has(moduleKey)) {
    moduleWeekSeen.add(moduleKey)
    cyc.seg = cyc.lastWeek === week - 1 && cyc.seen ? cyc.seg + 1 : 0
    cyc.lastWeek = week
  }
  const n = perModuleCount.get(moduleKey) ?? 0
  perModuleCount.set(moduleKey, n + 1)
  const choice = c.modules?.[moduleKey] ?? {}
  if (strength) {
    const variant = pickVariant(strength, choice)
    const len = clamp(choice.blockLength ?? variant.weeks.length, 1, variant.weeks.length)
    const pos = cyc.seg % len
    if (pos === 0 && cyc.seen && n === 0) g.progress(strength, choice, week)
    cyc.seen = true
    const sessionParam = cell.params?.session
    const sessionId = typeof sessionParam === 'string' || typeof sessionParam === 'number' ? String(sessionParam) : strength.sessions[n % strength.sessions.length]?.id
    const sessionDef = strength.sessions.find((s) => s.id === sessionId)
    const vw = variant.weeks[pos]
    const ws = vw?.sessions.find((s) => s.session === sessionId)
    if (!ws || !sessionDef) {
      g.warnings.push(`Week ${week}: ${strength.title} has no prescription for ${sessionId} in its week ${pos + 1}`)
      return
    }
    const notes = [...(ws.note ? [ws.note] : []), ...(vw.kind === 'peak' && strength.peak?.note ? [strength.peak.note] : [])]
    g.emit(week, counts, day, slotInDay, strength.domain, vw.kind === 'deload' ? 'deload' : 'progression', `strength:${strength.key}`, `${strength.title}: ${sessionDef.label}`,
      g.resolveItems(strength, choice, ws.items), null, notes)
  } else {
    const se = seMod!
    const pos = Math.min(cyc.seg, se.weeks.length - 1)
    cyc.seen = true
    const w = se.weeks[pos]
    const names = choice.exercises?.SE ?? []
    const note = se.load_pct_1rm ? `light: ${Math.trunc(se.load_pct_1rm.min)}-${Math.trunc(se.load_pct_1rm.max)}% of 1RM, reps first` : null
    const items: GeneratedItem[] = names.map((ex) => ({ slot: 'SE', exercise: ex, role: 'circuit', sets: w.circuits, reps: w.reps, load: { kind: 'no_load' }, technique: null, optional: false, note, projected: false }))
    if (names.length === 0) g.warnings.push(`${se.title}: choose the exercises of the circuit`)
    g.emit(week, counts, day, slotInDay, 'strength_endurance', 'progression', `se:${se.key}`, se.title, items)
  }
}

// ---- merged blocks: a strength module and a conditioning protocol on the lifter's own layout ----

export function generateComposedBlock(strength: StrengthModuleDef | null, protocol: ConditioningProtocolDef | null, idx: DefinitionIndex, c: GenChoices,
  eq: GenEquipment = DEFAULT_EQUIPMENT, maxes: MaxLookup = () => null): GeneratedBlock {
  if (!strength && !protocol) throw new Error('a composed block needs a strength module or a conditioning protocol')
  const total = c.weeks ?? 6
  const choice = strength ? c.modules?.[strength.key] ?? {} : {}
  const variant = strength ? pickVariant(strength, choice) : null
  const len = variant ? clamp(choice.blockLength ?? variant.weeks.length, 1, variant.weeks.length) : 1
  const timeline: WeekKind[] = strength ? defaultTimeline(len, total, false, c.deloadAfterBlock !== false) : Array.from({ length: total }, () => 'normal' as const)
  const bp: BlockBlueprint = { strengthKey: strength?.key ?? null, protocolKey: protocol?.key ?? null, layout: suggestLayout(strength, protocol, weekdayOf(c.startDate), c.conditioningPerWeek), timeline }
  return mergeBlock(bp, strength, protocol, idx, c, eq, maxes)
}

export function generateMergedBlock(bp: BlockBlueprint, idx: DefinitionIndex, c: GenChoices, eq: GenEquipment = DEFAULT_EQUIPMENT, maxes: MaxLookup = () => null): GeneratedBlock {
  return mergeBlock(bp, bp.strengthKey ? idx.strength(bp.strengthKey) ?? null : null, bp.protocolKey ? idx.protocol(bp.protocolKey) ?? null : null, idx, c, eq, maxes)
}

const TEST_ROLES = new Set(['press', 'squat', 'hinge', 'pull', 'power'])

function mergeBlock(bp: BlockBlueprint, strength: StrengthModuleDef | null, protocol: ConditioningProtocolDef | null, idx: DefinitionIndex, c: GenChoices, eq: GenEquipment, maxes: MaxLookup): GeneratedBlock {
  if (!strength && !protocol) throw new Error('a block needs a strength module or a conditioning protocol')
  const g = new Gen(idx, c, eq, maxes)
  const choice = strength ? c.modules?.[strength.key] ?? {} : {}
  const variant = strength ? pickVariant(strength, choice) : null
  const len = variant ? clamp(choice.blockLength ?? variant.weeks.length, 1, variant.weeks.length) : 1
  const startDay = weekdayOf(c.startDate)
  const position = (d: Weekday) => ((d - startDay + 7) % 7) + 1
  const strengthDays = bp.layout.filter((d) => d.strengthSession != null).sort((a, b) => position(a.weekday) - position(b.weekday))

  let blockPos = 0
  let skipProgress = false
  let normalOrdinal = 0
  let counted = 0
  const rotation = new Map<string, number>()
  bp.timeline.forEach((kind, i) => {
    const week = i + 1
    if (countsTowardBlock(kind)) counted++
    if (kind === 'normal') normalOrdinal++
    if (strength && variant) {
      if (kind === 'normal') {
        if (blockPos === len) { blockPos = 0; if (!skipProgress) g.progress(strength, choice, week) }
        skipProgress = false
        const vw = variant.weeks[blockPos]
        for (const d of strengthDays) {
          const sd = strength.sessions[d.strengthSession!]
          if (!sd) continue
          const ws = vw.sessions.find((s) => s.session === sd.id)
          if (!ws) continue
          const notes = [...(ws.note ? [ws.note] : []), ...(vw.kind === 'peak' && strength.peak?.note ? [strength.peak.note] : [])]
          g.emit(week, true, position(d.weekday), 1, strength.domain, 'progression', `strength:${strength.key}`, `${strength.title}: ${sd.label}`, g.resolveItems(strength, choice, ws.items), null, notes)
        }
        blockPos++
      } else if (kind === 'deload') {
        const d = strengthDays[0]
        if (d) g.emit(week, false, position(d.weekday), 1, 'recovery', 'deload', 'inline', 'Deload week: reduced volume and intensity')
      } else {
        skipProgress = true
        for (const d of strengthDays) {
          const sd = strength.sessions[d.strengthSession!]
          if (!sd) continue
          const roles = new Map(strength.slots.map((s) => [s.id, s]))
          const items: GeneratedItem[] = sd.slots.map((id) => roles.get(id)).filter((s): s is SlotDef => !!s && TEST_ROLES.has(s.role) && !s.optional && s.pick == null)
            .map((slot) => ({
              slot: slot.id, exercise: choice.exercises?.[slot.id]?.[0] ?? slot.standard, role: 'test', sets: null, reps: null,
              load: { kind: 'work_up', minRm: 1, maxRm: 3 }, technique: null, optional: false, note: 'work up to a heavy single or triple and record it', projected: false,
            }))
          g.emit(week, true, position(d.weekday), 1, 'max_strength', 'test', 'test:one_rm', `Test: ${sd.label}`, items)
        }
      }
    }
    if (protocol) addConditioning(g, protocol, bp.layout, position, week, kind, normalOrdinal, rotation)
  })
  return finish(g, c, counted, bp.timeline.length)
}

function addConditioning(g: Gen, p: ConditioningProtocolDef, layout: DayPlan[], position: (d: Weekday) => number, week: number, kind: WeekKind, normalOrdinal: number, rotation: Map<string, number>): void {
  const b = p.budget
  const normal = kind === 'normal'
  const hicWeek = normal && (b?.high_intensity_every_n_weeks != null ? (normalOrdinal - 1) % b.high_intensity_every_n_weeks === 0 : true)
  const slots = layout.filter((d) => d.conditioning != null).sort((a, b2) => position(a.weekday) - position(b2.weekday))
    .filter((d) => { const k = d.conditioning!.kind; return (normal && (k !== 'hic' || hicWeek)) || (!normal && k === 'lic') })
  const licCount = slots.filter((d) => d.conditioning!.kind === 'lic').length
  const adjust = p.week_adjustments.find((a) => a.when === (hicWeek ? 'week_has_high_intensity' : 'week_without_high_intensity'))
  const licBudget = adjust?.low_intensity_minutes_per_week ?? b?.low_intensity_minutes_per_week
  const minSession = b?.session_min_minutes ?? 0
  for (const d of slots) {
    const s = d.conditioning!
    let list = g.c.conditioning?.[s.kind] ?? []
    if (list.length === 0) list = s.kind === 'lic' ? p.suggested.lic : s.kind === 'hic' ? p.suggested.hic : p.suggested.wc
    const i = rotation.get(s.kind) ?? 0
    rotation.set(s.kind, i + 1)
    const key = list[i % Math.max(1, list.length)] ?? null
    let share: NumRange | null = null
    if (s.kind === 'lic' && licBudget && licCount > 0) {
      const full = Math.max(minSession, Math.floor(licBudget.min / licCount / 5) * 5)
      share = rangeOf(normal ? full : Math.max(minSession, Math.floor(full / 2 / 5) * 5))
    }
    const minutes = s.minutes ?? share
    const def = key ? g.idx.session(key) : undefined
    const label = def?.title ?? `Choose a ${s.kind.toUpperCase()} session`
    if (!def) g.warnings.push(`Week ${week}: choose a ${s.kind.toUpperCase()} session for ${p.title}`)
    g.emit(week, countsTowardBlock(kind), position(d.weekday), d.strengthSession != null ? 2 : 1, def ? sessionDomain(def) : categoryDomain(s.kind),
      normal ? 'progression' : 'deload', def ? `cond:${def.key}` : `cond:${s.kind}`, label, [], g.conditioningFor(key, label, s.kind, null, minutes))
  }
}

function finish(g: Gen, c: GenChoices, counted: number, plannedWeeks?: number): GeneratedBlock {
  const weeks = plannedWeeks ?? g.sessions.reduce((m, s) => Math.max(m, s.weekIndex), 0)
  const end = plusDays(c.startDate, 7 * Math.max(1, weeks) - 1)
  return { sessions: g.sessions, calendarWeeks: weeks, countedWeeks: counted, endDate: end, progressions: g.progressions, warnings: [...new Set(g.warnings)] }
}
