// Health concerns (docs/health-concerns/01-plan.md §3.2-3.4, §3.9): episodes, their measures, the concern's current
// state, data quality and safety prompts, from the day cells of healthSeries.ts. Erasable TypeScript, no I/O.
//
// Episodes are derived, never stored as input: recomputing from the same check-ins and marks gives the same episodes
// (and the same ids). Rules, in order, for each day:
//   * a split_at mark, or an onset check-in during an episode, ends the episode the day before and starts a new one;
//     a start mark starts one; an end mark ends one on its date (the person's word beats the rules);
//   * a symptomatic day outside an episode starts one;
//   * inside an episode, a run of symptom-free days ends it when the run spans the resolution days and holds at least
//     three observed days; unobserved days neither extend nor break the run; the end is the first day of the run;
//   * an unobserved stretch of more than 14 days inside an episode is flagged; more than 28 with symptoms on both sides
//     ends the episode at the last symptomatic day and starts a new one, both marked uncertain, unless a merge_across
//     mark falls in the gap.
// Boundaries carry ranges: the start lies between the day after the last symptom-free check-in and the first
// symptomatic day, the end between the day after the last symptomatic day and the first day of the resolving run.
import { fnv1a } from './hash.ts'
import { HEALTH_RULES, daysBetween, shiftDate } from './healthSeries.ts'
import type { ConcernRow, DayCell, MarkRow } from './healthSeries.ts'
import { median } from './stats.ts'

export type ConcernState = 'unobserved' | 'active' | 'improving' | 'stable' | 'worsening' | 'resolved'

export interface Episode {
  id: string
  start: string
  /** Earliest possible start (the day after the last symptom-free check-in); null when nothing was observed before. */
  startEarliest: string | null
  /** First day after the episode (the resolving run's first day or an end mark); null while open. */
  end: string | null
  /** Earliest possible end (the day after the last symptomatic day). */
  endEarliest: string | null
  lastSymptomatic: string
  open: boolean
  endedBy: 'rule' | 'mark' | 'split' | 'gap' | null
  gap: boolean
  boundaryUncertain: boolean
  /** Duration in days as a range (equal when both boundaries are known to the day). */
  durationDays: { min: number; max: number }
  peakSeverity: number | null
  peakDate: string | null
  meanSeverity: number | null
  severityDays: number
  impactDays: number
  observedDays: number
  spanDays: number
  coverage: number
  recalledDays: number
  daysPeakToEnd: number | null
  sessionIds: number[]
  observationIds: string[]
}

export interface DataQuality {
  coverage30: number
  coverage90: number
  symptomFreeObservedDays90: number
  recalledShare90: number
  withinEpisodeCoverage: number | null
  /** Check-in rate inside episodes over the rate outside them (last 180 days); > 3 means quiet days are under-observed. */
  informativeMissingness: number | null
  word: 'GOOD' | 'PARTIAL' | 'THIN'
  have: number
  need: number
}

export interface SafetyPrompt {
  code: 'severe' | 'impact_daily_life' | 'rising' | 'fever' | 'long_illness' | 'neuro' | 'chest' | 'night_pain'
  text: string
}

export interface ConcernSummary {
  state: ConcernState
  stateReason: string
  lastObserved: string | null
  episodes: Episode[]
  currentEpisode: Episode | null
  lastEpisode: Episode | null
  recurrence: {
    episodes: number
    recurrences: number
    episodesLast90: number
    episodesLast365: number
    /** Symptom-free days between consecutive episodes, as ranges. */
    intervals: { min: number; max: number }[]
    medianInterval: number | null
    within30: number
    within90: number
  }
  quality: DataQuality
  safety: SafetyPrompt[]
  resolutionDays: number
}

export function resolutionDaysFor(c: ConcernRow): number {
  return c.resolution_days ?? HEALTH_RULES.resolutionDays[c.kind] ?? 7
}

const episodeId = (concernId: string, start: string) => fnv1a(`${concernId}|${start}|${HEALTH_RULES.version}`)

interface Open {
  start: string
  startEarliest: string | null
  lastSymptomatic: string
  runStart: string | null
  runObserved: number
  lastObserved: string
  gap: boolean
  uncertainStart: boolean
}

