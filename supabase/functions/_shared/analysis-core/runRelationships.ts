// I/O for the relationship engine: reads a user's raw rows through a duck-typed Supabase client (tested in Node with an
// in-memory fake, run unchanged in the analysis-run Edge Function), builds the daily frame the catalogue refers to, runs
// the catalogue and publishes one analysis_results row per hypothesis (kind 'relationship').
import { loadAnchorsByDate, sessionLoad } from './trainingLoad.ts'
import { HYPOTHESES, CATALOGUE_VERSION, registeredHypothesis } from './hypotheses.ts'
import type { RegisteredQuestionRow } from './hypotheses.ts'
import { analyseHypothesis, MIN_PAIRS, runCatalogue } from './relationship.ts'
import type { Frame, RelationshipOutcome } from './relationship.ts'
import { alignToAxis, dateAxis } from './stats.ts'
import type { Num } from './stats.ts'
import { CORE_VERSION } from './version.ts'
import { fetchAll } from './paging.ts'
import { publishResults } from './publish.ts'
import type { ResultRow } from './publish.ts'
import { baselineExclusionSpans, loadHealthInputs } from './healthLoad.ts'
import { LATE_SCREEN_CATEGORIES, routineSeries } from './routineExposures.ts'
import type { RoutineInputs } from './routineExposures.ts'
import { gutDayScore, mealSeries } from './mealExposures.ts'
import { caffeineBoardSeries, sleepOutcomeSeries } from './sleepExposures.ts'
import { offsetLookup, samplesOf, watchStressDays, type WatchStressDay } from './watchStress.ts'
import type { GutCheckinRow, StoolRow } from './mealExposures.ts'

export const RELATIONSHIP_LOOKBACK_DAYS = 365
/**
 * The relationship engine's own lookback: sleep (from Mar 2023), sessions, steps and resting heart rate go back years, so the
 * questions that rest on them use about 4 years instead of 1 (HRV starts in Jan 2026, so its questions are unchanged: a day
 * without both ends of a pair is never used). The model already carries a weekday term and a spline trend every 60 days. The
 * other engines keep the 365-day frame their calibrations were set on.
 */
export const RELATIONSHIP_LONG_LOOKBACK_DAYS = 1500
// A day counts as having "afternoon caffeine" from this local hour on.
export const CAFFEINE_PM_HOUR = 14
export const MIN_TRAINING_MIN = 20
// Session types that are not training for this purpose (a walk or an unclassified activity of 20+ minutes is almost daily).
export const NON_TRAINING_TYPES = ['walk', 'other']

export interface CheckinRow {
  date: string
  drinks: number | null
  caffeine_after_14: boolean | null
  late_meal: boolean | null
  stress: number | null
  /** The evening check-in's gut questions (2 Nov 2026); absent on older rows and tests. */
  gut_comfort?: number | null
  gut_symptoms?: string[] | null
}

export interface RawInputs {
  wearable: { date: string; hrv: number | null; rhr: number | null; steps: number | null; spo2_avg?: number | null }[]
  sleep: { date: string; hours: number | null; deep_min?: number | null; rem_min?: number | null; light_min?: number | null; respiratory_rate?: number | null; bedtime?: string | null }[]
  /** Water logged per day (hydration_daily), litres in the frame; a day without a row is unknown. */
  hydration?: { date: string; ml: number | null }[]
  /** The nightly heart-rate dip (hr_dip_nightly), a sleep outcome from catalogue 2.5.0. */
  hrDip?: { date: string; dip_pct: number | null }[]
  wellbeing: { date: string; energy: number | null; mood: number | null; stress: number | null; soreness: number | null }[]
  meals: {
    logged_at: string
    calories?: number | null
    protein_g?: number | null
    carbs_g?: number | null
    fat_g?: number | null
    fiber_g?: number | null
    meal_items: { alcohol_g: number | null; caffeine_mg: number | null }[] | null
  }[]
  sessions: { start_time: string; duration_min: number | null; type?: string | null; rpe?: number | null; avg_hr?: number | null; max_hr?: number | null }[]
  recovery: { date: string }[]
  illnesses: { start_date: string; end_date: string | null }[]
  /** Evening check-ins (analysis review phase B); optional so older callers and tests keep working. */
  checkins?: CheckinRow[]
  /** The phone's UTC offset per local date (daily_context); timestamps become local dates and hours with it. */
  context?: { date: string; utc_offset_min: number; temp_max_c?: number | null }[]
  /** Routine intelligence R4: phone use and mobility published by the phone (routineExposures.ts). */
  routine?: RoutineInputs
  /** The app's scored mornings (score_history): next-morning RECOVERY and SLEEP as outcomes (catalogue 2.2.0). */
  scores?: { day: string; recovery: number | null; sleep: number | null; charge?: number | null }[]
  /** Stool log (catalogue 2.3.0): the daily digestion score with the evening check-in's gut questions. */
  stool?: StoolRow[]
  /** Meal profile tags (catalogue 2.4.0): a meal tagged caffeine by its food groups counts as caffeine. */
  mealTags?: { logged_at: string; tags: string[] | null }[]
  /** The watch's all-day stress per local day (watchStress.ts), from the raw extracts; days under 12 readings are absent. */
  watchStress?: WatchStressDay[]
}

