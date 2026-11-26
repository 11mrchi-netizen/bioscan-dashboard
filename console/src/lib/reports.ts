// Weekly and monthly reports (docs/reports/README.md): the `period_report` rows the nightly analysis run writes
// (analysis-core/periodReport.ts), and the words the Reports page and Today's card show for them. The report is computed on
// the server once a period ends; the web only reads it (workspaces/Reports.tsx), so the phone and the web can never
// disagree about a past week. No I/O here, so it is tested in Node.
import type { LabVerdict, MetricLine, PeriodReport, ReportGrain } from '../../../supabase/functions/_shared/analysis-core/periodReport.ts'

export type { AdherenceLine, LabLine, MetricLine, MicroLine, PeriodReport, ReportGrain, Suggestion } from '../../../supabase/functions/_shared/analysis-core/periodReport.ts'
export { SECTION_LABELS } from '../../../supabase/functions/_shared/analysis-core/periodReport.ts'

/** The newest report of a grain, or of either when none is given. */
export function latest(reports: PeriodReport[], grain?: ReportGrain): PeriodReport | null {
  return [...reports].filter((r) => !grain || r.grain === grain).sort((a, b) => b.end.localeCompare(a.end) || (a.grain === 'week' ? -1 : 1))[0] ?? null
}

/** A number as the report shows it: whole numbers from 100, one decimal below, no trailing zero. */
export function fmt(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return '–'
  if (Math.abs(v) >= 100) return Math.round(v).toLocaleString('en-US')
  return String(Number(v.toFixed(1)))
}

/** "+3.2" / "−1.5" / "±0". */
export function signed(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return ''
  if (Math.abs(v) < 0.05) return '±0'
  return `${v > 0 ? '+' : '−'}${fmt(Math.abs(v))}`
}

export type Tone = 'ok' | 'warn' | 'bad' | null

/** The status word of a line and its status colour (how it is, never which metric it is). */
export function lineStatus(l: MetricLine): { word: string; tone: Tone } {
  switch (l.status) {
    case 'better': return { word: 'better', tone: 'ok' }
    case 'worse': return { word: 'worse', tone: 'bad' }
    case 'up': return { word: 'up', tone: null }
    case 'down': return { word: 'down', tone: null }
    case 'steady': return { word: 'steady', tone: null }
    case 'thin': return { word: 'too few days', tone: 'warn' }
    default: return { word: 'no data', tone: null }
  }
}

/** The arrow of a change, by the number only. */
export function arrow(delta: number | null): string {
  if (delta == null || Math.abs(delta) < 0.05) return '→'
  return delta > 0 ? '▲' : '▼'
}

/** "vs 48.1 the week before · 6 of 7 days". */
export function lineSub(l: MetricLine, grain: ReportGrain): string {
  const before = grain === 'week' ? 'the week before' : 'the month before'
  const parts = [l.prev != null ? `${signed(l.delta)} vs ${fmt(l.prev)} ${before}` : `nothing ${before}`]
  if (l.perWeek) parts.push('per week')
  else if (l.days > 0) parts.push(`${l.days} day${l.days === 1 ? '' : 's'}`)
  return parts.join(' · ')
}

export const LAB_WORDS: Record<LabVerdict, { word: string; tone: Tone }> = {
  working: { word: 'Working', tone: 'ok' },
  no_effect: { word: 'No effect', tone: 'warn' },
  worse: { word: 'Making it worse', tone: 'bad' },
  not_followed: { word: 'Not followed', tone: 'bad' },
  too_early: { word: 'Too early', tone: null },
}

export function adherenceTone(rate: number | null): Tone {
  if (rate == null) return null
  return rate >= 0.85 ? 'ok' : rate >= 0.7 ? 'warn' : 'bad'
}

/** "16 – 22 Nov" or "1 – 30 Nov 2026". */
export function spanText(r: Pick<PeriodReport, 'start' | 'end' | 'grain'>): string {
  const d = (s: string, withMonth: boolean, withYear: boolean) => new Date(`${s}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', ...(withMonth ? { month: 'short' } : {}), ...(withYear ? { year: 'numeric' } : {}) })
  const sameMonth = r.start.slice(0, 7) === r.end.slice(0, 7)
  return `${d(r.start, !sameMonth, false)} – ${d(r.end, true, r.grain === 'month')}`
}

/** The page's address for one report. */
export function reportHref(key: string): string {
  return `#/review/reports?key=${encodeURIComponent(key)}`
}

/** The one-line headline of a report for Today's card: what got better and worse. */
export function headline(r: PeriodReport): string {
  const b = r.summary.better.length
  const w = r.summary.worse.length
  if (!b && !w) return 'Everything steady against the period before.'
  return [b ? `${b} better` : null, w ? `${w} worse` : null].filter(Boolean).join(', ') + ` than the ${r.grain === 'week' ? 'week' : 'month'} before.`
}