/** Episodes of one concern from its day cells (oldest first) and marks. */
export function deriveEpisodes(concern: ConcernRow, cells: DayCell[], marks: MarkRow[]): Omit<Episode, 'id' | 'durationDays' | 'peakSeverity' | 'peakDate' | 'meanSeverity' | 'severityDays' | 'impactDays' | 'observedDays' | 'spanDays' | 'coverage' | 'recalledDays' | 'daysPeakToEnd' | 'sessionIds' | 'observationIds'>[] {
  const resolution = resolutionDaysFor(concern)
  const live = marks.filter((m) => m.concern_id === concern.id && !m.retracted_at)
  const markOn = (date: string, kind: MarkRow['kind']) => live.some((m) => m.date === date && m.kind === kind)
  const mergeIn = (a: string, b: string) => live.some((m) => m.kind === 'merge_across' && m.date >= a && m.date <= b)
  type Raw = ReturnType<typeof deriveEpisodes>[number]
  const out: Raw[] = []
  let cur: Open | null = null
  let lastFree: string | null = null

  const close = (end: string, endEarliest: string, endedBy: Raw['endedBy'], uncertain = false) => {
    if (!cur) return
    out.push({
      start: cur.start, startEarliest: cur.startEarliest, end, endEarliest: endEarliest > end ? end : endEarliest,
      lastSymptomatic: cur.lastSymptomatic, open: false, endedBy, gap: cur.gap, boundaryUncertain: cur.uncertainStart || uncertain,
    })
    cur = null
  }
  const open = (date: string, uncertain = false) => {
    cur = { start: date, startEarliest: lastFree ? shiftDate(lastFree, 1) : null, lastSymptomatic: date, runStart: null, runObserved: 0, lastObserved: date, gap: false, uncertainStart: uncertain }
  }

  for (const cell of cells) {
    const d = cell.date
    if (cur && (markOn(d, 'split_at') || (cell.onset && d !== (cur as Open).start))) {
      close(d, shiftDate((cur as Open).lastSymptomatic, 1), 'split')
      open(d)
    } else if (!cur && (markOn(d, 'split_at') || markOn(d, 'start'))) {
      open(d)
    }
    if (cur && markOn(d, 'end') && d > (cur as Open).start) {
      close(d, shiftDate((cur as Open).lastSymptomatic, 1), 'mark')
      if (cell.observed && !cell.present) lastFree = d
      continue
    }
    if (!cell.observed) continue

    if (cell.present) {
      if (!cur) {
        open(d)
      } else {
        const c = cur as Open
        const silent = daysBetween(c.lastObserved, d) - 1
        if (silent > HEALTH_RULES.gapSplitDays && !mergeIn(c.lastObserved, d)) {
          close(shiftDate(c.lastSymptomatic, 1), shiftDate(c.lastSymptomatic, 1), 'gap', true)
          open(d, true)
        } else if (silent > HEALTH_RULES.gapFlagDays) {
          c.gap = true
        }
      }
      const c = cur as unknown as Open
      c.lastSymptomatic = d
      c.lastObserved = d
      c.runStart = null
      c.runObserved = 0
      // An end mark on the first day: a one-day episode (a legacy row with the same start and end date).
      if (c.start === d && markOn(d, 'end')) close(d, d, 'mark')
      continue
    }

    // Observed and symptom-free.
    lastFree = d
    if (!cur) continue
    const c = cur as Open
    if (daysBetween(c.lastObserved, d) - 1 > HEALTH_RULES.gapFlagDays) c.gap = true
    c.lastObserved = d
    if (c.runStart === null) c.runStart = d
    c.runObserved++
    if (daysBetween(c.runStart, d) + 1 >= resolution && c.runObserved >= Math.min(HEALTH_RULES.resolutionMinObserved, resolution)) {
      close(c.runStart, shiftDate(c.lastSymptomatic, 1), 'rule')
    }
  }
  if (cur) {
    const c = cur as Open
    out.push({
      start: c.start, startEarliest: c.startEarliest, end: null, endEarliest: null, lastSymptomatic: c.lastSymptomatic,
      open: true, endedBy: null, gap: c.gap, boundaryUncertain: c.uncertainStart,
    })
  }
  return out
}