// Until 04:00 the small hours belong to the evening before: a drink or a late meal logged at 00:40 is part of that night.
export const EVENING_ROLLOVER_HOUR = 4
// A session of this many minutes or more is a long one (catalogue 2.4.0, as in trainingFuel.ts).
export const LONG_SESSION_MIN = 90
// A meal from this local hour on counts as a late meal (on the evening's date).
export const LATE_MEAL_HOUR = 21
// A session ending at or after this local hour counts as evening training.
export const EVENING_TRAINING_HOUR = 19
// Stress at or above this on the check-in's 1-5 scale counts as a stressful day.
export const HIGH_STRESS = 4

/**
 * Local date and hour of a UTC timestamp, using the phone's recorded offset for that day: the latest daily_context row
 * on or before the timestamp's UTC date, the earliest row for anything before the first one, or UTC when there are none.
 * Before this, dates and hours were sliced from the UTC string, so in UTC+8 a 15:00 coffee counted as morning caffeine
 * and a 07:30 breakfast landed on the day before.
 */
export function localClock(context: { date: string; utc_offset_min: number }[]): (stamp: string) => { date: string; hour: number } {
  const local = localMsOf(context)
  return (stamp: string) => {
    const t = local(stamp)
    if (t === null) return { date: day(stamp), hour: hourOf(stamp) }
    const iso = new Date(t).toISOString()
    return { date: iso.slice(0, 10), hour: Number(iso.slice(11, 13)) }
  }
}

/** A real instant as milliseconds of local time (read as UTC), with the phone's offset for that day (see localClock). */
export function localMsOf(context: { date: string; utc_offset_min: number }[]): (stamp: string) => number | null {
  const sorted = [...context].sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))
  const offsetFor = (utcDate: string): number => {
    if (sorted.length === 0) return 0
    let lo = 0
    let hi = sorted.length - 1
    let best = -1
    while (lo <= hi) {
      const mid = (lo + hi) >> 1
      if (sorted[mid].date <= utcDate) { best = mid; lo = mid + 1 } else hi = mid - 1
    }
    return Number(sorted[best >= 0 ? best : 0].utc_offset_min) || 0
  }
  return (stamp: string) => {
    const t = Date.parse(stamp)
    if (!Number.isFinite(t)) return null
    return t + offsetFor(new Date(t).toISOString().slice(0, 10)) * 60000
  }
}

/**
 * Local date and hour of a LOGGED time: the app and the sync writers store the phone's wall-clock reading with a zero
 * offset (android domain/WallClock.kt; zepp-extract toLocalLabeledUtcIso), so the digits are the local time. Reading
 * them through localClock moved every meal and session by the UTC offset (+8 h: lunch counted as a late meal, an
 * 08:00 coffee as afternoon caffeine, a 17:00 session on the next day).
 */
export function wallClock(stamp: string): { date: string; hour: number } {
  return { date: day(stamp), hour: hourOf(stamp) }
}

/** The evening a local moment belongs to: the same date, or the day before in the small hours. */
function eveningOf(c: { date: string; hour: number }): string {
  return c.hour < EVENING_ROLLOVER_HOUR ? shift(c.date, -1) : c.date
}

const day = (stamp: string) => String(stamp).slice(0, 10)
const hourOf = (stamp: string) => Number(String(stamp).slice(11, 13))
const num = (v: unknown): number | null => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

function shift(date: string, days: number): string {
  return new Date(Date.parse(date + 'T00:00:00Z') + days * 86400000).toISOString().slice(0, 10)
}

