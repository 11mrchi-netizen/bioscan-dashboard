// Port of the Android domain/fuel PlannedDay.kt, SessionDemand.kt, SessionMatching.kt, CalendarWorkout.kt and the run-time
// estimate of CourseTimeEstimate.kt: the training of a day as fuel planning sees it. Four sources, merged: events (races, long
// days), planned sessions of a training block, calendar training entries (colour 8) that neither covers, and recorded sessions,
// which replace the plan they were (a stored answer first, else the cheapest scored pairing of the day) and are all a past day
// keeps. Each item gets an intensity class and an energy estimate. Deterministic; parity by fixture (PlannedDayParityTest).
//
// Callers pass what the database holds: planned sessions must already exclude status 'superseded' (a revised session has two rows).
// Time zones: a local time becomes an instant with the zone's offset at that time (Intl), like java.time outside a DST gap or overlap.

import { type CalendarReview, type EventKind, type EventPriority, fmt, type Intensity, type PlannedDay, type PlannedItem, roundToInt } from './day.ts'

// ---- instants and zones ----

export const TRAINING_COLOR_ID = '8'

const formatters = new Map<string, Intl.DateTimeFormat>()
function parts(ms: number, zone: string) {
  let f = formatters.get(zone)
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', { timeZone: zone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
    formatters.set(zone, f)
  }
  const o: Record<string, string> = {}
  for (const p of f.formatToParts(new Date(Math.floor(ms / 1000) * 1000))) o[p.type] = p.value
  return { y: Number(o.year), mo: Number(o.month), d: Number(o.day), h: Number(o.hour) % 24, mi: Number(o.minute), s: Number(o.second) }
}
/** The zone's offset from UTC at an instant, minutes. */
export function offsetMin(ms: number, zone: string): number {
  const p = parts(ms, zone)
  return Math.round((Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s) - Math.floor(ms / 1000) * 1000) / 60000)
}
const pad = (n: number) => String(n).padStart(2, '0')
/** The local calendar date of an instant in a zone. */
export function localDate(ms: number, zone: string): string {
  const p = parts(ms, zone)
  return `${p.y}-${pad(p.mo)}-${pad(p.d)}`
}
/** Kotlin ZonedDateTime.toString(). */
export function zdtString(ms: number, zone: string): string {
  const p = parts(ms, zone)
  const off = offsetMin(ms, zone)
  const sign = off < 0 ? '-' : '+'
  const o = off === 0 ? 'Z' : `${sign}${pad(Math.floor(Math.abs(off) / 60))}:${pad(Math.abs(off) % 60)}`
  return `${p.y}-${pad(p.mo)}-${pad(p.d)}T${pad(p.h)}:${pad(p.mi)}${p.s ? `:${pad(p.s)}` : ''}${o}[${zone}]`
}
/** An instant from an ISO string with an offset (a trailing [zone] is ignored). */
export const instant = (iso: string): number => Date.parse(iso.replace(/\[.*\]$/, ''))
/** date.atTime(time).atZone(zone). */
export function atTime(date: string, time: string, zone: string): number {
  const [h, m, s = '0'] = time.split(':')
  const asUtc = Date.UTC(Number(date.slice(0, 4)), Number(date.slice(5, 7)) - 1, Number(date.slice(8, 10)), Number(h), Number(m), Number(s))
  let ms = asUtc - offsetMin(asUtc, zone) * 60000
  ms = asUtc - offsetMin(ms, zone) * 60000
  return ms
}
const dateMs = (d: string) => Date.UTC(Number(d.slice(0, 4)), Number(d.slice(5, 7)) - 1, Number(d.slice(8, 10)))
const plusDays = (d: string, n: number) => new Date(dateMs(d) + n * 86400000).toISOString().slice(0, 10)
const minutesBetween = (a: number, b: number) => Math.trunc((b - a) / 60000)

// ---- how demanding a session is (SessionDemand.kt) ----

export interface Classified { intensity: Intensity; reason: string }
export const LONG_SESSION_MIN = 90

const DOMAIN_INTENSITY: Record<string, Intensity> = {
  aerobic_base: 'EASY', recovery: 'EASY', sport_skill: 'EASY',
  anaerobic_capacity: 'HARD', speed: 'HARD', power: 'HARD', specific_endurance: 'HARD', work_capacity: 'HARD',
  max_strength: 'MODERATE', hypertrophy: 'MODERATE', strength_endurance: 'MODERATE',
}
const HARD_WORDS = /interval|ripetut|tempo|threshold|soglia|vo2|fartlek|repeats|progressiv|hiit|crossfit|wod|sprint/i
const EASY_WORDS = /easy|\blss\b|deload|recovery|rigenerant|\bz2\b|zone ?2|lento|shakeout|mobility|yoga|stretch/i
const LONG_WORDS = /\blong\b|lungo|lunghissim|long run|long ride/i
const KIND_LABEL: Record<EventKind, string> = { RACE: 'RACE', LONG_SESSION: 'LONG SESSION', MATCH: 'MATCH', HIKE_DAY: 'HIKE DAY', OTHER_HARD_DAY: 'OTHER HARD DAY' }
const lc = (s: string) => s.toLowerCase()
/** Kotlin String.trim(): whitespace at both ends. */
const trim = (s: string) => s.replace(/^[\s\u0000- ]+|[\s\u0000- ]+$/g, '')

