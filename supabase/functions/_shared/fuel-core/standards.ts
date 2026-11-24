// Fuel STANDARDS (web): a standard is a named, reusable way of fuelling: rules such as "one gel every 30 minutes from the 30th", "one serving of the mix every
// 20 minutes from the start", "carbohydrate at 60 g an hour from this product", "salt tablet at 60 minutes, once". Applying a standard to a session of any length gives the
// schedule's blocks (the phone's FuelBlock), so the same habit fills a 2-hour run and a 6-hour one without typing it again. Nothing here is on the phone: the schedule
// that results is an ordinary fuel_schedules row, which the phone reads and edits.

import type { FuelBlock } from './schedule.ts'

export interface StandardRule {
  label: string
  form: string
  fuelingProductId?: number | null
  supplementProductId?: number | null
  /** the nutrients of ONE serving */
  carbsG: number; fluidMl: number; sodiumMg: number; caffeineMg: number
  /** minutes from the start of the first serving (negative: before it) */
  startMin: number
  /** one serving only, at startMin */
  once?: boolean
  /** 'every': a serving every everyMin minutes; 'rate': as many as give carbsPerHour grams of carbohydrate an hour */
  mode: 'every' | 'rate'
  everyMin?: number
  carbsPerHour?: number
  /** the last serving is no later than this many minutes before the finish (0: up to the finish) */
  stopBeforeEndMin?: number
}
export interface Standard { id?: number; name: string; rules: StandardRule[] }

/** The minutes between servings a rule asks for; null for a rule that is one serving or cannot be worked out. */
export function ruleInterval(r: StandardRule): number | null {
  if (r.once) return null
  if (r.mode === 'rate') {
    if (!(r.carbsG > 0) || !((r.carbsPerHour ?? 0) > 0)) return null
    return Math.max(1, Math.round((60 * r.carbsG) / (r.carbsPerHour as number)))
  }
  return (r.everyMin ?? 0) >= 1 ? Math.round(r.everyMin as number) : null
}

/** What is wrong with a rule as entered; empty when it can be used. */
export function ruleProblems(r: StandardRule): string[] {
  const p: string[] = []
  if (!Number.isFinite(r.startMin) || r.startMin < -240 || r.startMin > 10080) p.push('FIRST AT needs minutes from the start, from -240.')
  if (!r.once && r.mode === 'every' && !((r.everyMin ?? 0) >= 1 && (r.everyMin ?? 0) <= 600)) p.push('EVERY needs minutes between servings, 1 to 600.')
  if (!r.once && r.mode === 'rate') {
    if (!(r.carbsG > 0)) p.push('A rate in grams an hour needs a serving with carbohydrate in it.')
    if (!((r.carbsPerHour ?? 0) > 0 && (r.carbsPerHour ?? 0) <= 120)) p.push('G PER HOUR needs a number from 1 to 120.')
  }
  if ((r.stopBeforeEndMin ?? 0) < 0) p.push('STOP BEFORE THE FINISH cannot be negative.')
  return p
}

/** How many servings the rule gives over a session of durationMin (0: none fit). */
export function ruleCount(r: StandardRule, durationMin: number): number {
  const last = durationMin - (r.stopBeforeEndMin ?? 0)
  if (r.startMin > last && !(r.once && r.startMin <= durationMin)) return 0
  const every = ruleInterval(r)
  if (every == null) return 1
  return Math.min(200, Math.floor((last - r.startMin) / every) + 1)
}

/** The blocks a standard gives for a session of durationMin. idPrefix keeps the ids of several applications apart. */
export function applyStandard(s: Standard, durationMin: number, idPrefix = 'std'): FuelBlock[] {
  const out: FuelBlock[] = []
  s.rules.forEach((r, i) => {
    if (ruleProblems(r).length) return
    const count = ruleCount(r, durationMin)
    if (count < 1) return
    out.push({
      id: `${idPrefix}-${i}`, label: r.label || (r.form ? r.form.replace(/_/g, ' ') : 'Fuel'), form: r.form, startMin: Math.round(r.startMin), intervalMin: count > 1 ? (ruleInterval(r) as number) : 0, count,
      carbsG: r.carbsG, fluidMl: r.fluidMl, sodiumMg: r.sodiumMg, caffeineMg: r.caffeineMg, fuelingProductId: r.fuelingProductId ?? null, supplementProductId: r.supplementProductId ?? null,
    })
  })
  return out
}

/** The shape of a schedule as a standard: each repeating block becomes "every N minutes, up to the same distance from the finish"; a single serving stays once. */
export function standardFromBlocks(name: string, blocks: FuelBlock[], durationMin: number): Standard {
  return {
    name,
    rules: blocks.map((b) => {
      const repeats = b.count > 1 && b.intervalMin > 0
      return {
        label: b.label, form: b.form, fuelingProductId: b.fuelingProductId, supplementProductId: b.supplementProductId, carbsG: b.carbsG, fluidMl: b.fluidMl, sodiumMg: b.sodiumMg, caffeineMg: b.caffeineMg,
        startMin: b.startMin, once: !repeats, mode: 'every' as const, everyMin: repeats ? b.intervalMin : undefined, stopBeforeEndMin: repeats ? Math.max(0, durationMin - (b.startMin + b.intervalMin * (b.count - 1))) : 0,
      }
    }),
  }
}

/** One line for a rule: "Gel (25 g carbs): every 30 min from 30 min, none in the last 10 min". */
export function describeRule(r: StandardRule): string {
  const what = [r.carbsG > 0 && `${r.carbsG} g carbs`, r.fluidMl > 0 && `${r.fluidMl} ml`, r.sodiumMg > 0 && `${r.sodiumMg} mg Na`, r.caffeineMg > 0 && `${r.caffeineMg} mg caffeine`].filter(Boolean).join(', ')
  const every = ruleInterval(r)
  const when = r.startMin < 0 ? `${-r.startMin} min before the start` : r.startMin === 0 ? 'at the start' : `from ${r.startMin} min`
  const rhythm = every == null ? (r.startMin < 0 ? `once, ${when}` : `once, at ${r.startMin} min`) : `every ${every} min ${when}${r.mode === 'rate' ? ` (${r.carbsPerHour} g/h)` : ''}${(r.stopBeforeEndMin ?? 0) > 0 ? `, none in the last ${r.stopBeforeEndMin} min` : ''}`
  return `${r.label || r.form}${what ? ` (${what})` : ''}: ${rhythm}`
}
