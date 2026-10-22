// Health concerns (docs/health-concerns/01-plan.md §3.1): the raw check-ins as one row per concern per local day.
// Erasable TypeScript, no I/O.
//
// Three kinds of day, never confused:
//   observed     a check-in covers the concern that day; severity is the day's highest rated signal (0 when the
//                check-in said symptom-free), `present` is whether it counted as symptomatic
//   recalled     a later check-in said "nothing since <date>"; observed, symptom-free, lower quality
//   unobserved   nothing covers it; severity is null and the day is never read as a zero
// What covers a concern: a check-in about it (scope concern or session); a sweep ("anything bothering you?"), which is
// everything bothering the person then, so a concern with no signal in it is symptom-free that day. Only the newest entry
// of each correction chain counts; a retraction withdraws the entry it replaces.

export const HEALTH_RULES = {
  version: 'ep-1',
  /** Severity at or above this counts as symptomatic (owner decision 7.2.4). A present but unrated signal also counts. */
  symptomatic: 2,
  /** Symptom-free span that ends an episode, by concern kind (7.2.4). */
  resolutionDays: { anatomical: 7, mixed: 7, systemic: 3 } as Record<string, number>,
  /** Fewest observed symptom-free days inside that span (unobserved days neither extend nor break it). */
  resolutionMinObserved: 3,
  /** Unobserved stretch inside an episode that is flagged as a gap, and that splits an episode with symptoms on both sides. */
  gapFlagDays: 14,
  gapSplitDays: 28,
  /** A concern with no check-in for this long is UNOBSERVED (an open systemic episode: the shorter one). */
  staleDays: 7,
  staleDaysSystemic: 3,
  /** Severity change between the medians of the last three and the three before that reads as improving or worsening. */
  changePoints: 2,
  /** Longest recall a check-in may claim (the table check allows 7). */
  maxRecallDays: 7,
}

export interface ConcernRow {
  id: string
  title: string
  kind: 'anatomical' | 'systemic' | 'mixed'
  onset_type: 'acute' | 'gradual' | 'unknown'
  regions: { code: string; side?: string | null }[]
  symptoms: string[]
  excludes_from_baselines: boolean
  resolution_days: number | null
  archived_at: string | null
  created_at: string
}

export interface ObservationRow {
  id: string
  observed_at: string
  local_date: string
  concern_id: string | null
  scope: 'concern' | 'sweep' | 'session'
  symptom_free: boolean
  recall: 'now' | 'today' | 'since_last'
  recall_from: string | null
  function_impact: number | null
  change_vs_last: 'better' | 'same' | 'worse' | null
  onset: boolean
  session_id: number | null
  timing: string | null
  supersedes: string | null
  superseded_at: string | null
  retracted: boolean
  source: string
  created_at: string
}

export interface SignalRow {
  observation_id: string
  concern_id: string | null
  symptom: string
  region: string | null
  side: string | null
  severity: number | null
  details?: Record<string, unknown> | null
  temperature_c?: number | null
}

export interface MarkRow {
  concern_id: string
  kind: 'start' | 'end' | 'merge_across' | 'split_at'
  date: string
  retracted_at: string | null
}

export interface DayCell {
  date: string
  observed: boolean
  recalled: boolean
  /** Highest rated severity that day (0 = explicitly symptom-free); null when unobserved or present but unrated. */
  severity: number | null
  present: boolean
  impact: number | null
  onset: boolean
  observationIds: string[]
  sessionIds: number[]
  /** When the symptoms were felt, as the check-ins said (during_session, night, ...). */
  timings: string[]
  /** Signals assigned to the concern that day (for follow-ups and safety prompts). */
  signals: SignalRow[]
}

const DAY = 86400000
export const shiftDate = (d: string, n: number) => new Date(Date.parse(d + 'T00:00:00Z') + n * DAY).toISOString().slice(0, 10)
export const daysBetween = (a: string, b: string) => Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / DAY)

/** The entries that count: not replaced by a later correction, not a retraction. */
export function currentObservations(rows: ObservationRow[]): ObservationRow[] {
  const replaced = new Set(rows.map((r) => r.supersedes).filter((x): x is string => !!x))
  return rows.filter((r) => !replaced.has(r.id) && r.superseded_at === null && !r.retracted)
}

/** Which concern a signal belongs to: its own assignment, else the check-in's concern, else the one concern whose region
 *  and side it names (an unassigned signal in a sweep). Null when none or several match. */
export function signalConcern(sig: SignalRow, obs: ObservationRow, concerns: ConcernRow[]): string | null {
  if (sig.concern_id) return sig.concern_id
  if (obs.concern_id) return obs.concern_id
  if (!sig.region) return null
  const hits = concerns.filter((c) => c.regions.some((r) => r.code === sig.region && (!r.side || !sig.side || r.side === sig.side || sig.side === 'both')))
  return hits.length === 1 ? hits[0].id : null
}

