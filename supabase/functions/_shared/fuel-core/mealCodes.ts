// Port of the Android domain/fuel/MealCodes.kt and SessionFuel.kt: each meal of a day gets a carbohydrate code (LOW / MEDIUM / HIGH), a
// gram range for carbohydrate, protein and fat, and the reason. The day's carbohydrate less what is planned during sessions is split by
// weight (LOW 0.5, MEDIUM 1, HIGH 2); a pre-session snack is added when no meal falls 1-4 h before a key session, a recovery snack when
// none falls within 2 h after it. Parity by fixture (MealCodesParityTest), reasons included.

import type { DayFuelPlan, Intensity, PlannedDay, PlannedItem } from './day.ts'
import { isKeyIntensity, startHour } from './day.ts'
import { atTime, instant, zdtString } from './plannedDay.ts'
import { type MealSlotKind, roundToInt } from './plates.ts'

export type CarbCode = 'LOW' | 'MEDIUM' | 'HIGH'
const CODE_WEIGHT: Record<CarbCode, number> = { LOW: 0.5, MEDIUM: 1, HIGH: 2 }
const MAIN: Record<MealSlotKind, boolean> = { BREAKFAST: true, LUNCH: true, SNACK: false, DINNER: true, PRE_SESSION: false, RECOVERY: false }

/** No meal is planned before this time of day. */
export const EARLIEST_MEAL = '06:00:00'
/** "HH:mm[:ss]" times of day; snack null for none. */
export interface MealTimes { breakfast: string; lunch: string; snack: string | null; dinner: string }
export const DEFAULT_MEAL_TIMES: MealTimes = { breakfast: '09:00', lunch: '12:30', snack: '16:00', dinner: '19:30' }

export const PRE_SESSION_FAT_CAP_G = 10
export const RECOVERY_FAT_CAP_G = 15
export const MAIN_MEAL_PROTEIN_MIN_G_PER_KG = 0.3

// ---- carbohydrate during a session (SessionFuel.kt) ----

export const DEFAULT_TOLERATED_G_PER_H = 60
export const MAX_G_PER_H = 90
export type SessionFuelKind = 'NONE' | 'RINSE' | 'STEADY' | 'HIGH'
export interface SessionFuel { kind: SessionFuelKind; gPerHourLow: number; gPerHourHigh: number; durationMin: number; note: string }
const sessionFuelMid = (f: SessionFuel) => (f.gPerHourLow * f.durationMin / 60 + (f.gPerHourHigh * f.durationMin) / 60) / 2

export function sessionFuel(durationMin: number, intensity: Intensity, toleratedGPerHour = DEFAULT_TOLERATED_G_PER_H): SessionFuel {
  const cap = Math.min(Math.max(toleratedGPerHour, 30), MAX_G_PER_H)
  const hard = intensity === 'HARD' || intensity === 'RACE'
  if (durationMin < 45) return { kind: 'NONE', gPerHourLow: 0, gPerHourHigh: 0, durationMin, note: "Under 45 minutes the body's own stores cover it." }
  if (durationMin < 75) {
    return { kind: 'RINSE', gPerHourLow: 0, gPerHourHigh: hard ? 30 : 15, durationMin, note: '45-75 minutes: a little carbohydrate, or a sports-drink mouth rinse, helps hard efforts.' }
  }
  if (durationMin <= 150) {
    const lo = hard ? 45 : 30
    return { kind: 'STEADY', gPerHourLow: Math.min(lo, cap), gPerHourHigh: Math.min(60, cap), durationMin, note: '1-2.5 hours: 30-60 g of carbohydrate an hour, from the start.' }
  }
  const hi = Math.min(MAX_G_PER_H, cap)
  const lo = Math.min(60, hi)
  const note = hi > 60
    ? `Over 2.5 hours: up to ${javaDouble(hi)} g an hour, from glucose and fructose together (about 2:1 or 1:0.8).`
    : `Over 2.5 hours: up to 90 g an hour helps once the gut is trained to it; ${Math.trunc(cap)} g an hour is your tolerated ceiling.`
  return { kind: 'HIGH', gPerHourLow: lo, gPerHourHigh: Math.max(lo, hi), durationMin, note }
}
/** Kotlin's Double.toString for the whole and half numbers a rate takes ("75.0", "82.5"). */
const javaDouble = (v: number) => (Number.isInteger(v) ? `${v}.0` : String(v))

export interface DayMeal {
  kind: MealSlotKind; time: string; code: CarbCode; carbLowG: number; carbHighG: number; proteinG: number; fatG: number | null; reason: string; beforeKeySession: boolean
}
export interface SessionWindow { item: PlannedItem; fuel: SessionFuel }
export interface DayMeals { date: string; meals: DayMeal[]; sessions: SessionWindow[] }

