// Meal timing and composition exposures, and the daily digestion score, for the relationship engine (catalogue 2.3.0,
// docs/nutrition/timing-and-gut.md). Pure: the frame builder (runRelationships.ts) hands in the rows and two clocks.
//
// Exposures, one value per EATING DAY (the local date; a meal before 04:00 belongs to the evening before, as for late
// meals and drinks). Days with no meal logged are unknown (null), never zero.
//   meal_gap_h          hours from the day's last meal (50 kcal or more) to sleep onset that night. Needs both; a gap under
//                       -1 h (a meal logged after falling asleep) or over 12 h is left out.
//   kcal_before_bed     kcal eaten in the 3 h before that night's sleep onset (0 on a day with meals and a bedtime but none).
//   eating_window_h     first to last meal of 50 kcal or more; days with fewer than two such meals are left out.
//   protein_day, carbs_day, fat_day, fiber_day   daily sums (fibre only over meals that carry a fibre value, and only on
//                       days where most of the energy has one, so a half-logged day does not read as low fibre).
//
// Outcome, one value per local day with any digestion record:
//   gut_day_score       0-100, higher is better. The mean of what is known that day:
//     stools   each stool's form (Bristol 4 = 100, 3 and 5 = 90, 2 = 70, 6 = 60, 1 = 40, 7 = 30), less 3 points per point
//              of discomfort (0-10) and 10 for a partly or 20 for an incomplete evacuation; averaged over the day's stools;
//              Then, against your usual over the 28 days before (once 7 days with stools are known there): 10 points off
//              per stool beyond one more than your usual count, and 5 per hour that the day's first stool is more than 2 h
//              from your usual time (at most 15);
//     evening  the check-in's gut comfort (0-4) x 25, less 10 per symptom ticked.
//   Not the app's FUEL > gut sub-score (that one is relative to your usual timing and frequency); this is a plain daily
//   reading, so the engine can ask what goes with a better or worse gut day.

import type { Num } from './stats.ts'

export const MEAL_MIN_KCAL = 50
export const BEFORE_BED_HOURS = 3
export const ROLLOVER_HOUR = 4

export interface MealRow {
  logged_at: string
  calories?: number | null
  protein_g?: number | null
  carbs_g?: number | null
  fat_g?: number | null
  fiber_g?: number | null
}

export interface SleepOnsetRow { date: string; bedtime?: string | null }

export interface StoolRow { occurred_at: string; bristol_type: number | null; discomfort?: number | null; complete_evacuation?: string | null }

export interface GutCheckinRow { date: string; gut_comfort?: number | null; gut_symptoms?: string[] | null }

const num = (v: unknown): number | null => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))
const HOUR = 3600000

/** A logged (wall-clock) time as milliseconds of local time: its digits are local, so it is read as if UTC. */
export function wallMs(stamp: string): number {
  return Date.parse(String(stamp).slice(0, 19) + 'Z')
}

function dateOfMs(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}

/** The eating day of a local moment: its date, or the day before in the small hours. */
export function eatingDay(localMs: number): string {
  return dateOfMs(localMs - ROLLOVER_HOUR * HOUR)
}

function shift(date: string, days: number): string {
  return dateOfMs(Date.parse(date + 'T00:00:00Z') + days * 86400000)
}

/**
 * The meal series on [axis]. [bedtimeLocalMs] turns a sleep row's bedtime (a real instant) into local milliseconds with
 * the day's UTC offset; the night after eating day d is the sleep row dated d + 1 (sleep rows carry the wake date).
 */
export function mealSeries(axis: string[], meals: MealRow[], sleep: SleepOnsetRow[], bedtimeLocalMs: (stamp: string) => number | null): Record<string, Num[]> {
  const byDay = new Map<string, { t: number; kcal: number; p: number | null; c: number | null; f: number | null; fib: number | null }[]>()
  for (const m of meals) {
    const t = wallMs(m.logged_at)
    if (!Number.isFinite(t)) continue
    const d = eatingDay(t)
    const list = byDay.get(d) ?? []
    list.push({ t, kcal: num(m.calories) ?? 0, p: num(m.protein_g), c: num(m.carbs_g), f: num(m.fat_g), fib: num(m.fiber_g) })
    byDay.set(d, list)
  }
  const onset = new Map<string, number>()
  for (const s of sleep) {
    if (!s.bedtime) continue
    const ms = bedtimeLocalMs(s.bedtime)
    if (ms !== null && Number.isFinite(ms)) onset.set(s.date, ms)
  }
  const sum = (xs: (number | null)[]) => xs.reduce<number>((a, v) => a + (v ?? 0), 0)
  const out: Record<string, Num[]> = {
    meal_gap_h: [], kcal_before_bed: [], eating_window_h: [], protein_day: [], carbs_day: [], fat_day: [], fiber_day: [],
  }
  for (const d of axis) {
    const day = byDay.get(d)
    if (!day || day.length === 0) {
      for (const k of Object.keys(out)) out[k].push(null)
      continue
    }
    const real = day.filter((m) => m.kcal >= MEAL_MIN_KCAL).sort((a, b) => a.t - b.t)
    const bed = onset.get(shift(d, 1))
    const last = real.at(-1)
    const gap = bed !== undefined && last ? (bed - last.t) / HOUR : null
    out.meal_gap_h.push(gap !== null && gap >= -1 && gap <= 12 ? Math.round(Math.max(0, gap) * 100) / 100 : null)
    out.kcal_before_bed.push(bed !== undefined && (gap === null || gap >= -1) ? Math.round(sum(day.filter((m) => m.t >= bed - BEFORE_BED_HOURS * HOUR && m.t <= bed).map((m) => m.kcal))) : null)
    out.eating_window_h.push(real.length >= 2 ? Math.round(((real.at(-1)!.t - real[0].t) / HOUR) * 100) / 100 : null)
    out.protein_day.push(day.some((m) => m.p !== null) ? Math.round(sum(day.map((m) => m.p))) : null)
    out.carbs_day.push(day.some((m) => m.c !== null) ? Math.round(sum(day.map((m) => m.c))) : null)
    out.fat_day.push(day.some((m) => m.f !== null) ? Math.round(sum(day.map((m) => m.f))) : null)
    const kcal = sum(day.map((m) => m.kcal))
    const kcalWithFiber = sum(day.filter((m) => m.fib !== null).map((m) => m.kcal))
    out.fiber_day.push(kcal > 0 && kcalWithFiber / kcal >= 0.6 ? Math.round(sum(day.map((m) => m.fib)) * 10) / 10 : null)
  }
  return out
}

