// DAV-314: Training workspace — stress balance, VO2max trend, session list.
// CTL/ATL/TSB computed from runs using duration-based TSS proxy.
// VO2max estimated from best recent pace via Daniels VDOT.

import { useEffect, useState, useMemo } from 'react'
import { supabase } from '../lib/supabase'
import { useAnalysis } from '../context/AnalysisContext'
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
  source: string | null
}

// Simple TSS proxy: (duration_hours × rpe_factor) × 100
// rpe_factor from activity_type: run=1.0, interval/track=1.3, race=1.5, easy=0.7
function tss(run: RunRow): number {
  const hours = (run.duration_minutes ?? 0) / 60
  const factorMap: Record<string, number> = {
    interval: 1.3, track: 1.3, race: 1.5, tempo: 1.2, easy: 0.7
  }
  const type = (run.activity_type ?? '').toLowerCase()
  const factor = Object.entries(factorMap).find(([k]) => type.includes(k))?.[1] ?? 1.0
  return hours * factor * 100
}

// Daniels VDOT VO2max estimate from pace (km/h)
function vo2maxFromPace(distKm: number, durMin: number): number | null {
  if (!distKm || !durMin) return null
  const velKmh = distKm / (durMin / 60)
  // Simplified: VO2max ≈ -4.60 + 0.182258*v + 0.000104*v^2  (Daniels)
  return Math.round((-4.60 + 0.182258 * velKmh + 0.000104 * velKmh * velKmh) * 10) / 10
}

// Exponential moving average
function ema(values: number[], halfLifeDays: number): number[] {
  const k = 1 - Math.exp(-Math.LN2 / halfLifeDays)
  let acc = 0
  return values.map(v => { acc = acc + k * (v - acc); return Math.round(acc * 10) / 10 })
}

interface TrainingData {
  dates: string[]
  ctl: number[]
  atl: number[]
  tsb: number[]
  vo2maxDates: string[]
  vo2maxValues: (number | null)[]
  sessions: RunRow[]
}

function useTrainingData(start: string, end: string): { data: TrainingData | null; loading: boolean } {
  const [data, setData] = useState<TrainingData | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    // Pull 90 days before start for CTL warm-up
    const warmupStart = new Date(start)
    warmupStart.setDate(warmupStart.getDate() - 90)
    const warmupStr = warmupStart.toISOString().slice(0, 10)

    supabase.from('runs')
      .select('id, date, distance_km, duration_minutes, activity_type, avg_hr, source')
      .gte('date', warmupStr).lte('date', end)
      .order('date', { ascending: true })
      .then(({ data: rows }) => {
        const runs = (rows ?? []) as RunRow[]

        // Build daily TSS map over full range (warmup + visible)
        const allDates: string[] = []
        const d = new Date(warmupStr)
        const endD = new Date(end)
        while (d <= endD) {
          allDates.push(d.toISOString().slice(0, 10))
          d.setDate(d.getDate() + 1)
        }

        const tssMap = new Map<string, number>()
        for (const r of runs) tssMap.set(r.date, (tssMap.get(r.date) ?? 0) + tss(r))
        const dailyTss = allDates.map(dt => tssMap.get(dt) ?? 0)

        const ctlAll = ema(dailyTss, 42)
        const atlAll = ema(dailyTss, 7)

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

        setData({ dates: visibleDates, ctl, atl, tsb, vo2maxDates, vo2maxValues, sessions: visibleRuns.slice().reverse() })
        setLoading(false)
      })
  }, [start, end])

  return { data, loading }
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
  const { ctx, inspect } = useAnalysis()
  const { data, loading } = useTrainingData(ctx.primaryRange.start, ctx.primaryRange.end)

  const ctlSpec = useMemo(() => makeStressSpec('training.ctl', 'CTL', 'AU', 'area'), [])
  const atlSpec = useMemo(() => makeStressSpec('training.atl', 'ATL', 'AU', 'line'), [])
  const tsbSpec = useMemo(() => makeStressSpec('training.tsb', 'TSB', 'AU', 'bar'), [])
  const vo2Spec = useMemo(() => makeStressSpec('training.vo2max', 'VO2max est.', 'ml/kg/min', 'line'), [])

  const ctlResult  = useMemo(() => data ? toMetricResult(data.dates, data.ctl,          'training.ctl')    : null, [data])
  const atlResult  = useMemo(() => data ? toMetricResult(data.dates, data.atl,          'training.atl')    : null, [data])
  const tsbResult  = useMemo(() => data ? toMetricResult(data.dates, data.tsb,          'training.tsb')    : null, [data])
  const vo2Result  = useMemo(() => data ? toMetricResult(data.vo2maxDates, data.vo2maxValues, 'training.vo2max') : null, [data])

  if (loading) return <div className="state-loading mono">LOADING TRAINING…</div>
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