export function splitWithCaps(total: number, weights: number[], caps: (number | null)[]): number[] {
  const out = weights.map(() => 0)
  const open = new Set(weights.map((_, i) => i))
  let left = total
  while (open.size > 0) {
    let w = 0
    for (const i of open) w += weights[i]
    if (w <= 0) break
    const over = [...open].filter((i) => caps[i] != null && (left * weights[i]) / w > (caps[i] as number))
    if (over.length === 0) { for (const i of open) out[i] = (left * weights[i]) / w; break }
    for (const i of over) { out[i] = caps[i] as number; left -= caps[i] as number; open.delete(i) }
  }
  return out
}

export function splitProtein(dayProteinG: number, kinds: MealSlotKind[], weightKg: number): number[] {
  const weight = (k: MealSlotKind) => (MAIN[k] || k === 'RECOVERY' ? 1 : 0.5)
  const total = kinds.reduce((a, k) => a + weight(k), 0)
  if (total <= 0) return kinds.map(() => 0)
  const shares = kinds.map((k) => (dayProteinG * weight(k)) / total)
  const floor = MAIN_MEAL_PROTEIN_MIN_G_PER_KG * weightKg
  const mains = kinds.map((_, i) => i).filter((i) => MAIN[kinds[i]])
  if (!mains.some((i) => shares[i] < floor) || dayProteinG < floor * mains.length) return shares
  const others = kinds.map((_, i) => i).filter((i) => !MAIN[kinds[i]])
  const left = Math.max(dayProteinG - floor * mains.length, 0)
  const otherW = others.reduce((a, i) => a + weight(kinds[i]), 0)
  return kinds.map((k) => (MAIN[k] && otherW > 0 ? floor : MAIN[k] ? dayProteinG / mains.length : (left * weight(k)) / otherW))
}

const hoursBetween = (a: number, b: number) => Math.trunc((b - a) / 60000) / 60
const startMs = (i: PlannedItem) => instant(i.start as string)
const endMs = (i: PlannedItem) => startMs(i) + Math.trunc(i.durationMin * 60) * 1000
const minutes = (m: number) => m * 60000
const inRange = (v: number, lo: number, hi: number) => v >= lo && v <= hi

function formatGap(a: number, b: number): string {
  const m = Math.trunc((b - a) / 60000)
  return m >= 90 ? `${roundToInt(m / 60)} h` : `${m} min`
}

interface Coded { kind: MealSlotKind; time: number; code: CarbCode; reason: string; beforeKey: boolean }

/**
 * Codes the meals of plan's day. day holds its sessions; nextDay is checked for a hard morning; trainLow is the person's opt-in; deficit is
 * true when the goal is a deficit. zone is the person's time zone.
 */
