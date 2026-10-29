// Body workspace: what the scale's impedance says beyond weight (docs/body-composition/README.md). Reads scale_readings, the
// profile and body_metrics straight from Supabase (row-level security), derives the composition with the same equations as the
// Android app (supabase/functions/_shared/body-composition) and draws it: scale body fat against the Health Connect value,
// fat-free mass, the model-free impedance index, body water, impedance against weight, the time-of-day effect, how the last
// 30 and 90 days of weight change split into fat and lean, and every reading the quality gate refused.

import { useEffect, useMemo, useRef, useState } from 'react'
import { LoadBanner } from '../components/shared/LoadBanner'
import { supabase } from '../lib/supabase'
import { warnOnError } from '../lib/schema'
import { useChart } from '../lib/useChart'
import { useLoadEffect } from '../lib/useLoadEffect'
import { PALETTE } from '../lib/palette'
import type { EChartsCoreOption } from '../lib/echarts'
import {
  STATUS_LABEL, buildPoints, dailyLast, goodWeighIns, impedanceIndexByTimeOfDay,
  type ProfileRow, type ScaleRow,
} from '../lib/bodyCompositionSeries'
import { healthyWeightRangeKg, weightChangeSplit } from '../../../supabase/functions/_shared/body-composition/bodyComposition.ts'
import './Body.css'

interface MetricRow {
  date: string
  body_fat_pct: number | null
  height_cm: number | null
  source: string | null
}

const BASE_AXES = {
  grid: { left: 48, right: 16, top: 24, bottom: 28 },
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'time', axisLabel: { color: PALETTE.textMuted }, axisLine: { lineStyle: { color: PALETTE.border } } },
} as const

function yAxis(name: string): EChartsCoreOption {
  return { type: 'value', name, scale: true, nameTextStyle: { color: PALETTE.textMuted }, axisLabel: { color: PALETTE.textMuted }, splitLine: { lineStyle: { color: PALETTE.border } } }
}

function lineSeries(name: string, data: Array<[string, number]>, color: string, extra: object = {}): EChartsCoreOption {
  return { name, type: 'line', data, showSymbol: true, symbolSize: 5, itemStyle: { color }, lineStyle: { color, width: 2 }, ...extra }
}

