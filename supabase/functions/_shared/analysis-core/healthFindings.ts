// Health concerns (docs/health-concerns/01-plan.md §3.7-3.9): from evaluations to findings a person can read. Erasable
// TypeScript, no I/O.
//
// The ladder: INSUFFICIENT (not enough to compare, and what is missing), OBSERVED RECURRENCE (it has come back; no claim
// about why), NO PATTERN (compared, nothing stands out), POSSIBLE (exploratory: tonight's primary-window criteria hold),
// PERSISTENT (was possible, then held on at least three new flare-ups that came after it was first noticed), FADED (was
// possible, then failed on two re-checks in a row that each had new flare-ups). The ledger keeps every night's raw
// verdict; the status shown is derived from that history, so a finding cannot flicker on one night's data.
// Every finding answers five things: what changed, what evidence supports it, what else might explain it, how uncertain
// it is, and a useful next step. Words follow contract R5: "came before", "went with", "followed"; never a cause verb.
import { HEALTH_GATES } from './healthAssociations.ts'
import type { CaseInfo, CrossoverResult, ExposureEvaluation, SessionLevelResult } from './healthAssociations.ts'
import { confirmation } from './healthAssociations.ts'
import type { ConcernSummary, DataQuality, Episode } from './healthEpisodes.ts'
import { UNAVAILABLE_EXPOSURES } from './healthExposures.ts'
import type { ExposureSpec } from './healthExposures.ts'
import { daysBetween, shiftDate } from './healthSeries.ts'
import type { ConcernRow } from './healthSeries.ts'
import type { Num } from './stats.ts'

export type RawVerdict = 'insufficient' | 'unclear' | 'possible' | 'persistent' | 'observed'
export type FindingStatus = 'insufficient' | 'observed' | 'no_pattern' | 'emerging' | 'possible' | 'persistent' | 'faded'

export interface LedgerEntry {
  question_id: string
  as_of: string
  verdict: string
  n: number | null
}

export interface Finding {
  questionId: string
  concernId: string
  exposure: string | null
  status: FindingStatus
  rawVerdict: RawVerdict
  /** Status from earlier nights still shown while tonight's re-check failed once (it fades on a second failure). */
  weakening: boolean
  rung: 'observed' | 'associated'
  exploratory: boolean
  what: string
  evidence: {
    text: string
    cases?: number
    exposedCases?: number
    expectedExposed?: number
    referenceDays?: number
    exposedReferenceDays?: number
    or?: number | null
    lo?: number | null
    hi?: number | null
    q?: number | null
    window?: string
    session?: { exposedSessions: number; exposedFollowed: number; otherSessions: number; otherFollowed: number; rr: number | null; lo: number | null; hi: number | null; q: number | null } | null
    sensitivity?: { window: string; cases: number; exposedCases: number; lift: number | null }[]
    links: { episodes: string[]; sessions: number[]; dates: string[] }
  }
  explanations: string[]
  uncertainty: string
  nextStep: { kind: 'log' | 'review' | 'observe' | 'trial' | 'none'; text: string }
  firstPossible: string | null
  confirmation: { newCases: number; newExposed: number; need: number } | null
  /** The count behind the primary analysis (flare-ups, or sessions for a session kind); the ledger's n. */
  primaryN?: number
  dataQuality: DataQuality['word']
}

export const questionId = (concernId: string, exposure: string) => `health:${concernId}:${exposure}`

export function windowWords(w: [number, number]): string {
  if (w[1] === 0) return 'on the day'
  return `on the day or the ${w[1] === 1 ? 'day' : `${w[1]} days`} before`
}

const pct = (x: number, n: number) => (n > 0 ? `${Math.round((x / n) * 100)}%` : '–')
const fmtDate = (d: string) => {
  const [y, m, day] = d.split('-').map(Number)
  return `${day} ${['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][m - 1]} ${y}`
}

/** A pass has to repeat on a re-check at least this many days later, with new data in between, before it is shown. */
export const REPEAT_GAP_DAYS = 14
/** One-sided exact p the new flare-ups (or sessions) must reach on their own for a finding to be called persistent. */
export const CONFIRM_P = 0.2

const isPass = (v: string) => v === 'possible' || v === 'persistent'

