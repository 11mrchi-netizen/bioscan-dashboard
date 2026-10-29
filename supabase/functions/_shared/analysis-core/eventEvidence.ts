// Repeatable, timed interventions (docs: plan "Interventions as repeatable, timed events"): the informal N-of-1 evidence
// layer. Erasable TypeScript, no I/O.
//
// An episodic intervention (nasal strips tonight, a lactose-heavy meal at 13:00) produces events. Each expectation
// declares, before the first event, an outcome and a window in hours after the event; the outcome of one event is the
// aggregate of the outcome's points inside that window. Those values are compared with the same window placed at the same
// clock time on days with no event, the way the exposure catalogue (healthExposures.ts) fixes windows before any data
// is looked at. Nothing here is a verdict: the label climbs with the number of usable events (anecdote -> early signal ->
// emerging -> ready to test properly), and the wording stays "consistent with", never "caused". The step after "ready" is
// a pre-registered randomised experiment.
//
// Time is local wall-clock minutes (wallMin = days since 1970-01-01 * 1440 + minutes of the day). A daily series
// (sleep, HRV, a 0-10 rating) enters as one point at an anchor hour chosen by the loader -- a night belongs to the
// morning it ends, so its anchor sits in the small hours -- which makes "that night" a window of 0-12 h after a 22:00 event
// and puts last night's value outside it.
import { benjaminiHochberg, mean, median, ranks, sd } from './stats.ts'

export const EVENT_EVIDENCE = {
  /** Usable events (with outcome data) at which each label starts. */
  ladder: { earlySignal: 3, emerging: 7, ready: 15 },
  /** An interval needs this many events with data and this many reference occasions. */
  minWith: 3,
  minReference: 5,
  referenceLookbackDays: 60,
  /** Below this share of |effect| / personal SD the difference is called flat when the interval spans zero. */
  flatSd: 0.2,
}

export type Ladder = 'anecdote' | 'early_signal' | 'emerging' | 'ready'
export type Role = 'primary' | 'secondary' | 'watch'
export type Direction = 'up' | 'down' | 'none'
export type Aggregation = 'mean' | 'min' | 'max' | 'sum' | 'last'
export type Observed = 'as_expected' | 'opposite' | 'unexpected_change' | 'no_difference' | 'unclear' | 'too_few'
export type Assignment = 'self' | 'mixed' | 'drawn'

export interface SeriesPoint {
  wallMin: number
  value: number
}

export interface EventInput {
  id: string
  /** Local wall minute of the event. */
  wallMin: number
  localDate: string
  adhered: 'yes' | 'partial' | 'no'
  confounders: string[]
  feeling: number | null
  assignedBy: 'self' | 'drawn'
}

export interface ExpectationInput {
  id: string
  outcomeKey: string
  label: string
  unit: string | null
  role: Role
  direction: Direction
  windowStartH: number
  windowEndH: number
  aggregation: Aggregation
  /** Added after the first event: read, but never the headline. */
  exploratory: boolean
}

export interface EventEvidenceInput {
  interventionId: string
  /** The latest day with data; a window that ends after the end of this day is still open. */
  asOf: string
  events: EventInput[]
  expectations: ExpectationInput[]
  /** Points per outcomeKey. */
  points: Record<string, SeriesPoint[]>
  /** Wall minutes of events of every OTHER intervention (a stacked event is not read as this one's). */
  otherEventMins: number[]
  /** Local dates known to be confounded (illness, travel, ...), excluded from the reference. */
  confoundedDays: string[]
}

export interface ExpectationResult {
  expectationId: string
  outcomeKey: string
  label: string
  role: Role
  exploratory: boolean
  direction: Direction
  windowStartH: number
  windowEndH: number
  nWithData: number
  nReference: number
  withMedian: number | null
  referenceMedian: number | null
  /** Hodges-Lehmann shift (with minus reference), natural units. */
  diff: number | null
  lo: number | null
  hi: number | null
  /** diff / personal SD of the reference occasions. */
  diffSd: number | null
  unit: string | null
  observed: Observed
  /** Shown from the top rung only. */
  p: number | null
  pAdjusted: number | null
  /** First three events alone vs all, on the primary only, once there are at least six (novelty). */
  firstThree: { diff: number | null; all: number | null } | null
  summary: string
}