function Panel({ title, note, option, empty }: { title: string; note: string; option: EChartsCoreOption; empty?: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  const chart = useChart(ref)
  useEffect(() => { chart.current?.setOption(option, true) }, [chart, option])
  return (
    <section className="body-panel">
      <h3 className="body-panel-title mono">{title}</h3>
      <div ref={ref} className="body-chart" style={empty ? { display: 'none' } : undefined} />
      {empty && <p className="body-empty">Not enough readings yet.</p>}
      <p className="body-note">{note}</p>
    </section>
  )
}

export function Body() {
  const [scale, setScale] = useState<ScaleRow[]>([])
  const [profile, setProfile] = useState<ProfileRow | null>(null)
  const [metrics, setMetrics] = useState<MetricRow[]>([])

  const status = useLoadEffect(async (isCurrent) => {
    const [scaleRes, metricsRes] = await Promise.all([
      supabase.from('scale_readings').select('occurred_at, weight_kg, impedance_ohm').order('occurred_at', { ascending: true }),
      supabase.from('body_metrics').select('date, body_fat_pct, height_cm, source').order('date', { ascending: true }),
    ])
    if (scaleRes.error) throw new Error(`scale_readings: ${scaleRes.error.message}`)
    warnOnError('body_metrics', metricsRes.error)
    // height_cm is a newer column: if the migration has not reached this database, fall back to date of birth and sex only.
    let profileRes = await supabase.from('user_profile').select('date_of_birth, sex, height_cm').maybeSingle()
    if (profileRes.error) profileRes = await supabase.from('user_profile').select('date_of_birth, sex').maybeSingle() as typeof profileRes
    warnOnError('user_profile', profileRes.error)
    if (!isCurrent()) return
    setScale((scaleRes.data ?? []) as unknown as ScaleRow[])
    setMetrics((metricsRes.data ?? []) as unknown as MetricRow[])
    setProfile((profileRes.data ?? null) as unknown as ProfileRow | null)
  }, [])

  const heightFallback = useMemo(() => [...metrics].reverse().find((m) => m.height_cm != null)?.height_cm ?? null, [metrics])
  const points = useMemo(() => buildPoints(scale, profile, heightFallback), [scale, profile, heightFallback])
  const good = useMemo(() => points.filter((p) => p.status === 'ok'), [points])
  const latest = good[good.length - 1]
  const heightCm = profile?.height_cm ?? heightFallback

  const charts = useMemo(() => {
    const scaleFat = dailyLast(good, (p) => p.composition!.split!.bodyFatPct)
    const hcFat: Array<[string, number]> = metrics.filter((m) => m.body_fat_pct != null && m.source !== 'scale_bia').map((m) => [m.date, m.body_fat_pct!])
    const compare: EChartsCoreOption = {
      ...BASE_AXES, yAxis: yAxis('%'), legend: { top: 0, textStyle: { color: PALETTE.text } },
      series: [lineSeries('Scale (Sun 2003)', scaleFat, PALETTE.emerald), lineSeries('Health Connect', hcFat, PALETTE.amber)],
    }
    const single = (name: string, unit: string, data: Array<[string, number]>, color: string): EChartsCoreOption => ({
      ...BASE_AXES, yAxis: yAxis(unit), series: [lineSeries(name, data, color)],
    })
    const ffm = dailyLast(good, (p) => p.composition!.split!.fatFreeKg)
    const index = dailyLast(points, (p) => p.composition?.impedanceIndex ?? null)
    const water = dailyLast(good, (p) => (p.composition!.totalBodyWaterL! / p.weightKg) * 100)
    const scatter: EChartsCoreOption = {
      grid: BASE_AXES.grid, tooltip: { trigger: 'item' }, legend: { top: 0, textStyle: { color: PALETTE.text } },
      xAxis: { type: 'value', name: 'kg', scale: true, nameTextStyle: { color: PALETTE.textMuted }, axisLabel: { color: PALETTE.textMuted }, splitLine: { lineStyle: { color: PALETTE.border } } },
      yAxis: yAxis('Ω'),
      series: [
        { name: 'Used', type: 'scatter', itemStyle: { color: PALETTE.emerald }, data: good.map((p) => [p.weightKg, p.impedanceOhm]) },
        { name: 'Refused', type: 'scatter', itemStyle: { color: PALETTE.critical }, data: points.filter((p) => p.status === 'rejected').map((p) => [p.weightKg, p.impedanceOhm]) },
      ],
    }
    const tod = impedanceIndexByTimeOfDay(points)
    const byHour: EChartsCoreOption = {
      grid: BASE_AXES.grid, tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: tod.map((b) => `${b.label} (n=${b.n})`), axisLabel: { color: PALETTE.textMuted } },
      yAxis: yAxis('cm²/Ω'), series: [{ type: 'bar', itemStyle: { color: PALETTE.emerald }, data: tod.map((b) => b.mean) }],
    }
    return { compare, ffm: single('Fat-free mass', 'kg', ffm, PALETTE.emerald), index: single('Impedance index', 'cm²/Ω', index, PALETTE.emerald), water: single('Body water', '%', water, PALETTE.emerald), scatter, byHour, counts: { scaleFat, ffm, index, water, tod } }
  }, [good, points, metrics])

  const [today] = useState(() => new Date().toLocaleDateString('sv-SE')) // local YYYY-MM-DD, fixed for this visit
  const changes = useMemo(() => [30, 90].map((d) => ({ days: d, split: weightChangeSplit(goodWeighIns(points), d, today) })), [points, today])
  const refused = points.filter((p) => p.status !== 'ok')

  if (status.loading) return <div className="state-loading">LOADING…</div>
  if (status.error) return <LoadBanner error={status.error} onRetry={status.retry} />

  return (
    <div className="body-ws">
      <header className="body-head">
        <h2 className="mono">BODY COMPOSITION (SCALE)</h2>
        <p className="body-note">
          Estimated from the scale's impedance (Sun 2003 for fat-free mass and water, Janssen 2000 for muscle). The level carries the
          scale's bias; follow the trend. {points.length} weigh-ins, {good.length} used.
        </p>
      </header>

      {latest ? (
        <div className="body-tiles">
          <Tile label="Body fat" value={`${latest.composition!.split!.bodyFatPct.toFixed(1)} %`} />
          <Tile label="Fat-free mass" value={`${latest.composition!.split!.fatFreeKg.toFixed(1)} kg`} />
          <Tile label="Fat mass" value={`${latest.composition!.split!.fatKg.toFixed(1)} kg`} />
          <Tile label="Skeletal muscle" value={latest.composition!.skeletalMuscleKg != null ? `~${latest.composition!.skeletalMuscleKg.toFixed(1)} kg` : '—'} />
          <Tile label="Body water" value={`${latest.composition!.totalBodyWaterL!.toFixed(1)} L`} />
          <Tile label="BMI" value={latest.composition!.bmi != null ? latest.composition!.bmi.toFixed(1) : '—'} />
          <Tile label="Resting energy" value={latest.composition!.bmrKcal != null ? `${latest.composition!.bmrKcal.toFixed(0)} kcal` : '—'} />
          <Tile label="BMI 18.5–25" value={heightCm != null && healthyWeightRangeKg(heightCm) ? `${healthyWeightRangeKg(heightCm)!.low.toFixed(0)}–${healthyWeightRangeKg(heightCm)!.high.toFixed(0)} kg` : '—'} />
        </div>
      ) : (
        <p className="body-empty">No usable weigh-in yet. Height, date of birth and sex must be in the profile (Setup › Profile in the app).</p>
      )}

      <div className="body-grid">
        <Panel title="BODY FAT: SCALE VS HEALTH CONNECT" empty={charts.counts.scaleFat.length < 2}
          note="Two methods, a few points apart for one person: compare the direction, not the gap." option={charts.compare} />
        <Panel title="FAT-FREE MASS" empty={charts.counts.ffm.length < 2}
          note="Lean tissue plus water. Moves with hydration as well as muscle." option={charts.ffm} />
        <Panel title="IMPEDANCE INDEX (HEIGHT² ÷ IMPEDANCE)" empty={charts.counts.index.length < 2}
          note="No population equation involved, so no model error: it rises with conductive (water-rich, lean) tissue and falls when dehydrated." option={charts.index} />
        <Panel title="BODY WATER, % OF WEIGHT" empty={charts.counts.water.length < 2}
          note="Mostly a hydration signal. A one-day swing is water, not tissue." option={charts.water} />
        <Panel title="IMPEDANCE VS WEIGHT" empty={points.length < 2}
          note="Heavier readings normally sit at lower impedance. Points far off the cloud are contact or posture glitches." option={charts.scatter} />
        <Panel title="TIME OF DAY" empty={charts.counts.tod.length < 2}
          note="Mean impedance index by 3-hour bucket. Large differences mean the same body reads differently across the day: weigh at one time." option={charts.byHour} />
      </div>

      <section className="body-panel">
        <h3 className="body-panel-title mono">WEIGHT CHANGE: FAT OR LEAN</h3>
        <table className="body-table mono">
          <thead><tr><th>Window</th><th>Weight</th><th>Fat</th><th>Fat-free</th></tr></thead>
          <tbody>
            {changes.map(({ days, split }) => (
              <tr key={days}>
                <td>Last {days} days</td>
                {split
                  ? <><td>{split.deltaWeightKg.toFixed(1)} kg</td><td>{split.deltaFatKg.toFixed(1)} kg</td><td>{split.deltaFatFreeKg.toFixed(1)} kg</td></>
                  : <td colSpan={3} className="body-empty">needs 4+ good weigh-ins across at least half the window</td>}
              </tr>
            ))}
          </tbody>
        </table>
        <p className="body-note">Compares the first two and last two good readings in each window. A few tenths of a kilo is within the scale's noise.</p>
      </section>

      <section className="body-panel">
        <h3 className="body-panel-title mono">READINGS NOT USED ({refused.length})</h3>
        {refused.length === 0 ? <p className="body-empty">None.</p> : (
          <table className="body-table mono">
            <thead><tr><th>When</th><th>Weight</th><th>Impedance</th><th>Why</th></tr></thead>
            <tbody>
              {refused.slice(-30).reverse().map((p, i) => (
                <tr key={i}><td>{p.date} {String(p.hour).padStart(2, '0')}h</td><td>{p.weightKg.toFixed(1)} kg</td><td>{p.impedanceOhm ?? '—'}</td><td>{STATUS_LABEL[p.status]}</td></tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}

function Tile({ label, value }: { label: string; value: string }) {
  return <div className="body-tile"><div className="body-tile-label mono">{label}</div><div className="body-tile-value mono">{value}</div></div>
}

export default Body