export function intensityForEvent(kind: EventKind, priority: EventPriority, durationMin: number): Classified {
  if (kind === 'RACE' && priority !== 'C') return { intensity: 'RACE', reason: `${priority} race` }
  if (durationMin > LONG_SESSION_MIN) return { intensity: 'LONG', reason: `${lc(KIND_LABEL[kind])} over ${Math.trunc(LONG_SESSION_MIN)} min` }
  return { intensity: 'HARD', reason: lc(KIND_LABEL[kind]) }
}

export function intensityForDomain(domain: string | null | undefined): Classified | null {
  const i = domain ? DOMAIN_INTENSITY[domain] : undefined
  return i && domain ? { intensity: i, reason: `planned as ${domain.replace(/_/g, ' ')}` } : null
}

export function intensityForTitle(title: string | null | undefined): Classified | null {
  if (title == null) return null
  if (HARD_WORDS.test(title)) return { intensity: 'HARD', reason: `"${trim(title)}" reads as a hard session` }
  if (LONG_WORDS.test(title)) return { intensity: 'LONG', reason: `"${trim(title)}" reads as a long session` }
  if (EASY_WORDS.test(title)) return { intensity: 'EASY', reason: `"${trim(title)}" reads as an easy session` }
  if (title.includes('🏋')) return { intensity: 'MODERATE', reason: 'strength session' }
  return null
}

export function intensityForRecorded(rpe: number | null, avgHr: number | null, restingHr: number | null, maxHr: number | null): Classified | null {
  if (rpe != null && rpe > 0) {
    if (rpe >= 7) return { intensity: 'HARD', reason: `RPE ${rpe}` }
    if (rpe >= 5) return { intensity: 'MODERATE', reason: `RPE ${rpe}` }
    return { intensity: 'EASY', reason: `RPE ${rpe}` }
  }
  if (avgHr == null || restingHr == null || maxHr == null || maxHr <= restingHr) return null
  const reserve = Math.min(Math.max((avgHr - restingHr) / (maxHr - restingHr), 0), 1)
  const pct = Math.trunc(reserve * 100)
  if (reserve >= 0.75) return { intensity: 'HARD', reason: `average heart rate at ${pct} % of reserve` }
  if (reserve >= 0.6) return { intensity: 'MODERATE', reason: `average heart rate at ${pct} % of reserve` }
  return { intensity: 'EASY', reason: `average heart rate at ${pct} % of reserve` }
}

/** Banister TRIMP per minute at a heart-rate-reserve fraction. */
export const trimpPerMinute = (reserve: number) => reserve * 0.64 * Math.exp(1.92 * reserve)

export function intensityForTypical(type: string, trimpPerMinByType: Record<string, number[]>): Classified | null {
  const v = trimpPerMinByType[type]
  if (!v || v.length < 3) return null
  const values = [...v].sort((a, b) => a - b)
  const median = values[Math.trunc(values.length / 2)]
  const intensity: Intensity = median >= trimpPerMinute(0.75) ? 'HARD' : median >= trimpPerMinute(0.6) ? 'MODERATE' : 'EASY'
  return { intensity, reason: `your usual ${type.replace(/_/g, ' ')} (${values.length} sessions)` }
}

export function finalIntensity(type: string, durationMin: number, ...candidates: (Classified | null | undefined)[]): Classified {
  const picked = candidates.find((c) => c != null) ?? { intensity: 'MODERATE' as Intensity, reason: 'no intensity known' }
  if (durationMin > LONG_SESSION_MIN && type !== 'strength' && picked.intensity !== 'RACE' && picked.intensity !== 'LONG') {
    return { intensity: 'LONG', reason: `${Math.trunc(durationMin)} min (${picked.reason})` }
  }
  return picked
}

const MET_BY_TYPE: Record<string, number> = { run: 9.8, ride: 7.5, hike: 6, walk: 3.5, strength: 5, swim: 8, yoga: 2.5, other: 6 }
const INTENSITY_ENERGY: Record<Intensity, number> = { EASY: 0.85, MODERATE: 1, HARD: 1.15, LONG: 0.95, RACE: 1.15 }