export interface EventEvidenceResult {
  interventionId: string
  asOf: string
  nEvents: number
  nUsed: number
  excluded: { notDone: number; confounded: number; stacked: number; overlapping: number; open: number }
  nPartial: number
  ladder: Ladder
  assignment: Assignment
  feeling: { n: number; mean: number | null }
  expectations: ExpectationResult[]
  notes: string[]
}

const r = (v: number, d = 3) => Math.round(v * 10 ** d) / 10 ** d
const MIN_PER_DAY = 1440

export const dayNumber = (date: string): number => Math.floor(Date.parse(date + 'T00:00:00Z') / 86400000)
export const wallMinOf = (date: string, hour: number): number => dayNumber(date) * MIN_PER_DAY + Math.round(hour * 60)

export function ladderFor(n: number): Ladder {
  if (n >= EVENT_EVIDENCE.ladder.ready) return 'ready'
  if (n >= EVENT_EVIDENCE.ladder.emerging) return 'emerging'
  if (n >= EVENT_EVIDENCE.ladder.earlySignal) return 'early_signal'
  return 'anecdote'
}

function aggregate(values: number[], how: Aggregation): number | null {
  if (values.length === 0) return null
  if (how === 'min') return Math.min(...values)
  if (how === 'max') return Math.max(...values)
  if (how === 'sum') return values.reduce((s, v) => s + v, 0)
  if (how === 'last') return values[values.length - 1]
  return mean(values)
}

/** The outcome of one occasion: the aggregate of the points inside [anchor + start, anchor + end). */
function outcomeAt(points: SeriesPoint[], anchor: number, e: ExpectationInput): number | null {
  const from = anchor + e.windowStartH * 60
  const to = anchor + e.windowEndH * 60
  return aggregate(points.filter((p) => p.wallMin >= from && p.wallMin < to).sort((a, b) => a.wallMin - b.wallMin).map((p) => p.value), e.aggregation)
}

/** The span an occasion occupies for overlap purposes: its own instant through the end of the window. */
function span(anchor: number, startH: number, endH: number): [number, number] {
  return [anchor + Math.min(0, startH * 60), anchor + endH * 60]
}

const intersects = (a: [number, number], b: [number, number]) => a[0] < b[1] && b[0] < a[1]

function erfc(x: number): number {
  // Abramowitz & Stegun 7.1.26 on |x|; accurate to about 1.5e-7, plenty for a displayed p.
  const t = 1 / (1 + 0.3275911 * Math.abs(x))
  const y = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429)))) * Math.exp(-x * x)
  return x >= 0 ? y : 2 - y
}

/** Two-sided Mann-Whitney U p, normal approximation with tie and continuity corrections. */
function mannWhitneyP(a: number[], b: number[]): number {
  const n = a.length
  const m = b.length
  const all = [...a, ...b]
  const rk = ranks(all)
  const u = rk.slice(0, n).reduce((s, v) => s + v, 0) - (n * (n + 1)) / 2
  const mu = (n * m) / 2
  const counts = new Map<number, number>()
  for (const v of all) counts.set(v, (counts.get(v) ?? 0) + 1)
  let ties = 0
  for (const t of counts.values()) ties += t ** 3 - t
  const big = n + m
  const variance = ((n * m) / 12) * (big + 1 - ties / (big * (big - 1)))
  if (variance <= 0) return 1
  const z = Math.max(0, Math.abs(u - mu) - 0.5) / Math.sqrt(variance)
  return Math.min(1, erfc(z / Math.SQRT2))
}

/** Hodges-Lehmann shift with a rank-based interval (normal approximation for the interval's order statistics). */
function hodgesLehmann(a: number[], b: number[]): { diff: number; lo: number | null; hi: number | null } {
  const diffs: number[] = []
  for (const x of a) for (const y of b) diffs.push(x - y)
  diffs.sort((p, q) => p - q)
  const diff = median(diffs)
  if (a.length < EVENT_EVIDENCE.minWith || b.length < EVENT_EVIDENCE.minReference) return { diff, lo: null, hi: null }
  const n = a.length
  const m = b.length
  const k = Math.floor((n * m) / 2 - 1.96 * Math.sqrt((n * m * (n + m + 1)) / 12))
  const c = Math.max(0, k)
  return { diff, lo: diffs[c], hi: diffs[diffs.length - 1 - c] }
}

