// Port of the Android domain/fuel/FuelSnapshot.kt and of how FuelPlanRepository.remember keys a stored plan: a compact copy of a day's or an event's
// fuel plan (what fuel_plans holds), the hash the phone gives it (Java's String.hashCode of the Kotlin JsonObject's toString, in hex), and the lines
// that say what changed. The hash is only comparable when this text is the phone's to the character, so the field order and the number and string
// text follow kotlinx.serialization (SnapshotParityTest). Parity by fixture.

import type { DayFuelPlan } from './day.ts'
import { roundToInt } from './plates.ts'
import type { DayMeals } from './mealCodes.ts'
import type { RaceFuelPlan } from './raceFuel.ts'

/** The engine version the phone stores plans under (FUEL_ENGINE_VERSION in FuelPlanRepository.kt). */
export const FUEL_ENGINE_VERSION = '4'

export interface MealSnapshot { kind: string; time: string; code: string; carb_mid_g: number }
export interface SessionSnapshot { title: string; start: string | null; duration_min: number; intensity: string }
export interface DaySnapshot { date: string; day_class: string; carb_g: number; protein_g: number; fat_g: number | null; kcal: number | null; meals: MealSnapshot[]; sessions: SessionSnapshot[] }
export interface StepSnapshot { elapsed_min: number; carbs_g: number; fluid_ml: number; sodium_mg: number; caffeine_mg: number; items: string[] }
export interface EventSnapshot {
  duration_min: number; carbs_g_per_h: number; fluid_ml_per_h: number; sodium_mg_per_l: number; max_temp_c: number | null; weather_source: string
  total_carbs_g: number; total_fluid_ml: number; caffeine_mg: number; steps: StepSnapshot[]
}

/** LocalTime.toString() of a Kotlin ZonedDateTime.toString(): "HH:mm", or "HH:mm:ss" with seconds. */
const localTime = (zdt: string) => { const m = /T(\d{2}:\d{2}(?::\d{2})?)/.exec(zdt); return m ? m[1] : '' }

/** snapshotOf(plan: DayFuelPlan, coded: CodedDay). */
export function daySnapshot(plan: DayFuelPlan, coded: DayMeals, sessions: { title: string; start: string | null; durationMin: number; intensity: string }[]): DaySnapshot {
  return {
    date: plan.date, day_class: plan.dayClass, carb_g: roundToInt(plan.carbG), protein_g: roundToInt(plan.proteinG), fat_g: plan.fatG == null ? null : roundToInt(plan.fatG),
    kcal: plan.kcal == null ? null : roundToInt(plan.kcal),
    meals: coded.meals.map((m) => ({ kind: m.kind, time: localTime(m.time), code: m.code, carb_mid_g: roundToInt((m.carbLowG + m.carbHighG) / 2) })),
    sessions: sessions.map((s) => ({ title: s.title, start: s.start == null ? null : localTime(s.start), duration_min: roundToInt(s.durationMin), intensity: s.intensity })),
  }
}

/** snapshotOf(plan: RaceFuelPlan). */
export function eventSnapshot(plan: RaceFuelPlan): EventSnapshot {
  return {
    duration_min: roundToInt(plan.durationMin), carbs_g_per_h: roundToInt(plan.carbsPerHourHigh), fluid_ml_per_h: roundToInt((plan.fluidMlPerHourLow + plan.fluidMlPerHourHigh) / 2),
    sodium_mg_per_l: roundToInt(plan.sodiumMgPerL), max_temp_c: plan.heat ? roundToInt(plan.heat.maxTempC) : null, weather_source: plan.weatherSource,
    total_carbs_g: roundToInt(plan.totalCarbsG), total_fluid_ml: roundToInt(plan.totalFluidMl), caffeine_mg: roundToInt(plan.totalCaffeineMg),
    steps: plan.steps.map((s) => ({ elapsed_min: roundToInt(s.elapsedMin), carbs_g: roundToInt(s.carbsG), fluid_ml: roundToInt(s.fluidMl), sodium_mg: roundToInt(s.sodiumMg), caffeine_mg: roundToInt(s.caffeineMg), items: s.items })),
  }
}