export function estimateSessionKcal(type: string, durationMin: number, intensity: Intensity, weightKg: number | null, kcalPerMinByType: Record<string, number[]>): number | null {
  const v = kcalPerMinByType[type]
  let own: number | null = null
  if (v && v.length >= 3) { const s = [...v].sort((a, b) => a - b); own = s[Math.trunc(s.length / 2)] }
  let perMin = own
  if (perMin == null) {
    if (weightKg == null) return null
    perMin = (((MET_BY_TYPE[type] ?? 6) - 1) * 3.5 * weightKg) / 200
  }
  return perMin * durationMin * (INTENSITY_ENERGY[intensity] ?? 1)
}

// ---- how long a run takes (CourseTimeEstimate.kt) ----

export const RIEGEL_EXPONENT = 1.06
export interface RecentRun { distanceKm: number; ascentM: number; durationMin: number }
export interface TimeEstimate { minutes: number; lowMinutes: number; highMinutes: number }
export const kmEffort = (distanceKm: number, ascentM: number) => distanceKm + ascentM / 100

export function estimateRunTime(distanceKm: number, ascentM: number, runs: RecentRun[]): TimeEstimate | null {
  const usable = runs.filter((r) => r.durationMin >= 30 && r.distanceKm >= 3)
  if (usable.length < 5) return null
  const paces = usable.map((r) => r.durationMin / kmEffort(r.distanceKm, r.ascentM)).sort((a, b) => a - b)
  const q = (p: number) => paces[Math.trunc((paces.length - 1) * p)]
  const typicalEffort = usable.map((r) => kmEffort(r.distanceKm, r.ascentM)).sort((a, b) => a - b)[Math.trunc(usable.length / 2)]
  const effort = kmEffort(distanceKm, ascentM)
  const riegel = Math.pow(Math.max(effort / typicalEffort, 1), RIEGEL_EXPONENT - 1)
  return { minutes: q(0.5) * effort * riegel, lowMinutes: q(0.25) * effort * riegel, highMinutes: q(0.75) * effort * riegel }
}

// ---- what a calendar entry says (CalendarWorkout.kt) ----

export interface ParsedWorkout {
  distanceKm: number | null; ascentM: number | null; rounds: number | null; repDistanceM: number | null; sets: number | null; minReps: number | null
  loadPct: number | null; loadKg: boolean; deload: boolean; easy: boolean; long: boolean; hard: boolean
}

const NUMBER = '(\\d+(?:[.,]\\d+)?)'
const DISTANCE = new RegExp(`${NUMBER}\\s?km\\b`, 'i')
const ASCENT = /(?:\+|d\+\s?)\s?(\d{2,5})\s?m\b/i
const REPS_BY_DISTANCE = /(\d{1,2})\s?[x×]\s?(\d{3,4})\s?m?\b/i
const REP_DISTANCE = /(\d{3,4})\s?m\b/i
const ROUNDS = /(\d{1,2})(?:\s?[-–]\s?(\d{1,2}))?\s?(?:rounds|round|reps|repeats|ripetute|giri)\b/i
const SETS_REPS = /(\d{1,2})(?:\s?[-–]\s?(\d{1,2}))?\s?[x×]\s?(\d{1,2})(?!\d|\s?m\b)/gi
const PCT = /(\d{2,3})\s?%/g
const KG = /@\s?\d+(?:[.,]\d+)?\s?kg/i
const DELOAD = /deload|light activity|scarico|rest day|riposo/i
const EASY_TEXT = /\blss\b|easy|recovery|rigenerant|\bz2\b|zone ?2|lento|shakeout|easy pace/i
const LONG_TEXT = /\blong\b|lungo|back-to-back|b2b/i
const HARD_TEXT = /interval|ripetut|tempo|threshold|soglia|vo2|fartlek|repeats|hiit|sprint/i
const num = (s: string) => Number(s.replace(',', '.'))
const within = (v: number, lo: number, hi: number) => v >= lo && v <= hi

