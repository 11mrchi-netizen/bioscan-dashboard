// Lifting gear (straps, belt, ...): what a set was done with, the person's rules for when they use it ("straps from 140 kg"), and
// the comparison of the same lift with and without. Pure, so the console, the Edge Functions and the tests share it; the phone mirrors
// the rule in domain/training/Gear.kt (the same cases are tested on both sides).
//
// A set's `gear` is a list of tags. ABSENT means "not recorded" (every set before this feature), `[]` means "recorded: no gear". Never
// read a missing list as "no gear": the comparison only uses sets where it was recorded.
import { movementKey } from './maxes.ts'

export const GEAR_TAGS = ['straps', 'belt', 'sleeves', 'wraps', 'chalk', 'hook', 'shoes'] as const
export type GearTag = (typeof GEAR_TAGS)[number]
export const GEAR_LABEL: Record<GearTag, string> = { straps: 'Straps', belt: 'Belt', sleeves: 'Sleeves', wraps: 'Wraps', chalk: 'Chalk', hook: 'Hook grip', shoes: 'Lifting shoes' }

/** One rule: use `gear` on `movement_key` from `from_kg` upward (null: always). The key is `movementKey(name)`. */
export interface GearRule { movement_key: string; gear: string; from_kg: number | null }

const isTag = (g: unknown): g is GearTag => typeof g === 'string' && (GEAR_TAGS as readonly string[]).includes(g)

/** The recorded gear of a set: known tags, in the standard order, no repeats; `null` when it was not recorded. */
export function recordedGear(set: { gear?: unknown } | null | undefined): GearTag[] | null {
  const g = set?.gear
  if (!Array.isArray(g)) return null
  return GEAR_TAGS.filter((t) => g.includes(t))
}

/** Clean a list of tags the person picked (unknown tags dropped, standard order). */
export function cleanGear(g: readonly string[]): GearTag[] {
  return GEAR_TAGS.filter((t) => g.includes(t))
}

/** The gear the person's rules call for on a set of `name` at `weightKg`. A rule with no threshold applies at any load; a weight of 0 or unknown applies only those. */
export function gearForLoad(rules: readonly GearRule[], name: string, weightKg: number | null | undefined): GearTag[] {
  const key = movementKey(name)
  const w = typeof weightKg === 'number' && Number.isFinite(weightKg) ? weightKg : null
  const on = rules.filter((r) => r.movement_key === key && isTag(r.gear) && (r.from_kg == null || (w != null && w >= r.from_kg))).map((r) => r.gear)
  return cleanGear(on)
}

/**
 * What to pre-fill on a new set: the rules' gear at that load, or `null` (leave it not recorded) when the lift has no rule at all,
 * so a lift the person never set up is not recorded as "no gear" by default.
 */
export function suggestGear(rules: readonly GearRule[], name: string, weightKg: number | null | undefined): GearTag[] | null {
  const key = movementKey(name)
  if (!rules.some((r) => r.movement_key === key)) return null
  return gearForLoad(rules, name, weightKg)
}

/** The rules of one lift, for a line such as "belt from 160 kg · straps from 140 kg" (lowest threshold first). Empty when none. */
export function describeRules(rules: readonly GearRule[], name: string): string {
  const key = movementKey(name)
  return rules
    .filter((r) => r.movement_key === key && isTag(r.gear))
    .sort((a, b) => (a.from_kg ?? 0) - (b.from_kg ?? 0) || GEAR_TAGS.indexOf(a.gear as GearTag) - GEAR_TAGS.indexOf(b.gear as GearTag))
    .map((r) => `${GEAR_LABEL[r.gear as GearTag].toLowerCase()}${r.from_kg == null ? '' : ` from ${r.from_kg} kg`}`)
    .join(' · ')
}

// ---------------------------------------------------------------------------------------------------------------------------------
// With and without
// ---------------------------------------------------------------------------------------------------------------------------------