/** The daily frame the hypothesis catalogue refers to. Pure. Exposure models are the ones stated on each hypothesis. */
export function buildFrame(raw: RawInputs, today: string, lookbackDays = RELATIONSHIP_LOOKBACK_DAYS): Frame {
  const axis = dateAxis(shift(today, -(lookbackDays - 1)), today)
  const col = <T extends { date: string }>(rows: T[], key: keyof T, min: number): Num[] =>
    alignToAxis(axis, rows.map((r) => { const v = num(r[key]); return { date: r.date, value: v !== null && v >= min ? v : null } }))

  const series: Record<string, Num[]> = {
    hrv: col(raw.wearable, 'hrv', 0.0001),
    rhr: col(raw.wearable, 'rhr', 0.0001),
    steps: col(raw.wearable, 'steps', 0),
    sleep_hours: col(raw.sleep, 'hours', 2), // under 2 h is a nap or a fault
    energy: col(raw.wellbeing, 'energy', 0),
    mood: col(raw.wellbeing, 'mood', 0),
    stress: col(raw.wellbeing, 'stress', 0),
    soreness: col(raw.wellbeing, 'soreness', 0),
  }
  // The night's mean respiratory rate (repeatable interventions such as a nasal strip); nothing below 1 br/min is real.
  series.resp_rate = col(raw.sleep, 'respiratory_rate', 1)
  // Deep + REM minutes, only on nights where both stages were recorded (the typicality engine's fourth signal).
  series.deep_rem_min = alignToAxis(
    axis,
    raw.sleep.map((r) => {
      const deep = num(r.deep_min)
      const rem = num(r.rem_min)
      return { date: r.date, value: deep !== null && rem !== null && deep + rem > 0 ? deep + rem : null }
    }),
  )

  // Analysis review phase B. Exposures, built in local time with explicit zeros:
  //   1  positive evidence that evening: a food-log item, or the check-in;
  //   0  the check-in says none; or, only before the first check-in ever, a day with any food log (the legacy rule);
  //   null  unknown. After check-ins start, a day without one and without a positive log is unknown, not a zero.
  // Food logged in the small hours belongs to the evening before (a nightcap at 00:40, a midnight snack).
  // Logged times (meals.logged_at, exercise_sessions.start_time) are the phone's local wall clock labelled UTC, so their
  // digits are already local (CLAUDE.md, "Dates and times"); only real instants (trips) go through the offset clock.
  const clock = wallClock
  const instantClock = localClock(raw.context ?? [])
  const checkins = new Map((raw.checkins ?? []).map((c) => [c.date, c]))
  const firstCheckin = [...checkins.keys()].sort()[0] ?? null
  const mealDays = new Set<string>()
  const alcoholByDay = new Map<string, number>()
  const caffeinePmByDay = new Map<string, number>()
  const lateMealDays = new Set<string>()
  for (const m of raw.meals) {
    const c = clock(m.logged_at)
    const evening = eveningOf(c)
    mealDays.add(evening)
    const late = c.hour >= LATE_MEAL_HOUR || c.hour < EVENING_ROLLOVER_HOUR
    if (late) lateMealDays.add(evening)
    for (const it of m.meal_items ?? []) {
      alcoholByDay.set(evening, (alcoholByDay.get(evening) ?? 0) + (num(it.alcohol_g) ?? 0))
      if (c.hour >= CAFFEINE_PM_HOUR || c.hour < EVENING_ROLLOVER_HOUR) caffeinePmByDay.set(evening, (caffeinePmByDay.get(evening) ?? 0) + (num(it.caffeine_mg) ?? 0))
    }
  }
  const legacyZero = (d: string) => (firstCheckin === null || d < firstCheckin) && mealDays.has(d)
  const exposure = (d: string, logged: boolean, answer: boolean | null | undefined): Num =>
    logged || answer === true ? 1 : answer === false ? 0 : legacyZero(d) ? 0 : null
  series.alcohol_any = axis.map((d) => {
    const drinks = checkins.get(d)?.drinks
    return exposure(d, (alcoholByDay.get(d) ?? 0) > 0, drinks === null || drinks === undefined ? null : drinks > 0)
  })
  // Analysis review phase D: how much (standard drinks), for the dose curve. The check-in's count wins; otherwise logged
  // grams at 14 g a drink; a known none is 0; unknown stays unknown.
  series.alcohol_drinks = axis.map((d, i) => {
    const drinks = num(checkins.get(d)?.drinks)
    if (drinks !== null) return drinks
    const grams = alcoholByDay.get(d) ?? 0
    if (grams > 0) return Math.round((grams / 14) * 10) / 10
    return series.alcohol_any[i] === 0 ? 0 : null
  })
  // Catalogue 2.4.0: any caffeine in the eating day, from the items' caffeine or a meal tagged caffeine by its food groups;
  // a day with meals logged and none of either is a zero, a day with no meals unknown.
  const caffeineDays = new Set<string>()
  for (const m of raw.meals) if ((m.meal_items ?? []).some((it) => (num(it.caffeine_mg) ?? 0) > 0)) caffeineDays.add(eveningOf(clock(m.logged_at)))
  for (const m of raw.mealTags ?? []) if (m.tags?.includes('caffeine')) caffeineDays.add(eveningOf(clock(m.logged_at)))
  series.caffeine_any = axis.map((d) => (caffeineDays.has(d) ? 1 : mealDays.has(d) ? 0 : null))
  series.caffeine_pm_any = axis.map((d) => exposure(d, (caffeinePmByDay.get(d) ?? 0) > 0, checkins.get(d)?.caffeine_after_14))
  series.late_meal = axis.map((d) => exposure(d, lateMealDays.has(d), checkins.get(d)?.late_meal))
  // Stress is a rating, so only an answered check-in says anything about it.
  // A high-stress day is a 4 or 5 in the evening check-in or in the morning wellbeing log (the question's own model text says
  // so; the wellbeing log was never read, so the evening check-in's 1-3 left the exposure at zero days). Either one saying
  // high makes it a high day; otherwise a known low rating makes it a zero; neither known leaves the day out.
  const wellbeingStress = new Map(raw.wellbeing.map((w) => [w.date, num(w.stress)]))
  series.stress_high = axis.map((d) => {
    const rated = [num(checkins.get(d)?.stress), wellbeingStress.get(d) ?? null].filter((v): v is number => v !== null)
    return rated.length === 0 ? null : rated.some((v) => v >= HIGH_STRESS) ? 1 : 0
  })
  // The watch's own all-day stress (Zepp), from Feb 2025: the day's mean and the share of readings in its high band.
  const watchByDay = new Map((raw.watchStress ?? []).map((w) => [w.date, w]))
  series.stress_watch = axis.map((d) => watchByDay.get(d)?.mean ?? null)
  series.stress_watch_high = axis.map((d) => watchByDay.get(d)?.highShare ?? null)

  // Training: recorded by the watch, so a day with a watch record and no qualifying session is a real zero.
  const training = raw.sessions.filter((s) => (num(s.duration_min) ?? 0) >= MIN_TRAINING_MIN && !NON_TRAINING_TYPES.includes(String(s.type ?? '')))
  const sessionDays = new Set(training.map((s) => clock(s.start_time).date))
  const eveningSessionDays = new Set(training
    .map((s) => {
      const end = new Date(Date.parse(s.start_time) + (num(s.duration_min) ?? 0) * 60000).toISOString()
      return clock(end)
    })
    .filter((c) => c.hour >= EVENING_TRAINING_HOUR)
    .map((c) => c.date))
  const watchDays = new Set(raw.wearable.filter((w) => num(w.hrv) !== null || num(w.rhr) !== null || num(w.steps) !== null).map((w) => w.date))
  series.training_day = axis.map((d) => (sessionDays.has(d) ? 1 : watchDays.has(d) ? 0 : null))
  series.evening_training = axis.map((d) => (eveningSessionDays.has(d) ? 1 : watchDays.has(d) ? 0 : null))
  // Analysis review phase D: training minutes per day, the dose for the lag curve (zero on watch days without a session).
  const minutesByDay = new Map<string, number>()
  for (const s of training) {
    const d = clock(s.start_time).date
    minutesByDay.set(d, (minutesByDay.get(d) ?? 0) + (num(s.duration_min) ?? 0))
  }
  series.training_min = axis.map((d) => (minutesByDay.has(d) ? (minutesByDay.get(d) as number) : watchDays.has(d) ? 0 : null))
  // Catalogue 2.4.0: a day with a session of 90 minutes or more (zero on other watch days).
  const longDays = new Set(training.filter((s) => (num(s.duration_min) ?? 0) >= LONG_SESSION_MIN).map((s) => clock(s.start_time).date))
  series.long_session = axis.map((d) => (longDays.has(d) ? 1 : watchDays.has(d) ? 0 : null))
  // Analysis review phase G: the apps' session load (session RPE x minutes, else TRIMP against the person's maximum and
  // resting heart rate as of the session's date, as on Android and in the console), summed per local day, for the personal
  // fitness-fatigue fit; zero on watch days without a session.
  const sessionMaxima: [string, number][] = []
  for (const s of raw.sessions) {
    const max = num(s.max_hr)
    if (max !== null) sessionMaxima.push([clock(s.start_time).date, max])
  }
  const restingDaily: [string, number][] = raw.wearable.flatMap((w) => {
    const rhr = num(w.rhr)
    return rhr === null ? [] : [[w.date, rhr] as [string, number]]
  })
  const anchorsOn = loadAnchorsByDate(sessionMaxima, restingDaily)
  const loadByDay = new Map<string, number>()
  for (const s of raw.sessions) {
    const d = clock(s.start_time).date
    const load = sessionLoad({ duration_min: num(s.duration_min), rpe: num(s.rpe), avg_hr: num(s.avg_hr), max_hr: num(s.max_hr) }, anchorsOn(d))
    if (load === null) continue
    loadByDay.set(d, (loadByDay.get(d) ?? 0) + load)
  }
  series.training_load = axis.map((d) => (loadByDay.has(d) ? (loadByDay.get(d) as number) : watchDays.has(d) ? 0 : null))

  // Recovery sessions are logged by hand: zero only from the day the first one was logged.
  const recoveryDays = new Set(raw.recovery.map((r) => r.date))
  const firstRecovery = [...recoveryDays].sort()[0]
  series.recovery_any = axis.map((d) => (recoveryDays.has(d) ? 1 : firstRecovery !== undefined && d >= firstRecovery ? 0 : null))

  // The app's own morning scores (score_history), dated by the morning they describe, as HRV is.
  const scores = raw.scores ?? []
  series.recovery_score = alignToAxis(axis, scores.map((r) => ({ date: day(r.day), value: num(r.recovery) })))
  series.sleep_score = alignToAxis(axis, scores.map((r) => ({ date: day(r.day), value: num(r.sleep) })))

  // Catalogue 2.3.0 (mealExposures.ts): meal timing and composition per eating day, and the daily digestion score.
  Object.assign(series, mealSeries(axis, raw.meals, raw.sleep, localMsOf(raw.context ?? [])))
  series.gut_day_score = gutDayScore(axis, raw.stool ?? [], (raw.checkins ?? []) as GutCheckinRow[])
  // Catalogue 2.5.0 (sleepExposures.ts): the caffeine still on board at bedtime, and sleep outcomes the engine did not read.
  series.hydration_l = alignToAxis(axis, (raw.hydration ?? []).map((h) => { const ml = num(h.ml); return { date: h.date, value: ml !== null && ml >= 200 && ml <= 8000 ? Math.round(ml) / 1000 : null } }))
  series.temp_max_c = alignToAxis(axis, (raw.context ?? []).map((c) => { const t = num(c.temp_max_c); return { date: c.date, value: t !== null && t > -40 && t < 55 ? t : null } }))
  series.caffeine_board_bed = caffeineBoardSeries(axis, raw.meals, raw.sleep, localMsOf(raw.context ?? []))
  Object.assign(series, sleepOutcomeSeries(axis, raw.sleep, raw.hrDip ?? [], raw.wearable, localMsOf(raw.context ?? [])))

  // Routine intelligence R4: phone use, places and commutes, each known only on days its source covers.
  Object.assign(series, routineSeries(axis, raw.routine ?? {}, instantClock))

  // Illness days (and the day after recovery) are left out of every comparison.
  const excludedDays = new Set<string>()
  for (const ill of raw.illnesses) {
    const end = ill.end_date ?? today
    for (const d of dateAxis(day(ill.start_date), shift(day(end), ill.end_date ? 1 : 0))) excludedDays.add(d)
  }
  const last28 = axis.slice(-28)
  const known = (xs: Num[]) => xs.slice(-28).filter((v) => v !== null).length / Math.max(1, last28.length)
  return {
    dates: axis,
    series,
    excluded: axis.map((d) => excludedDays.has(d)),
    capture: {
      firstCheckin,
      knownShare28: { alcohol: Math.round(known(series.alcohol_any) * 100) / 100, caffeine_pm: Math.round(known(series.caffeine_pm_any) * 100) / 100 },
      localTime: (raw.context ?? []).length > 0,
    },
  }
}

