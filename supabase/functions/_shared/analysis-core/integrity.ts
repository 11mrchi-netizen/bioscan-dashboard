// Analysis hub phase C (docs/analysis-console/PLAN.md): the integrity engine. For every stream (a series of
// metric-series.json) over a window of local days it answers: is the data arriving, is it complete enough to trust, are
// the values plausible and consistent? Every day of every stream gets one of the five quality states (seriesContract.ts),
// and every problem becomes an issue that names its series, its days and the records behind it (dataRef.ts).
//
// Rules:
//  - A day is due only once it is over (today is still syncing, so it is unknown, never missing), and only under the
//    stream's coverage rule (`expect`): 'daily' from the stream's first ever reading on,
//    'worn' on days the band recorded heart rate (the wear signal; before it exists, unknown), 'none' never. A day that
//    was not due is never "missing", and a day nobody can judge is "unknown", not missing.
//  - Staleness: a due stream whose newest reading is more than 2 days old is late, more than 7 days stale. For a 'worn'
//    stream the wear signal tells an ingestion gap (the band was worn, the data did not arrive) from a wear gap.
//  - Source switches: a run of at least SWITCH_RUN days from one source followed by a run from another is reported, so a
//    level change at that date can be read as a possible device change rather than a change in the body.
// Erasable TypeScript, no I/O: callers load the rows (console lib/seriesQuery.ts, runIntegrity.ts for the nightly job).
import { addDays, classifyDay, daySpan, type QualityState, type SeriesContract } from './seriesContract.ts'
import type { DataRef } from './dataRef.ts'

export const INTEGRITY_VERSION = '1.0.0'
export const LATE_DAYS = 2
export const STALE_DAYS = 7
export const SWITCH_RUN = 3
const MAX_REFS = 25

export interface IntegrityRow {
  date: string
  value: number | null
  source: string | null
  id: string | number
}

export interface StreamInput {
  series: SeriesContract
  /** Rows on local days inside the window, in time order. */
  rows: IntegrityRow[]
  /** The stream's first and newest local day ever (null when it has no rows at all). */
  firstSeen: string | null
  lastSeen: string | null
}

export interface WearSignal {
  /** Days the band recorded heart rate. */
  days: Set<string>
  /** First day the signal exists; before it, 'worn' days are unknown. */
  since: string
}

export interface IntegrityInput {
  start: string
  end: string
  /** Today's local date, for staleness. */
  today: string
  streams: StreamInput[]
  wear: WearSignal | null
}

export type Severity = 'critical' | 'warning' | 'info'
export type IssueKind = 'stale' | 'missing' | 'invalid' | 'conflict' | 'duplicate' | 'suspect_zero' | 'source_switch'
export type Freshness = 'fresh' | 'late' | 'stale' | 'never' | 'not_judged'

export interface Issue {
  /** Stable across runs while the problem lasts: kind, series and first day. */
  key: string
  kind: IssueKind
  severity: Severity
  seriesId: string
  title: string
  detail: string
  firstDate: string
  lastDate: string
  days: number
  refs: DataRef[]
}

export interface StreamStatus {
  seriesId: string
  label: string
  table: string
  expect: SeriesContract['expect']
  lastSeen: string | null
  lagDays: number | null
  freshness: Freshness
  /** Days of the window that were due, and of those, days with a valid or flagged value. */
  daysDue: number
  daysObserved: number
  /** daysObserved / daysDue; null when nothing was due (coverage cannot be judged). */
  coverage: number | null
  /** Days of the window with any value (for 'none' streams: how often it was logged). */
  daysWithValue: number
  states: Record<QualityState, number>
  sources: string[]
  issues: number
}

export interface IntegrityReport {
  version: string
  start: string
  end: string
  today: string
  wearSignal: boolean
  streams: StreamStatus[]
  issues: Issue[]
  totals: Record<Severity, number>
}

const SEVERITY_RANK: Record<Severity, number> = { critical: 0, warning: 1, info: 2 }

function* daysOf(start: string, end: string): Generator<string> {
  for (let d = start; d <= end; d = addDays(d, 1)) yield d
}

function recordRefs(table: string, rows: IntegrityRow[]): DataRef[] {
  return rows.slice(0, MAX_REFS).map((r) => ({ kind: 'record', table, id: r.id }))
}

/** Is a value due on this day? true, false, or null when it cannot be told. */
function due(s: SeriesContract, day: string, today: string, firstSeen: string | null, wear: WearSignal | null): boolean | null {
  if (s.expect === 'none' || day >= today) return null
  if (!firstSeen || day < firstSeen) return false
  if (s.expect === 'daily') return true
  if (!wear || day < wear.since) return null
  return wear.days.has(day)
}