export interface GearSetRow { name: string; weight_kg: number; reps: number; rpe?: number | null; gear?: unknown; date?: string }

export interface GearSide { sets: number; meanReps: number | null; meanRpe: number | null; bestE1rmKg: number | null }
export interface GearComparison {
  movement: string
  gear: GearTag
  with: GearSide
  without: GearSide
  /** The load range (kg) where the lift was done both with and without the gear: the fair part to compare. Null when the ranges do not meet. */
  overlapKg: [number, number] | null
  /** Sets inside the overlap, per side. */
  inOverlap: { with: GearSide; without: GearSide } | null
  /** Enough in the overlap to say anything: at least MIN_SETS_PER_SIDE on both sides. */
  enough: boolean
}
export const MIN_SETS_PER_SIDE = 8

const e1rm = (w: number, reps: number) => (reps <= 0 || w <= 0 ? null : reps === 1 ? w : w * (1 + reps / 30)) // Epley
const mean = (x: number[]) => (x.length ? x.reduce((a, b) => a + b, 0) / x.length : null)
const r1 = (x: number | null) => (x == null ? null : Math.round(x * 10) / 10)

function side(sets: GearSetRow[]): GearSide {
  const rpes = sets.map((s) => s.rpe).filter((v): v is number => typeof v === 'number')
  const best = sets.map((s) => e1rm(s.weight_kg, s.reps)).filter((v): v is number => v != null)
  return { sets: sets.length, meanReps: r1(mean(sets.map((s) => s.reps))), meanRpe: r1(mean(rpes)), bestE1rmKg: best.length ? r1(Math.max(...best)) : null }
}

/**
 * Every lift and gear tag that has sets both with and without the gear, among sets where gear was RECORDED. Gear is used at the heavy
 * end, so a plain average would compare light sets without a belt with heavy sets with one; the comparison that means something is
 * inside the load range both sides share, and it says when that range holds too few sets to read.
 */
export function gearComparisons(rows: readonly GearSetRow[]): GearComparison[] {
  const byLift = new Map<string, { label: string; sets: Array<GearSetRow & { g: GearTag[] }> }>()
  for (const r of rows) {
    const g = recordedGear(r)
    if (g == null || !(r.weight_kg > 0) || !(r.reps > 0)) continue
    const k = movementKey(r.name)
    const e = byLift.get(k) ?? { label: r.name, sets: [] }
    e.sets.push({ ...r, g })
    byLift.set(k, e)
  }
  const out: GearComparison[] = []
  for (const [key, lift] of byLift) {
    for (const tag of GEAR_TAGS) {
      const w = lift.sets.filter((s) => s.g.includes(tag)), wo = lift.sets.filter((s) => !s.g.includes(tag))
      if (w.length === 0 || wo.length === 0) continue
      const lo = Math.max(Math.min(...w.map((s) => s.weight_kg)), Math.min(...wo.map((s) => s.weight_kg)))
      const hi = Math.min(Math.max(...w.map((s) => s.weight_kg)), Math.max(...wo.map((s) => s.weight_kg)))
      const overlap: [number, number] | null = lo <= hi ? [lo, hi] : null
      const inO = overlap ? { with: side(w.filter((s) => s.weight_kg >= lo && s.weight_kg <= hi)), without: side(wo.filter((s) => s.weight_kg >= lo && s.weight_kg <= hi)) } : null
      out.push({
        movement: key, gear: tag, with: side(w), without: side(wo), overlapKg: overlap, inOverlap: inO,
        enough: !!inO && inO.with.sets >= MIN_SETS_PER_SIDE && inO.without.sets >= MIN_SETS_PER_SIDE,
      })
    }
  }
  return out.sort((a, b) => a.movement.localeCompare(b.movement) || GEAR_TAGS.indexOf(a.gear) - GEAR_TAGS.indexOf(b.gear))
}