const FORM_POINTS: Record<number, number> = { 1: 40, 2: 70, 3: 90, 4: 100, 5: 90, 6: 60, 7: 30 }

/** One stool's points, or null without a Bristol type. */
export function stoolPoints(s: StoolRow): number | null {
  const b = num(s.bristol_type)
  if (b === null || !(b in FORM_POINTS)) return null
  const disc = num(s.discomfort)
  const evac = s.complete_evacuation === 'partly' ? 10 : s.complete_evacuation === 'no' ? 20 : 0
  return Math.max(0, Math.min(100, FORM_POINTS[b] - 3 * (disc ?? 0) - evac))
}

/** The evening check-in's gut part, or null when the gut questions were not answered. */
export function eveningGutPoints(c: GutCheckinRow): number | null {
  const comfort = num(c.gut_comfort)
  if (comfort === null) return null
  return Math.max(0, Math.min(100, comfort * 25 - 10 * (c.gut_symptoms?.length ?? 0)))
}

export const USUAL_DAYS = 28
export const USUAL_MIN_DAYS = 7

const median = (xs: number[]) => {
  const v = [...xs].sort((a, b) => a - b)
  const m = Math.floor(v.length / 2)
  return v.length % 2 ? v[m] : (v[m - 1] + v[m]) / 2
}

/** Points off a day's stool part for frequency and timing away from the usual of the 28 days before. */
export function rhythmPenalty(day: { count: number; firstHour: number }, before: { count: number; firstHour: number }[]): number {
  if (before.length < USUAL_MIN_DAYS) return 0
  const usualCount = median(before.map((b) => b.count))
  const usualHour = median(before.map((b) => b.firstHour))
  const extra = Math.max(0, day.count - (usualCount + 1))
  const off = Math.max(0, Math.abs(day.firstHour - usualHour) - 2)
  return Math.round(10 * extra + Math.min(15, 5 * off))
}

/** The daily digestion score on [axis] (stool_log.occurred_at is a logged time: its digits are local). */
export function gutDayScore(axis: string[], stool: StoolRow[], checkins: GutCheckinRow[]): Num[] {
  const stoolByDay = new Map<string, number[]>()
  const rhythm = new Map<string, { count: number; firstHour: number }>()
  for (const s of stool) {
    const t = wallMs(s.occurred_at)
    if (!Number.isFinite(t)) continue
    const d = dateOfMs(t)
    const hour = (t - Date.parse(d + 'T00:00:00Z')) / HOUR
    const r = rhythm.get(d)
    rhythm.set(d, r ? { count: r.count + 1, firstHour: Math.min(r.firstHour, hour) } : { count: 1, firstHour: hour })
    const p = stoolPoints(s)
    if (p === null) continue
    stoolByDay.set(d, [...(stoolByDay.get(d) ?? []), p])
  }
  const evening = new Map<string, number>()
  for (const c of checkins) {
    const p = eveningGutPoints(c)
    if (p !== null) evening.set(c.date, p)
  }
  return axis.map((d) => {
    const parts: number[] = []
    const st = stoolByDay.get(d)
    if (st?.length) {
      const before: { count: number; firstHour: number }[] = []
      for (let i = 1; i <= USUAL_DAYS; i++) {
        const r = rhythm.get(shift(d, -i))
        if (r) before.push(r)
      }
      const form = st.reduce((a, b) => a + b, 0) / st.length
      parts.push(Math.max(0, form - rhythmPenalty(rhythm.get(d)!, before)))
    }
    const ev = evening.get(d)
    if (ev !== undefined) parts.push(ev)
    return parts.length ? Math.round((parts.reduce((a, b) => a + b, 0) / parts.length) * 10) / 10 : null
  })
}