// deno-lint-ignore no-explicit-any
export type Admin = any

export interface RelationshipRun {
  userId: string
  error?: string
  results?: { id: string; state: string; n: number; p: number | null; pAdjusted: number | null }[]
  /** Analysis review phase E: registered questions tested tonight. */
  registered?: { id: string; state: string; n: number }[]
  /** The ledger append failed (for example before its migration); the results above were still published. */
  ledgerError?: string
}

export interface LoadedFrame {
  userId: string
  /** The UTC date of the run: the frame's last day. */
  today: string
  /**
   * The person's own calendar date at the run, from their latest UTC offset in daily_context (else the UTC date). Check-ins
   * and episode marks carry the person's local date, so the health engine reads up to this day: east of UTC the nightly
   * run (20:42 UTC) and morning check-ins otherwise fall a day "in the future" and are left out.
   */
  localToday?: string
  frame: Frame
}

/** Reads a user's raw rows once and builds the daily frame; engines that need it share the result. */
export async function loadFrame(admin: Admin, userId: string, now: Date = new Date(), lookbackDays: number = RELATIONSHIP_LOOKBACK_DAYS): Promise<LoadedFrame | { userId: string; error: string }> {
  const today = now.toISOString().slice(0, 10)
  const since = shift(today, -(lookbackDays - 1))
  const sinceStamp = `${since}T00:00:00Z`
  const q = (table: string, cols: string, col: string, from: string) => (a: number, b: number) =>
    admin.from(table).select(cols).eq('user_id', userId).gte(col, from).order(col).range(a, b)

  const [wearable, sleep, wellbeing, meals, sessions, recovery, ill, checkins, context, health, phone, phoneApps, mobility, commutes, scoreRows, stool, gutCheckins, mealTags, stressRaw, hrDip, hydration] = await Promise.all([
    fetchAll<RawInputs['wearable'][number]>(q('wearable_daily', 'date, hrv, rhr, steps, spo2_avg', 'date', since)),
    fetchAll<RawInputs['sleep'][number]>(q('sleep_daily', 'date, hours, deep_min, rem_min, light_min, respiratory_rate, bedtime', 'date', since)),
    fetchAll<RawInputs['wellbeing'][number]>(q('wellbeing_daily', 'date, energy, mood, stress, soreness', 'date', since)),
    fetchAll<RawInputs['meals'][number]>(q('meals', 'logged_at, calories, protein_g, carbs_g, fat_g, fiber_g, meal_items(alcohol_g, caffeine_mg)', 'logged_at', sinceStamp)),
    fetchAll<RawInputs['sessions'][number]>(q('exercise_sessions', 'start_time, duration_min, type, rpe, avg_hr, max_hr', 'start_time', sinceStamp)),
    fetchAll<RawInputs['recovery'][number]>(q('recovery_sessions', 'date', 'date', since)),
    admin.from('illnesses').select('start_date, end_date').eq('user_id', userId),
    // Phase B tables: read softly, so the nightly run keeps working before the migration is applied.
    fetchAll<CheckinRow>(q('evening_checkins', 'date, drinks, caffeine_after_14, late_meal, stress', 'date', since)),
    fetchAll<{ date: string; utc_offset_min: number; temp_max_c?: number | null }>(q('daily_context', 'date, utc_offset_min, temp_max_c', 'date', shift(since, -30))),
    // Health concerns (docs/health-concerns): systemic episodes leave their days out as logged illness does. Read softly;
    // for one release both are read (owner decision 7.2.7), and the copied legacy rows give the same spans.
    loadHealthInputs(admin, userId),
    // Routine intelligence (R1-R3 tables): read softly, like the phase B tables. Apps are filtered to the late-screen
    // categories here rather than in the query, so the reader needs only eq / gte / order / range.
    fetchAll<NonNullable<RoutineInputs['phone']>[number]>(q('phone_usage_daily', 'date, bed_window_min, night_screen_min, night_unlocks, screen_min, unlocks, longest_free_min', 'date', since)),
    fetchAll<NonNullable<RoutineInputs['phoneApps']>[number]>(q('phone_app_usage_daily', 'date, category, after_21_min', 'date', since)),
    fetchAll<NonNullable<RoutineInputs['mobility']>[number]>(q('mobility_daily', 'date, coverage_pct, day_type, outdoor_daylight_min, entropy_norm, new_places', 'date', since)),
    fetchAll<NonNullable<RoutineInputs['commutes']>[number]>((a: number, b: number) =>
      admin.from('trips').select('start_at, end_at, active_min').eq('user_id', userId).eq('is_commute', true).gte('start_at', sinceStamp).order('start_at').range(a, b)),
    // The app's scored mornings: read softly (the table may not exist yet, or the app may not have written it).
    fetchAll<NonNullable<RawInputs['scores']>[number]>(q('score_history', 'day, recovery, sleep, charge', 'day', since)),
    // Catalogue 2.3.0: the stool log and the check-in's gut questions, read softly like the phase B tables.
    fetchAll<StoolRow>(q('stool_log', 'occurred_at, bristol_type, discomfort, complete_evacuation', 'occurred_at', sinceStamp)),
    fetchAll<{ date: string; gut_comfort: number | null; gut_symptoms: string[] | null }>(q('evening_checkins', 'date, gut_comfort, gut_symptoms', 'date', since)),
    // Catalogue 2.4.0: meal profile tags (a view), read softly.
    fetchAll<NonNullable<RawInputs['mealTags']>[number]>(q('meal_profiles', 'logged_at, tags', 'logged_at', sinceStamp)),
    // The watch's all-day stress: the raw extracts zepp-extract stores, one row per UTC day (read in small pages, they are large).
    fetchAll<{ query_date: string; raw_body: unknown }>((a: number, b: number) =>
      admin.from('zepp_raw_extracts').select('query_date, raw_body').eq('user_id', userId).eq('metric', 'stress').eq('status_code', 200)
        .gte('query_date', shift(since, -1)).order('query_date').range(a, b), 200),
    fetchAll<NonNullable<RawInputs['hrDip']>[number]>(q('hr_dip_nightly', 'date, dip_pct', 'date', since)),
    fetchAll<NonNullable<RawInputs['hydration']>[number]>(q('hydration_daily', 'date, ml', 'date', since)),
  ])
  const failed = [wearable, sleep, wellbeing, meals, sessions, recovery].find((r) => r.error)
  if (failed) return { userId, error: failed.error as string }
  if (ill.error) return { userId, error: ill.error.message }

  const frame = buildFrame(
    {
      wearable: wearable.rows, sleep: sleep.rows, wellbeing: wellbeing.rows, meals: meals.rows, sessions: sessions.rows,
      recovery: recovery.rows,
      illnesses: [...((ill.data ?? []) as RawInputs['illnesses']), ...(health.error ? [] : baselineExclusionSpans(health, today))],
      checkins: checkins.error ? [] : mergeGut(checkins.rows, gutCheckins.error ? [] : gutCheckins.rows),
      stool: stool.error ? [] : stool.rows,
      mealTags: mealTags.error ? [] : mealTags.rows,
      hrDip: hrDip.error ? [] : hrDip.rows,
      hydration: hydration.error ? [] : hydration.rows,
      watchStress: stressRaw.error ? [] : watchStressDays(stressRaw.rows.flatMap((r) => samplesOf(r.raw_body)), offsetLookup(context.error ? [] : context.rows)),
      context: context.error ? [] : context.rows,
      routine: {
        phone: phone.error ? [] : phone.rows,
        phoneApps: phoneApps.error ? [] : phoneApps.rows.filter((r) => LATE_SCREEN_CATEGORIES.includes(String(r.category))),
        mobility: mobility.error ? [] : mobility.rows,
        commutes: commutes.error ? [] : commutes.rows,
      },
      scores: scoreRows.error ? [] : scoreRows.rows,
    },
    today,
    lookbackDays,
  )
  return { userId, today, localToday: localDateAt(now, context.error ? [] : context.rows), frame }
}

