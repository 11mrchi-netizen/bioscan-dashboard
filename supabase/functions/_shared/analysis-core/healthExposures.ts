// Health concerns (docs/health-concerns/01-plan.md §3.5, §3.6 C): the exposure catalogue, built from data the app
// already keeps (sessions, heart-rate histograms, sleep, the HRV band, evening check-ins, the phone's time zone), and
// the context shown beside a concern. Erasable TypeScript, no I/O.
//
// The catalogue is fixed and versioned before any data is analysed. Each exposure has one primary window (the days
// before and including the day in question) and sensitivity windows that may only weaken a finding. An exposure is
// asked only of the concern families where it is plausible, which keeps the questions per concern to about ten.
// A day's exposure is 1 (it happened), 0 (it did not, and that is known) or null (unknown: before the data starts, or
// a check-in that was not answered). Sessions are synced from the watch, so a day without one is a known 0 from the
// first synced session on.
import type { VocabRegion } from './healthVocab.ts'
import { shiftDate } from './healthSeries.ts'
import type { ConcernRow } from './healthSeries.ts'
import type { Num } from './stats.ts'

export const CATALOGUE_VERSION_HEALTH = 'hx-cat-1'

export type Family = 'lower_limb' | 'trunk' | 'upper_limb' | 'head_neck' | 'systemic'

export interface SessionRow {
  id: number
  start_time: string
  duration_min: number | null
  distance_km: number | null
  elevation_gain_m: number | null
  type: string
  avg_hr: number | null
  max_hr: number | null
  details: Record<string, unknown> | null
}

export interface LibraryRow {
  name: string
  primary_muscles: string[] | null
}

/** Everything the exposures are built from, aligned to one day axis (local dates). */
export interface ExposureContext {
  dates: string[]
  /** Sessions with their local date. */
  sessions: (SessionRow & { date: string })[]
  /** zepp_workout_detail.hr_histogram (seconds per bpm) and sport_type by session id. */
  histograms: Map<number, Record<string, number>>
  zeppSport: Map<number, string>
  library: LibraryRow[]
  /** Robust maximum heart rate as of a date (trainingLoad.ts), for the zone-4 floor at 80% of maximum. */
  robustMax: (date: string) => number | null
  sleepHours: Num[]
  /** 1 when the morning's HRV was below its 80% expected band, 0 inside or above it, null without a band. */
  hrvBelow: Num[]
  /** HRV distance from expected, %, for the chart lane. */
  hrvDeviation: Num[]
  /** Daily training load (trainingLoad.ts, the frame's series). */
  load: Num[]
  alcohol: Num[]
  stressHigh: Num[]
  /** The phone's time zone per day (daily_context), null where unknown. */
  timeZone: (string | null)[]
}

export interface ExposureSpec {
  id: string
  label: string
  /** How a sentence names it: "a trail run", "short sleep". */
  phrase: string
  families: Family[]
  /** Days back from the day in question, inclusive: [0, 2] is that day and the two before. */
  window: [number, number]
  sensitivity: [number, number][]
  /** A session kind: its primary analysis is the session-level comparison (analysis B), fixed in advance; the
   *  case-crossover is shown beside it as supporting evidence. Otherwise the case-crossover is primary. */
  sessionLevel: boolean
  /** Reverse direction is plausible (an illness starting lowers HRV before symptoms are noticed). */
  reverseRisk?: boolean
}

export const QUALITY_RUN_TYPES = new Set(['intervals', 'tempo', 'hills', 'race', 'threshold'])
export const HILLS_MIN_M_PER_KM = 25
export const QUALITY_Z4_SHARE = 0.2
export const Z4_FRACTION_OF_MAX = 0.8
export const LONG_RUN_MIN = 90
export const LONG_RUN_MEDIAN_FACTOR = 1.5
export const VOLUME_JUMP_RATIO = 1.3
export const SHORT_SLEEP_H = 6
export const NO_REST_DAYS = 6
const ZEPP_TRAIL_SPORT = '7'
const NON_TRAINING = new Set(['walk', 'other'])