export function parseCalendarWorkout(title: string, description: string | null, type: string): ParsedWorkout {
  const desc = description ?? ''
  const text = `${title}\n${desc}`
  const dm = DISTANCE.exec(title) ?? DISTANCE.exec(desc)
  const dnum = dm ? num(dm[1]) : null
  const distance = dnum != null && within(dnum, 0.5, 300) ? dnum : null
  const am = ASCENT.exec(text)
  const anum = am ? Number(am[1]) : null
  const ascent = anum != null && within(anum, 10, 10000) ? anum : null

  let rounds: number | null = null
  let rep: number | null = null
  if (type !== 'strength') {
    const m = REPS_BY_DISTANCE.exec(text)
    if (m) { rounds = Number(m[1]); rep = Number(m[2]) }
    if (rounds == null) {
      const r = ROUNDS.exec(text)
      if (r) {
        const lo = Number(r[1])
        const hi = r[2] ? Number(r[2]) : lo
        rounds = (lo + hi) / 2
      }
      if (rounds != null) { const rd = REP_DISTANCE.exec(text); rep = rd ? Number(rd[1]) : null }
    }
  }

  let sets: number | null = null
  let minReps: number | null = null
  if (type === 'strength') {
    const found = [...(desc.trim() === '' ? title : desc).matchAll(SETS_REPS)]
    if (found.length) {
      sets = found.reduce((a, m) => { const lo = Number(m[1]); const hi = m[2] ? Number(m[2]) : lo; return a + (lo + hi) / 2 }, 0)
      minReps = Math.min(...found.map((m) => Number(m[3])))
    }
  }
  const pcts = [...text.matchAll(PCT)].map((m) => Number(m[1])).filter((v) => within(v, 40, 105))
  const pct = pcts.length ? Math.max(...pcts) : null

  return {
    distanceKm: distance, ascentM: ascent, rounds, repDistanceM: rep, sets, minReps,
    loadPct: type === 'strength' ? pct : null,
    loadKg: type === 'strength' && KG.test(text),
    deload: DELOAD.test(title), easy: EASY_TEXT.test(text), long: LONG_TEXT.test(title),
    hard: HARD_TEXT.test(title) || (type === 'strength' && (pct ?? 0) >= 85),
  }
}

export interface WorkoutEstimate { durationMin: number; classified: Classified }
const FALLBACK_MIN_PER_KM_EFFORT = 6.5
const WARM_UP_MIN = 15
const COOL_DOWN_MIN = 10
const INTERVAL_RECOVERY_MIN = 4
const STRENGTH_SETUP_MIN = 10
const STRENGTH_MIN_PER_SET = 3
const DELOAD_MIN = 30

function hoursMinutes(min: number): string {
  const m = roundToInt(min)
  return m >= 60 ? `${Math.trunc(m / 60)} h ${pad(m % 60)}` : `${m} min`
}

export function estimateCalendarWorkout(p: ParsedWorkout, type: string, runs: RecentRun[]): WorkoutEstimate | null {
  if (p.deload) return { durationMin: DELOAD_MIN, classified: { intensity: 'EASY', reason: `deload: light activity, about ${Math.trunc(DELOAD_MIN)} min` } }
  if (type === 'strength') {
    if (p.sets == null) return null
    const minutes = STRENGTH_SETUP_MIN + STRENGTH_MIN_PER_SET * p.sets
    const heavy = (p.loadPct ?? 0) >= 85 || (p.minReps != null && p.minReps <= 5 && (p.loadKg || p.loadPct != null))
    const load = p.loadPct != null ? ` at ${roundToInt(p.loadPct)} %` : ''
    return { durationMin: minutes, classified: { intensity: heavy ? 'HARD' : 'MODERATE', reason: `${roundToInt(p.sets)} working sets${load} from the calendar, about ${hoursMinutes(minutes)}` } }
  }
  const effort = p.distanceKm != null ? kmEffort(p.distanceKm, p.ascentM ?? 0) : null
  const est = p.distanceKm != null ? estimateRunTime(p.distanceKm, p.ascentM ?? 0, runs) : null
  const rounds = p.rounds
  if (rounds != null && type === 'run') {
    const fast = ((est != null && effort != null ? est.lowMinutes / effort : null) ?? estimateRunTime(1, 0, runs)?.lowMinutes ?? 5.5) * 0.85
    const repMin = ((p.repDistanceM ?? 800) / 1000) * fast
    const minutes = WARM_UP_MIN + rounds * (repMin + INTERVAL_RECOVERY_MIN) + COOL_DOWN_MIN
    const what = `${fmt(rounds, 1).replace(/\.0$/, '')} × ${roundToInt(p.repDistanceM ?? 800)} m`
    return { durationMin: minutes, classified: { intensity: 'HARD', reason: `${what} intervals from the calendar, about ${hoursMinutes(minutes)} with warm-up and cool-down` } }
  }
  const km = p.distanceKm
  if (km == null) return null
  if (type !== 'run' && type !== 'hike') return null
  const climb = p.ascentM != null ? `, +${roundToInt(p.ascentM)} m` : ''
  let minutes: number
  let basis: string
  if (est != null && p.easy) { minutes = (est.minutes + est.highMinutes) / 2; basis = 'at your easy pace' }
  else if (est != null) { minutes = est.minutes; basis = 'at your recent pace' }
  else { minutes = FALLBACK_MIN_PER_KM_EFFORT * kmEffort(km, p.ascentM ?? 0); basis = 'at a default pace (fewer than 5 recent runs)' }
  const km1 = fmt(km, 1)
  const intensity: Intensity = p.long || minutes > LONG_SESSION_MIN ? 'LONG' : p.hard ? 'HARD' : p.easy ? 'EASY' : 'MODERATE'
  const word = intensity === 'LONG' ? 'long run' : intensity === 'HARD' ? 'hard run' : intensity === 'EASY' ? 'easy run' : 'run'
  return { durationMin: minutes, classified: { intensity, reason: `${km1} km${climb} from the calendar, about ${hoursMinutes(minutes)} ${basis}: ${word}` } }
}

