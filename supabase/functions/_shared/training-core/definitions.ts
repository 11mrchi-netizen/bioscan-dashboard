// Port of domain/training/definition/Definitions.kt: the shapes of the program definitions stored privately in
// training_definitions.body (docs/training-programming/02-schema-and-definitions.md). Fields keep the stored JSON names.
// The phone validates strictly on import; here a definition is read as stored, and every number-or-range field is
// normalised to {min, max} so the engine never has to ask which form it got.

export interface NumRange { min: number; max: number }
export const isFixed = (r: NumRange): boolean => r.min === r.max
export const rangeOf = (x: number): NumRange => ({ min: x, max: x })

/** A number written as `5` or as `{"min": 3, "max": 5}`; anything else is null. */
export function toNumRange(x: unknown): NumRange | null {
  if (typeof x === 'number' && Number.isFinite(x)) return rangeOf(x)
  if (x && typeof x === 'object' && !Array.isArray(x)) {
    const o = x as Record<string, unknown>
    if (typeof o.min === 'number' && typeof o.max === 'number') return { min: o.min, max: o.max }
  }
  return null
}

type Json = Record<string, unknown>

export interface LoadDto { kind: string; value?: number; min_rm?: number; max_rm?: number; kg?: number; rpe?: number }
export interface ItemDto {
  slot: string; role: string; sets?: NumRange; reps?: NumRange; load: LoadDto; rest_s?: NumRange; optional: boolean
  option_of?: string; technique?: string; pair?: string; note?: string
}
export interface SlotDef { id: string; role: string; standard: string; alternates: string[]; weighted_calisthenics: boolean; optional: boolean; pick?: NumRange }
export interface SessionDef { id: string; label: string; slots: string[]; note?: string }
export interface WeekSessionDef { session: string; items: ItemDto[]; note?: string }
export interface WeekDef { week: number; kind: string; counts_toward_block: boolean; sessions: WeekSessionDef[] }
export interface VariantDef { key: string; title: string; weeks: WeekDef[]; note?: string }
export interface OptionDef { id: string; title: string; kind: string; description: string; weeks?: number[]; effect?: Json }
export interface PeakDef { techniques: string[]; every_weeks?: NumRange; schedule_days: number[]; note?: string }
export interface ProgressionDef { unit: string; upper: NumRange; lower: NumRange; after_weeks: NumRange; skip_if_incomplete: boolean; note?: string }
export interface AltCluster { label: string; sessions: Record<string, string[]>; note?: string }
export interface SeWeek { week: number; circuits: NumRange; reps: NumRange; kind: string; counts_toward_block: boolean }
export interface Budget {
  low_intensity_minutes_per_week?: NumRange; session_min_minutes?: number; high_intensity_per_week?: NumRange
  high_intensity_every_n_weeks?: number; sessions_per_week?: NumRange; strength_sessions_per_week?: NumRange
  endurance_sessions_per_week?: NumRange; endurance_every_n_weeks?: number; easy_week_every_n_weeks?: number
  min_conditioning_sessions_per_week?: number; min_rest_days_per_week?: number
}
export interface WeekAdjustment { when: string; low_intensity_minutes_per_week: NumRange }
export interface LayoutDay { day: number; kind: string; minutes?: NumRange; note?: string }
export interface Suggested { lic: string[]; hic: string[]; wc: string[] }
export interface Cell { ref: string; params?: Json; label?: string; items?: ItemDto[] }
export interface GridDay { day: number; cells: Cell[] }
export interface GridWeek { week: number; kind: string; counts_toward_block: boolean; days: GridDay[] }

interface Base { kind: string; schema_version: number; key: string; version: number; title: string; source_ref?: string; notes: string[] }