/**
 * Status from the ledger's nights (oldest first) and tonight's raw verdict. A first pass is a hint (EMERGING), re-checked
 * on new data; it is shown as POSSIBLE once it has passed again at least 14 days later with more flare-ups or sessions
 * behind it, every re-check in between passing too. A shown finding that then fails one re-check with new data is
 * WEAKENING (still shown); a second such failure FADES it. Without new data a failure never counts. Repeated looks are
 * what the gap and the repeat guard against: one lucky night cannot become a pattern on screen.
 */
export function deriveStatus(raw: RawVerdict, n: number, history: LedgerEntry[], today: string): { status: FindingStatus; weakening: boolean; firstPossible: string | null } {
  const rows = [...history.filter((h) => h.as_of < today), { question_id: '', as_of: today, verdict: raw, n }]
  let streakStart: LedgerEntry | null = null
  let shownSince: string | null = null
  let shown = false
  let faded = false
  let lastPass: LedgerEntry | null = null
  let failures = 0
  let nAtCheck = 0
  for (const r of rows) {
    const rn = r.n ?? 0
    if (isPass(r.verdict)) {
      if (!streakStart) streakStart = r
      if (!shown && daysBetween(streakStart.as_of, r.as_of) >= REPEAT_GAP_DAYS && rn > (streakStart.n ?? 0)) {
        shown = true
        faded = false
        if (!shownSince) shownSince = r.as_of
      }
      lastPass = r
      failures = 0
      nAtCheck = rn
    } else {
      streakStart = null
      if (shown && r.verdict === 'unclear' && rn > nAtCheck) {
        failures++
        nAtCheck = rn
        if (failures >= 2) { shown = false; faded = true }
      }
    }
  }
  const last = rows[rows.length - 1]
  if (shown) {
    if (isPass(last.verdict)) return { status: last.verdict as FindingStatus, weakening: false, firstPossible: shownSince }
    return { status: (lastPass?.verdict ?? 'possible') as FindingStatus, weakening: true, firstPossible: shownSince }
  }
  if (isPass(last.verdict)) return { status: 'emerging', weakening: false, firstPossible: shownSince }
  if (faded) return { status: 'faded', weakening: false, firstPossible: shownSince }
  return { status: raw === 'unclear' ? 'no_pattern' : 'insufficient', weakening: false, firstPossible: shownSince }
}

function sessionWord(spec: ExposureSpec): string {
  return spec.id === 'regional_strength' ? 'strength sessions' : 'runs'
}

export interface FindingInputs {
  concern: ConcernRow
  summary: ConcernSummary
  evaluations: ExposureEvaluation[]
  cases: CaseInfo[]
  referenceOk: boolean[]
  dates: string[]
  series: Record<string, Num[]>
  droppedCases: number
  history: LedgerEntry[]
  /** The session-level comparison on sessions after a date (confirmation of a session-route finding). */
  sessionsSince?: (spec: ExposureSpec, since: string) => SessionLevelResult | null
}

/** One finding per exposure asked of the concern, plus the concern's recurrence finding. */
export function buildFindings(inp: FindingInputs): Finding[] {
  const { concern, summary } = inp
  const out: Finding[] = [recurrenceFinding(concern, summary)]
  if (concern.onset_type === 'acute') return out // owner decision 7.2.5: a knock is not an overuse pattern
  const G = HEALTH_GATES
  const byId = new Map(inp.evaluations.map((e) => [e.exposure.id, e]))
  for (const e of inp.evaluations) {
    const spec = e.exposure
    const qid = questionId(concern.id, spec.id)
    const c = e.crossover
    const s = e.session
    const enough = s !== null ? s.exposedSessions >= G.minSessions : c.cases >= G.minCases
    let raw: RawVerdict = e.meets ? 'possible' : enough ? 'unclear' : 'insufficient'
    const hist = inp.history.filter((h) => h.question_id === qid)
    const primaryN = s !== null ? s.exposedSessions : c.cases
    const today = inp.dates[inp.dates.length - 1]
    // Persistent: shown as possible before tonight, and the flare-ups (or sessions) since then hold up on their own.
    const before = deriveStatus(raw, primaryN, hist, today)
    let conf: Finding['confirmation'] = null
    if (raw === 'possible' && before.firstPossible && before.firstPossible < today) {
      if (s === null) {
        const x = inp.series[spec.id] ?? inp.dates.map(() => null)
        const fresh: CrossoverResult = confirmation(inp.dates, x, inp.cases, inp.referenceOk, spec.window, before.firstPossible)
        conf = { newCases: fresh.cases, newExposed: fresh.exposedCases, need: G.confirmCases }
        if (fresh.cases >= G.confirmCases && fresh.p !== null && fresh.p <= CONFIRM_P) raw = 'persistent'
      } else if (inp.sessionsSince) {
        const fresh = inp.sessionsSince(spec, before.firstPossible)
        if (fresh) {
          conf = { newCases: fresh.exposedFollowed, newExposed: fresh.exposedFollowed, need: G.confirmCases }
          if (fresh.exposedFollowed >= G.confirmCases && fresh.p !== null && fresh.p <= CONFIRM_P) raw = 'persistent'
        }
      }
    }
    const { status, weakening, firstPossible } = deriveStatus(raw, primaryN, hist, today)
    out.push({ ...exposureFinding(concern, summary, e, byId, status, raw, weakening, firstPossible, conf, inp.droppedCases), primaryN })
  }
  return out
}