// ---- inputs (what the database gives) ----

export type EventSport = 'RUN' | 'TRAIL_RUN' | 'RIDE' | 'HIKE' | 'SWIM' | 'TRIATHLON' | 'TEAM_SPORT' | 'OTHER'
export type EventStatus = 'PLANNED' | 'DONE' | 'DNS' | 'CANCELLED'
/** The first session type of each sport (EventSport.sessionTypes.first()). */
export const SPORT_TYPE: Record<EventSport, string> = { RUN: 'run', TRAIL_RUN: 'run', RIDE: 'ride', HIKE: 'hike', SWIM: 'swim', TRIATHLON: 'run', TEAM_SPORT: 'other', OTHER: 'other' }

export interface EventInput {
  id: number | null; name: string; kind: EventKind; sport: EventSport; priority: EventPriority; date: string
  /** An ISO instant with an offset. */
  start: string | null; status: EventStatus
  /** The target time, else the expected duration. */
  plannedDurationMin: number | null
  distanceKm: number | null
  /** The last course point's distance, m. */
  courseEndM: number | null
  calendarEventId: string | null
}
export interface PlannedSessionInput {
  id: number; date: string; startTime: string | null; durationMin: number | null; domain: string | null; title: string
  gcalEventId?: string | null; exerciseSessionId?: number | null
}
export interface CalendarSessionInput { eventId: string; title: string; start: string; end: string | null; colorId: string | null; description?: string | null }
export interface RecordedSessionInput {
  id: number; type: string; start: string; durationMin: number
  caloriesActive?: number | null; avgHr?: number | null; rpe?: number | null; distanceKm?: number | null; ascentM?: number | null
}
export interface DemandHistory {
  kcalPerMinByType: Record<string, number[]>; trimpPerMinByType: Record<string, number[]>
  restingHr: number | null; maxHr: number | null; weightKg: number | null; recentRuns: RecentRun[]
}
export interface StoredLink { sessionId: number; planRef: string | null; manual: boolean; confidence?: number | null; planTitle?: string | null }
export type CalendarOverride = { eventId: string; kind: 'ignore' | 'long_session'; durationMin?: number | null; intensity?: Intensity | null }

export const isTrainingSession = (type: string, durationMin: number) => type !== 'walk' && durationMin >= 10
const DEFAULT_PLANNED_MIN = 60
const SAME_SESSION_MIN = 120
export const REVIEW_OVER_MIN = 240

export function typeFromCalendarTitle(title: string): string {
  if (title.includes('🏋')) return 'strength'
  if (title.includes('🚴') || /\b(ride|bike|bici|cycling)\b/i.test(title)) return 'ride'
  if (title.includes('🏊') || /\b(swim|nuoto)\b/i.test(title)) return 'swim'
  if (/\b(hike|escursion)/i.test(title)) return 'hike'
  return 'run'
}
export function typeFromDomain(domain: string | null | undefined): string {
  switch (domain) {
    case 'max_strength': case 'hypertrophy': case 'strength_endurance': case 'power': return 'strength'
    case 'work_capacity': case 'anaerobic_capacity': return 'other'
    default: return 'run'
  }
}

// ---- matching recordings to plans (SessionMatching.kt) ----

export const MATCH_NONE_COST = 2
export const LOW_MATCH_CONFIDENCE = 0.5
const ONLY_PAIR_CONFIDENCE = 0.8
const EXHAUSTIVE_MAX = 6

export interface PlanMatch { plan: PlannedItem; confidence: number; stored: boolean; manual: boolean }

export function compatibleTypes(plannedType: string, recordedType: string): boolean {
  if (plannedType === recordedType) return true
  switch (plannedType) {
    case 'run': return ['run', 'hike', 'other'].includes(recordedType)
    case 'hike': return ['hike', 'walk', 'run'].includes(recordedType)
    case 'other': return recordedType !== 'walk'
    case 'strength': return ['strength', 'other'].includes(recordedType)
    default: return false
  }
}

