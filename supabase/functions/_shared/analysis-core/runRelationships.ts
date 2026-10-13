// I/O for the relationship engine: reads a user's raw rows through a duck-typed Supabase client (tested in Node with an
// in-memory fake, run unchanged in the analysis-run Edge Function), builds the daily frame the catalogue refers to, runs
// the catalogue and publishes one analysis_results row per hypothesis (kind 'relationship').
import { HYPOTHESES, CATALOGUE_VERSION } from './hypotheses.ts'
import { MIN_PAIRS, runCatalogue } from './relationship.ts'
import type { Frame, RelationshipOutcome } from './relationship.ts'
import { alignToAxis, dateAxis } from './stats.ts'
import type { Num } from './stats.ts'
import { CORE_VERSION } from './version.ts'

export const RELATIONSHIP_LOOKBACK_DAYS = 365
// A day counts as having "afternoon caffeine" from this local hour on.
export const CAFFEINE_PM_HOUR = 14
export const MIN_TRAINING_MIN = 20
// Session types that are not training for this purpose (a walk or an unclassified activity of 20+ minutes is almost daily).
export const NON_TRAINING_TYPES = ['walk', 'other']

export interface RawInputs {
  wearable: { date: string; hrv: number | null; rhr: number | null; steps: number | null }[]
  sleep: { date: string; hours: number | null; deep_min?: number | null; rem_min?: number | null }[]
  wellbeing: { date: string; energy: number | null; mood: number | null; stress: number | null; soreness: number | null }[]
  meals: { logged_at: string; meal_items: { alcohol_g: number | null; caffeine_mg: number | null }[] | null }[]
  sessions: { start_time: string; duration_min: number | null; type?: string | null }[]
  recovery: { date: string }[]
  illnesses: { start_date: string; end_date: string | null }[]
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
  }
  // Deep + REM minutes, only on nights where both stages were recorded (the typicality engine's fourth signal).
  series.deep_rem_min = alignToAxis(
    axis,
    raw.sleep.map((r) => {
      const deep = num(r.deep_min)
      const rem = num(r.rem_min)
      return { date: r.date, value: deep !== null && rem !== null && deep + rem > 0 ? deep + rem : null }
    }),
  )

  // Nutrition-derived exposures: zero only on days with a food log.
  const mealDays = new Set<string>()
  const alcoholByDay = new Map<string, number>()
  const caffeinePmByDay = new Map<string, number>()
  for (const m of raw.meals) {
    const d = day(m.logged_at)
    mealDays.add(d)
    for (const it of m.meal_items ?? []) {
      alcoholByDay.set(d, (alcoholByDay.get(d) ?? 0) + (num(it.alcohol_g) ?? 0))
      if (hourOf(m.logged_at) >= CAFFEINE_PM_HOUR) caffeinePmByDay.set(d, (caffeinePmByDay.get(d) ?? 0) + (num(it.caffeine_mg) ?? 0))
    }
  }
  series.alcohol_any = axis.map((d) => ((alcoholByDay.get(d) ?? 0) > 0 ? 1 : mealDays.has(d) ? 0 : null))
  series.caffeine_pm_any = axis.map((d) => ((caffeinePmByDay.get(d) ?? 0) > 0 ? 1 : mealDays.has(d) ? 0 : null))

  // Training: recorded by the watch, so a day with a watch record and no qualifying session is a real zero.
  const sessionDays = new Set(raw.sessions.filter((s) => (num(s.duration_min) ?? 0) >= MIN_TRAINING_MIN && !NON_TRAINING_TYPES.includes(String(s.type ?? ''))).map((s) => day(s.start_time)))
  const watchDays = new Set(raw.wearable.filter((w) => num(w.hrv) !== null || num(w.rhr) !== null || num(w.steps) !== null).map((w) => w.date))
  series.training_day = axis.map((d) => (sessionDays.has(d) ? 1 : watchDays.has(d) ? 0 : null))

  // Recovery sessions are logged by hand: zero only from the day the first one was logged.
  const recoveryDays = new Set(raw.recovery.map((r) => r.date))
  const firstRecovery = [...recoveryDays].sort()[0]
  series.recovery_any = axis.map((d) => (recoveryDays.has(d) ? 1 : firstRecovery !== undefined && d >= firstRecovery ? 0 : null))

  // Illness days (and the day after recovery) are left out of every comparison.
  const excludedDays = new Set<string>()
  for (const ill of raw.illnesses) {
    const end = ill.end_date ?? today
    for (const d of dateAxis(day(ill.start_date), shift(day(end), ill.end_date ? 1 : 0))) excludedDays.add(d)
  }
  return { dates: axis, series, excluded: axis.map((d) => excludedDays.has(d)) }
}

