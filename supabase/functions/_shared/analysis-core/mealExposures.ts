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

/** The daily digestion score on [axis] (stool_log.occurred_at is a logged time: its digits are local). */
export function gutDayScore(axis: string[], stool: StoolRow[], checkins: GutCheckinRow[]): Num[] {
  const stoolByDay = new Map<string, number[]>()
  for (const s of stool) {
    const p = stoolPoints(s)
    if (p === null) continue
    const d = String(s.occurred_at).slice(0, 10)
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
    if (st?.length) parts.push(st.reduce((a, b) => a + b, 0) / st.length)
    const ev = evening.get(d)
    if (ev !== undefined) parts.push(ev)
    return parts.length ? Math.round((parts.reduce((a, b) => a + b, 0) / parts.length) * 10) / 10 : null
  })
}