export function matchCost(plan: PlannedItem, rec: RecordedSessionInput, longest: boolean): number | null {
  if (!compatibleTypes(plan.type, rec.type)) return null
  let cost = plan.type === rec.type ? 0 : 0.8
  const start = instant(rec.start)
  cost += plan.start ? Math.min((Math.abs(minutesBetween(instant(plan.start), start)) / 60) * 0.15, 1.5) : 0.6
  const pk = plan.distanceKm
  const rk = rec.distanceKm ?? null
  if (pk != null && rk != null && pk > 0 && rk > 0) cost += Math.min(Math.abs(Math.log(rk / pk)) * 1.2, 2)
  else if (plan.durationMin > 0 && rec.durationMin > 0) cost += Math.min(Math.abs(Math.log(rec.durationMin / plan.durationMin)) * 0.8, 2)
  else cost += 0.5
  if (plan.intensity === 'LONG' || plan.durationMin >= LONG_SESSION_MIN) cost += longest ? -0.3 : 0.6
  return Math.max(cost, 0)
}

export function assignMatches(plans: PlannedItem[], recs: RecordedSessionInput[]): Map<number, PlanMatch> {
  const out = new Map<number, PlanMatch>()
  if (plans.length === 0 || recs.length === 0) return out
  let longest = recs[0]
  for (const r of recs) if (r.durationMin > longest.durationMin) longest = r
  const cost = recs.map((r) => plans.map((p) => { const c = matchCost(p, r, r.id === longest.id); return c != null && c < MATCH_NONE_COST ? c : null }))
  const pick: number[] = recs.map(() => -1)

  if (recs.length <= EXHAUSTIVE_MAX && plans.length <= EXHAUSTIVE_MAX) {
    let best = Number.MAX_VALUE
    const current: number[] = recs.map(() => -1)
    const used: boolean[] = plans.map(() => false)
    const search = (i: number, total: number): void => {
      if (total >= best) return
      if (i === recs.length) { best = total; for (let k = 0; k < pick.length; k++) pick[k] = current[k]; return }
      for (let j = 0; j < plans.length; j++) {
        const c = cost[i][j]
        if (c == null || used[j]) continue
        used[j] = true; current[i] = j
        search(i + 1, total + c)
        used[j] = false; current[i] = -1
      }
      search(i + 1, total + MATCH_NONE_COST)
    }
    search(0, 0)
  } else {
    const used: boolean[] = plans.map(() => false)
    const pairs: [number, number, number][] = []
    recs.forEach((_, i) => plans.forEach((_p, j) => { const c = cost[i][j]; if (c != null) pairs.push([i, j, c]) }))
    pairs.sort((a, b) => a[2] - b[2])
    for (const [i, j] of pairs) if (pick[i] < 0 && !used[j]) { pick[i] = j; used[j] = true }
  }

  recs.forEach((r, i) => {
    const j = pick[i]
    if (j < 0) return
    const c = cost[i][j] as number
    const onlyPair = cost[i].filter((x) => x != null).length === 1 && cost.filter((row) => row[j] != null).length === 1
    let confidence = Math.exp(-0.7 * c)
    if (onlyPair) confidence = Math.max(confidence, ONLY_PAIR_CONFIDENCE)
    confidence = Math.min(Math.max(confidence, 0), 1)
    out.set(r.id, { plan: plans[j], confidence, stored: false, manual: false })
  })
  return out
}

// ---- the planned days (PlannedDay.kt) ----

const startsClose = (a: string | null, b: string | null, minutes = SAME_SESSION_MIN) =>
  a == null || b == null || Math.abs(minutesBetween(instant(a), instant(b))) <= minutes

function item(p: Partial<PlannedItem> & Pick<PlannedItem, 'source' | 'sourceId' | 'title' | 'type' | 'start' | 'durationMin' | 'intensity' | 'intensityReason' | 'kcal'>): PlannedItem {
  return {
    recorded: false, eventId: null, eventKind: null, eventPriority: null, distanceKm: null, planRef: null, planTitle: null,
    matchConfidence: null, matchStored: false, matchManual: false, ...p,
  }
}