export const EXPOSURES: ExposureSpec[] = [
  { id: 'any_run', label: 'Any run', phrase: 'a run', families: ['lower_limb', 'trunk'], window: [0, 2], sensitivity: [[0, 1], [0, 3]], sessionLevel: false },
  { id: 'long_run', label: 'Long run', phrase: 'a long run', families: ['lower_limb', 'trunk', 'systemic'], window: [0, 2], sensitivity: [[0, 1], [0, 3]], sessionLevel: true },
  { id: 'trail_run', label: 'Trail or hilly run', phrase: 'a trail or hilly run', families: ['lower_limb'], window: [0, 2], sensitivity: [[0, 1], [0, 3]], sessionLevel: true },
  { id: 'quality_run', label: 'Quality run', phrase: 'a quality run (intervals, tempo, hills or race)', families: ['lower_limb'], window: [0, 2], sensitivity: [[0, 1], [0, 3]], sessionLevel: true },
  { id: 'volume_jump', label: 'Run-volume jump', phrase: 'a week of running 30% above the previous four', families: ['lower_limb'], window: [0, 3], sensitivity: [[0, 6]], sessionLevel: false },
  { id: 'back_to_back', label: 'Back-to-back run days', phrase: 'runs on consecutive days', families: ['lower_limb'], window: [0, 1], sensitivity: [[0, 2]], sessionLevel: false },
  { id: 'regional_strength', label: 'Strength work for this area', phrase: 'strength work loading this area', families: ['lower_limb', 'trunk', 'upper_limb', 'head_neck'], window: [0, 2], sensitivity: [[0, 1], [0, 3]], sessionLevel: true },
  { id: 'no_rest', label: 'No rest day', phrase: 'six or more days in a row with training', families: ['lower_limb', 'trunk', 'upper_limb', 'head_neck', 'systemic'], window: [0, 1], sensitivity: [[0, 3]], sessionLevel: false },
  { id: 'short_sleep', label: 'Short sleep', phrase: 'a night under 6 hours', families: ['lower_limb', 'trunk', 'upper_limb', 'head_neck', 'systemic'], window: [0, 1], sensitivity: [[0, 2]], sessionLevel: false },
  { id: 'hrv_below', label: 'HRV below expected', phrase: 'a morning HRV below its expected band', families: ['lower_limb', 'trunk', 'upper_limb', 'head_neck', 'systemic'], window: [0, 2], sensitivity: [[0, 1]], sessionLevel: false, reverseRisk: true },
  { id: 'high_load', label: 'High weekly load', phrase: 'a 7-day training load above your usual 80th percentile', families: ['lower_limb', 'trunk', 'upper_limb', 'head_neck', 'systemic'], window: [0, 3], sensitivity: [[0, 6]], sessionLevel: false },
  { id: 'alcohol', label: 'Alcohol', phrase: 'an evening with alcohol', families: ['systemic'], window: [0, 2], sensitivity: [[0, 1]], sessionLevel: false },
  { id: 'stress_high', label: 'High stress', phrase: 'a high-stress evening', families: ['systemic'], window: [0, 2], sensitivity: [[0, 1]], sessionLevel: false },
  { id: 'travel', label: 'Time-zone change', phrase: 'a change of time zone', families: ['systemic'], window: [0, 3], sensitivity: [[0, 6]], sessionLevel: false },
]

/** Exposures the data cannot build yet, listed in every finding so their absence is visible. */
export const UNAVAILABLE_EXPOSURES = [
  'Downhill running (descent is not stored for past sessions)',
  'Footwear and surface',
  'Weather on the day',
]

export function familiesOf(concern: ConcernRow, regions: VocabRegion[]): Family[] {
  const out = new Set<Family>()
  if (concern.kind !== 'anatomical') out.add('systemic')
  if (concern.kind !== 'systemic') {
    for (const r of concern.regions) {
      const v = regions.find((x) => x.code === r.code)
      if (v) out.add(v.group)
    }
  }
  return [...out]
}

