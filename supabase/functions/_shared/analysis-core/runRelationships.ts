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

export const RELATIONSHIP_LOOKBACK_DAYS = 365
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
}

export interface RawInputs {
  wearable: { date: string; hrv: number | null; rhr: number | null; steps: number | null }[]
  sleep: { date: string; hours: number | null; deep_min?: number | null; rem_min?: number | null }[]
  wellbeing: { date: string; energy: number | null; mood: number | null; stress: number | null; soreness: number | null }[]
  meals: { logged_at: string; meal_items: { alcohol_g: number | null; caffeine_mg: number | null }[] | null }[]
  sessions: { start_time: string; duration_min: number | null; type?: string | null; rpe?: number | null; avg_hr?: number | null; max_hr?: number | null }[]
  recovery: { date: string }[]
  illnesses: { start_date: string; end_date: string | null }[]
  /** Evening check-ins (analysis review phase B); optional so older callers and tests keep working. */
  checkins?: CheckinRow[]
  /** The phone's UTC offset per local date (daily_context); timestamps become local dates and hours with it. */
  context?: { date: string; utc_offset_min: number }[]
  /** Routine intelligence R4: phone use and mobility published by the phone (routineExposures.ts). */
  routine?: RoutineInputs
}

// Until 04:00 the small hours belong to the evening before: a drink or a late meal logged at 00:40 is part of that night.
export const EVENING_ROLLOVER_HOUR = 4
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
    if (!Number.isFinite(t)) return { date: day(stamp), hour: hourOf(stamp) }
    const local = new Date(t + offsetFor(new Date(t).toISOString().slice(0, 10)) * 60000).toISOString()
    return { date: local.slice(0, 10), hour: Number(local.slice(11, 13)) }
  }
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
  const clock = localClock(raw.context ?? [])
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
  series.caffeine_pm_any = axis.map((d) => exposure(d, (caffeinePmByDay.get(d) ?? 0) > 0, checkins.get(d)?.caffeine_after_14))
  series.late_meal = axis.map((d) => exposure(d, lateMealDays.has(d), checkins.get(d)?.late_meal))
  // Stress is a rating, so only an answered check-in says anything about it.
  series.stress_high = axis.map((d) => {
    const s = num(checkins.get(d)?.stress)
    return s === null ? null : s >= HIGH_STRESS ? 1 : 0
  })

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

  // Routine intelligence R4: phone use, places and commutes, each known only on days its source covers.
  Object.assign(series, routineSeries(axis, raw.routine ?? {}, clock))

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

  const [wearable, sleep, wellbeing, meals, sessions, recovery, ill, checkins, context, health, phone, phoneApps, mobility, commutes] = await Promise.all([
    fetchAll<RawInputs['wearable'][number]>(q('wearable_daily', 'date, hrv, rhr, steps', 'date', since)),
    fetchAll<RawInputs['sleep'][number]>(q('sleep_daily', 'date, hours, deep_min, rem_min', 'date', since)),
    fetchAll<RawInputs['wellbeing'][number]>(q('wellbeing_daily', 'date, energy, mood, stress, soreness', 'date', since)),
    fetchAll<RawInputs['meals'][number]>(q('meals', 'logged_at, meal_items(alcohol_g, caffeine_mg)', 'logged_at', sinceStamp)),
    fetchAll<RawInputs['sessions'][number]>(q('exercise_sessions', 'start_time, duration_min, type, rpe, avg_hr, max_hr', 'start_time', sinceStamp)),
    fetchAll<RawInputs['recovery'][number]>(q('recovery_sessions', 'date', 'date', since)),
    admin.from('illnesses').select('start_date, end_date').eq('user_id', userId),
    // Phase B tables: read softly, so the nightly run keeps working before the migration is applied.
    fetchAll<CheckinRow>(q('evening_checkins', 'date, drinks, caffeine_after_14, late_meal, stress', 'date', since)),
    fetchAll<{ date: string; utc_offset_min: number }>(q('daily_context', 'date, utc_offset_min', 'date', shift(since, -30))),
    // Health concerns (docs/health-concerns): systemic episodes leave their days out as logged illness does. Read softly;
    // for one release both are read (owner decision 7.2.7), and the copied legacy rows give the same spans.
    loadHealthInputs(admin, userId),
    // Routine intelligence (R1-R3 tables): read softly, like the phase B tables. Apps are filtered to the late-screen
    // categories here rather than in the query, so the reader needs only eq / gte / order / range.
    fetchAll<NonNullable<RoutineInputs['phone']>[number]>(q('phone_usage_daily', 'date, bed_window_min, night_screen_min, night_unlocks', 'date', since)),
    fetchAll<NonNullable<RoutineInputs['phoneApps']>[number]>(q('phone_app_usage_daily', 'date, category, after_21_min', 'date', since)),
    fetchAll<NonNullable<RoutineInputs['mobility']>[number]>(q('mobility_daily', 'date, coverage_pct, day_type, outdoor_daylight_min', 'date', since)),
    fetchAll<NonNullable<RoutineInputs['commutes']>[number]>((a: number, b: number) =>
      admin.from('trips').select('start_at, end_at, active_min').eq('user_id', userId).eq('is_commute', true).gte('start_at', sinceStamp).order('start_at').range(a, b)),
  ])
  const failed = [wearable, sleep, wellbeing, meals, sessions, recovery].find((r) => r.error)
  if (failed) return { userId, error: failed.error as string }
  if (ill.error) return { userId, error: ill.error.message }

  const frame = buildFrame(
    {
      wearable: wearable.rows, sleep: sleep.rows, wellbeing: wellbeing.rows, meals: meals.rows, sessions: sessions.rows,
      recovery: recovery.rows,
      illnesses: [...((ill.data ?? []) as RawInputs['illnesses']), ...(health.error ? [] : baselineExclusionSpans(health, today))],
      checkins: checkins.error ? [] : checkins.rows,
      context: context.error ? [] : context.rows,
      routine: {
        phone: phone.error ? [] : phone.rows,
        phoneApps: phoneApps.error ? [] : phoneApps.rows.filter((r) => LATE_SCREEN_CATEGORIES.includes(String(r.category))),
        mobility: mobility.error ? [] : mobility.rows,
        commutes: commutes.error ? [] : commutes.rows,
      },
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