function recordedItem(r: RecordedSessionInput, match: PlanMatch | undefined, answer: StoredLink | undefined, zone: string, history: DemandHistory): PlannedItem {
  const start = zdtString(instant(r.start), zone)
  const plan = match?.plan
  const own = intensityForRecorded(r.rpe ?? null, r.avgHr ?? null, history.restingHr, history.maxHr)
  const base = plan && plan.source === 'EVENT' ? { intensity: plan.intensity, reason: plan.intensityReason } : null
  const c = finalIntensity(r.type, r.durationMin, base, own, plan ? { intensity: plan.intensity, reason: plan.intensityReason } : null, intensityForTypical(r.type, history.trimpPerMinByType))
  const kcal = r.caloriesActive != null && r.caloriesActive > 0 ? r.caloriesActive : estimateSessionKcal(r.type, r.durationMin, c.intensity, history.weightKg, history.kcalPerMinByType)
  const title = plan?.title ?? answer?.planTitle ?? r.type.charAt(0).toUpperCase() + r.type.slice(1)
  return item({
    source: 'RECORDED', sourceId: `session:${r.id}`, title, type: r.type, start, durationMin: r.durationMin, intensity: c.intensity, intensityReason: c.reason, kcal,
    recorded: true, eventId: plan?.eventId ?? null, eventKind: plan?.eventKind ?? null, eventPriority: plan?.eventPriority ?? null,
    distanceKm: r.distanceKm ?? null,
    planRef: plan?.sourceId ?? answer?.planRef ?? null, planTitle: plan?.title ?? answer?.planTitle ?? null,
    matchConfidence: match ? match.confidence : answer ? 1 : null,
    matchStored: match?.stored === true || answer != null,
    matchManual: match?.manual === true || answer?.manual === true,
  })
}

function planDay(
  date: string, zone: string, events: EventInput[], planned: PlannedSessionInput[], calendar: CalendarSessionInput[], history: DemandHistory,
  overrides: Record<string, CalendarOverride>,
): [PlannedItem[], CalendarReview[]] {
  const items: PlannedItem[] = []
  const reviews: CalendarReview[] = []
  for (const e of events) {
    if (e.date !== date || e.status === 'CANCELLED' || e.status === 'DNS') continue
    const duration = e.plannedDurationMin ?? DEFAULT_PLANNED_MIN
    const type = SPORT_TYPE[e.sport]
    const c = intensityForEvent(e.kind, e.priority, duration)
    items.push(item({
      source: 'EVENT', sourceId: `event:${e.id}`, title: e.name, type, start: e.start ? zdtString(instant(e.start), zone) : null, durationMin: duration,
      intensity: c.intensity, intensityReason: c.reason, kcal: estimateSessionKcal(type, duration, c.intensity, history.weightKg, history.kcalPerMinByType),
      eventId: e.id, eventKind: e.kind, eventPriority: e.priority,
      distanceKm: e.distanceKm ?? (e.courseEndM != null ? e.courseEndM / 1000 : null),
    }))
  }

  for (const p of planned) {
    if (p.date !== date) continue
    const start = p.startTime ? zdtString(atTime(date, p.startTime, zone), zone) : null
    const type = typeFromDomain(p.domain)
    if (items.some((i) => i.source === 'EVENT' && startsClose(i.start, start) && i.type === type)) continue
    const duration = p.durationMin ?? DEFAULT_PLANNED_MIN
    const c = finalIntensity(type, duration, intensityForDomain(p.domain), intensityForTitle(p.title), intensityForTypical(type, history.trimpPerMinByType))
    items.push(item({
      source: 'PLANNED_SESSION', sourceId: `planned:${p.id}`, title: p.title, type, start, durationMin: duration, intensity: c.intensity, intensityReason: c.reason,
      kcal: estimateSessionKcal(type, duration, c.intensity, history.weightKg, history.kcalPerMinByType),
    }))
  }

  const covered = new Set<string>([...planned.map((p) => p.gcalEventId).filter((x): x is string => x != null), ...events.map((e) => e.calendarEventId).filter((x): x is string => x != null)])
  for (const c of calendar) {
    if (c.colorId !== TRAINING_COLOR_ID || localDate(instant(c.start), zone) !== date || covered.has(c.eventId)) continue
    const startMs = instant(c.start)
    const start = zdtString(startMs, zone)
    if (items.some((i) => startsClose(i.start, start, 60) && i.start != null)) continue
    const untimed = items.findIndex((i) => i.source === 'EVENT' && i.start == null && compatibleTypes(i.type, typeFromCalendarTitle(c.title)))
    if (untimed >= 0) { items[untimed] = { ...items[untimed], start }; continue }
    const override = overrides[c.eventId]
    if (override?.kind === 'ignore') continue
    const type = typeFromCalendarTitle(c.title)
    const clockMin = c.end ? minutesBetween(startMs, instant(c.end)) : null
    const title = trim(c.title) === '' ? 'Training' : trim(c.title)
    if (override?.kind === 'long_session') {
      const minutes = override.durationMin ?? (clockMin != null ? Math.min(clockMin, 600) : null) ?? DEFAULT_PLANNED_MIN
      const intensity = override.intensity ?? 'LONG'
      items.push(item({
        source: 'CALENDAR', sourceId: `calendar:${c.eventId}`, title, type, start, durationMin: minutes, intensity,
        intensityReason: `you set it as ${Math.trunc(minutes)} min, ${lc(intensity)}`,
        kcal: estimateSessionKcal(type, minutes, intensity, history.weightKg, history.kcalPerMinByType),
      }))
      continue
    }
    const parsed = parseCalendarWorkout(c.title, c.description ?? null, type)
    const read = estimateCalendarWorkout(parsed, type, history.recentRuns)
    const duration = read?.durationMin ?? (clockMin != null && within(clockMin, 5, 1440) ? clockMin : null) ?? DEFAULT_PLANNED_MIN
    const titled = intensityForTitle(c.title)
    if (read == null && titled == null && (clockMin ?? 0) > REVIEW_OVER_MIN) {
      reviews.push({
        eventId: c.eventId, title: trim(c.title) === '' ? 'Untitled' : trim(c.title), description: c.description ?? null, start,
        end: c.end ? zdtString(instant(c.end), zone) : null, clockMin: clockMin as number,
      })
      continue
    }
    const k = finalIntensity(type, duration, read?.classified, titled, intensityForTypical(type, history.trimpPerMinByType))
    items.push(item({
      source: 'CALENDAR', sourceId: `calendar:${c.eventId}`, title, type, start, durationMin: duration, intensity: k.intensity, intensityReason: k.reason,
      kcal: estimateSessionKcal(type, duration, k.intensity, history.weightKg, history.kcalPerMinByType), distanceKm: parsed.distanceKm,
    }))
  }
  return [items, reviews]
}