function assessStream(st: StreamInput, input: IntegrityInput): { status: StreamStatus; issues: Issue[] } {
  const s = st.series
  const issues: Issue[] = []
  const byDay = new Map<string, IntegrityRow[]>()
  for (const r of st.rows) {
    const list = byDay.get(r.date)
    if (list) list.push(r)
    else byDay.set(r.date, [r])
  }

  const states: Record<QualityState, number> = { unknown: 0, missing: 0, invalid: 0, conflict: 0, valid: 0 }
  const invalidRows: IntegrityRow[] = []
  const conflictRows: IntegrityRow[] = []
  const duplicateRows: IntegrityRow[] = []
  const zeroRows: IntegrityRow[] = []
  const missingDays: string[] = []
  let daysDue = 0
  let daysObserved = 0
  let daysWithValue = 0

  for (const day of daysOf(input.start, input.end)) {
    const rows = byDay.get(day) ?? []
    const isDue = due(s, day, input.today, st.firstSeen, input.wear)
    const v = classifyDay({ rows, expected: isDue, collectorAlive: null }, s)
    // A day with no rows that was not due is outside the stream's coverage rule: count it as unknown, not as valid.
    const state: QualityState = rows.length === 0 && isDue !== true ? 'unknown' : v.state
    states[state]++
    const values = rows.filter((r) => typeof r.value === 'number' && Number.isFinite(r.value))
    if (values.length) daysWithValue++
    if (isDue === true) {
      daysDue++
      if (values.length) daysObserved++
    }
    if (state === 'missing') missingDays.push(day)
    if (state === 'invalid' && s.plausible) {
      const [lo, hi] = s.plausible
      invalidRows.push(...values.filter((r) => (r.value as number) < lo || (r.value as number) > hi))
    }
    if (state === 'conflict') conflictRows.push(...rows)
    else if (v.duplicate) duplicateRows.push(...rows)
    if (s.zeroIsSuspect) zeroRows.push(...values.filter((r) => r.value === 0))
  }

  const issue = (kind: IssueKind, severity: Severity, title: string, detail: string, first: string, last: string, days: number, refs: DataRef[]) =>
    issues.push({ key: `${kind}|${s.id}|${first}`, kind, severity, seriesId: s.id, title, detail, firstDate: first, lastDate: last, days, refs })
  const span = (rows: IntegrityRow[]) => {
    const dates = [...new Set(rows.map((r) => r.date))].sort()
    return { first: dates[0], last: dates[dates.length - 1], days: dates.length }
  }

  if (invalidRows.length) {
    const p = span(invalidRows)
    const [lo, hi] = s.plausible!
    issue('invalid', 'warning', `${s.label}: ${invalidRows.length} implausible value${invalidRows.length === 1 ? '' : 's'}`,
      `Outside the plausible range ${lo}-${hi} ${s.unit}: ${invalidRows.slice(0, 5).map((r) => `${r.value} on ${r.date}`).join(', ')}${invalidRows.length > 5 ? ', ...' : ''}. No analysis drops them automatically: check the records.`,
      p.first, p.last, p.days, recordRefs(s.table, invalidRows))
  }
  if (conflictRows.length) {
    const p = span(conflictRows)
    issue('conflict', 'warning', `${s.label}: rows disagree on ${p.days} day${p.days === 1 ? '' : 's'}`,
      `A daily series holds more than one row with different values on the same day; which one an analysis uses depends on the day method (${s.day}).`,
      p.first, p.last, p.days, recordRefs(s.table, conflictRows))
  }
  if (duplicateRows.length) {
    const p = span(duplicateRows)
    issue('duplicate', 'info', `${s.label}: duplicate rows on ${p.days} day${p.days === 1 ? '' : 's'}`,
      'More than one row on a day of a daily series, with the same value. Harmless for the value, but a sign of a double write.',
      p.first, p.last, p.days, recordRefs(s.table, duplicateRows))
  }
  if (zeroRows.length) {
    const p = span(zeroRows)
    issue('suspect_zero', 'info', `${s.label}: ${zeroRows.length} reading${zeroRows.length === 1 ? '' : 's'} of exactly 0`,
      'For a daily total a 0 more likely means the device recorded nothing than a real zero. Such days are counted as observed.',
      p.first, p.last, p.days, recordRefs(s.table, zeroRows))
  }

  // Runs of consecutive missing due days.
  let runStart: string | null = null
  let prev: string | null = null
  const flush = () => {
    if (!runStart || !prev) return
    const n = daySpan(runStart, prev)
    issue('missing', n >= 3 ? 'warning' : 'info', `${s.label}: ${n} day${n === 1 ? '' : 's'} missing`,
      s.expect === 'worn'
        ? `The band recorded heart rate on ${n === 1 ? 'that day' : 'those days'} but no ${s.label.toLowerCase()} arrived.`
        : `A value is due every day since ${st.firstSeen} and none arrived.`,
      runStart, prev, n, [{ kind: 'period', series: s.id, start: runStart, end: prev }])
  }
  for (const d of missingDays) {
    if (prev && addDays(prev, 1) === d) { prev = d; continue }
    flush()
    runStart = d
    prev = d
  }
  flush()

  // Freshness.
  const lagDays = st.lastSeen ? daySpan(st.lastSeen, input.today) - 1 : null
  let freshness: Freshness = 'not_judged'
  if (s.expect !== 'none') {
    if (lagDays === null) freshness = 'never'
    else if (lagDays > STALE_DAYS) freshness = 'stale'
    else if (lagDays > LATE_DAYS) freshness = 'late'
    else freshness = 'fresh'
  }
  if ((freshness === 'late' || freshness === 'stale') && st.lastSeen) {
    const since = addDays(st.lastSeen, 1)
    let worn = 0
    let wearKnown = false
    if (input.wear) for (const d of daysOf(since, input.today)) {
      if (d >= input.wear.since) wearKnown = true
      if (input.wear.days.has(d)) worn++
    }
    const why = s.expect === 'daily'
      ? 'A value is due every day: the sync or the source has stopped.'
      : !wearKnown
        ? 'No wear signal for those days: the band may not have been worn, or its heart rate has not synced either.'
        : worn > 0
          ? `The band recorded heart rate on ${worn} of those days, so this is an ingestion gap, not a wear gap.`
          : 'The band recorded no heart rate either: probably not worn, or the whole sync is behind.'
    issue('stale', freshness === 'stale' ? 'critical' : 'warning', `${s.label}: nothing new for ${lagDays} days`,
      `Newest reading on ${st.lastSeen}. ${why}`, since, input.today, lagDays!, [{ kind: 'period', series: s.id, start: st.lastSeen, end: input.today }])
  }

  // Source switches: runs of one source of at least SWITCH_RUN days.
  const daySources: { date: string; source: string }[] = []
  for (const [date, rows] of [...byDay.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1))) {
    const srcs = [...new Set(rows.filter((r) => r.value !== null).map((r) => r.source).filter((x): x is string => !!x))]
    if (srcs.length === 1) daySources.push({ date, source: srcs[0] })
  }
  const runs: { source: string; first: string; last: string; n: number }[] = []
  for (const d of daySources) {
    const last = runs[runs.length - 1]
    if (last && last.source === d.source) { last.last = d.date; last.n++ }
    else runs.push({ source: d.source, first: d.date, last: d.date, n: 1 })
  }
  const stable = runs.filter((r) => r.n >= SWITCH_RUN)
  for (let i = 1; i < stable.length; i++) {
    const a = stable[i - 1]
    const b = stable[i]
    if (a.source === b.source) continue
    issue('source_switch', 'info', `${s.label}: source ${a.source} -> ${b.source} on ${b.first}`,
      `${a.n} days from ${a.source} (to ${a.last}), then ${b.n} from ${b.source}. A level change at this date may be the device, not the body; the series-break review decides.`,
      b.first, b.first, 1, [{ kind: 'period', series: s.id, start: a.first, end: b.last }])
  }

  const sources = [...new Set(st.rows.map((r) => r.source).filter((x): x is string => !!x))].sort()
  return {
    status: {
      seriesId: s.id, label: s.label, table: s.table, expect: s.expect, lastSeen: st.lastSeen, lagDays, freshness,
      daysDue, daysObserved, coverage: daysDue ? daysObserved / daysDue : null, daysWithValue, states, sources, issues: issues.length,
    },
    issues,
  }
}

export function assessIntegrity(input: IntegrityInput): IntegrityReport {
  const streams: StreamStatus[] = []
  const issues: Issue[] = []
  for (const st of input.streams) {
    const r = assessStream(st, input)
    streams.push(r.status)
    issues.push(...r.issues)
  }
  issues.sort((a, b) => SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity] || (a.lastDate < b.lastDate ? 1 : a.lastDate > b.lastDate ? -1 : 0))
  const totals: Record<Severity, number> = { critical: 0, warning: 0, info: 0 }
  for (const i of issues) totals[i.severity]++
  return { version: INTEGRITY_VERSION, start: input.start, end: input.end, today: input.today, wearSignal: input.wear !== null, streams, issues, totals }
}