function observedLabel(direction: Direction, diff: number, lo: number | null, hi: number | null, diffSd: number | null): Observed {
  if (lo === null || hi === null) return 'unclear'
  const excludesZero = lo > 0 || hi < 0
  if (!excludesZero) {
    return diffSd !== null && Math.abs(diffSd) < EVENT_EVIDENCE.flatSd ? 'no_difference' : direction === 'none' ? 'no_difference' : 'unclear'
  }
  if (direction === 'none') return 'unexpected_change'
  return (direction === 'up') === diff > 0 ? 'as_expected' : 'opposite'
}

const signed = (v: number) => `${v > 0 ? '+' : v < 0 ? '−' : ''}${Math.abs(r(v, 2))}`

function summarise(e: ExpectationInput, res: Pick<ExpectationResult, 'nWithData' | 'nReference' | 'diff' | 'lo' | 'hi' | 'unit' | 'observed'>): string {
  const unit = res.unit ? ` ${res.unit}` : ''
  if (res.diff === null || res.nWithData === 0) return `${e.label}: no event has data in its window yet.`
  const base = `${e.label}: ${signed(res.diff)}${unit} across ${res.nWithData} event${res.nWithData === 1 ? '' : 's'} with data vs ${res.nReference} reference occasions`
  const interval = res.lo !== null && res.hi !== null ? ` (${signed(res.lo)} to ${signed(res.hi)})` : ''
  const tail: Record<Observed, string> = {
    as_expected: 'the data are consistent with the expected direction.',
    opposite: 'the data point the opposite way to what was expected.',
    unexpected_change: 'a change was seen where none was expected.',
    no_difference: 'no clear difference.',
    unclear: 'too uncertain to read.',
    too_few: 'too few events to read.',
  }
  return `${base}${interval}; ${tail[res.observed]}`
}