export function exposuresFor(concern: ConcernRow, regions: VocabRegion[]): ExposureSpec[] {
  const fam = familiesOf(concern, regions)
  return EXPOSURES.filter((e) => e.families.some((f) => fam.includes(f)))
}

// ---- exercise names to muscles (port of domain/training/MovementKey.kt, kept identical) ----

const DROP = new Set(['barbell', 'standing', 'and', 'the', 'a', 'bb', 'with'])
const WORD_MAP: Record<string, string> = {
  military: 'overhead', shoulder: 'overhead', inclined: 'incline',
  ups: 'up', squats: 'squat', presses: 'press', rows: 'row', dips: 'dip', lunges: 'lunge', deadlifts: 'deadlift',
}
const PHRASE_MAP: [string, string][] = [
  ['chest press', 'bench press'],
  ['bent over row', 'row'],
  ['bent over barbell row', 'row'],
  ['standing barbell press', 'overhead press'],
]

export function movementKey(name: string): string {
  let s = name.replace(/\([A-Z]{2,5}\)/g, ' ').toLowerCase()
  s = s.replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim()
  s = s.replaceAll('pullups', 'pull up').replaceAll('pullup', 'pull up').replaceAll('chinups', 'chin up').replaceAll('chinup', 'chin up')
  for (const [from, to] of PHRASE_MAP) s = s.replaceAll(from, to)
  const tokens = s.split(' ').filter((t) => t && !DROP.has(t)).map((t) => WORD_MAP[t] ?? t)
  return [...new Set(tokens)].sort().join(' ')
}

/** Primary muscles of a logged exercise name: exact library name first, then the movement key; null when unknown. */
export function musclesOf(name: string, library: LibraryRow[]): string[] | null {
  const exact = library.find((l) => l.name.toLowerCase() === name.toLowerCase())
  if (exact) return exact.primary_muscles ?? []
  const key = movementKey(name)
  if (!key) return null
  const byKey = library.find((l) => movementKey(l.name) === key)
  return byKey ? byKey.primary_muscles ?? [] : null
}

// ---- building the daily exposure series ----

const isRun = (s: SessionRow) => s.type === 'run'
const runMin = (s: SessionRow) => s.duration_min ?? 0
const km = (s: SessionRow) => s.distance_km ?? 0
const det = (s: SessionRow, k: string) => (s.details && typeof s.details[k] === 'string' ? (s.details[k] as string).toLowerCase() : null)

export function isTrail(s: SessionRow, zeppSport: Map<number, string>): boolean {
  const route = det(s, 'route_type')
  if (route === 'trail' || route === 'mixed' || zeppSport.get(s.id) === ZEPP_TRAIL_SPORT) return true
  return s.elevation_gain_m !== null && km(s) > 0 && s.elevation_gain_m / km(s) >= HILLS_MIN_M_PER_KM
}

export function isQuality(s: SessionRow & { date: string }, ctx: Pick<ExposureContext, 'histograms' | 'robustMax'>): boolean {
  const rt = det(s, 'run_type')
  if (rt) return QUALITY_RUN_TYPES.has(rt)
  const max = ctx.robustMax(s.date)
  if (max === null) return false
  const floor = max * Z4_FRACTION_OF_MAX
  const h = ctx.histograms.get(s.id)
  if (h) {
    let total = 0
    let high = 0
    for (const [bpm, sec] of Object.entries(h)) {
      total += sec
      if (Number(bpm) >= floor) high += sec
    }
    if (total > 0) return high / total >= QUALITY_Z4_SHARE
  }
  return s.avg_hr !== null && s.avg_hr >= floor
}

function quantile(xs: number[], p: number): number | null {
  if (xs.length === 0) return null
  const s = [...xs].sort((a, b) => a - b)
  const i = (s.length - 1) * p
  const lo = Math.floor(i)
  return s[lo] + (s[Math.min(lo + 1, s.length - 1)] - s[lo]) * (i - lo)
}

