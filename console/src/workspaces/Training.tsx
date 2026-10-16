// DAV-314: Training workspace — stress balance, VO2max trend, session list.
// CTL/ATL/TSB from session load (sRPE, else TRIMP from heart rate), with the same model and EWMA convention as the
// Android app (analysis review A5). VO2max estimated per effort via Daniels & Gilbert.

import { LoadBanner } from '../components/shared/LoadBanner'
import { useLoadEffect } from '../lib/useLoadEffect'
import { useState, useMemo } from 'react'
import { supabase } from '../lib/supabase'
import { useRange, useInspect } from '../lib/analysisStore'
import { ENDURANCE_TYPES, dayBounds, localDate, warnOnError } from '../lib/schema'
import { loadAnchorsByDate, sessionLoad, vo2maxFromPace, ema } from '../lib/training'
import { FTChart } from '../components/shared/FTChart'
import type { MetricResult } from '../lib/metricAdapter'
import type { ChartSpec } from '../lib/chartSpec'
import './Training.css'

interface RunRow {
  id: string
  date: string
  distance_km: number | null
  duration_minutes: number | null
  activity_type: string | null
  avg_hr: number | null
  max_hr: number | null
  rpe: number | null
  source: string | null
}

interface TrainingData {
  dates: string[]
  ctl: number[]
  atl: number[]
  tsb: number[]
  vo2maxDates: string[]
  vo2maxValues: (number | null)[]
  sessions: RunRow[]
  /** Analysis review phase G: the spans the averages use, and the published personal fit behind them. */
  spans: { ctl: number; atl: number; personal: boolean }
  spansSummary: string | null
}

// PostgREST stops at 1,000 rows (metricAdapter.ts does the same): ask for the next page until a short one comes back.
async function paged<T>(page: (from: number, to: number) => PromiseLike<{ data: unknown; error: { message: string } | null }>): Promise<{ rows: T[]; error: { message: string } | null }> {
  const PAGE = 1000
  const rows: T[] = []
  for (let from = 0; ; from += PAGE) {
    const { data, error } = await page(from, from + PAGE - 1)
    if (error) return { rows, error }
    const got = (data ?? []) as T[]
    rows.push(...got)
    if (got.length < PAGE) return { rows, error: null }
  }
}