/** Episodes with their measures. `today` closes the span of an open episode. */
export function measureEpisodes(concern: ConcernRow, cells: DayCell[], marks: MarkRow[], today: string): Episode[] {
  const byDate = new Map(cells.map((c) => [c.date, c]))
  return deriveEpisodes(concern, cells, marks).map((e) => {
    const last = e.end ? shiftDate(e.end, -1) : today
    const inside: DayCell[] = []
    for (let d = e.start; d <= last; d = shiftDate(d, 1)) {
      const c = byDate.get(d)
      if (c) inside.push(c)
    }
    const observed = inside.filter((c) => c.observed)
    const rated = observed.filter((c) => c.severity !== null) as (DayCell & { severity: number })[]
    let peak: DayCell | null = null
    for (const c of rated) if (!peak || (c.severity as number) > (peak.severity as number)) peak = c
    // Severity-days: observed days, linearly interpolated across gaps of up to two days.
    let severityDays = 0
    for (let i = 0; i < rated.length; i++) {
      severityDays += rated[i].severity
      if (i + 1 < rated.length) {
        const gap = daysBetween(rated[i].date, rated[i + 1].date) - 1
        if (gap > 0 && gap <= 2) severityDays += gap * (rated[i].severity + rated[i + 1].severity) / 2
      }
    }
    const spanDays = daysBetween(e.start, last) + 1
    const minDur = e.end ? daysBetween(e.start, e.endEarliest ?? e.end) : spanDays
    const maxDur = e.end ? daysBetween(e.startEarliest ?? e.start, e.end) : daysBetween(e.startEarliest ?? e.start, today) + 1
    const sessionIds = [...new Set(inside.flatMap((c) => c.sessionIds))]
    return {
      ...e,
      id: episodeId(concern.id, e.start),
      durationDays: { min: Math.max(1, minDur), max: Math.max(1, maxDur, minDur) },
      peakSeverity: peak ? (peak.severity as number) : null,
      peakDate: peak ? peak.date : null,
      meanSeverity: rated.length ? Math.round((rated.reduce((a, c) => a + c.severity, 0) / rated.length) * 10) / 10 : null,
      severityDays: Math.round(severityDays * 10) / 10,
      impactDays: observed.filter((c) => (c.impact ?? 0) >= 2).length,
      observedDays: observed.length,
      spanDays,
      coverage: Math.round((observed.length / spanDays) * 100) / 100,
      recalledDays: observed.filter((c) => c.recalled).length,
      daysPeakToEnd: peak && e.end ? daysBetween(peak.date, e.end) : null,
      sessionIds,
      observationIds: [...new Set(inside.flatMap((c) => c.observationIds))],
    }
  })
}

function quality(cells: DayCell[], episodes: Episode[], today: string): DataQuality {
  const window = (n: number) => cells.filter((c) => c.date > shiftDate(today, -n) && c.date <= today)
  const inEpisode = (d: string) => episodes.some((e) => d >= e.start && (e.end === null || d < e.end))
  const w30 = window(30)
  const w90 = window(90)
  const cov = (w: DayCell[]) => (w.length ? Math.round((w.filter((c) => c.observed).length / w.length) * 100) / 100 : 0)
  const obs90 = w90.filter((c) => c.observed)
  const w180 = window(180)
  const inside = w180.filter((c) => inEpisode(c.date))
  const outside = w180.filter((c) => !inEpisode(c.date))
  const rate = (w: DayCell[]) => (w.length ? w.filter((c) => c.observed).length / w.length : 0)
  let missingness: number | null = null
  if (inside.length >= 5 && outside.length >= 5) {
    const ro = rate(outside)
    missingness = ro > 0 ? Math.round((rate(inside) / ro) * 100) / 100 : null
  }
  const ep = episodes[episodes.length - 1]
  const coverage90 = cov(w90)
  const need = 0.6
  const ratio = coverage90 / need
  return {
    coverage30: cov(w30),
    coverage90,
    symptomFreeObservedDays90: obs90.filter((c) => !c.present && !c.recalled && !inEpisode(c.date)).length,
    recalledShare90: obs90.length ? Math.round((obs90.filter((c) => c.recalled).length / obs90.length) * 100) / 100 : 0,
    withinEpisodeCoverage: ep ? ep.coverage : null,
    informativeMissingness: missingness,
    // Contract R2: a count against what is needed, never "confidence".
    word: ratio >= 0.8 ? 'GOOD' : ratio >= 0.5 ? 'PARTIAL' : 'THIN',
    have: coverage90,
    need,
  }
}