/** The check-ins with their gut answers, read apart so an older database without those columns still loads. */
function mergeGut(rows: CheckinRow[], gut: { date: string; gut_comfort: number | null; gut_symptoms: string[] | null }[]): CheckinRow[] {
  const byDate = new Map(gut.map((g) => [g.date, g]))
  const out = rows.map((r) => ({ ...r, gut_comfort: byDate.get(r.date)?.gut_comfort ?? null, gut_symptoms: byDate.get(r.date)?.gut_symptoms ?? null }))
  const have = new Set(rows.map((r) => r.date))
  for (const g of gut) if (!have.has(g.date)) out.push({ date: g.date, drinks: null, caffeine_after_14: null, late_meal: null, stress: null, gut_comfort: g.gut_comfort, gut_symptoms: g.gut_symptoms })
  return out
}

/** The person's calendar date at [now] from the newest UTC offset logged (dates up to the day after the UTC date). */
export function localDateAt(now: Date, context: { date: string; utc_offset_min: number | null }[]): string {
  const utc = now.toISOString().slice(0, 10)
  const latest = context.filter((c) => c.utc_offset_min !== null && c.date <= shift(utc, 1)).sort((a, b) => a.date.localeCompare(b.date)).at(-1)
  if (!latest) return utc
  return new Date(now.getTime() + Number(latest.utc_offset_min) * 60_000).toISOString().slice(0, 10)
}