export interface StrengthModuleDef extends Base {
  kind: 'strength_module'; family: string; domain: string; sessions_per_week: number; day_positions: number[]
  slots: SlotDef[]; sessions: SessionDef[]; variants: VariantDef[]; block_lengths: number[]; options: OptionDef[]
  peak?: PeakDef; progression?: ProgressionDef; scheduling_rules: string[]; alt_clusters: AltCluster[]; compatible_conditioning: string[]
}
export interface SeModuleDef extends Base {
  kind: 'se_module'; sessions_per_week: number; day_positions: number[]; cluster_size: NumRange; weeks: SeWeek[]
  load_pct_1rm?: NumRange; finisher?: string; options: OptionDef[]; sample_clusters: AltCluster[]; compatible_conditioning: string[]
}
export interface ConditioningSessionDef extends Base {
  kind: 'conditioning_session'; category: string; domains: string[]; aliases: string[]
  prescription: { by: string[]; minutes?: NumRange; rounds?: NumRange; distance_km?: NumRange }
  structure: { type: string; steps: string[] }; intensity?: { rpe?: NumRange; hr_pct_max?: NumRange; cue?: string }
  modes: string[]; doubles_as_wc: boolean
}
export interface ConditioningProtocolDef extends Base {
  kind: 'conditioning_protocol'; protocol_type: string; oa_type?: string; weeks?: NumRange; budget?: Budget
  week_adjustments: WeekAdjustment[]; default_layout: LayoutDay[]; suggested: Suggested; rotation: string[]
  variants: { key: string; title: string; strength?: string; budget?: Budget; note?: string }[]
  pairs_well_with: string[]; min_session_note?: string
}
export interface CompositionDef extends Base { kind: 'composition'; strength: { choose_from: string[]; optional: boolean }; conditioning: { choose_from: string[]; optional: boolean }; weeks: NumRange }
export interface TemplateDef extends Base {
  kind: 'template'; weeks: number; domains: string[]; grid: GridWeek[]; budget?: Budget; benchmark?: Json; variables?: Json; minimums: string[]
}
export interface SystemDef extends Base { kind: 'system'; subtype: string; blocks: Json[]; repeat: boolean }

export type Definition = StrengthModuleDef | SeModuleDef | ConditioningSessionDef | ConditioningProtocolDef | CompositionDef | TemplateDef | SystemDef

// ---- normalisation ----

const arr = <T>(x: unknown, f: (v: unknown) => T): T[] => (Array.isArray(x) ? x.map(f) : [])
const strs = (x: unknown): string[] => arr(x, (v) => String(v))
const nums = (x: unknown): number[] => arr(x, (v) => Number(v))
const opt = (x: unknown): NumRange | undefined => toNumRange(x) ?? undefined
const str = (x: unknown): string | undefined => (typeof x === 'string' ? x : undefined)
const obj = (x: unknown): Json => (x && typeof x === 'object' && !Array.isArray(x) ? (x as Json) : {})

function item(x: unknown): ItemDto {
  const o = obj(x)
  return {
    slot: String(o.slot), role: str(o.role) ?? 'main', sets: opt(o.sets), reps: opt(o.reps),
    load: (o.load as LoadDto) ?? { kind: 'none' }, rest_s: opt(o.rest_s), optional: o.optional === true,
    option_of: str(o.option_of), technique: str(o.technique), pair: str(o.pair), note: str(o.note),
  }
}

function budget(x: unknown): Budget | undefined {
  if (!x || typeof x !== 'object') return undefined
  const o = obj(x)
  const n = (v: unknown) => (typeof v === 'number' ? v : undefined)
  return {
    low_intensity_minutes_per_week: opt(o.low_intensity_minutes_per_week), session_min_minutes: n(o.session_min_minutes),
    high_intensity_per_week: opt(o.high_intensity_per_week), high_intensity_every_n_weeks: n(o.high_intensity_every_n_weeks),
    sessions_per_week: opt(o.sessions_per_week), strength_sessions_per_week: opt(o.strength_sessions_per_week),
    endurance_sessions_per_week: opt(o.endurance_sessions_per_week), endurance_every_n_weeks: n(o.endurance_every_n_weeks),
    easy_week_every_n_weeks: n(o.easy_week_every_n_weeks), min_conditioning_sessions_per_week: n(o.min_conditioning_sessions_per_week),
    min_rest_days_per_week: n(o.min_rest_days_per_week),
  }
}