function useTrainingData(start: string, end: string): { data: TrainingData | null; loading: boolean; error: string | null; retry: () => void } {
  const [data, setData] = useState<TrainingData | null>(null)

  const status = useLoadEffect(async (isCurrent) => {
    // Pull 90 days before start for CTL warm-up
    const warmupStart = new Date(start)
    warmupStart.setDate(warmupStart.getDate() - 90)
    const warmupStr = warmupStart.toISOString().slice(0, 10)

    // exercise_sessions replaced the old `runs` table; only endurance types feed this run-based load model
    // (4,000+ auto-detected walks would otherwise dominate the TSS proxy).
    const { from, to } = dayBounds(warmupStr, end)
    const [{ data: rows, error }, maximaRes, restingRes, ffRes] = await Promise.all([
      supabase.from('exercise_sessions')
        .select('id, start_time, distance_km, duration_min, type, avg_hr, max_hr, rpe, source')
        .in('type', ENDURANCE_TYPES)
        .gte('start_time', from).lte('start_time', to)
        .order('start_time', { ascending: true }),
      // TRIMP's anchors, as on Android: every session's maximum (all types) for the robust maximum, and the daily resting
      // heart rate, both over the same window, taken as of each session's date.
      paged<{ start_time: string; max_hr: number }>((a, b) => supabase.from('exercise_sessions').select('start_time, max_hr')
        .gt('max_hr', 0).gte('start_time', from).lte('start_time', to).order('start_time').range(a, b)),
      paged<{ date: string; rhr: number }>((a, b) => supabase.from('wearable_daily').select('date, rhr')
        .gt('rhr', 0).gte('date', warmupStr).lte('date', end).order('date').range(a, b)),
      // Analysis review phase G: the personal fitness-fatigue spans, used only when adopted (as on Android).
      supabase.from('analysis_results').select('payload, calculated_at').eq('kind', 'fitness_fatigue').eq('key', 'hrv').order('calculated_at', { ascending: false }).limit(1),
    ])
    type FfPayload = { adopted?: boolean; personal?: { ctl: number; atl: number } | null; summary?: string }
    const ff = ((ffRes.data ?? [])[0] as { payload?: FfPayload } | undefined)?.payload
    const spans = ff?.adopted && ff.personal ? { ctl: ff.personal.ctl, atl: ff.personal.atl, personal: true } : { ctl: 42, atl: 7, personal: false }
    warnOnError('session maxima', maximaRes.error)
    warnOnError('resting heart rate', restingRes.error)
    const anchorsOn = loadAnchorsByDate(
      maximaRes.rows.map((r) => [localDate(r.start_time), r.max_hr] as [string, number]),
      restingRes.rows.map((r) => [r.date, r.rhr] as [string, number]),
    )
    {
        warnOnError('training sessions', error)
        if (error) throw new Error(error.message)
        const runs: RunRow[] = (rows ?? []).map((r: Record<string, unknown>) => ({
          id: String(r.id),
          date: localDate(r.start_time as string),
          distance_km: r.distance_km as number | null,
          duration_minutes: r.duration_min as number | null,
          activity_type: r.type as string | null,
          avg_hr: r.avg_hr as number | null,
          max_hr: r.max_hr as number | null,
          rpe: r.rpe as number | null,
          source: r.source as string | null,
        }))

        // Build daily TSS map over full range (warmup + visible)
        const allDates: string[] = []
        const d = new Date(warmupStr)
        const endD = new Date(end)
        while (d <= endD) {
          allDates.push(d.toISOString().slice(0, 10))
          d.setDate(d.getDate() + 1)
        }

        const tssMap = new Map<string, number>()
        for (const r of runs) {
          const load = sessionLoad(r, anchorsOn(r.date))
          if (load !== null) tssMap.set(r.date, (tssMap.get(r.date) ?? 0) + load)
        }
        const dailyTss = allDates.map(dt => tssMap.get(dt) ?? 0)

        const ctlAll = ema(dailyTss, spans.ctl)
        const atlAll = ema(dailyTss, spans.atl)

        // Slice to visible range only
        const visibleStart = allDates.indexOf(start)
        const sliceFrom = visibleStart >= 0 ? visibleStart : 0
        const visibleDates = allDates.slice(sliceFrom)
        const ctl = ctlAll.slice(sliceFrom)
        const atl = atlAll.slice(sliceFrom)
        const tsb = ctl.map((c, i) => Math.round((c - atl[i]) * 10) / 10)

        // VO2max per session in visible range
        const visibleRuns = runs.filter(r => r.date >= start && r.date <= end)
        const vo2maxDates = visibleRuns.map(r => r.date)
        const vo2maxValues = visibleRuns.map(r =>
          vo2maxFromPace(r.distance_km ?? 0, r.duration_minutes ?? 0)
        )

        if (isCurrent()) setData({ dates: visibleDates, ctl, atl, tsb, vo2maxDates, vo2maxValues, sessions: visibleRuns.slice().reverse(), spans, spansSummary: ff?.summary ?? null })
    }
  }, [start, end])

  return { data, loading: status.loading, error: status.error, retry: status.retry }
}

function makeStressSpec(id: string, title: string, unit: string, kind: ChartSpec['kind']): ChartSpec {
  return { specVersion: 1, metricId: id, title, unit, kind, smooth: true }
}

function toMetricResult(dates: string[], values: (number | null)[], metricId: string): MetricResult {
  return {
    metricId,
    unit: null,
    points: dates.map((date, i) => ({ date, value: values[i] ?? null, source: null, confidence: null, provenance: null })),
    missingness: 'none',
    error: null,
  }
}