export async function runRelationships(admin: Admin, userId: string, now: Date = new Date(), loaded?: LoadedFrame): Promise<RelationshipRun> {
  const got = loaded ?? (await loadFrame(admin, userId, now))
  if ('error' in got) return { userId, error: got.error }
  const { frame, today } = got
  const outcomes: RelationshipOutcome[] = runCatalogue(HYPOTHESES, frame)

  const results: NonNullable<RelationshipRun['results']> = []
  const ledger: LedgerRow[] = []
  // One upsert per batch of rows and one delete per batch (publish.ts), not an upsert and a delete per hypothesis.
  const familySize = outcomes.filter((x) => x.state !== 'not_yet').length
  const catalogueOut = await publishResults(admin, userId, today, outcomes.map((o) => ({
    kind: 'relationship', key: o.id, state: o.state, have: o.readiness?.have ?? o.n, need: o.readiness?.need ?? MIN_PAIRS, hash: o.inputsHash,
    payload: {
      catalogueVersion: CATALOGUE_VERSION,
      familySize,
      window: { start: frame.dates[0], end: frame.dates[frame.dates.length - 1] },
      // Analysis review phase D: how complete the exposure capture was, so a reader can tell thin logging from no effect.
      capture: frame.capture ?? null,
      ...o,
    },
  })), now)
  const publishedIds = new Set(catalogueOut.published.map((p) => p.key))
  for (const o of outcomes) {
    if (!publishedIds.has(o.id)) continue
    results.push({ id: o.id, state: o.state, n: o.n, p: o.p, pAdjusted: o.pAdjusted })
    ledger.push(ledgerRow(userId, today, 'catalogue', o))
  }
  if (catalogueOut.error) return { userId, error: catalogueOut.error, results }

  // Analysis review phase E: registered questions, each tested only on days after its registration date. Read softly,
  // so the nightly run keeps working before the migration is applied.
  const registered: NonNullable<RelationshipRun['registered']> = []
  const registeredRows: ResultRow[] = []
  const registeredOutcomes = new Map<string, RelationshipOutcome>()
  const reg = await admin.from('registered_questions').select('id, exposure, outcome, lag, no_effect, registered_on, source, withdrawn_at').eq('user_id', userId)
  if (!reg.error) {
    for (const row of (reg.data ?? []) as RegisteredQuestionRow[]) {
      if (row.withdrawn_at) continue
      const h = registeredHypothesis(row)
      if (!h) continue
      const from = frame.dates.findIndex((d) => d > row.registered_on)
      const after: Frame = from < 0
        ? { dates: [], series: {}, excluded: [] }
        : { ...frame, dates: frame.dates.slice(from), excluded: frame.excluded.slice(from), series: Object.fromEntries(Object.entries(frame.series).map(([k, v]) => [k, v.slice(from)])) }
      const o = after.dates.length > 0 ? analyseHypothesis(h, after) : null
      if (!o) continue
      registeredRows.push({
        kind: 'registered_question', key: h.id, state: o.state, have: o.readiness?.have ?? o.n, need: o.readiness?.need ?? MIN_PAIRS, hash: o.inputsHash,
        payload: { registeredOn: row.registered_on, source: row.source ?? null, exposure: row.exposure, target: row.outcome, lag: row.lag, daysSince: after.dates.length, ...o },
      })
      registeredOutcomes.set(h.id, o)
    }
    const registeredOut = await publishResults(admin, userId, today, registeredRows, now)
    for (const p of registeredOut.published) {
      const o = registeredOutcomes.get(p.key)!
      registered.push({ id: p.key, state: o.state, n: o.n })
      ledger.push(ledgerRow(userId, today, 'registered', o))
    }
    if (registeredOut.error) return { userId, error: registeredOut.error, results, registered }
  }

  // Every verdict, every night, kept (append-only; a re-run the same night keeps the first row).
  const { error: ledgerError } = ledger.length > 0
    ? await admin.from('evidence_ledger').upsert(ledger, { onConflict: 'user_id,question_id,as_of,engine_version', ignoreDuplicates: true })
    : { error: null }
  return { userId, results, registered, ...(ledgerError ? { ledgerError: ledgerError.message } : {}) }
}

export interface LedgerRow {
  user_id: string
  question_id: string
  source: 'catalogue' | 'registered'
  as_of: string
  engine_version: string
  catalogue_version: string | null
  inputs_hash: string
  verdict: string
  estimate: number | null
  lo: number | null
  hi: number | null
  unit: string | null
  verdict_lo: number | null
  verdict_hi: number | null
  information: number | null
  no_effect: number
  n: number
  n_on: number | null
}

function ledgerRow(userId: string, asOf: string, source: LedgerRow['source'], o: RelationshipOutcome): LedgerRow {
  return {
    user_id: userId, question_id: o.id, source, as_of: asOf, engine_version: CORE_VERSION,
    catalogue_version: source === 'catalogue' ? CATALOGUE_VERSION : null, inputs_hash: o.inputsHash, verdict: o.state,
    estimate: o.effect?.value ?? null, lo: o.effect?.lo ?? null, hi: o.effect?.hi ?? null, unit: o.effect?.unit ?? null,
    verdict_lo: o.verdictInterval?.lo ?? null, verdict_hi: o.verdictInterval?.hi ?? null, information: o.verdictInterval?.information ?? null,
    no_effect: o.noEffect, n: o.n, n_on: o.nOn,
  }
}
