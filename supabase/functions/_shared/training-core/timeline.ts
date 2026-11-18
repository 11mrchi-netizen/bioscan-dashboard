// Ports of generate/BlockBlueprint.kt and Timeline.kt: how a strength template and a conditioning protocol share the
// week (the layout) and what each calendar week is (the timeline). Deload weeks are invisible: they do not count toward
// the block and pause the strength cycle.

import type { NumRange } from './definitions.ts'
import type { Weekday } from './dates.ts'

// An early week (Nov 2026) repeats the cycle's opening weeks before the first full cycle, when building toward a race.
export type WeekKind = 'normal' | 'deload' | 'test' | 'lead'
export const WEEK_KINDS: WeekKind[] = ['normal', 'deload', 'test', 'lead']
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

/**
 * Building toward a race: the last [taper] weeks light, whole cycles before them (a deload between), the last ending right
 * before the taper so its peak is the week before it; the weeks that do not make a whole cycle go first as early weeks.
 */
export function raceTimeline(blockLength: number, weeks: number, taper: number, startWithTest = false): WeekKind[] {
  const len = Math.max(1, blockLength)
  const w = Math.max(0, weeks)
  const t = Math.min(w, Math.max(0, taper))
  const head = startWithTest && w - t > 0 ? 1 : 0
  const avail = w - t - head
  const cycles = Math.trunc((avail + 1) / (len + 1))
  const lead = cycles === 0 ? avail : avail - (cycles * len + cycles - 1)
  const out: WeekKind[] = []
  if (head === 1) out.push('test')
  for (let i = 0; i < lead; i++) out.push('lead')
  for (let c = 0; c < cycles; c++) {
    if (c > 0) out.push('deload')
    for (let i = 0; i < len; i++) out.push('normal')
  }
  for (let i = 0; i < t; i++) out.push('deload')
  return out
}