function safety(concern: ConcernRow, cells: DayCell[], current: Episode | null, today: string): SafetyPrompt[] {
  const out: SafetyPrompt[] = []
  const recent = cells.filter((c) => c.observed && c.date > shiftDate(today, -3) && c.date <= today)
  const sigs = recent.flatMap((c) => c.signals)
  if (recent.some((c) => (c.severity ?? 0) >= 8)) {
    out.push({ code: 'severe', text: 'Severity of 8 or more in the last few days. Consider seeing a clinician, especially if it is not easing.' })
  }
  const lastObs = cells.filter((c) => c.observed && c.date <= today).slice(-4)
  if (lastObs.length === 4 && lastObs.every((c) => (c.impact ?? 0) >= 3) && daysBetween(lastObs[0].date, today) <= 6) {
    out.push({ code: 'impact_daily_life', text: 'It has affected daily life for more than three days. Consider seeing a clinician.' })
  }
  const rated = cells.filter((c) => c.observed && c.severity !== null && c.date > shiftDate(today, -7) && c.date <= today).slice(-3)
  if (rated.length === 3 && (rated[0].severity as number) < (rated[1].severity as number) && (rated[1].severity as number) < (rated[2].severity as number)) {
    out.push({ code: 'rising', text: 'Worse at each of the last three check-ins. If it keeps rising, consider seeing a clinician.' })
  }
  if (sigs.some((s) => (s.temperature_c ?? 0) >= 39)) {
    out.push({ code: 'fever', text: 'A temperature of 39 °C or more. Consider seeing a clinician, and rest from hard training while it lasts.' })
  }
  if (concern.kind === 'systemic' && current && current.open && daysBetween(current.start, today) + 1 > 10) {
    out.push({ code: 'long_illness', text: 'This illness has lasted more than ten days. Consider seeing a clinician.' })
  }
  if (sigs.some((s) => s.symptom === 'sym.numbness' || s.details?.radiates === true)) {
    out.push({ code: 'neuro', text: 'Numbness, tingling or pain spreading along a limb. Consider seeing a clinician.' })
  }
  if (sigs.some((s) => s.region === 'chest' || s.details?.breathing === true)) {
    out.push({ code: 'chest', text: 'Chest symptoms or breathlessness at rest. Seek medical advice promptly, urgently if severe.' })
  }
  const nights = cells.filter((c) => c.present && c.date > shiftDate(today, -7) && c.date <= today && c.timings.some((t) => t === 'night' || t === 'at_rest'))
  if (concern.kind !== 'systemic' && nights.length >= 3) {
    out.push({ code: 'night_pain', text: 'Symptoms at night or at rest on several days this week. Consider seeing a clinician if that continues.' })
  }
  return out
}