/** A long run: 90 minutes or more, or 1.5 times the person's median run for someone who never runs that long. (A
 *  percentile of their own durations was tried first: with runs of two usual lengths it fell on an ordinary run.) */
export function longRunMinutes(durations: number[]): number {
  const med = quantile(durations, 0.5)
  return med === null ? LONG_RUN_MIN : Math.min(LONG_RUN_MIN, Math.round(med * LONG_RUN_MEDIAN_FACTOR))
}

/** Daily 1 / 0 / null for every catalogue exposure; `regional_strength` for the concern's regions. */
export function buildExposureSeries(ctx: ExposureContext, concern: ConcernRow, regions: VocabRegion[]): Record<string, Num[]> {
  const n = ctx.dates.length
  const idx = new Map(ctx.dates.map((d, i) => [d, i]))
  const firstSession = ctx.sessions.map((s) => s.date).sort()[0] ?? null
  const known = (i: number) => firstSession !== null && ctx.dates[i] >= firstSession
  const daily = (pred: (s: SessionRow & { date: string }) => boolean): Num[] => {
    const out: Num[] = ctx.dates.map((_, i) => (known(i) ? 0 : null))
    for (const s of ctx.sessions) {
      const i = idx.get(s.date)
      if (i !== undefined && pred(s)) out[i] = 1
    }
    return out
  }
  const runs = ctx.sessions.filter((s) => isRun(s) && runMin(s) >= 10)
  const longMin = longRunMinutes(runs.map(runMin))
  const series: Record<string, Num[]> = {
    any_run: daily((s) => isRun(s) && runMin(s) >= 10),
    long_run: daily((s) => isRun(s) && runMin(s) >= longMin),
    trail_run: daily((s) => isRun(s) && runMin(s) >= 10 && isTrail(s, ctx.zeppSport)),
    quality_run: daily((s) => isRun(s) && runMin(s) >= 10 && isQuality(s, ctx)),
  }

  // Run volume: the last 7 days against the weekly mean of the 28 before.
  const runKm = ctx.dates.map(() => 0)
  for (const s of runs) {
    const i = idx.get(s.date)
    if (i !== undefined) runKm[i] += km(s)
  }
  series.volume_jump = ctx.dates.map((_, i) => {
    if (!known(i) || i < 34) return null
    const last7 = runKm.slice(i - 6, i + 1).reduce((a, b) => a + b, 0)
    const prev28 = runKm.slice(i - 34, i - 6).reduce((a, b) => a + b, 0)
    if (prev28 <= 0) return last7 > 0 ? null : 0
    return last7 > (VOLUME_JUMP_RATIO * prev28) / 4 ? 1 : 0
  })
  series.back_to_back = series.any_run.map((v, i) => (v === null || i === 0 || series.any_run[i - 1] === null ? null : v === 1 && series.any_run[i - 1] === 1 ? 1 : 0))

  const regionMuscles = new Set(concern.regions.flatMap((r) => regions.find((v) => v.code === r.code)?.muscles ?? []))
  series.regional_strength = regionMuscles.size === 0
    ? ctx.dates.map(() => null)
    : daily((s) => {
      if (s.type !== 'strength') return false
      const ex = Array.isArray(s.details?.exercises) ? (s.details!.exercises as { name?: string }[]) : []
      return ex.some((e) => typeof e.name === 'string' && (musclesOf(e.name, ctx.library) ?? []).some((m) => regionMuscles.has(m)))
    })

  const trainingDay = daily((s) => !NON_TRAINING.has(s.type) && (s.duration_min ?? 0) >= 20)
  let streak = 0
  series.no_rest = trainingDay.map((v) => {
    if (v === null) return null
    streak = v === 1 ? streak + 1 : 0
    return streak >= NO_REST_DAYS ? 1 : 0
  })
  series.short_sleep = ctx.sleepHours.map((h) => (h === null ? null : h < SHORT_SLEEP_H ? 1 : 0))
  series.hrv_below = [...ctx.hrvBelow]

  const load7 = ctx.load.map((_, i) => {
    if (i < 6) return null
    const w = ctx.load.slice(i - 6, i + 1)
    return w.some((x) => x === null) ? null : (w as number[]).reduce((a, b) => a + b, 0)
  })
  const loadP80 = quantile(load7.filter((x): x is number => x !== null), 0.8)
  series.high_load = load7.map((x) => (x === null || loadP80 === null ? null : x > loadP80 ? 1 : 0))
  series.alcohol = [...ctx.alcohol]
  series.stress_high = [...ctx.stressHigh]
  series.travel = ctx.timeZone.map((tz, i) => {
    if (tz === null || i === 0) return null
    let j = i - 1
    while (j >= 0 && ctx.timeZone[j] === null) j--
    return j < 0 ? null : ctx.timeZone[j] !== tz ? 1 : 0
  })
  for (const k of Object.keys(series)) if (series[k].length !== n) throw new Error(`exposure ${k} misaligned`)
  return series
}