/** Reads a stored definition body. Throws on a kind the engine does not know. */
export function parseDefinition(input: unknown): Definition {
  const o = obj(typeof input === 'string' ? JSON.parse(input) : input)
  const base = {
    schema_version: typeof o.schema_version === 'number' ? o.schema_version : 1, key: String(o.key),
    version: typeof o.version === 'number' ? o.version : 1, title: String(o.title ?? o.key), source_ref: str(o.source_ref), notes: strs(o.notes),
  }
  switch (o.kind) {
    case 'strength_module':
      return {
        ...base, kind: 'strength_module', family: String(o.family ?? ''), domain: String(o.domain ?? 'max_strength'),
        sessions_per_week: Number(o.sessions_per_week ?? 0), day_positions: nums(o.day_positions),
        slots: arr(o.slots, (s) => {
          const so = obj(s)
          return { id: String(so.id), role: String(so.role ?? ''), standard: String(so.standard ?? ''), alternates: strs(so.alternates), weighted_calisthenics: so.weighted_calisthenics === true, optional: so.optional === true, pick: opt(so.pick) }
        }),
        sessions: arr(o.sessions, (s) => { const so = obj(s); return { id: String(so.id), label: String(so.label ?? so.id), slots: strs(so.slots), note: str(so.note) } }),
        variants: arr(o.variants, (v) => {
          const vo = obj(v)
          return {
            key: String(vo.key), title: String(vo.title ?? vo.key), note: str(vo.note),
            weeks: arr(vo.weeks, (w) => {
              const wo = obj(w)
              return {
                week: Number(wo.week), kind: str(wo.kind) ?? 'normal', counts_toward_block: wo.counts_toward_block !== false,
                sessions: arr(wo.sessions, (s) => { const so = obj(s); return { session: String(so.session), items: arr(so.items, item), note: str(so.note) } }),
              }
            }),
          }
        }),
        block_lengths: Array.isArray(o.block_lengths) ? nums(o.block_lengths) : [3, 6],
        options: arr(o.options, (x) => obj(x) as unknown as OptionDef),
        peak: o.peak ? (() => { const p = obj(o.peak); return { techniques: strs(p.techniques), every_weeks: opt(p.every_weeks), schedule_days: nums(p.schedule_days), note: str(p.note) } })() : undefined,
        progression: o.progression ? (() => {
          const p = obj(o.progression)
          return { unit: String(p.unit ?? 'kg'), upper: toNumRange(p.upper) ?? rangeOf(0), lower: toNumRange(p.lower) ?? rangeOf(0), after_weeks: toNumRange(p.after_weeks) ?? rangeOf(0), skip_if_incomplete: p.skip_if_incomplete !== false, note: str(p.note) }
        })() : undefined,
        scheduling_rules: strs(o.scheduling_rules), alt_clusters: arr(o.alt_clusters, (x) => obj(x) as unknown as AltCluster),
        compatible_conditioning: strs(o.compatible_conditioning),
      }
    case 'se_module':
      return {
        ...base, kind: 'se_module', sessions_per_week: Number(o.sessions_per_week ?? 0), day_positions: nums(o.day_positions),
        cluster_size: toNumRange(o.cluster_size) ?? rangeOf(1),
        weeks: arr(o.weeks, (w) => { const wo = obj(w); return { week: Number(wo.week), circuits: toNumRange(wo.circuits) ?? rangeOf(1), reps: toNumRange(wo.reps) ?? rangeOf(1), kind: str(wo.kind) ?? 'normal', counts_toward_block: wo.counts_toward_block !== false } }),
        load_pct_1rm: opt(o.load_pct_1rm), finisher: str(o.finisher), options: arr(o.options, (x) => obj(x) as unknown as OptionDef),
        sample_clusters: arr(o.sample_clusters, (x) => obj(x) as unknown as AltCluster), compatible_conditioning: strs(o.compatible_conditioning),
      }
    case 'conditioning_session': {
      const p = obj(o.prescription); const st = obj(o.structure); const it = o.intensity ? obj(o.intensity) : undefined
      return {
        ...base, kind: 'conditioning_session', category: String(o.category ?? 'lic'), domains: strs(o.domains), aliases: strs(o.aliases),
        prescription: { by: strs(p.by), minutes: opt(p.minutes), rounds: opt(p.rounds), distance_km: opt(p.distance_km) },
        structure: { type: String(st.type ?? 'custom'), steps: strs(st.steps) },
        intensity: it ? { rpe: opt(it.rpe), hr_pct_max: opt(it.hr_pct_max), cue: str(it.cue) } : undefined,
        modes: strs(o.modes), doubles_as_wc: o.doubles_as_wc === true,
      }
    }
    case 'conditioning_protocol': {
      const s = obj(o.suggested)
      return {
        ...base, kind: 'conditioning_protocol', protocol_type: String(o.protocol_type ?? ''), oa_type: str(o.oa_type), weeks: opt(o.weeks),
        budget: budget(o.budget),
        week_adjustments: arr(o.week_adjustments, (w) => { const wo = obj(w); return { when: String(wo.when), low_intensity_minutes_per_week: toNumRange(wo.low_intensity_minutes_per_week) ?? rangeOf(0) } }),
        default_layout: arr(o.default_layout, (d) => { const dd = obj(d); return { day: Number(dd.day), kind: String(dd.kind), minutes: opt(dd.minutes), note: str(dd.note) } }),
        suggested: { lic: strs(s.lic), hic: strs(s.hic), wc: strs(s.wc) }, rotation: strs(o.rotation),
        variants: arr(o.variants, (v) => { const vo = obj(v); return { key: String(vo.key), title: String(vo.title ?? vo.key), strength: str(vo.strength), budget: budget(vo.budget), note: str(vo.note) } }),
        pairs_well_with: strs(o.pairs_well_with), min_session_note: str(o.min_session_note),
      }
    }
    case 'composition': {
      const sa = obj(o.strength); const ca = obj(o.conditioning)
      return { ...base, kind: 'composition', strength: { choose_from: strs(sa.choose_from), optional: sa.optional === true }, conditioning: { choose_from: strs(ca.choose_from), optional: ca.optional === true }, weeks: toNumRange(o.weeks) ?? rangeOf(1) }
    }
    case 'template':
      return {
        ...base, kind: 'template', weeks: Number(o.weeks ?? 0), domains: strs(o.domains), budget: budget(o.budget),
        benchmark: o.benchmark ? obj(o.benchmark) : undefined, variables: o.variables ? obj(o.variables) : undefined, minimums: strs(o.minimums),
        grid: arr(o.grid, (w) => {
          const wo = obj(w)
          return {
            week: Number(wo.week), kind: str(wo.kind) ?? 'normal', counts_toward_block: wo.counts_toward_block !== false,
            days: arr(wo.days, (d) => {
              const dd = obj(d)
              return { day: Number(dd.day), cells: arr(dd.cells, (c) => { const co = obj(c); return { ref: String(co.ref), params: co.params ? obj(co.params) : undefined, label: str(co.label), items: Array.isArray(co.items) ? co.items.map(item) : undefined } }) }
            }),
          }
        }),
      }
    case 'system':
      return { ...base, kind: 'system', subtype: String(o.subtype ?? ''), blocks: arr(o.blocks, obj), repeat: o.repeat === true }
    default:
      throw new Error(`unknown definition kind '${String(o.kind)}'`)
  }
}