/** Everything the concern page and the engine's later stages need about one concern. */
export function summariseConcern(concern: ConcernRow, cells: DayCell[], marks: MarkRow[], today: string): ConcernSummary {
  const episodes = measureEpisodes(concern, cells, marks, today)
  const current = episodes.find((e) => e.open) ?? null
  const closed = episodes.filter((e) => !e.open)
  const lastEpisode = closed[closed.length - 1] ?? null
  const observed = cells.filter((c) => c.observed && c.date <= today)
  const lastObserved = observed.length ? observed[observed.length - 1].date : null

  const stale = current && concern.kind === 'systemic' ? HEALTH_RULES.staleDaysSystemic : HEALTH_RULES.staleDays
  let state: ConcernState
  let stateReason: string
  if (lastObserved === null || daysBetween(lastObserved, today) >= stale) {
    state = 'unobserved'
    stateReason = lastObserved === null ? 'No check-in yet.' : `No check-in for ${daysBetween(lastObserved, today)} days; last known on ${lastObserved}.`
  } else if (!current) {
    state = 'resolved'
    stateReason = lastEpisode ? `Symptom-free since ${lastEpisode.end}.` : 'No symptomatic days on record.'
  } else {
    const inEp = observed.filter((c) => c.date >= current.start)
    if (inEp.length < 4 || daysBetween(current.start, today) < 3) {
      state = 'active'
      stateReason = `Started ${current.start}; ${inEp.length} check-in${inEp.length === 1 ? '' : 's'} so far, too few to read a direction.`
    } else {
      const rated = inEp.filter((c) => c.severity !== null)
      const last3 = rated.filter((c) => c.date > shiftDate(today, -7)).slice(-3)
      const before = rated.filter((c) => last3.length > 0 && c.date < last3[0].date && c.date > shiftDate(last3[0].date, -15)).slice(-3)
      const impacts = inEp.filter((c) => c.impact !== null)
      const lastTwo = impacts.slice(-2)
      const prevImpacts = impacts.slice(-5, -2)
      const impactUp = lastTwo.length === 2 && prevImpacts.length > 0 && lastTwo.every((c) => (c.impact as number) >= Math.max(...prevImpacts.map((p) => p.impact as number)) + 1)
      const impactDown = lastTwo.length === 2 && prevImpacts.length > 0 && lastTwo.every((c) => (c.impact as number) <= Math.min(...prevImpacts.map((p) => p.impact as number)) - 1)
      if (last3.length < 3 || before.length < 3) {
        if (impactUp) {
          state = 'worsening'
          stateReason = 'Function impact rose by a level at the last two check-ins.'
        } else {
          state = 'active'
          stateReason = 'Too few rated check-ins in the last three weeks to read a direction.'
        }
      } else {
        const a = median(before.map((c) => c.severity as number))
        const b = median(last3.map((c) => c.severity as number))
        if (b - a >= HEALTH_RULES.changePoints || impactUp) {
          state = 'worsening'
          stateReason = impactUp && b - a < HEALTH_RULES.changePoints ? 'Function impact rose by a level at the last two check-ins.' : `Median severity ${a} → ${b} across the last six rated check-ins.`
        } else if (a - b >= HEALTH_RULES.changePoints || impactDown) {
          state = 'improving'
          stateReason = a - b >= HEALTH_RULES.changePoints ? `Median severity ${a} → ${b} across the last six rated check-ins.` : 'Function impact fell by a level at the last two check-ins.'
        } else {
          state = 'stable'
          stateReason = `Median severity ${a} then ${b}: within ${HEALTH_RULES.changePoints} points.`
        }
      }
    }
  }

  const intervals: { min: number; max: number }[] = []
  for (let i = 1; i < episodes.length; i++) {
    const prev = episodes[i - 1]
    if (!prev.end) continue
    const max = daysBetween(prev.endEarliest ?? prev.end, episodes[i].start)
    const min = daysBetween(prev.end, episodes[i].startEarliest ?? episodes[i].start)
    intervals.push({ min: Math.max(0, Math.min(min, max)), max: Math.max(0, max, min) })
  }
  const within = (n: number) => intervals.filter((x) => x.max <= n).length
  const quality90 = quality(cells, episodes, today)
  return {
    state,
    stateReason,
    lastObserved,
    episodes,
    currentEpisode: current,
    lastEpisode,
    recurrence: {
      episodes: episodes.length,
      recurrences: Math.max(0, episodes.length - 1),
      episodesLast90: episodes.filter((e) => e.start > shiftDate(today, -90)).length,
      episodesLast365: episodes.filter((e) => e.start > shiftDate(today, -365)).length,
      intervals,
      medianInterval: intervals.length ? median(intervals.map((x) => (x.min + x.max) / 2)) : null,
      within30: within(30),
      within90: within(90),
    },
    quality: quality90,
    safety: safety(concern, cells, current, today),
    resolutionDays: resolutionDaysFor(concern),
  }
}
