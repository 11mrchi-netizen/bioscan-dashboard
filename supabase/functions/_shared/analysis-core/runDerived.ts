// Engine `derived` (group `reports`): writes the computed values the pages show over time, so the console and the phone
// read them like any stored series instead of working them out again (migration 20261125140000_derived_daily_session_fuel).
//
//   derived_daily  gut_day_score (mealExposures.ts) and energy_availability (trainingFuel.ts) per local date
//   session_fuel   the food around each training session (trainingFuel.ts sessionFuel)
//
// Each run rewrites the last WINDOW_DAYS days: rows in the window are deleted and written again, so a deleted stool or meal
// does not leave a stale value behind. Logged times are wall clock (their digits are local), read as they are.

import { fetchAll } from './paging.ts'
import { gutDayScore, type GutCheckinRow, type StoolRow } from './mealExposures.ts'
import { localDateAt, type Admin } from './runRelationships.ts'
import { addDays } from './seriesContract.ts'
import { energyAvailability, sessionFuel, TRAINING_FUEL, type TfBody, type TfFuel, type TfMeal, type TfSession } from './trainingFuel.ts'

export const WINDOW_DAYS = 180

export interface DerivedDailyRow { user_id: string; metric: string; date: string; value: number; detail: Record<string, unknown> | null }
export interface SessionFuelRow {
  session_id: number; user_id: string; date: string; duration_min: number; long: boolean; hours_since_meal: number | null; fasted: boolean
  carbs_before_g: number; carbs_during_g: number | null; protein_after_g: number; carbs_after_g: number; protein_dose_g: number
}

export interface DerivedInputs {
  stool: StoolRow[]
  checkins: GutCheckinRow[]
  meals: TfMeal[]
  sessions: TfSession[]
  body: TfBody[]
  fuel: TfFuel[]
}

/** The rows for [from]..[to] (local dates). Pure. */
export function derivedRows(userId: string, x: DerivedInputs, from: string, to: string): { daily: DerivedDailyRow[]; sessions: SessionFuelRow[] } {
  const axis: string[] = []
  for (let d = from; d <= to; d = addDays(d, 1)) axis.push(d)
  const daily: DerivedDailyRow[] = []
  gutDayScore(axis, x.stool, x.checkins).forEach((v, i) => {
    if (v !== null) daily.push({ user_id: userId, metric: 'gut_day_score', date: axis[i], value: v, detail: null })
  })
  for (const e of energyAvailability(x.meals, x.sessions, x.body)) {
    if (e.date < from || e.date > to) continue
    daily.push({ user_id: userId, metric: 'energy_availability', date: e.date, value: e.ea, detail: { intake_kcal: e.intakeKcal, exercise_kcal: e.exerciseKcal, ffm_kg: e.ffmKg } })
  }
  const weights = x.body.filter((b) => b.weightKg !== null).sort((a, b) => a.date.localeCompare(b.date))
  const doseOn = (date: string) => {
    const w = weights.filter((b) => b.date <= date).at(-1)?.weightKg ?? null
    return w !== null && w >= 30 && w <= 250 ? Math.round(TRAINING_FUEL.proteinPerKgAfter * w) : TRAINING_FUEL.defaultProteinAfterG
  }
  const sessions = sessionFuel(x.sessions, x.meals, x.fuel).filter((s) => s.date >= from && s.date <= to).map((s) => ({
    session_id: s.id, user_id: userId, date: s.date, duration_min: s.durationMin, long: s.long, hours_since_meal: s.hoursSinceMeal,
    fasted: s.fasted, carbs_before_g: s.carbsBefore, carbs_during_g: s.carbsDuring, protein_after_g: s.proteinAfter,
    carbs_after_g: s.carbsAfter, protein_dose_g: doseOn(s.date),
  }))
  return { daily, sessions }
}

const num = (v: unknown) => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

export interface DerivedRun { userId: string; from: string; to: string; daily: number; sessions: number }