/** kotlinx.serialization's quoted string (JsonPrimitive.toString): quotes, backslashes and control characters escaped, the rest as it is. */
function quoted(s: string): string {
  let out = '"'
  for (const ch of s) {
    const c = ch.codePointAt(0) as number
    if (ch === '"') out += '\\"'
    else if (ch === '\\') out += '\\\\'
    else if (ch === '\n') out += '\\n'
    else if (ch === '\r') out += '\\r'
    else if (ch === '\t') out += '\\t'
    else if (ch === '\b') out += '\\b'
    else if (c < 0x20 || c === 0x7f) out += `\\u${c.toString(16).padStart(4, '0')}`
    else out += ch
  }
  return `${out}"`
}

/** Kotlin's JsonObject.toString() of a snapshot: keys in the order given, no spaces, numbers whole, null as null. */
export function snapshotText(v: unknown): string {
  if (v === null || v === undefined) return 'null'
  if (typeof v === 'string') return quoted(v)
  if (typeof v === 'number') return String(v)
  if (typeof v === 'boolean') return String(v)
  if (Array.isArray(v)) return `[${v.map(snapshotText).join(',')}]`
  return `{${Object.entries(v as Record<string, unknown>).map(([k, x]) => `${quoted(k)}:${snapshotText(x)}`).join(',')}}`
}

/** Kotlin's String.hashCode().toString(16): the 32-bit hash over UTF-16 code units, in signed hexadecimal. */
export function javaHashHex(s: string): string {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0
  return h < 0 ? `-${(-h >>> 0).toString(16)}` : h.toString(16)
}
/** What fuel_plans.inputs_hash holds for a snapshot. */
export const snapshotHash = (snapshot: DaySnapshot | EventSnapshot) => javaHashHex(snapshotText(snapshot))

// ---- what changed (FuelSnapshot.kt describeChanges) ----

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)

export function describeDayChanges(old: DaySnapshot, now: DaySnapshot): string[] {
  const lines: string[] = []
  for (const n of now.sessions) {
    const was = old.sessions.find((o) => o.title === n.title)
    if (!was) lines.push(`Added: ${n.title}${n.start ? ` at ${n.start}` : ''}.`)
    else if (was.start !== n.start) lines.push(`${n.title} moved to ${n.start ?? 'no set time'} (was ${was.start ?? 'no set time'}).`)
    else if (was.intensity !== n.intensity) lines.push(`${n.title} is now ${n.intensity.toLowerCase()} (was ${was.intensity.toLowerCase()}).`)
  }
  for (const o of old.sessions.filter((x) => !now.sessions.some((n) => n.title === x.title))) lines.push(`Dropped: ${o.title}.`)
  if (old.day_class !== now.day_class) lines.push(`The day is now ${now.day_class} (was ${old.day_class}).`)
  if (Math.abs(now.carb_g - old.carb_g) >= 30) lines.push(`Carbohydrate ${now.carb_g > old.carb_g ? 'up' : 'down'} to ${now.carb_g} g (was ${old.carb_g}).`)
  for (const m of now.meals) {
    const was = old.meals.find((o) => o.kind === m.kind)
    if (!was) continue
    if (was.code !== m.code) lines.push(`${cap(m.kind.replaceAll('_', '-').toLowerCase())} is now ${m.code} (was ${was.code}).`)
  }
  return lines
}

export function describeEventChanges(old: EventSnapshot, now: EventSnapshot): string[] {
  const lines: string[] = []
  if (old.weather_source !== now.weather_source && now.weather_source === 'FORECAST') lines.push('The forecast now covers the race.')
  if (old.max_temp_c != null && now.max_temp_c != null && Math.abs(now.max_temp_c - old.max_temp_c) >= 2) lines.push(`Expected up to ${now.max_temp_c} °C (was ${old.max_temp_c}).`)
  if (Math.abs(now.fluid_ml_per_h - old.fluid_ml_per_h) >= 50) lines.push(`Fluid ${now.fluid_ml_per_h > old.fluid_ml_per_h ? 'up' : 'down'} to ${now.fluid_ml_per_h} ml an hour (was ${old.fluid_ml_per_h}).`)
  if (Math.abs(now.carbs_g_per_h - old.carbs_g_per_h) >= 5) lines.push(`Carbohydrate now ${now.carbs_g_per_h} g an hour (was ${old.carbs_g_per_h}).`)
  if (Math.abs(now.duration_min - old.duration_min) >= 5) lines.push(`Planned duration ${now.duration_min} min (was ${old.duration_min}).`)
  if (now.sodium_mg_per_l !== old.sodium_mg_per_l) lines.push(`Sodium ${now.sodium_mg_per_l} mg per litre (was ${old.sodium_mg_per_l}).`)
  return lines
}