function recurrenceFinding(concern: ConcernRow, summary: ConcernSummary): Finding {
  const r = summary.recurrence
  const eps = summary.episodes
  const observed = r.episodes >= 2
  const span = eps.length >= 2 ? daysBetween(eps[0].start, eps[eps.length - 1].start) : 0
  const what = observed
    ? `${concern.title} has come back ${r.recurrences} time${r.recurrences === 1 ? '' : 's'} in ${span >= 60 ? `${Math.round(span / 30)} months` : `${span} days`}${r.medianInterval !== null ? `; typically ${Math.round(r.medianInterval)} symptom-free days in between` : ''}.`
    : eps.length === 1
      ? `One episode on record (from ${fmtDate(eps[0].start)}).`
      : 'No episode on record yet.'
  const durations = eps.filter((e) => !e.open).map((e) => e.durationDays)
  const evidence = eps.length
    ? `${eps.length} episode${eps.length === 1 ? '' : 's'}: ${eps.map((e) => `${fmtDate(e.start)}${e.open ? ' (ongoing)' : ''}`).join(', ')}.${durations.length ? ` Durations ${durations.map((d) => (d.min === d.max ? `${d.min}` : `${d.min}-${d.max}`)).join(', ')} days.` : ''}`
    : 'No symptomatic check-ins yet.'
  return {
    questionId: questionId(concern.id, 'recurrence'),
    concernId: concern.id,
    exposure: null,
    status: observed ? 'observed' : 'insufficient',
    rawVerdict: observed ? 'observed' : 'insufficient',
    weakening: false,
    rung: 'observed',
    exploratory: false,
    what,
    evidence: { text: evidence, links: { episodes: eps.map((e) => e.id), sessions: [], dates: eps.map((e) => e.start) } },
    explanations: [],
    uncertainty: eps.some((e) => e.boundaryUncertain || e.gap)
      ? 'Some episode boundaries are uncertain because of days without check-ins; durations are shown as ranges.'
      : 'Episode boundaries follow the check-ins; durations are exact to the day where both ends were checked in.',
    nextStep: observed
      ? { kind: 'review', text: 'Review the sessions in the week before each episode (WHAT CAME BEFORE on each one).' }
      : { kind: 'log', text: 'Keep checking in, including NOTHING on good days, so the next episode has clear edges.' },
    firstPossible: null,
    confirmation: null,
    dataQuality: summary.quality.word,
  }
}