export interface ConcernSeries {
  concernId: string
  dates: string[]
  cells: DayCell[]
}

/** Day cells for every concern from `from` to `today`. */
export function buildConcernSeries(
  concerns: ConcernRow[],
  observationRows: ObservationRow[],
  signals: SignalRow[],
  from: string,
  today: string,
): { series: Map<string, ConcernSeries>; unassigned: { date: string; signal: SignalRow }[] } {
  const obs = currentObservations(observationRows).filter((o) => o.local_date <= today)
  const sigByObs = new Map<string, SignalRow[]>()
  for (const s of signals) {
    const list = sigByObs.get(s.observation_id) ?? []
    list.push(s)
    sigByObs.set(s.observation_id, list)
  }
  const n = Math.max(0, daysBetween(from, today) + 1)
  const dates = Array.from({ length: n }, (_, i) => shiftDate(from, i))
  const index = new Map(dates.map((d, i) => [d, i]))
  const series = new Map<string, ConcernSeries>()
  for (const c of concerns) {
    series.set(c.id, {
      concernId: c.id,
      dates,
      cells: dates.map((date) => ({ date, observed: false, recalled: false, severity: null, present: false, impact: null, onset: false, observationIds: [], sessionIds: [], timings: [], signals: [] })),
    })
  }
  const unassigned: { date: string; signal: SignalRow }[] = []

  // Direct observations first, in time order; recall fills only days nothing else covered.
  const sorted = [...obs].sort((a, b) => (a.observed_at < b.observed_at ? -1 : a.observed_at > b.observed_at ? 1 : 0))
  const recalls: ObservationRow[] = []
  for (const o of sorted) {
    if (o.recall === 'since_last' && o.recall_from && o.symptom_free) recalls.push(o)
    const i = index.get(o.local_date)
    if (i === undefined) continue
    const sigs = sigByObs.get(o.id) ?? []
    const byConcern = new Map<string, SignalRow[]>()
    for (const s of sigs) {
      const cid = signalConcern(s, o, concerns)
      if (cid === null || !series.has(cid)) {
        unassigned.push({ date: o.local_date, signal: s })
        continue
      }
      const list = byConcern.get(cid) ?? []
      list.push(s)
      byConcern.set(cid, list)
    }
    const covered = o.scope === 'sweep' ? concerns.map((c) => c.id) : o.concern_id ? [o.concern_id] : []
    for (const cid of covered) {
      const s = series.get(cid)
      if (!s) continue
      const cell = s.cells[i]
      const mine = o.symptom_free ? [] : byConcern.get(cid) ?? []
      // A concern-scoped entry that is not symptom-free but names no signal: present, unrated.
      const presentUnrated = !o.symptom_free && mine.length === 0 && o.scope !== 'sweep'
      const rated = mine.map((x) => x.severity).filter((v): v is number => v !== null)
      const unratedSignal = mine.some((x) => x.severity === null)
      const sev = rated.length > 0 ? Math.max(...rated) : presentUnrated || unratedSignal ? null : 0
      const present = presentUnrated || unratedSignal || rated.some((v) => v >= HEALTH_RULES.symptomatic)
      cell.observed = true
      if (sev !== null) cell.severity = cell.severity === null ? sev : Math.max(cell.severity, sev)
      cell.present = cell.present || present
      if (o.function_impact !== null && o.function_impact !== undefined) cell.impact = Math.max(cell.impact ?? 0, o.function_impact)
      if (o.onset && (o.scope !== 'sweep' || mine.length > 0)) cell.onset = true
      cell.observationIds.push(o.id)
      if (o.session_id !== null && o.session_id !== undefined && !cell.sessionIds.includes(o.session_id)) cell.sessionIds.push(o.session_id)
      if (o.timing && !o.symptom_free && !cell.timings.includes(o.timing)) cell.timings.push(o.timing)
      cell.signals.push(...mine)
    }
  }
  for (const o of recalls) {
    const from = o.recall_from as string
    const covered = o.scope === 'sweep' ? concerns.map((c) => c.id) : o.concern_id ? [o.concern_id] : []
    for (let d = from; d < o.local_date; d = shiftDate(d, 1)) {
      const i = index.get(d)
      if (i === undefined) continue
      for (const cid of covered) {
        const cell = series.get(cid)?.cells[i]
        if (!cell || cell.observed) continue
        cell.observed = true
        cell.recalled = true
        cell.severity = 0
        cell.observationIds.push(o.id)
      }
    }
  }
  // Signals no concern claims are returned once ("new" on the check-in screen), with their check-in date.
  return { series, unassigned }
}