export function Training() {
  const range = useRange()
  const inspect = useInspect()
  const { data, loading, error, retry } = useTrainingData(range.start, range.end)

  const ctlSpec = useMemo(() => makeStressSpec('training.ctl', 'CTL', 'AU', 'area'), [])
  const atlSpec = useMemo(() => makeStressSpec('training.atl', 'ATL', 'AU', 'line'), [])
  const tsbSpec = useMemo(() => makeStressSpec('training.tsb', 'TSB', 'AU', 'bar'), [])
  const vo2Spec = useMemo(() => makeStressSpec('training.vo2max', 'VO2max est.', 'ml/kg/min', 'line'), [])

  const ctlResult  = useMemo(() => data ? toMetricResult(data.dates, data.ctl,          'training.ctl')    : null, [data])
  const atlResult  = useMemo(() => data ? toMetricResult(data.dates, data.atl,          'training.atl')    : null, [data])
  const tsbResult  = useMemo(() => data ? toMetricResult(data.dates, data.tsb,          'training.tsb')    : null, [data])
  const vo2Result  = useMemo(() => data ? toMetricResult(data.vo2maxDates, data.vo2maxValues, 'training.vo2max') : null, [data])

  if (loading) return <div className="state-loading mono">LOADING TRAINING…</div>
  if (error) return <LoadBanner error={error} onRetry={retry} />
  if (!data) return <div className="state-error mono">LOAD FAILED</div>

  return (
    <div className="training">
      <h2 className="training-title">Training</h2>

      <section className="training-section">
        <div className="training-section-label mono">STRESS BALANCE</div>
        <div className="training-stress-grid">
          <div className="card training-chart-card">
            <FTChart spec={ctlSpec} result={ctlResult!} height={140} />
          </div>
          <div className="card training-chart-card">
            <FTChart spec={atlSpec} result={atlResult!} height={140} />
          </div>
          <div className="card training-chart-card training-chart-wide">
            <FTChart spec={tsbSpec} result={tsbResult!} height={140} />
          </div>
        </div>
        <p className="training-footnote mono">
          Spans: fitness {data.spans.ctl} days, fatigue {data.spans.atl} days · {data.spans.personal ? 'yours (predictive)' : 'standard'}
        </p>
        {data.spansSummary && <p className="training-footnote">{data.spansSummary}</p>}
      </section>

      <section className="training-section">
        <div className="training-section-label mono">VO₂MAX ESTIMATE</div>
        <div className="card">
          <FTChart spec={vo2Spec} result={vo2Result!} height={160} />
        </div>
        <p className="training-footnote mono">
          Pace-derived · Daniels VDOT approximation · not a lab measurement
        </p>
      </section>

      <section className="training-section">
        <div className="training-section-label mono">SESSIONS</div>
        <div className="training-session-list">
          {data.sessions.length === 0 && (
            <div className="state-empty mono">NO SESSIONS IN RANGE</div>
          )}
          {data.sessions.map(r => {
            const pace = r.distance_km && r.duration_minutes
              ? `${(r.duration_minutes / r.distance_km).toFixed(2)} min/km`
              : null
            const vo2est = vo2maxFromPace(r.distance_km ?? 0, r.duration_minutes ?? 0)
            return (
              <button
                key={r.id}
                className="training-session-row"
                onClick={() => inspect({ kind: 'event', eventRef: { kind: 'session', id: r.id } })}
              >
                <span className="training-session-date mono">{r.date}</span>
                <span className="training-session-type">{r.activity_type ?? 'Run'}</span>
                <span className="training-session-stat mono">{r.distance_km?.toFixed(1) ?? '—'} km</span>
                <span className="training-session-stat mono">{r.duration_minutes ? `${Math.round(r.duration_minutes)} min` : '—'}</span>
                {pace && <span className="training-session-stat mono">{pace}</span>}
                {vo2est && <span className="training-session-stat mono">VO₂ ~{vo2est}</span>}
              </button>
            )
          })}
        </div>
      </section>
    </div>
  )
}

export default Training
