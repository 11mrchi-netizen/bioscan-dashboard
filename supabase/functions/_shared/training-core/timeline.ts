// Ports of generate/BlockBlueprint.kt and Timeline.kt: how a strength template and a conditioning protocol share the
// week (the layout) and what each calendar week is (the timeline). Deload weeks are invisible: they do not count toward
// the block and pause the strength cycle.

import type { NumRange } from './definitions.ts'
import type { Weekday } from './dates.ts'

export type WeekKind = 'normal' | 'deload' | 'test'
export const WEEK_KINDS: WeekKind[] = ['normal', 'deload', 'test']
export const countsTowardBlock = (k: WeekKind): boolean => k !== 'deload'

export interface CondSlot { kind: string; minutes?: NumRange | null }
export interface DayPlan { weekday: Weekday; strengthSession: number | null; conditioning: CondSlot | null }
export const isRest = (d: DayPlan): boolean => d.strengthSession == null && d.conditioning == null

export interface BlockBlueprint { strengthKey: string | null; protocolKey: string | null; layout: DayPlan[]; timeline: WeekKind[] }

export function defaultTimeline(blockLength: number, weeks: number, startWithTest = false, deloads = true): WeekKind[] {
  const len = Math.max(1, blockLength)
  const out: WeekKind[] = []
  if (startWithTest && weeks > 0) out.push('test')
  let run = 0
  while (out.length < weeks) {
    if (deloads && run === len) { out.push('deload'); run = 0 } else { out.push('normal'); run++ }
  }
  return out
}

export function weeksForBlocks(blocks: number, blockLength: number, trailingDeload = true, startWithTest = false): number {
  const b = Math.max(1, blocks)
  return b * Math.max(1, blockLength) + (trailingDeload ? b : b - 1) + (startWithTest ? 1 : 0)
}

export const cycleWeekKind = (k: WeekKind): WeekKind => WEEK_KINDS[(WEEK_KINDS.indexOf(k) + 1) % WEEK_KINDS.length]
export const countedWeeks = (t: WeekKind[]): number => t.filter(countsTowardBlock).length

export function resizeTimeline(current: WeekKind[], blockLength: number, weeks: number): WeekKind[] {
  if (weeks <= current.length) return current.slice(0, weeks)
  const out = [...current]
  let run = 0
  for (let i = out.length - 1; i >= 0; i--) { if (out[i] === 'normal') run++; else break }
  while (out.length < weeks) {
    if (run >= blockLength) { out.push('deload'); run = 0 } else { out.push('normal'); run++ }
  }
  return out
}

/** The last [taper] weeks made lighter (domain/events/EventTaper.kt). */
export function withTaper(timeline: WeekKind[], taper: number): WeekKind[] {
  if (taper <= 0 || timeline.length === 0) return timeline
  const from = Math.max(1, timeline.length - taper)
  return timeline.map((k, i) => (i >= from ? 'deload' : k))
}