/**
 * The planned days from `from` for `days` days. `now` (an ISO instant) and `zone` decide what is past: a day before today keeps only
 * what was recorded; today keeps what is planned until a recording replaces it. `links` are the stored answers for recordings,
 * before any scoring; they may point at a plan of another day of the range.
 */
export function resolvePlannedDays(
  from: string, days: number, events: EventInput[], planned: PlannedSessionInput[], calendar: CalendarSessionInput[], recorded: RecordedSessionInput[],
  history: DemandHistory, now: string, zone: string, overrides: Record<string, CalendarOverride> = {}, links: Record<string, StoredLink> = {},
): PlannedDay[] {
  const today = localDate(instant(now), zone)
  const dates = Array.from({ length: days }, (_, i) => plusDays(from, i))
  const planDays = new Map(dates.map((d) => [d, planDay(d, zone, events, planned, calendar, history, overrides)] as const))
  const open = new Map(dates.map((d) => [d, [...(planDays.get(d) as [PlannedItem[], CalendarReview[]])[0]]] as const))
  const done = new Map(dates.map((d) => [d, d > today ? [] : recorded
    .filter((r) => localDate(instant(r.start), zone) === d && isTrainingSession(r.type, r.durationMin))
    .sort((a, b) => instant(a.start) - instant(b.start))] as const))

  const matches = new Map<number, PlanMatch>()
  const answered = new Map<number, StoredLink>()
  for (const list of done.values()) for (const r of list) {
    const link = links[String(r.id)]
    if (!link) continue
    if (link.planRef == null) { answered.set(r.id, link); continue }
    let hit: [string, PlannedItem] | null = null
    for (const [d, l] of open) { const f = l.find((i) => i.sourceId === link.planRef); if (f) { hit = [d, f]; break } }
    if (hit) {
      const l = open.get(hit[0]) as PlannedItem[]
      l.splice(l.indexOf(hit[1]), 1)
      matches.set(r.id, { plan: hit[1], confidence: link.confidence ?? 1, stored: true, manual: link.manual })
    } else if (link.manual) {
      answered.set(r.id, link)
    }
  }
  for (const date of dates) {
    const left = (done.get(date) as RecordedSessionInput[]).filter((r) => !matches.has(r.id) && !answered.has(r.id))
    const o = open.get(date) as PlannedItem[]
    for (const [id, m] of assignMatches(o, left)) { matches.set(id, m); o.splice(o.indexOf(m.plan), 1) }
  }
  return dates.map((date) => {
    const [plans, reviews] = planDays.get(date) as [PlannedItem[], CalendarReview[]]
    const items: PlannedItem[] = date < today ? [] : [...(open.get(date) as PlannedItem[])]
    for (const r of done.get(date) as RecordedSessionInput[]) items.push(recordedItem(r, matches.get(r.id), answered.get(r.id), zone, history))
    const key = (i: PlannedItem) => (i.start ? Math.floor(instant(i.start) / 1000) : Infinity)
    items.sort((a, b) => (key(a) === key(b) ? 0 : key(a) < key(b) ? -1 : 1))
    return { date, items, reviews, plans }
  })
}

