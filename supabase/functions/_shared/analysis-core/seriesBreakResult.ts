// Turns detected breaks into the published `series_break` result: applies provenance evidence and user decisions so a
// statistical candidate becomes `confirmed` only with a reason (docs/analysis-layer-3: a real physiological change looks
// the same as a device change). Pure; analysis-run does the I/O.
import { hashSeries } from './hash.ts'
import { detectSeriesBreaks } from './seriesBreak.ts'
import type { SegmentStats } from './seriesBreak.ts'
import type { Num } from './stats.ts'

export interface BreakDecision {
  break_date: string
  decision: 'source_change' | 'real_change' | 'ignore'
}

export interface PublishedBreak {
  date: string
  kind: 'level' | 'dispersion' | 'both'
  score: number
  before: SegmentStats
  after: SegmentStats
  status: 'candidate' | 'confirmed' | 'dismissed'
  reason: 'origin_change' | 'user_decision' | null
}

export interface SeriesBreakInput {
  metric: string
  deviceSensitive: boolean
  dates: string[]
  values: Num[]
  originChanges: string[] // dates on which the recorded origin of this field changed
  decisions: BreakDecision[]
  confirmWindowDays?: number // origin change within +- this many days confirms a candidate
  recentDays?: number // a non-dismissed break this recent makes the state Unstable
}

export interface SeriesBreakResult {
  key: string
  asOf: string
  state: 'Building' | 'Stable' | 'Unstable'
  confidenceHave: number
  confidenceNeed: number
  inputsHash: string
  payload: { metric: string; deviceSensitive: boolean; breaks: PublishedBreak[]; lastConfirmed: string | null }
}

const MIN_POINTS = 42 // two minimum segments (see seriesBreak.ts)

function dayDiff(a: string, b: string): number {
  return Math.abs(Date.parse(a + 'T00:00:00Z') - Date.parse(b + 'T00:00:00Z')) / 86400000
}

export function buildSeriesBreakResult(i: SeriesBreakInput): SeriesBreakResult {
  const win = i.confirmWindowDays ?? 3
  const recent = i.recentDays ?? 60
  const present = i.values.filter((v) => v !== null).length
  const asOf = i.dates.length ? i.dates[i.dates.length - 1] : ''
  const breaks: PublishedBreak[] = detectSeriesBreaks(i.dates, i.values).map((b) => {
    const decision = i.decisions.find((d) => dayDiff(d.break_date, b.date) <= 7)
    let status: PublishedBreak['status'] = 'candidate'
    let reason: PublishedBreak['reason'] = null
    if (decision) {
      status = decision.decision === 'source_change' ? 'confirmed' : 'dismissed'
      reason = 'user_decision'
    } else if (i.originChanges.some((d) => dayDiff(d, b.date) <= win)) {
      status = 'confirmed'
      reason = 'origin_change'
    }
    return { date: b.date, kind: b.kind, score: b.score, before: b.before, after: b.after, status, reason }
  })
  const confirmed = breaks.filter((b) => b.status === 'confirmed').map((b) => b.date)
  const live = breaks.filter((b) => b.status !== 'dismissed' && asOf !== '' && dayDiff(asOf, b.date) <= recent)
  return {
    key: i.metric,
    asOf,
    state: present < MIN_POINTS ? 'Building' : live.length > 0 ? 'Unstable' : 'Stable',
    confidenceHave: present,
    confidenceNeed: MIN_POINTS,
    inputsHash: hashSeries(i.dates, i.values),
    payload: { metric: i.metric, deviceSensitive: i.deviceSensitive, breaks, lastConfirmed: confirmed.length ? confirmed[confirmed.length - 1] : null },
  }
}