/** Exposure in the window before day i: 1 if it happened on any day of it, 0 if every day is a known 0, else null. */
export function inWindow(series: Num[], i: number, w: [number, number]): Num {
  let anyNull = false
  for (let k = w[0]; k <= w[1]; k++) {
    const v = i - k >= 0 ? series[i - k] : null
    if (v === 1) return 1
    if (v === null) anyNull = true
  }
  return anyNull ? null : 0
}

// ---- context lanes and "what came before" ----

export interface ContextLanes {
  dates: string[]
  runKm: number[]
  trailKm: number[]
  strengthSessions: number[]
  load7: Num[]
  sleepHours: Num[]
  hrvDeviation: Num[]
}

export function contextLanes(ctx: ExposureContext, from: string): ContextLanes {
  const start = Math.max(0, ctx.dates.findIndex((d) => d >= from))
  const dates = ctx.dates.slice(start)
  const idx = new Map(dates.map((d, i) => [d, i]))
  const runKm = dates.map(() => 0)
  const trailKm = dates.map(() => 0)
  const strength = dates.map(() => 0)
  for (const s of ctx.sessions) {
    const i = idx.get(s.date)
    if (i === undefined) continue
    if (isRun(s)) {
      runKm[i] += km(s)
      if (isTrail(s, ctx.zeppSport)) trailKm[i] += km(s)
    } else if (s.type === 'strength') strength[i]++
  }
  const load7 = ctx.dates.map((_, i) => (i < 6 ? null : ctx.load.slice(i - 6, i + 1).reduce<number>((a, b) => a + (b ?? 0), 0)))
  const r1 = (x: number) => Math.round(x * 10) / 10
  return {
    dates,
    runKm: runKm.map(r1),
    trailKm: trailKm.map(r1),
    strengthSessions: strength,
    load7: load7.slice(start).map((x) => (x === null ? null : Math.round(x))),
    sleepHours: ctx.sleepHours.slice(start).map((x) => (x === null ? null : r1(x))),
    hrvDeviation: ctx.hrvDeviation.slice(start).map((x) => (x === null ? null : r1(x))),
  }
}

export interface BeforeEpisode {
  episodeStart: string
  sessions: { id: number; date: string; type: string; km: number | null; minutes: number | null; flags: string[] }[]
  /** The 14 days before the start against the 14-day windows of the 90 days before that. */
  comparisons: { id: string; label: string; value: number; typical: number | null; rank: 'highest' | 'above' | 'typical' | 'below' | null; unit: string }[]
  sentences: string[]
}

