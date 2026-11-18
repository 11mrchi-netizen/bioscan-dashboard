// Port of generate/BlockEnd.kt: ending a block early. The planned sessions after the last day are superseded (kept, not
// deleted), the block ends on that day, and the maxes stay unless the lifter asks to step them up.

import type { LocalDate } from './dates.ts'
import type { StrengthModuleDef } from './definitions.ts'
import { isCluster, type ModuleChoice, progressionIncrementKg } from './generator.ts'

export interface PlannedLite { id: number; date: LocalDate; weekIndex: number; status: string; topPct: number | null }
export interface DroppedWeek { weekIndex: number; sessions: number; topPct: number | null }
export interface EndEarlyPreview { kept: number; dropped: number; droppedWeeks: DroppedWeek[]; heaviestDroppedPct: number | null }

const maxOf = (xs: (number | null)[]): number | null => xs.reduce<number | null>((m, x) => (x == null ? m : m == null || x > m ? x : m), null)

export function endEarlyPreview(sessions: PlannedLite[], lastDay: LocalDate): EndEarlyPreview {
  const dropped = sessions.filter((s) => s.date > lastDay && s.status === 'planned')
  const byWeek = new Map<number, PlannedLite[]>()
  for (const s of dropped) byWeek.set(s.weekIndex, [...(byWeek.get(s.weekIndex) ?? []), s])
  const droppedWeeks = [...byWeek].sort((a, b) => a[0] - b[0]).map(([w, l]) => ({ weekIndex: w, sessions: l.length, topPct: maxOf(l.map((x) => x.topPct)) }))
  return { kept: sessions.length - dropped.length, dropped: dropped.length, droppedWeeks, heaviestDroppedPct: maxOf(dropped.map((x) => x.topPct)) }
}

/** The heaviest % of 1RM in a frozen prescription (planned_sessions.prescription), or null. */
export function topPct(prescription: unknown): number | null {
  if (!Array.isArray(prescription)) return null
  return maxOf(prescription.map((e) => {
    const l = (e as { type?: string; load?: { kind?: string; pct?: number } })
    return l.type === 'lift' && typeof l.load?.pct === 'number' ? l.load.pct : null
  }))
}

export interface MaxStep { exercise: string; fromKg: number; toKg: number }

export function stepUpMaxes(module: StrengthModuleDef, choice: ModuleChoice, oneRm: (name: string) => number | null | undefined, stepKg: number): MaxStep[] {
  const prog = module.progression
  if (!prog) return []
  const out: MaxStep[] = []
  for (const slot of module.slots) {
    const chosen = choice.exercises?.[slot.id] ?? []
    let names = isCluster(module, slot) ? chosen : chosen.slice(0, 1)
    if (names.length === 0) names = slot.pick == null ? [slot.standard] : []
    const lower = slot.role === 'squat' || slot.role === 'hinge'
    for (const name of names) {
      if (out.some((m) => m.exercise === name)) continue
      const from = oneRm(name)
      if (from == null) continue
      out.push({ exercise: name, fromKg: from, toKg: from + progressionIncrementKg(lower ? prog.lower : prog.upper, prog.unit, stepKg) })
    }
  }
  return out
}