export function codeMeals(
  plan: DayFuelPlan, day: PlannedDay, nextDay: PlannedDay | null, weightKg: number, zone: string,
  times: MealTimes = DEFAULT_MEAL_TIMES, trainLow = false, deficit = false, toleratedGPerHour = DEFAULT_TOLERATED_G_PER_H,
): DayMeals {
  const date = day.date
  const at = (t: string) => atTime(date, t, zone)
  const sessions = day.items.filter((i) => i.start != null).sort((a, b) => Math.floor(startMs(a) / 1000) - Math.floor(startMs(b) / 1000))
  const windows: SessionWindow[] = day.items.map((i) => ({ item: i, fuel: sessionFuel(i.durationMin, i.intensity, toleratedGPerHour) }))

  const slots: [MealSlotKind, number][] = [['BREAKFAST', at(times.breakfast)], ['LUNCH', at(times.lunch)], ['DINNER', at(times.dinner)]]
  if (times.snack != null) slots.push(['SNACK', at(times.snack)])
  const earliest = at(EARLIEST_MEAL)
  for (let i = 0; i < slots.length; i++) {
    const [kind, t] = slots[i]
    const clash = sessions.find((s) => t >= startMs(s) && t < endMs(s))
    if (clash) {
      const before = startMs(clash) - minutes(MAIN[kind] ? 150 : 60)
      slots[i] = [kind, before < earliest ? endMs(clash) + minutes(30) : before]
    }
  }
  for (const s of sessions.filter((x) => isKeyIntensity(x.intensity) && x.durationMin >= 60)) {
    const start = startMs(s)
    if (!slots.some(([, t]) => inRange(hoursBetween(t, start), 1, 4)) && !(start - minutes(90) < earliest)) slots.push(['PRE_SESSION', start - minutes(90)])
    const end = endMs(s)
    if (!slots.some(([, t]) => inRange(hoursBetween(end, t), 0, 2))) slots.push(['RECOVERY', end + minutes(30)])
  }
  slots.sort((a, b) => Math.floor(a[1] / 1000) - Math.floor(b[1] / 1000))

  const hardMorningTomorrow = nextDay?.items.find((i) => isKeyIntensity(i.intensity) && (startHour(i.start) ?? 12) < 12) ?? null
  const coded: Coded[] = slots.map(([kind, t]) => {
    const before = sessions.find((s) => isKeyIntensity(s.intensity) && inRange(hoursBetween(t, startMs(s)), 1, 4))
    const beforeAny = sessions.find((s) => inRange(hoursBetween(t, startMs(s)), 0.5, 4))
    const after = [...sessions].reverse().find((s) => isKeyIntensity(s.intensity) && inRange(hoursBetween(endMs(s), t), 0, 2))
    const keyAhead = before != null || sessions.some((s) => isKeyIntensity(s.intensity) && inRange(hoursBetween(t, startMs(s)), 0, 4))
    let code: CarbCode
    let reason: string
    if (plan.loading) { code = 'HIGH'; reason = 'Loading day: every meal carries carbohydrate.' }
    else if (before) { code = 'HIGH'; reason = `${formatGap(t, startMs(before))} before ${before.title}: top up for the work.` }
    else if (after) { code = 'HIGH'; reason = `After ${after.title}: refill (1-1.2 g/kg in the first hours).` }
    else if (kind === 'DINNER' && hardMorningTomorrow) { code = 'HIGH'; reason = `Tomorrow morning: ${hardMorningTomorrow.title}. Tonight's meal is its fuel.` }
    else if (trainLow && !deficit && beforeAny && beforeAny.intensity === 'EASY') { code = 'LOW'; reason = 'Before an easy session: training low, as you chose. Keep the protein.' }
    else if (day.items.length === 0) { code = 'LOW'; reason = 'Rest day: carbohydrate low, protein as usual.' }
    else { code = 'MEDIUM'; reason = 'Training day: a normal share.' }
    return { kind, time: t, code, reason, beforeKey: keyAhead }
  })

  const inSession = windows.reduce((a, w) => a + sessionFuelMid(w.fuel), 0)
  const forMeals = Math.max(plan.carbG - inSession, plan.carbG * 0.5)
  const totalWeight = Math.max(coded.reduce((a, c) => a + CODE_WEIGHT[c.code], 0), 0.5)
  const protein = splitProtein(plan.proteinG, coded.map((c) => c.kind), weightKg)
  const fat = plan.fatG == null ? null : splitWithCaps(
    Math.max(plan.fatG, 0),
    coded.map((c) => (!MAIN[c.kind] || (c.code === 'HIGH' && c.beforeKey) ? 0.5 : 1)),
    coded.map((c) => (c.kind === 'PRE_SESSION' ? PRE_SESSION_FAT_CAP_G : c.kind === 'RECOVERY' ? RECOVERY_FAT_CAP_G : c.beforeKey && !MAIN[c.kind] ? PRE_SESSION_FAT_CAP_G : null)),
  )
  const meals: DayMeal[] = coded.map((c, i) => {
    const share = (forMeals * CODE_WEIGHT[c.code]) / totalWeight
    return { kind: c.kind, time: zdtString(c.time, zone), code: c.code, carbLowG: share * 0.85, carbHighG: share * 1.15, proteinG: protein[i], fatG: fat ? fat[i] : null, reason: c.reason, beforeKeySession: c.beforeKey }
  })
  return { date, meals, sessions: windows }
}

/** Which coded meal a logged meal at loggedAt (an instant, ms) belongs to: the nearest one within 2 hours, else none. */
export function mealForLog(meals: DayMeal[], loggedAt: number): DayMeal | null {
  let best: DayMeal | null = null
  let bestGap = Infinity
  for (const m of meals) { const g = Math.abs(Math.trunc((loggedAt - instant(m.time)) / 60000)); if (g < bestGap) { best = m; bestGap = g } }
  return best && bestGap <= 120 ? best : null
}

export type MealAdherence = 'WITHIN' | 'UNDER' | 'OVER' | 'NOT_LOGGED'
export function adherence(meal: DayMeal, loggedCarbG: number | null): MealAdherence {
  if (loggedCarbG == null) return 'NOT_LOGGED'
  return loggedCarbG < meal.carbLowG ? 'UNDER' : loggedCarbG > meal.carbHighG ? 'OVER' : 'WITHIN'
}
