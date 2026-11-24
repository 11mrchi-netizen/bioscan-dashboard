// Port of the Android domain/fuel/FuelSchedule.kt: the editable fuel schedule of a race or a long session: a list of BLOCKS (one product, the nutrients in
// one intake, when the first intake is, how often, how many), what they expand into, add up to, and warn about, and the planner's plan as blocks to start
// from. The block JSON is the phone's FuelBlock (fuel_schedules.blocks), field for field. Parity by fixture (RaceFuelParityTest).

import { fmt } from './day.ts'
import { roundToInt } from './plates.ts'
import { type RaceFuelPlan, STEP_MIN } from './raceFuel.ts'

export type FuelForm = 'gel' | 'drink_mix' | 'chew' | 'bar' | 'water' | 'electrolyte_drink' | 'preworkout' | 'tablet' | 'other'
export const FUEL_FORMS: { key: FuelForm; label: string }[] = [
  { key: 'gel', label: 'GEL' }, { key: 'drink_mix', label: 'DRINK MIX' }, { key: 'chew', label: 'CHEWS' }, { key: 'bar', label: 'BAR' }, { key: 'water', label: 'WATER' },
  { key: 'electrolyte_drink', label: 'WATER + ELECTROLYTES' }, { key: 'preworkout', label: 'PRE-WORKOUT' }, { key: 'tablet', label: 'TABLET' }, { key: 'other', label: 'OTHER' },
]

/** One line of the schedule. The nutrient values are for ONE intake. startMin is minutes from the start (negative: before it); count intakes follow every intervalMin minutes. */
export interface FuelBlock {
  id: string; label: string; form: string; startMin: number; intervalMin: number; count: number
  carbsG: number; fluidMl: number; sodiumMg: number; caffeineMg: number; fuelingProductId: number | null; supplementProductId: number | null
}
export const blockEndMin = (b: FuelBlock) => b.startMin + b.intervalMin * Math.max(0, b.count - 1)

export interface ScheduledIntake { blockId: string; index: number; elapsedMin: number }

/** Every intake of every block, in time order (ties keep the blocks' order). */
export function expandSchedule(blocks: FuelBlock[]): ScheduledIntake[] {
  const all: [number, number, number][] = []
  blocks.forEach((b, bi) => { for (let i = 0; i < b.count; i++) all.push([bi, i, b.startMin + i * b.intervalMin]) })
  all.sort((a, b) => a[2] - b[2] || a[0] - b[0] || a[1] - b[1])
  return all.map(([b, i, t]) => ({ blockId: blocks[b].id, index: i, elapsedMin: t }))
}

export interface ScheduleTotals {
  carbsG: number; fluidMl: number; sodiumMg: number; caffeineMg: number; intakes: number; preStartCarbsG: number
  carbsPerHour: number | null; fluidPerHour: number | null; sodiumPerHour: number | null
}

/** What the schedule adds up to over a run of durationMin; rates count only intakes from the start on. */
export function scheduleTotals(blocks: FuelBlock[], durationMin: number): ScheduleTotals {
  const byId = new Map(blocks.map((b) => [b.id, b]))
  const all = expandSchedule(blocks).map((x) => [byId.get(x.blockId) as FuelBlock, x.elapsedMin] as [FuelBlock, number])
  const sum = (inRunOnly: boolean, pick: (b: FuelBlock) => number) => all.filter((x) => !inRunOnly || x[1] >= 0).reduce((a, x) => a + pick(x[0]), 0)
  const hours = durationMin / 60
  const rate = (v: number) => (durationMin > 0 ? v / hours : null)
  return {
    carbsG: sum(false, (b) => b.carbsG), fluidMl: sum(false, (b) => b.fluidMl), sodiumMg: sum(false, (b) => b.sodiumMg), caffeineMg: sum(false, (b) => b.caffeineMg),
    intakes: all.length, preStartCarbsG: all.filter((x) => x[1] < 0).reduce((a, x) => a + x[0].carbsG, 0),
    carbsPerHour: rate(sum(true, (b) => b.carbsG)), fluidPerHour: rate(sum(true, (b) => b.fluidMl)), sodiumPerHour: rate(sum(true, (b) => b.sodiumMg)),
  }
}