function exposureFinding(
  concern: ConcernRow,
  summary: ConcernSummary,
  e: ExposureEvaluation,
  byId: Map<string, ExposureEvaluation>,
  status: FindingStatus,
  raw: RawVerdict,
  weakening: boolean,
  firstPossible: string | null,
  conf: Finding['confirmation'],
  dropped: number,
): Finding {
  const G = HEALTH_GATES
  const spec = e.exposure
  const c = e.crossover
  const s = e.session
  const ww = windowWords(spec.window)
  const viaSession = s !== null // the exposure's pre-specified primary analysis
  const crossoverText = c.cases > 0
    ? `Before ${c.exposedCases} of ${c.cases} flare-ups (${pct(c.exposedCases, c.cases)}) there was ${spec.phrase} ${ww}; on ${c.exposedReferenceDays} of ${c.referenceDays} comparable symptom-free days (${pct(c.exposedReferenceDays, c.referenceDays)}). From how common it is, about ${Math.round(c.expectedExposed * 10) / 10} of those flare-ups would have had it anyway.`
    : 'No flare-up yet with check-ins in the days before it.'
  const sessionText = s && s.exposedSessions > 0
    ? `Symptoms started within ${spec.window[1]} days after ${s.exposedFollowed} of ${s.exposedSessions} sessions with ${spec.phrase} (${pct(s.exposedFollowed, s.exposedSessions)}), and after ${s.otherFollowed} of ${s.otherSessions} other ${sessionWord(spec)} (${pct(s.otherFollowed, s.otherSessions)}).`
    : ''
  const title = concern.title
  let what: string
  switch (status) {
    case 'possible':
      what = viaSession
        ? `${title}: symptoms started more often after ${spec.phrase} than after other ${sessionWord(spec)}.`
        : `${title} flare-ups came after ${spec.phrase} more often than comparable symptom-free days did.`
      break
    case 'persistent':
      what = `${title} flare-ups have kept coming after ${spec.phrase} more often than comparable days, including ${conf?.newCases ?? 'several'} flare-ups since it was first noticed on ${firstPossible ? fmtDate(firstPossible) : 'an earlier night'}.`
      break
    case 'emerging':
      what = viaSession
        ? `A hint, not yet a pattern: ${title} symptoms started more often after ${spec.phrase} than after other ${sessionWord(spec)} on this check.`
        : `A hint, not yet a pattern: ${title} flare-ups came after ${spec.phrase} more often than comparable symptom-free days on this check.`
      break
    case 'faded':
      what = `Earlier, ${title} flare-ups often followed ${spec.phrase}; with more flare-ups since, that is no longer clear.`
      break
    case 'no_pattern':
      what = `No clear difference: ${spec.phrase} came before flare-ups about as often as on comparable symptom-free days.`
      break
    default:
      what = `Not enough yet to compare ${spec.phrase} with ${title} flare-ups.`
  }
  if (weakening) what += ' The latest re-check did not hold; it fades if the next one does not either.'

  const explanations: string[] = []
  if (status === 'emerging' || status === 'possible' || status === 'persistent' || status === 'faded') {
    const exposedDates = new Set(c.exposedCaseDates)
    for (const other of byId.values()) {
      if (other.exposure.id === spec.id || exposedDates.size < 2) continue
      const both = other.crossover.exposedCaseDates.filter((d) => exposedDates.has(d)).length
      if (both >= 2 && both / exposedDates.size >= 0.6) {
        explanations.push(`Of the ${exposedDates.size} flare-ups after ${spec.phrase}, ${both} also followed ${other.exposure.phrase}.`)
      }
    }
    const anyRun = byId.get('any_run')
    if (['long_run', 'trail_run', 'quality_run', 'volume_jump', 'back_to_back'].includes(spec.id) && anyRun && anyRun.crossover.lift !== null && anyRun.crossover.lift >= 1.3) {
      explanations.push(`Running in general also went with flare-ups (${anyRun.crossover.lift}× as often as on comparable days), so ${spec.phrase} may partly stand for running more.`)
    }
    if (spec.reverseRisk) explanations.push('An illness or flare-up starting can lower HRV before symptoms are noticed, so this may run the other way.')
    const im = summary.quality.informativeMissingness
    if (im !== null && im > 2) explanations.push(`Check-ins were ${im}× as frequent during flare-ups as between them, so quiet days are under-represented.`)
    const dates = [...c.exposedCaseDates].sort()
    for (let i = 0; i < dates.length; i++) {
      const inBlock = dates.filter((d) => d >= dates[i] && d <= shiftDate(dates[i], 56))
      if (dates.length >= 3 && inBlock.length / dates.length >= 0.6) {
        explanations.push(`${inBlock.length} of these flare-ups fell between ${fmtDate(inBlock[0])} and ${fmtDate(inBlock[inBlock.length - 1])}; something else about that block may explain them.`)
        break
      }
    }
    if (concern.kind !== 'systemic') explanations.push(`Not in the data, so not checked: ${UNAVAILABLE_EXPOSURES.join('; ').toLowerCase()}.`)
  }

  const q = viaSession ? e.qSession : e.qCrossover
  let uncertainty: string
  if (status === 'insufficient') {
    uncertainty = `Needs: ${e.reasonsNot.join('; ') || 'more flare-ups with check-ins around them'}.${dropped > 0 ? ` ${dropped} flare-up${dropped === 1 ? ' was' : 's were'} left out because the days before had no check-in.` : ''}`
  } else if (viaSession && s) {
    uncertainty = `Risk ratio ${s.rr ?? '–'} (95% interval ${s.lo ?? '–'} to ${s.hi ?? '–'}) from ${s.exposedSessions} sessions; adjusted for the other questions asked of this concern (q = ${q ?? '–'}).`
  } else {
    const ci = c.or !== null ? `Odds ratio ${c.or} (95% interval ${c.lo} to ${c.hi})` : c.exposedCases === c.cases && c.cases > 0 ? 'Every flare-up had it, so the odds ratio cannot be estimated' : 'Odds ratio not estimable'
    uncertainty = `${ci} from ${c.cases} flare-ups; adjusted for the other questions asked of this concern (q = ${q ?? '–'}).`
  }
  if (status === 'emerging') uncertainty += ` It is shown as a possible pattern only if it holds again on a re-check at least ${REPEAT_GAP_DAYS} days later with more data, so one lucky check cannot make a pattern.`
  if (status === 'possible') uncertainty += ` EXPLORATORY until the ${G.confirmCases}+ ${viaSession ? 'sessions followed by symptoms' : 'flare-ups'} after it was first shown hold up on their own${conf ? ` (${conf.newCases} so far)` : ''}.`
  if (status === 'persistent') uncertainty += ' It has held on new data; it is still an association in your history, and does not show that one brings on the other.'

  let nextStep: Finding['nextStep']
  if (status === 'insufficient') {
    nextStep = summary.quality.symptomFreeObservedDays90 < G.minReferenceDays
      ? { kind: 'log', text: `Tap NOTHING on good days too: comparisons need symptom-free days (${summary.quality.symptomFreeObservedDays90} of ${G.minReferenceDays} in the last 90 days).` }
      : { kind: 'log', text: `Keep checking in; ${Math.max(0, G.minCases - c.cases)} more flare-ups with check-ins in the days before are needed.` }
  } else if (status === 'possible' || status === 'emerging') {
    nextStep = { kind: 'observe', text: `Check in the day after your next few sessions with ${spec.phrase}, symptom-free or not, so this can be confirmed or ruled out.` }
  } else if (status === 'persistent') {
    nextStep = { kind: 'trial', text: `A trial could clarify it: change ${spec.phrase} for at least four weeks and compare (Lab › N=1). If it limits training, this summary may help a physio.` }
  } else {
    nextStep = { kind: 'none', text: 'Nothing to act on; it is re-checked as flare-ups add up.' }
  }

  const sens = [
    ...e.crossoverSensitivity.map((x) => ({ window: windowWords(x.window), cases: x.cases, exposedCases: x.exposedCases, lift: x.lift })),
  ]
  return {
    questionId: questionId(concern.id, spec.id),
    concernId: concern.id,
    exposure: spec.id,
    status,
    rawVerdict: raw,
    weakening,
    rung: 'associated',
    exploratory: status !== 'persistent',
    what,
    evidence: {
      text: viaSession && sessionText ? `${sessionText} ${crossoverText}` : `${crossoverText}${sessionText ? ' ' + sessionText : ''}`,
      cases: c.cases, exposedCases: c.exposedCases, expectedExposed: c.expectedExposed, referenceDays: c.referenceDays,
      exposedReferenceDays: c.exposedReferenceDays, or: c.or, lo: c.lo, hi: c.hi, q: e.qCrossover, window: ww,
      session: s ? { exposedSessions: s.exposedSessions, exposedFollowed: s.exposedFollowed, otherSessions: s.otherSessions, otherFollowed: s.otherFollowed, rr: s.rr, lo: s.lo, hi: s.hi, q: e.qSession } : null,
      sensitivity: sens,
      links: {
        episodes: summary.episodes.filter((ep: Episode) => c.exposedCaseDates.includes(ep.start)).map((ep) => ep.id),
        sessions: s ? s.followedSessionIds : [],
        dates: c.exposedCaseDates,
      },
    },
    explanations,
    uncertainty,
    nextStep,
    firstPossible,
    confirmation: conf,
    dataQuality: summary.quality.word,
  }
}