export async function runDerived(admin: Admin, userId: string, now: Date = new Date()): Promise<DerivedRun> {
  const ctx = await admin.from('daily_context').select('date, utc_offset_min').eq('user_id', userId).order('date', { ascending: false }).limit(5)
  const to = localDateAt(now, ctx.error ? [] : (ctx.data ?? []) as { date: string; utc_offset_min: number | null }[])
  const from = addDays(to, -(WINDOW_DAYS - 1))
  const early = addDays(from, -60) // the 28-day stool baseline, a day of meals before the first session, 60 days of body data
  const q = (table: string, cols: string, col: string, lo: string, hi: string) => (a: number, b: number) =>
    admin.from(table).select(cols).eq('user_id', userId).gte(col, lo).lte(col, hi).order(col).order('id').range(a, b)
  const [stool, checkins, meals, sessions, body] = await Promise.all([
    fetchAll<Record<string, unknown>>(q('stool_log', 'id, occurred_at, bristol_type, discomfort, complete_evacuation', 'occurred_at', `${addDays(from, -28)}T00:00:00`, `${to}T23:59:59.999`)),
    fetchAll<Record<string, unknown>>((a, b) => admin.from('evening_checkins').select('date, gut_comfort, gut_symptoms').eq('user_id', userId).gte('date', from).lte('date', to).order('date').range(a, b)),
    fetchAll<Record<string, unknown>>(q('meals', 'id, logged_at, calories, carbs_g, protein_g', 'logged_at', `${addDays(from, -1)}T00:00:00`, `${addDays(to, 1)}T23:59:59.999`)),
    fetchAll<Record<string, unknown>>(q('exercise_sessions', 'id, start_time, type, duration_min, calories_active', 'start_time', `${from}T00:00:00`, `${to}T23:59:59.999`)),
    fetchAll<Record<string, unknown>>(q('body_metrics', 'id, date, weight_kg, body_fat_pct', 'date', early, to)),
  ])
  for (const r of [stool, meals, sessions, body]) if (r.error) throw new Error(`derived: ${r.error}`)
  const ss: TfSession[] = sessions.rows.map((r) => ({ id: Number(r.id), at: String(r.start_time), type: (r.type as string | null) ?? null, durationMin: num(r.duration_min), activeKcal: num(r.calories_active) }))
  let fuel: TfFuel[] = []
  if (ss.length) {
    const f = await admin.from('fueling_intake_log').select('exercise_session_id, carbs_g, status').eq('user_id', userId).in('exercise_session_id', ss.map((s) => s.id))
    if (!f.error) fuel = ((f.data ?? []) as Record<string, unknown>[]).map((r) => ({ sessionId: Number(r.exercise_session_id), carbsG: num(r.carbs_g), status: (r.status as string | null) ?? null }))
  }
  const rows = derivedRows(userId, {
    stool: stool.rows as unknown as StoolRow[],
    checkins: checkins.error ? [] : (checkins.rows as unknown as GutCheckinRow[]),
    meals: meals.rows.map((r) => ({ at: String(r.logged_at), kcal: num(r.calories), carbsG: num(r.carbs_g), proteinG: num(r.protein_g) })),
    sessions: ss,
    body: body.rows.map((r) => ({ date: String(r.date), weightKg: num(r.weight_kg), bodyFatPct: num(r.body_fat_pct) })),
    fuel,
  }, from, to)

  const del = await admin.from('derived_daily').delete().eq('user_id', userId).gte('date', from).lte('date', to)
  if (del.error) throw new Error(`derived_daily: ${del.error.message}`)
  for (let i = 0; i < rows.daily.length; i += 500) {
    const { error } = await admin.from('derived_daily').insert(rows.daily.slice(i, i + 500))
    if (error) throw new Error(`derived_daily: ${error.message}`)
  }
  const delS = await admin.from('session_fuel').delete().eq('user_id', userId).gte('date', from).lte('date', to)
  if (delS.error) throw new Error(`session_fuel: ${delS.error.message}`)
  if (rows.sessions.length) {
    const { error } = await admin.from('session_fuel').upsert(rows.sessions, { onConflict: 'session_id' })
    if (error) throw new Error(`session_fuel: ${error.message}`)
  }
  return { userId, from, to, daily: rows.daily.length, sessions: rows.sessions.length }
}
