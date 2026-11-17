// Port of domain/training/Prescription.kt. A prescription is a rule (80% of the 1RM), never a stored kilogram; the
// resolver turns it into an executable target and says explicitly when an input is missing instead of guessing.

import { type Loadable, loadableFor, roundToStep } from './plates.ts'

export type LoadSpec =
  | { kind: 'pct_1rm'; pct: number }
  | { kind: 'pct_tm'; pct: number }
  | { kind: 'pct_max_reps'; pct: number }
  | { kind: 'work_up_rm'; minRm: number; maxRm: number }
  | { kind: 'fixed'; kg: number }
  | { kind: 'rpe'; rpe: number }
  | { kind: 'bodyweight' }
  | { kind: 'none' }

export interface PrescriptionItem {
  slot: string
  load: LoadSpec
  weightedCalisthenics: boolean
}

/** What a weighted-calisthenics percentage is of: total (the book, bodyweight included) or added (the lifter's logs). */
export type PercentBase = 'total' | 'added'

export interface ResolveContext {
  oneRmKg?: number | null
  trainingMaxKg?: number | null
  maxReps?: number | null
  bodyweightKg?: number | null
  barKg: number
  platesKg: number[]
  addedStepKg: number
  weightedBase: PercentBase
}

export type Missing = 'OneRm' | 'TrainingMax' | 'MaxReps' | 'Bodyweight'

export type ResolvedLoad =
  | { kind: 'barbell'; pct: number; targetKg: number; loadable: Loadable }
  | { kind: 'added'; pct: number; totalKg: number | null; addedKg: number }
  | { kind: 'max_reps_fraction'; pct: number; reps: number }
  | { kind: 'work_up'; minRm: number; maxRm: number }
  | { kind: 'fixed_kg'; kg: number; loadable: Loadable }
  | { kind: 'rpe'; rpe: number }
  | { kind: 'bodyweight' }
  | { kind: 'no_load' }
  | { kind: 'unresolved'; missing: Missing }

const roundHalfUp = (x: number): number => Math.floor(x + 0.5)

export function resolveLoad(item: PrescriptionItem, ctx: ResolveContext): ResolvedLoad {
  const spec = item.load
  switch (spec.kind) {
    case 'pct_1rm':
      if (item.weightedCalisthenics) return resolveWeighted(spec.pct, ctx)
      if (ctx.oneRmKg == null) return { kind: 'unresolved', missing: 'OneRm' }
      return barbell(spec.pct, ctx.oneRmKg, ctx)
    case 'pct_tm':
      return ctx.trainingMaxKg != null ? barbell(spec.pct, ctx.trainingMaxKg, ctx) : { kind: 'unresolved', missing: 'TrainingMax' }
    case 'pct_max_reps':
      return ctx.maxReps != null ? { kind: 'max_reps_fraction', pct: spec.pct, reps: roundHalfUp((spec.pct / 100) * ctx.maxReps) } : { kind: 'unresolved', missing: 'MaxReps' }
    case 'work_up_rm': return { kind: 'work_up', minRm: spec.minRm, maxRm: spec.maxRm }
    case 'fixed': return { kind: 'fixed_kg', kg: spec.kg, loadable: loadableFor(spec.kg, ctx.barKg, ctx.platesKg) }
    case 'rpe': return { kind: 'rpe', rpe: spec.rpe }
    case 'bodyweight': return { kind: 'bodyweight' }
    case 'none': return { kind: 'no_load' }
  }
}

function barbell(pct: number, maxKg: number, ctx: ResolveContext): ResolvedLoad {
  const target = (pct / 100) * maxKg
  return { kind: 'barbell', pct, targetKg: target, loadable: loadableFor(target, ctx.barKg, ctx.platesKg) }
}

function resolveWeighted(pct: number, ctx: ResolveContext): ResolvedLoad {
  const oneRm = ctx.oneRmKg
  if (oneRm == null) return { kind: 'unresolved', missing: 'OneRm' }
  if (ctx.weightedBase === 'added') {
    const added = roundToStep((pct / 100) * oneRm, ctx.addedStepKg)
    return { kind: 'added', pct, totalKg: ctx.bodyweightKg != null ? ctx.bodyweightKg + added : null, addedKg: added }
  }
  const bodyweight = ctx.bodyweightKg
  if (bodyweight == null) return { kind: 'unresolved', missing: 'Bodyweight' }
  const total = (pct / 100) * oneRm
  if (total <= bodyweight) {
    if (ctx.maxReps == null) return { kind: 'unresolved', missing: 'MaxReps' }
    return { kind: 'max_reps_fraction', pct, reps: roundHalfUp((pct / 100) * ctx.maxReps) }
  }
  return { kind: 'added', pct, totalKg: total, addedKg: roundToStep(total - bodyweight, ctx.addedStepKg) }
}