/** Why a schedule deserves a second look before the start; empty when nothing does. */
export function scheduleWarnings(blocks: FuelBlock[], durationMin: number, toleratedGPerHour: number): string[] {
  const out: string[] = []
  const t = scheduleTotals(blocks, durationMin)
  if (t.carbsPerHour != null && t.carbsPerHour > toleratedGPerHour * 1.1) out.push(`${fmt(t.carbsPerHour, 0)} g/h of carbohydrate is above the ${fmt(toleratedGPerHour, 0)} g/h you tolerate.`)
  for (const b of blocks) {
    if (b.count > 1 && b.intervalMin <= 0) out.push(`${b.label}: set how often (minutes between intakes).`)
    if (blockEndMin(b) > durationMin) out.push(`${b.label}: the last intake is at ${blockEndMin(b)} min, after the planned finish.`)
  }
  if (t.caffeineMg > 400) out.push(`${fmt(t.caffeineMg, 0)} mg of caffeine is above the 400 mg a day usually kept to.`)
  return out
}

const roundTo5 = (v: number) => Math.max(5, roundToInt(v / 5) * 5)

/**
 * The planner's plan as editable blocks: one block per product (its servings spread between its first and last step), the rest of the fluid as water or, where
 * the plan asks for sodium the products do not bring, water with electrolytes, and each caffeine dose as its own block.
 */
export function seedBlocks(plan: RaceFuelPlan): FuelBlock[] {
  const blocks: FuelBlock[] = []
  const steps = plan.steps
  const byProduct = new Map<string, { t: number; count: number; product: RaceFuelPlan['steps'][number]['parts'][number]['product'] }[]>()
  for (const s of steps) for (const p of s.parts) { const l = byProduct.get(p.product.name); const u = { t: s.elapsedMin, count: p.count, product: p.product }; if (l) l.push(u); else byProduct.set(p.product.name, [u]) }
  let drinkFluid = 0
  let productSodium = 0
  let n = 0
  for (const uses of byProduct.values()) {
    const p = uses[0].product
    const servings = uses.reduce((a, u) => a + u.count, 0)
    const first = uses[0].t
    const last = uses[uses.length - 1].t
    const interval = servings > 1 ? roundTo5((last - first) / (servings - 1)) : 0
    blocks.push({
      id: `seed-p${n}`, label: p.name, form: p.kind, startMin: roundToInt(first), intervalMin: interval, count: servings, carbsG: p.carbsG, fluidMl: p.fluidMl ?? 0, sodiumMg: p.sodiumMg ?? 0,
      caffeineMg: p.caffeineMg ?? 0, fuelingProductId: p.id, supplementProductId: null,
    })
    drinkFluid += servings * (p.fluidMl ?? 0)
    productSodium += servings * (p.sodiumMg ?? 0)
    n++
  }
  const drinkSteps = steps.filter((s) => s.fluidMl > 0)
  const waterMl = Math.max(0, plan.totalFluidMl - drinkFluid)
  const sodiumNeeded = Math.max(0, plan.totalSodiumMg - productSodium)
  if (drinkSteps.length > 0 && waterMl > 0) {
    const k = drinkSteps.length
    const withSalt = sodiumNeeded >= 100
    blocks.push({
      id: 'seed-water', label: withSalt ? 'Water with electrolytes' : 'Water', form: withSalt ? 'electrolyte_drink' : 'water', startMin: roundToInt(drinkSteps[0].elapsedMin),
      intervalMin: k > 1 ? roundToInt(STEP_MIN) : 0, count: k, carbsG: 0, fluidMl: waterMl / k, sodiumMg: withSalt ? sodiumNeeded / k : 0, caffeineMg: 0, fuelingProductId: null, supplementProductId: null,
    })
  }
  steps.filter((s) => s.caffeineMg > 0).forEach((s, i) => {
    blocks.push({ id: `seed-caf${i}`, label: 'Caffeine', form: 'tablet', startMin: roundToInt(s.elapsedMin), intervalMin: 0, count: 1, carbsG: 0, fluidMl: 0, sodiumMg: 0, caffeineMg: s.caffeineMg, fuelingProductId: null, supplementProductId: null })
  })
  return blocks.map((b, i) => [b, i] as [FuelBlock, number]).sort((a, b) => a[0].startMin - b[0].startMin || a[1] - b[1]).map((x) => x[0])
}