export function analyseEventEvidence(input: EventEvidenceInput): EventEvidenceResult {
  const { events, expectations } = input
  const endOfAsOf = (dayNumber(input.asOf) + 1) * MIN_PER_DAY
  const confounded = new Set(input.confoundedDays)
  const primary = expectations.find((e) => e.role === 'primary' && !e.exploratory) ?? expectations[0] ?? null
  const maxEndH = Math.max(0, ...expectations.map((e) => e.windowEndH))
  const minStartH = Math.min(0, ...expectations.map((e) => e.windowStartH))

  const excluded = { notDone: 0, confounded: 0, stacked: 0, overlapping: 0, open: 0 }
  const used: EventInput[] = []
  let lastSpanEnd = -Infinity
  const ordered = [...events].sort((a, b) => a.wallMin - b.wallMin)
  const allSpans = ordered.map((ev) => span(ev.wallMin, minStartH, maxEndH))
  for (const ev of ordered) {
    const sp = span(ev.wallMin, minStartH, maxEndH)
    if (ev.adhered === 'no') { excluded.notDone++; continue }
    if (ev.confounders.length > 0) { excluded.confounded++; continue }
    if (ev.wallMin + maxEndH * 60 > endOfAsOf) { excluded.open++; continue }
    if (input.otherEventMins.some((t) => t >= sp[0] && t < sp[1])) { excluded.stacked++; continue }
    if (sp[0] < lastSpanEnd) { excluded.overlapping++; continue }
    lastSpanEnd = sp[1]
    used.push(ev)
  }

  // Reference occasions: the same clock time on days without an event of this intervention (or anything confounded).
  const refMinute = used.length > 0 ? Math.round(median(used.map((ev) => ((ev.wallMin % MIN_PER_DAY) + MIN_PER_DAY) % MIN_PER_DAY))) : 12 * 60
  const lastDay = dayNumber(input.asOf) - 1
  const refAnchors: number[] = []
  for (let d = lastDay; d > lastDay - EVENT_EVIDENCE.referenceLookbackDays; d--) {
    const anchor = d * MIN_PER_DAY + refMinute
    if (anchor + maxEndH * 60 > endOfAsOf) continue
    if (confounded.has(new Date(d * 86400000).toISOString().slice(0, 10))) continue
    const sp = span(anchor, minStartH, maxEndH)
    if (allSpans.some((s) => intersects(s, sp))) continue
    if (input.otherEventMins.some((t) => t >= sp[0] && t < sp[1])) continue
    refAnchors.push(anchor)
  }

  const results: ExpectationResult[] = []
  for (const e of expectations) {
    const pts = input.points[e.outcomeKey] ?? []
    const withVals = used.map((ev) => ({ ev, v: outcomeAt(pts, ev.wallMin, e) })).filter((x): x is { ev: EventInput; v: number } => x.v !== null)
    const refVals = refAnchors.map((a) => outcomeAt(pts, a, e)).filter((v): v is number => v !== null)
    const w = withVals.map((x) => x.v)
    const hl = w.length > 0 && refVals.length > 0 ? hodgesLehmann(w, refVals) : null
    const refSd = refVals.length >= EVENT_EVIDENCE.minReference ? sd(refVals) : 0
    const diffSd = hl && refSd > 0 ? hl.diff / refSd : null
    const observed: Observed = w.length < EVENT_EVIDENCE.minWith ? 'too_few' : hl ? observedLabel(e.direction, hl.diff, hl.lo, hl.hi, diffSd) : 'unclear'
    const base = {
      nWithData: w.length, nReference: refVals.length, diff: hl ? r(hl.diff) : null, lo: hl?.lo != null ? r(hl.lo) : null, hi: hl?.hi != null ? r(hl.hi) : null, unit: e.unit, observed,
    }
    let firstThree: ExpectationResult['firstThree'] = null
    if (e === primary && w.length >= 6 && refVals.length > 0) {
      firstThree = { diff: r(hodgesLehmann(w.slice(0, 3), refVals).diff), all: r(hodgesLehmann(w, refVals).diff) }
    }
    results.push({
      expectationId: e.id, outcomeKey: e.outcomeKey, label: e.label, role: e.role, exploratory: e.exploratory, direction: e.direction,
      windowStartH: e.windowStartH, windowEndH: e.windowEndH, ...base,
      withMedian: w.length ? r(median(w)) : null, referenceMedian: refVals.length ? r(median(refVals)) : null,
      diffSd: diffSd === null ? null : r(diffSd, 2),
      p: w.length >= EVENT_EVIDENCE.minWith && refVals.length >= EVENT_EVIDENCE.minReference ? mannWhitneyP(w, refVals) : null,
      pAdjusted: null, firstThree, summary: summarise(e, base),
    })
  }

  // The primary is judged alone; everything else is exploratory and corrected together.
  const rest = results.filter((x) => x !== results.find((y) => y.expectationId === primary?.id))
  const ps = rest.filter((x) => x.p !== null)
  const adj = benjaminiHochberg(ps.map((x) => x.p as number))
  ps.forEach((x, i) => { x.pAdjusted = adj[i] })
  const primaryResult = results.find((x) => x.expectationId === primary?.id) ?? null
  if (primaryResult) primaryResult.pAdjusted = primaryResult.p

  const basis = primaryResult ? primaryResult.nWithData : used.length
  const ladder = ladderFor(basis)
  // p-values are shown from the top rung only: below it the interval and the label are the honest summary.
  for (const x of results) {
    if (ladder !== 'ready') { x.p = null; x.pAdjusted = null } else { x.p = x.p === null ? null : r(x.p, 4); x.pAdjusted = x.pAdjusted === null ? null : r(x.pAdjusted, 4) }
  }

  const drawn = used.filter((ev) => ev.assignedBy === 'drawn').length
  const rated = events.filter((ev) => ev.feeling !== null).map((ev) => ev.feeling as number)
  const notes: string[] = []
  if (refAnchors.length < EVENT_EVIDENCE.minReference) notes.push(`Only ${refAnchors.length} comparison occasions are available, so no interval is shown yet.`)
  if (events.length > 0 && drawn === 0) notes.push('Every event was chosen by you, so a night you picked may differ from one you did not (a blocked nose, a heavy day); a drawn schedule removes that.')
  if (excluded.stacked > 0) notes.push(`${excluded.stacked} event${excluded.stacked === 1 ? '' : 's'} left out because another intervention fell in the same window.`)
  if (results.some((x) => x.exploratory || x.role !== 'primary')) notes.push('Secondary, watch and later-added outcomes are exploratory.')

  return {
    interventionId: input.interventionId,
    asOf: input.asOf,
    nEvents: events.length,
    nUsed: used.length,
    excluded,
    nPartial: used.filter((ev) => ev.adhered === 'partial').length,
    ladder,
    assignment: drawn === 0 ? 'self' : drawn === used.length ? 'drawn' : 'mixed',
    feeling: { n: rated.length, mean: rated.length ? r(mean(rated), 2) : null },
    expectations: results,
    notes,
  }
}
