// Routine intelligence R4 (docs/routine-intelligence/01-research-and-plan.md, section 5.1): the phone-use and mobility
// series the catalogue's routine questions refer to, from what the phone published (phone_usage_daily,
// phone_app_usage_daily, mobility_daily, trips). Pure.
//
// Every series keeps the frame's rule: a value only on a day the source actually covers, null otherwise, never a zero
// for a day nobody measured. Phone use is measured whenever Usage access is on, so a day with a phone row is known; a
// place-based day counts only when visits and trips cover at least MIN_MOBILITY_COVERAGE of it (plan section 8.4).
import type { Num } from './stats.ts'

export interface PhoneDayRow {
  date: string
  bed_window_min: number | null
  night_screen_min: number | null
  night_unlocks: number | null
}

export interface PhoneAppRow {
  date: string
  category: string | null
  after_21_min: number | null
}

export interface MobilityRow {
  date: string
  coverage_pct: number
  day_type: string
  outdoor_daylight_min: number | null
}

export interface CommuteRow {
  start_at: string
  end_at: string
  active_min: number | null
}

export interface RoutineInputs {
  phone?: PhoneDayRow[]
  phoneApps?: PhoneAppRow[]
  mobility?: MobilityRow[]
  commutes?: CommuteRow[]
}

export const MIN_MOBILITY_COVERAGE = 80
// Categories counted as late social or video use (the apps' own declared category, or the person's override).
export const LATE_SCREEN_CATEGORIES = ['social', 'video']
// A commute day counts as active from this many minutes on foot or on a bicycle.
export const ACTIVE_COMMUTE_MIN = 10
// A commute counts as long at this multiple of the median of the previous 28 days' commute days (from 5 of them).
export const LONG_COMMUTE_FACTOR = 1.25
export const LONG_COMMUTE_MIN_HISTORY = 5
// R6: the with-day rules of the routine self-experiments, checked against what the phone measured (passive adherence).
// Up to this many minutes still counts as none (a glance at an alarm, a notification lighting the screen).
export const PHONE_NONE_MIN = 5
// A daylight day: at least this many estimated minutes outdoors in daylight.
export const DAYLIGHT_DAY_MIN = 30

const num = (v: unknown): number | null => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

function median(xs: number[]): number {
  const s = [...xs].sort((a, b) => a - b)
  const m = s.length >> 1
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2
}

function shift(date: string, days: number): string {
  return new Date(Date.parse(date + 'T00:00:00Z') + days * 86400000).toISOString().slice(0, 10)
}

/**
 * The routine series on the frame's date axis. `clock` turns a UTC timestamp into its local date (runRelationships'
 * localClock), so a commute is filed under the day it happened on.
 */
export function routineSeries(axis: string[], raw: RoutineInputs, clock: (stamp: string) => { date: string; hour: number }): Record<string, Num[]> {
  const phone = new Map((raw.phone ?? []).map((r) => [r.date, r]))
  const lateSocial = new Map<string, number>()
  for (const a of raw.phoneApps ?? []) {
    if (!LATE_SCREEN_CATEGORIES.includes(String(a.category ?? ''))) continue
    lateSocial.set(a.date, (lateSocial.get(a.date) ?? 0) + (num(a.after_21_min) ?? 0))
  }
  const mobility = new Map((raw.mobility ?? []).filter((m) => (num(m.coverage_pct) ?? 0) >= MIN_MOBILITY_COVERAGE).map((m) => [m.date, m]))

  const commuteMin = new Map<string, number>()
  const commuteActive = new Map<string, number>()
  for (const c of raw.commutes ?? []) {
    const s = Date.parse(c.start_at)
    const e = Date.parse(c.end_at)
    if (!Number.isFinite(s) || !Number.isFinite(e) || e <= s) continue
    const d = clock(c.start_at).date
    commuteMin.set(d, (commuteMin.get(d) ?? 0) + (e - s) / 60000)
    commuteActive.set(d, (commuteActive.get(d) ?? 0) + (num(c.active_min) ?? 0))
  }
  const commuteDays = [...commuteMin.keys()].sort()

  const dayType = (d: string, type: string): Num => {
    const m = mobility.get(d)
    if (!m || m.day_type === 'unknown') return null
    return m.day_type === type ? 1 : 0
  }

  return {
    // The hour before that night's sleep onset (the phone day runs 04:00 to 04:00; its night is sleep_daily's date + 1).
    screen_bed_min: axis.map((d) => num(phone.get(d)?.bed_window_min)),
    // The phone unlocked at least once between sleep onset and wake that night.
    night_phone: axis.map((d) => {
      const n = num(phone.get(d)?.night_unlocks)
      return n === null ? null : n > 0 ? 1 : 0
    }),
    // Social and video minutes after 21:00; zero on a measured day without any.
    screen_pm_social: axis.map((d) => (phone.has(d) ? lateSocial.get(d) ?? 0 : null)),
    outdoor_daylight_min: axis.map((d) => num(mobility.get(d)?.outdoor_daylight_min)),
    home_day: axis.map((d) => dayType(d, 'home_day')),
    travel_day: axis.map((d) => dayType(d, 'travel')),
    // Only commute days are compared with each other: a day without a commute says nothing about how one went.
    active_commute: axis.map((d) => (commuteMin.has(d) ? ((commuteActive.get(d) ?? 0) >= ACTIVE_COMMUTE_MIN ? 1 : 0) : null)),
    // R6 adherence series (binary, known only on measured days): any phone use in the hour before sleep, any social or
    // video use after 21:00, and a day with at least DAYLIGHT_DAY_MIN outdoors in daylight.
    screen_bed_any: axis.map((d) => {
      const v = num(phone.get(d)?.bed_window_min)
      return v === null ? null : v > PHONE_NONE_MIN ? 1 : 0
    }),
    screen_pm_social_any: axis.map((d) => (phone.has(d) ? ((lateSocial.get(d) ?? 0) > PHONE_NONE_MIN ? 1 : 0) : null)),
    daylight_day: axis.map((d) => {
      const v = num(mobility.get(d)?.outdoor_daylight_min)
      return v === null ? null : v >= DAYLIGHT_DAY_MIN ? 1 : 0
    }),
    commute_long: axis.map((d) => {
      const today = commuteMin.get(d)
      if (today === undefined) return null
      const from = shift(d, -28)
      const previous = commuteDays.filter((x) => x >= from && x < d).map((x) => commuteMin.get(x) as number)
      if (previous.length < LONG_COMMUTE_MIN_HISTORY) return null
      return today >= LONG_COMMUTE_FACTOR * median(previous) ? 1 : 0
    }),
  }
}
