// I/O orchestration for the series_break engine, written against a minimal duck-typed Supabase client so it can be
// tested in Node with an in-memory fake and run unchanged in the analysis-run Edge Function.
import { fetchAll } from './paging.ts'
import { buildSeriesBreakResult } from './seriesBreakResult.ts'
import { alignToAxis, dateAxis } from './stats.ts'
import { CORE_VERSION } from './version.ts'

// Metrics whose regime changes matter. deviceSensitive: a change is plausibly a different device or classifier (stage
// minutes, HRV); the others also change for physiological reasons, so they are reported but never auto-confirmed
// (enforced in seriesBreakResult.ts since analysis review B10; before that every metric was auto-confirmed).
export const SERIES = [
  { table: 'wearable_daily', column: 'hrv', metric: 'hrv', deviceSensitive: true, minValue: 0 },
  { table: 'wearable_daily', column: 'rhr', metric: 'rhr', deviceSensitive: false, minValue: 0 },
  { table: 'wearable_daily', column: 'steps', metric: 'steps', deviceSensitive: false, minValue: 0 },
  { table: 'sleep_daily', column: 'hours', metric: 'sleep_hours', deviceSensitive: false, minValue: 2 }, // < 2 h is a nap or sensor fault
  { table: 'sleep_daily', column: 'deep_min', metric: 'deep_min', deviceSensitive: true, minValue: 0 },
  { table: 'sleep_daily', column: 'rem_min', metric: 'rem_min', deviceSensitive: true, minValue: 0 },
  { table: 'sleep_daily', column: 'light_min', metric: 'light_min', deviceSensitive: true, minValue: 0 },
] as const

export const LOOKBACK_DAYS = 420

// deno-lint-ignore no-explicit-any
export type Admin = any

export interface MetricOutcome {
  metric: string
  state?: string
  n?: number
  breaks?: { date: string; kind: string; status: string }[]
  skipped?: string
  error?: string
}

export async function runSeriesBreaks(admin: Admin, userId: string, now: Date = new Date()): Promise<{ userId: string; results: MetricOutcome[] }> {
  const since = new Date(now.getTime() - LOOKBACK_DAYS * 86400000).toISOString().slice(0, 10)
  // Analysis review B10: the provenance read is paged. Unpaged and sorted oldest first, it lost the newest rows once a
  // user had more than PostgREST's row cap, which is exactly where a recent device change would be.
  const [prov, dec] = await Promise.all([
    fetchAll<{ date: string; field: string; origin: string }>((a, b) =>
      admin.from('daily_field_provenance').select('date, field, origin').eq('user_id', userId).gte('date', since).order('date').order('field').range(a, b)),
    admin.from('series_break_decisions').select('metric, break_date, decision').eq('user_id', userId),
  ])
  const provRows = prov.rows
  const decisions: { metric: string; break_date: string; decision: 'source_change' | 'real_change' | 'ignore' }[] = dec.data ?? []

  const out: MetricOutcome[] = []
  for (const s of SERIES) {
    const { rows: data, error } = await fetchAll<Record<string, unknown>>((a, b) =>
      admin.from(s.table).select(`date, ${s.column}`).eq('user_id', userId).gte('date', since).order('date').range(a, b))
    if (error) {
      out.push({ metric: s.metric, error })
      continue
    }
    const pts: { date: string; value: number | null }[] = data
      .map((r: Record<string, unknown>) => {
        const v = r[s.column] === null || r[s.column] === undefined ? NaN : Number(r[s.column])
        return { date: String(r.date), value: Number.isFinite(v) && v >= s.minValue ? v : null }
      })
      .filter((p: { value: number | null }) => p.value !== null)
    if (pts.length === 0) {
      out.push({ metric: s.metric, skipped: 'no data' })
      continue
    }
    const axis = dateAxis(pts[0].date, pts[pts.length - 1].date)
    const values = alignToAxis(axis, pts)

    const originChanges = sustainedOriginChanges(provRows.filter((r) => r.field === `${s.table}.${s.column}`))

    const result = buildSeriesBreakResult({
      metric: s.metric,
      deviceSensitive: s.deviceSensitive,
      dates: axis,
      values,
      originChanges,
      decisions: decisions.filter((d) => d.metric === s.metric).map((d) => ({ break_date: d.break_date, decision: d.decision })),
    })

    const { error: upErr } = await admin.from('analysis_results').upsert(
      {
        user_id: userId,
        kind: 'series_break',
        key: result.key,
        as_of: result.asOf,
        engine_version: CORE_VERSION,
        inputs_hash: result.inputsHash,
        state: result.state,
        confidence_have: result.confidenceHave,
        confidence_need: result.confidenceNeed,
        payload: result.payload,
        calculated_at: now.toISOString(),
      },
      { onConflict: 'user_id,kind,key,as_of,engine_version' },
    )
    if (upErr) {
      out.push({ metric: s.metric, error: upErr.message })
      continue
    }
    // keep one current row per metric and engine version
    await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', 'series_break').eq('key', result.key).eq('engine_version', CORE_VERSION).lt('as_of', result.asOf)
    out.push({ metric: s.metric, state: result.state, n: result.confidenceHave, breaks: result.payload.breaks.map((b) => ({ date: b.date, kind: b.kind, status: b.status })) })
  }
  return { userId, results: out }
}

/**
 * Days on which the dominant source of a field switched and stayed switched. The origin is each day's dominant source
 * (DailyAggregation.kt), so a phone and a watch that alternate produce a "change" almost every day, which used to
 * confirm nearly any candidate break. A switch counts when the new origin holds at least two thirds of the next six
 * provenance days (within 21 days), the switch day included. The first record is never a change.
 */
export function sustainedOriginChanges(rows: { date: string; origin: string }[], lookahead = 6, windowDays = 21): string[] {
  const sorted = [...rows].sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))
  const out: string[] = []
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i].origin === sorted[i - 1].origin) continue
    const limit = Date.parse(sorted[i].date + 'T00:00:00Z') + windowDays * 86400000
    const next = sorted.slice(i, i + lookahead).filter((r) => Date.parse(r.date + 'T00:00:00Z') <= limit)
    const same = next.filter((r) => r.origin === sorted[i].origin).length
    // At least three days to judge by (a switch in the last day or two is decided on a later night) and more than two
    // thirds of them on the new source.
    if (next.length >= 3 && same / next.length > 2 / 3) out.push(sorted[i].date)
  }
  return out
}