// deno-lint-ignore no-explicit-any
export type Admin = any

async function fetchAll<T>(page: (from: number, to: number) => PromiseLike<{ data: T[] | null; error: { message: string } | null }>): Promise<{ rows: T[]; error?: string }> {
  const rows: T[] = []
  for (let from = 0; ; from += 1000) {
    const { data, error } = await page(from, from + 999)
    if (error) return { rows, error: error.message }
    rows.push(...(data ?? []))
    if ((data ?? []).length < 1000) return { rows }
  }
}

export interface RelationshipRun {
  userId: string
  error?: string
  results?: { id: string; state: string; n: number; p: number | null; pAdjusted: number | null }[]
}

export interface LoadedFrame {
  userId: string
  today: string
  frame: Frame
}

/** Reads a user's raw rows once and builds the daily frame; engines that need it share the result. */
export async function loadFrame(admin: Admin, userId: string, now: Date = new Date()): Promise<LoadedFrame | { userId: string; error: string }> {
  const today = now.toISOString().slice(0, 10)
  const since = shift(today, -(RELATIONSHIP_LOOKBACK_DAYS - 1))
  const sinceStamp = `${since}T00:00:00Z`
  const q = (table: string, cols: string, col: string, from: string) => (a: number, b: number) =>
    admin.from(table).select(cols).eq('user_id', userId).gte(col, from).order(col).range(a, b)

  const [wearable, sleep, wellbeing, meals, sessions, recovery, ill] = await Promise.all([
    fetchAll<RawInputs['wearable'][number]>(q('wearable_daily', 'date, hrv, rhr, steps', 'date', since)),
    fetchAll<RawInputs['sleep'][number]>(q('sleep_daily', 'date, hours, deep_min, rem_min', 'date', since)),
    fetchAll<RawInputs['wellbeing'][number]>(q('wellbeing_daily', 'date, energy, mood, stress, soreness', 'date', since)),
    fetchAll<RawInputs['meals'][number]>(q('meals', 'logged_at, meal_items(alcohol_g, caffeine_mg)', 'logged_at', sinceStamp)),
    fetchAll<RawInputs['sessions'][number]>(q('exercise_sessions', 'start_time, duration_min, type', 'start_time', sinceStamp)),
    fetchAll<RawInputs['recovery'][number]>(q('recovery_sessions', 'date', 'date', since)),
    admin.from('illnesses').select('start_date, end_date').eq('user_id', userId),
  ])
  const failed = [wearable, sleep, wellbeing, meals, sessions, recovery].find((r) => r.error)
  if (failed) return { userId, error: failed.error as string }
  if (ill.error) return { userId, error: ill.error.message }

  const frame = buildFrame(
    {
      wearable: wearable.rows, sleep: sleep.rows, wellbeing: wellbeing.rows, meals: meals.rows, sessions: sessions.rows,
      recovery: recovery.rows, illnesses: (ill.data ?? []) as RawInputs['illnesses'],
    },
    today,
  )
  return { userId, today, frame }
}

export async function runRelationships(admin: Admin, userId: string, now: Date = new Date(), loaded?: LoadedFrame): Promise<RelationshipRun> {
  const got = loaded ?? (await loadFrame(admin, userId, now))
  if ('error' in got) return { userId, error: got.error }
  const { frame, today } = got
  const outcomes: RelationshipOutcome[] = runCatalogue(HYPOTHESES, frame)

  const results: NonNullable<RelationshipRun['results']> = []
  for (const o of outcomes) {
    const { error } = await admin.from('analysis_results').upsert(
      {
        user_id: userId,
        kind: 'relationship',
        key: o.id,
        as_of: today,
        engine_version: CORE_VERSION,
        inputs_hash: o.inputsHash,
        state: o.state,
        confidence_have: o.n,
        confidence_need: MIN_PAIRS,
        payload: { catalogueVersion: CATALOGUE_VERSION, familySize: outcomes.filter((x) => x.state !== 'insufficient').length, window: { start: frame.dates[0], end: frame.dates[frame.dates.length - 1] }, ...o },
        calculated_at: now.toISOString(),
      },
      { onConflict: 'user_id,kind,key,as_of,engine_version' },
    )
    if (error) return { userId, error: error.message, results }
    await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', 'relationship').eq('key', o.id).eq('engine_version', CORE_VERSION).lt('as_of', today)
    results.push({ id: o.id, state: o.state, n: o.n, p: o.p, pAdjusted: o.pAdjusted })
  }
  return { userId, results }
}