/** The latest version of each key, and conditioning sessions by alias (port of DefinitionIndex). */
export class DefinitionIndex {
  private byKey = new Map<string, Definition>()
  private aliases = new Map<string, ConditioningSessionDef>()
  readonly all: Definition[]
  constructor(defs: Definition[]) {
    for (const d of defs) {
      const cur = this.byKey.get(d.key)
      if (!cur || d.version > cur.version) this.byKey.set(d.key, d)
    }
    for (const d of defs) if (d.kind === 'conditioning_session') for (const a of d.aliases) this.aliases.set(a, d)
    this.all = [...this.byKey.values()]
  }
  any(key: string): Definition | undefined { return this.byKey.get(key) }
  strength(key: string): StrengthModuleDef | undefined { const d = this.byKey.get(key); return d?.kind === 'strength_module' ? d : undefined }
  se(key: string): SeModuleDef | undefined { const d = this.byKey.get(key); return d?.kind === 'se_module' ? d : undefined }
  session(key: string): ConditioningSessionDef | undefined { const d = this.byKey.get(key); return d?.kind === 'conditioning_session' ? d : this.aliases.get(key) }
  protocol(key: string): ConditioningProtocolDef | undefined { const d = this.byKey.get(key); return d?.kind === 'conditioning_protocol' ? d : undefined }
  template(key: string): TemplateDef | undefined { const d = this.byKey.get(key); return d?.kind === 'template' ? d : undefined }
  ofKind<K extends Definition['kind']>(kind: K): Extract<Definition, { kind: K }>[] {
    return this.all.filter((d) => d.kind === kind) as Extract<Definition, { kind: K }>[]
  }
}