/** Descriptive only (analysis C): what the two weeks before an episode looked like next to the person's usual. */
export function whatCameBefore(ctx: ExposureContext, episodeStart: string): BeforeEpisode {
  const i0 = ctx.dates.indexOf(episodeStart)
  const sessions = ctx.sessions
    .filter((s) => s.date >= shiftDate(episodeStart, -7) && s.date <= episodeStart)
    .sort((a, b) => (a.start_time < b.start_time ? -1 : 1))
    .map((s) => ({
      id: s.id, date: s.date, type: s.type, km: s.distance_km, minutes: s.duration_min,
      flags: isRun(s) ? [isTrail(s, ctx.zeppSport) ? 'trail' : null, runMin(s) >= LONG_RUN_MIN ? 'long' : null, isQuality(s, ctx) ? 'quality' : null].filter((x): x is string => x !== null) : [],
    }))
  const comparisons: BeforeEpisode['comparisons'] = []
  const sentences: string[] = []
  if (i0 >= 14) {
    const sumOver = (vals: Num[], a: number, b: number) => vals.slice(a, b).reduce<number>((s, v) => s + (v ?? 0), 0)
    const meanOver = (vals: Num[], a: number, b: number) => {
      const xs = vals.slice(a, b).filter((v): v is number => v !== null)
      return xs.length ? xs.reduce((s, v) => s + v, 0) / xs.length : null
    }
    const runKm: Num[] = ctx.dates.map(() => 0)
    const idx = new Map(ctx.dates.map((d, i) => [d, i]))
    for (const s of ctx.sessions) if (isRun(s)) { const i = idx.get(s.date); if (i !== undefined) runKm[i] = (runKm[i] as number) + km(s) }
    const metrics: { id: string; label: string; unit: string; vals: Num[]; agg: 'sum' | 'mean'; higherIsNotable: boolean }[] = [
      { id: 'run_km', label: 'Running', unit: 'km', vals: runKm, agg: 'sum', higherIsNotable: true },
      { id: 'load', label: 'Training load', unit: '', vals: ctx.load, agg: 'sum', higherIsNotable: true },
      { id: 'sleep', label: 'Sleep', unit: 'h a night', vals: ctx.sleepHours, agg: 'mean', higherIsNotable: false },
    ]
    for (const m of metrics) {
      const f = m.agg === 'sum' ? sumOver : meanOver
      const value = f(m.vals, i0 - 14, i0)
      if (value === null) continue
      const ref: number[] = []
      for (let e = i0 - 14; e - 14 >= Math.max(0, i0 - 104); e -= 7) {
        const v = f(m.vals, e - 14, e)
        if (v !== null) ref.push(v)
      }
      const typical = ref.length >= 4 ? quantile(ref, 0.5) : null
      let rank: BeforeEpisode['comparisons'][number]['rank'] = null
      if (typical !== null && ref.length >= 4) {
        const hi = quantile(ref, 0.8) as number
        const lo = quantile(ref, 0.2) as number
        rank = value > Math.max(...ref) ? 'highest' : value > hi ? 'above' : value < lo ? 'below' : 'typical'
      }
      const r1 = Math.round(value * 10) / 10
      comparisons.push({ id: m.id, label: m.label, value: r1, typical: typical === null ? null : Math.round(typical * 10) / 10, rank, unit: m.unit })
      if (rank === 'highest' && m.higherIsNotable) sentences.push(`${m.label} in the two weeks before was the highest of the previous three months (${r1}${m.unit ? ' ' + m.unit : ''}).`)
      else if (rank === 'above' && m.higherIsNotable) sentences.push(`${m.label} in the two weeks before was above your usual (${r1} against a typical ${Math.round((typical as number) * 10) / 10}${m.unit ? ' ' + m.unit : ''}).`)
      else if (rank === 'below' && !m.higherIsNotable) sentences.push(`${m.label} in the two weeks before was below your usual (${r1} against a typical ${Math.round((typical as number) * 10) / 10} ${m.unit}).`)
    }
    if (sentences.length === 0 && comparisons.some((c) => c.rank !== null)) sentences.push('The two weeks before looked like your usual weeks for running, load and sleep.')
  }
  return { episodeStart, sessions, comparisons, sentences }
}
