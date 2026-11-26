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
import { offsetLookup, samplesOf, watchStressDays, type WatchStressDay } from './watchStress.ts'
import { energyAvailability, sessionFuel, TRAINING_FUEL, type TfBody, type TfFuel, type TfMeal, type TfSession } from './trainingFuel.ts'

export const WINDOW_DAYS = 180
/** A day with fewer logged kcal than this counts as not fully logged: no energy balance for it. */
export const MIN_LOGGED_KCAL = 1200
/** The nightly window for the watch's stress; a first fill asks for more (`stress_days`). */
export const STRESS_WINDOW_DAYS = 60
export const STRESS_METRICS = ['watch_stress_mean', 'watch_stress_high_share'] as const

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
  /** The watch's total daily energy use (wearable_daily.calories_total), for the logged energy balance. Optional. */
  expenditure?: { date: string; kcal: number | null }[]
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
  // Logged energy balance: what was eaten (the log) minus what the watch says was spent, only on days the log looks complete
  // (at least MIN_LOGGED_KCAL eaten), so a half-logged day is not read as a deficit. Over weeks its sum against the scale's
  // trend says whether the log and the watch agree (7,700 kcal is about a kilogram).
  const spent = new Map((x.expenditure ?? []).map((r) => [r.date, r.kcal]))
  for (const e of energyAvailability(x.meals, x.sessions, x.body)) {
    const out = spent.get(e.date)
    if (e.date < from || e.date > to || out === null || out === undefined || !(out > 800) || e.intakeKcal < MIN_LOGGED_KCAL) continue
    daily.push({ user_id: userId, metric: 'energy_balance', date: e.date, value: Math.round(e.intakeKcal - out), detail: { intake_kcal: e.intakeKcal, expenditure_kcal: out } })
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

/** derived_daily rows for the watch's stress: the day's mean (0-100) and the share of readings in the high band (%). Pure. */
export function stressDerivedRows(userId: string, days: WatchStressDay[]): DerivedDailyRow[] {
  return days.flatMap((d) => [
    { user_id: userId, metric: 'watch_stress_mean', date: d.date, value: d.mean, detail: { n: d.n } },
    { user_id: userId, metric: 'watch_stress_high_share', date: d.date, value: d.highShare, detail: { n: d.n } },
  ])
}

export interface DerivedRun { userId: string; from: string; to: string; daily: number; sessions: number }

export async function runDerived(admin: Admin, userId: string, now: Date = new Date(), opts: { stressDays?: number } = {}): Promise<DerivedRun> {
  const ctx = await admin.from('daily_context').select('date, utc_offset_min').eq('user_id', userId).order('date', { ascending: false }).limit(5)
  const to = localDateAt(now, ctx.error ? [] : (ctx.data ?? []) as { date: string; utc_offset_min: number | null }[])
  const from = addDays(to, -(WINDOW_DAYS - 1))
  const early = addDays(from, -60) // the 28-day stool baseline, a day of meals before the first session, 60 days of body data
  const q = (table: string, cols: string, col: string, lo: string, hi: string) => (a: number, b: number) =>
    admin.from(table).select(cols).eq('user_id', userId).gte(col, lo).lte(col, hi).order(col).order('id').range(a, b)
  const [stool, checkins, meals, sessions, body, wear] = await Promise.all([
    fetchAll<Record<string, unknown>>(q('stool_log', 'id, occurred_at, bristol_type, discomfort, complete_evacuation', 'occurred_at', `${addDays(from, -28)}T00:00:00`, `${to}T23:59:59.999`)),
    fetchAll<Record<string, unknown>>((a, b) => admin.from('evening_checkins').select('date, gut_comfort, gut_symptoms').eq('user_id', userId).gte('date', from).lte('date', to).order('date').range(a, b)),
    fetchAll<Record<string, unknown>>(q('meals', 'id, logged_at, calories, carbs_g, protein_g', 'logged_at', `${addDays(from, -1)}T00:00:00`, `${addDays(to, 1)}T23:59:59.999`)),
    fetchAll<Record<string, unknown>>(q('exercise_sessions', 'id, start_time, type, duration_min, calories_active', 'start_time', `${from}T00:00:00`, `${to}T23:59:59.999`)),
    fetchAll<Record<string, unknown>>(q('body_metrics', 'id, date, weight_kg, body_fat_pct', 'date', early, to)),
    fetchAll<Record<string, unknown>>((a, b) => admin.from('wearable_daily').select('date, calories_total').eq('user_id', userId).gte('date', from).lte('date', to).order('date').range(a, b)),
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
    expenditure: wear.error ? [] : wear.rows.map((r) => ({ date: String(r.date), kcal: num(r.calories_total) })),
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

  // The watch's all-day stress, from the raw extracts the zepp-extract function stores (watchStress.ts), per local day.
  const stressFrom = addDays(to, -((opts.stressDays ?? STRESS_WINDOW_DAYS) - 1))
  const raw = await fetchAll<{ query_date: string; raw_body: unknown }>((a, b) =>
    admin.from('zepp_raw_extracts').select('query_date, raw_body').eq('user_id', userId).eq('metric', 'stress').eq('status_code', 200)
      .gte('query_date', addDays(stressFrom, -1)).lte('query_date', addDays(to, 1)).order('query_date').range(a, b), 200)
  let stressRows = 0
  if (!raw.error) {
    const offsets = await fetchAll<{ date: string; utc_offset_min: number | null }>((a, b) =>
      admin.from('daily_context').select('date, utc_offset_min').eq('user_id', userId).gte('date', addDays(stressFrom, -30)).order('date').range(a, b))
    const days = watchStressDays(raw.rows.flatMap((r) => samplesOf(r.raw_body)), offsetLookup(offsets.error ? [] : offsets.rows))
      .filter((d) => d.date >= stressFrom && d.date <= to)
    const sr = stressDerivedRows(userId, days)
    const delStress = await admin.from('derived_daily').delete().eq('user_id', userId).in('metric', [...STRESS_METRICS]).gte('date', stressFrom).lte('date', to)
    if (delStress.error) throw new Error(`derived_daily: ${delStress.error.message}`)
    for (let i = 0; i < sr.length; i += 500) {
      const { error } = await admin.from('derived_daily').insert(sr.slice(i, i + 500))
      if (error) throw new Error(`derived_daily: ${error.message}`)
    }
    stressRows = sr.length
  }
  return { userId, from, to, daily: rows.daily.length + stressRows, sessions: rows.sessions.length }
}
