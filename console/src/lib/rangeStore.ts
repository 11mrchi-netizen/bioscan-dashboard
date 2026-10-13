// The chosen date range survives a reload. A preset ("90D") is saved as a number of days, so it stays relative to today;
// a custom range is saved as its two dates.
import { localDaysAgo, localToday } from './dates.ts'
import type { DateRange } from '../types/analysis'

export const RANGE_PRESETS = [7, 28, 90, 365]
const KEY = 'bioscan.range.v1'

export type SavedRange = { days: number } | { start: string; end: string }

export function toSaved(range: DateRange, now: Date = new Date()): SavedRange {
  const today = localToday(now)
  if (range.end === today) {
    const days = RANGE_PRESETS.find(d => localDaysAgo(d, now) === range.start)
    if (days) return { days }
  }
  return { start: range.start, end: range.end }
}

export function fromSaved(saved: unknown, now: Date = new Date()): DateRange | null {
  if (!saved || typeof saved !== 'object') return null
  const s = saved as Record<string, unknown>
  if (typeof s.days === 'number' && RANGE_PRESETS.includes(s.days)) return { start: localDaysAgo(s.days, now), end: localToday(now) }
  const iso = /^\d{4}-\d{2}-\d{2}$/
  if (typeof s.start === 'string' && typeof s.end === 'string' && iso.test(s.start) && iso.test(s.end) && s.start <= s.end) {
    return { start: s.start, end: s.end }
  }
  return null
}

export function loadRange(): DateRange | null {
  try {
    const raw = globalThis.localStorage?.getItem(KEY)
    return raw ? fromSaved(JSON.parse(raw)) : null
  } catch {
    return null
  }
}

export function saveRange(range: DateRange): void {
  try {
    globalThis.localStorage?.setItem(KEY, JSON.stringify(toSaved(range)))
  } catch {
    // private mode / storage blocked: the range simply is not remembered
  }
}
