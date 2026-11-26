// CAFFEINE and ALCOHOL in the body through the day, estimated from what was logged and when (lib/substanceCurves.ts holds
// the model and its assumptions). Drinks not logged cannot be counted, so every number here is "at least this much".
import { useState } from 'react'
import { useLoadEffect } from '../lib/useLoadEffect'
import { plusDays } from '../lib/dates'
import { supabase } from '../lib/supabase'
import { metricHref } from '../lib/metrics'
import {
  alcoholSeries, caffeineAt, caffeineBelowAt, caffeineSeries, CAFFEINE_BEDTIME_MG, CAFFEINE_HALF_LIFE_H, clockLabel,
  type CurvePoint, type Dose, usualBedtimeMin, wallParts,
} from '../lib/substanceCurves'

interface SubstanceData { caffeine: Dose[]; alcohol: Dose[]; bedtime: number | null }

const dayDiff = (a: string, b: string) => Math.round((Date.parse(`${a}T00:00:00Z`) - Date.parse(`${b}T00:00:00Z`)) / 86_400_000)

/** What was logged from the evening before `day` to the end of it, as doses on the day's own clock. */
async function loadSubstances(day: string): Promise<SubstanceData> {
  // Logged times are wall clock labelled +00 (CLAUDE.md, "Dates and times"): their digits are local.
  const lo = `${plusDays(day, -1)}T00:00:00+00:00`, hi = `${plusDays(day, 1)}T00:00:00+00:00`
  const [meals, others, sleep] = await Promise.all([
    supabase.from('meals').select('logged_at,meal_items(caffeine_mg,alcohol_g)').gte('logged_at', lo).lt('logged_at', hi),
    // Caffeine and alcohol that did not come in as a meal item (gels and supplements taken in training): single-counted in nutrient_intake.
    supabase.from('nutrient_intake').select('logged_at,nutrient,amount,unit,source_type').in('nutrient', ['caffeine', 'alcohol']).neq('source_type', 'meal_item').gte('logged_at', lo).lt('logged_at', hi),
    supabase.from('sleep_daily').select('bedtime,tz_offset_min').lte('date', day).order('date', { ascending: false }).limit(14),
  ])
  if (meals.error) throw new Error(`meals: ${meals.error.message}`)
  const at = (iso: string) => { const p = wallParts(iso); return p ? dayDiff(p.day, day) * 1440 + p.min : null }
  const caffeine: Dose[] = [], alcohol: Dose[] = []
  type MealRow = { logged_at: string; meal_items: { caffeine_mg: number | null; alcohol_g: number | null }[] | null }
  for (const m of (meals.data ?? []) as unknown as MealRow[]) {
    const t = at(m.logged_at)
    if (t == null) continue
    for (const it of m.meal_items ?? []) {
      if (it.caffeine_mg && it.caffeine_mg > 0) caffeine.push({ at: t, amount: Number(it.caffeine_mg) })
      if (it.alcohol_g && it.alcohol_g > 0) alcohol.push({ at: t, amount: Number(it.alcohol_g) })
    }
  }
  if (!others.error) {
    for (const r of (others.data ?? []) as { logged_at: string; nutrient: string; amount: number; unit: string | null }[]) {
      const t = at(r.logged_at)
      if (t == null || !(r.amount > 0)) continue
      if (r.nutrient === 'caffeine') caffeine.push({ at: t, amount: Number(r.amount) * (r.unit === 'g' ? 1000 : 1) })
      else if (r.nutrient === 'alcohol' && (r.unit == null || r.unit === 'g')) alcohol.push({ at: t, amount: Number(r.amount) })
    }
  }
  const bedtime = sleep.error ? null : usualBedtimeMin((sleep.data ?? []) as { bedtime: string | null; tz_offset_min: number | null }[])
  return { caffeine, alcohol, bedtime }
}

const W = 560, H = 150, PAD = { l: 34, r: 8, t: 10, b: 22 }

/** One filled curve over the day's clock, with a dashed bedtime line and a solid "now" line. */
function Curve({ pts, color, yMax, now, bedtime, line, label }: {
  pts: CurvePoint[]; color: string; yMax: number; now: number | null; bedtime: number | null; line: number | null; label: string
}) {
  const t0 = 0, t1 = pts[pts.length - 1]?.t ?? 1440
  const x = (t: number) => PAD.l + ((t - t0) / (t1 - t0)) * (W - PAD.l - PAD.r)
  const y = (v: number) => PAD.t + (1 - Math.min(v, yMax) / yMax) * (H - PAD.t - PAD.b)
  const split = now != null ? pts.filter((p) => p.t <= now) : pts
  const ahead = now != null ? pts.filter((p) => p.t >= now) : []
  const d = (ps: CurvePoint[]) => ps.map((p, i) => `${i ? 'L' : 'M'}${x(p.t).toFixed(1)} ${y(p.v).toFixed(1)}`).join('')
  const area = split.length ? `${d(split)}L${x(split[split.length - 1].t).toFixed(1)} ${y(0)}L${x(split[0].t).toFixed(1)} ${y(0)}Z` : ''
  const ticks = [0, 6, 12, 18, 24].map((h) => h * 60).filter((m) => m <= t1)
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="subst-chart" role="img" aria-label={label}>
      {[0, 0.5, 1].map((f) => <line key={f} x1={PAD.l} x2={W - PAD.r} y1={y(yMax * f)} y2={y(yMax * f)} stroke="var(--ft-line)" strokeWidth="1" />)}
      {[0, 0.5, 1].map((f) => <text key={f} x={PAD.l - 5} y={y(yMax * f) + 3} textAnchor="end" className="subst-tick">{Math.round(yMax * f)}</text>)}
      {ticks.map((m) => <text key={m} x={x(m)} y={H - 6} textAnchor="middle" className="subst-tick">{clockLabel(m)}</text>)}
      {line != null && <line x1={PAD.l} x2={W - PAD.r} y1={y(line)} y2={y(line)} stroke="var(--ft-muted)" strokeDasharray="3 3" strokeWidth="1" />}
      {area && <path d={area} fill={color} opacity=".22" />}
      <path d={d(split)} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" />
      {ahead.length > 1 && <path d={d(ahead)} fill="none" stroke={color} strokeWidth="2" strokeDasharray="4 4" opacity=".7" />}
      {bedtime != null && bedtime <= t1 && (
        <g>
          <line x1={x(bedtime)} x2={x(bedtime)} y1={PAD.t} y2={H - PAD.b} stroke="var(--ft-muted)" strokeDasharray="2 3" />
          <text x={x(bedtime) - 4} y={PAD.t + 9} textAnchor="end" className="subst-tick">BED {clockLabel(bedtime)}</text>
        </g>
      )}
      {now != null && <line x1={x(now)} x2={x(now)} y1={PAD.t} y2={H - PAD.b} stroke="var(--ft-fg)" strokeWidth="1.5" />}
    </svg>
  )
}

export function SubstanceCard({ day, isToday, nowMin }: { day: string; isToday: boolean; nowMin: number | null }) {
  const [data, setData] = useState<SubstanceData | null>(null)
  useLoadEffect(async (cur) => {
    const d = await loadSubstances(day)
    if (cur()) setData(d)
  }, [day, nowMin])
  if (!data) return null
  const today = data.caffeine.filter((d) => d.at >= 0)
  const todayAlc = data.alcohol.filter((d) => d.at >= 0)
  // A day with nothing logged and nothing left over from the evening before has no curve to draw.
  const carry = caffeineAt(data.caffeine, 0), carryAlc = alcoholSeries(data.alcohol, 0, 0)[0]?.v ?? 0
  if (today.length === 0 && todayAlc.length === 0 && carry < 1 && carryAlc < 0.1) return null

  const bed = data.bedtime ?? 24 * 60
  const end = Math.max(24 * 60, bed + 60)
  const now = isToday ? nowMin : null
  const cSeries = caffeineSeries(data.caffeine, 0, end)
  const cPeak = Math.max(...cSeries.map((p) => p.v), CAFFEINE_BEDTIME_MG * 2)
  const cNow = now != null ? caffeineAt(data.caffeine, now) : null
  const cBed = caffeineAt(data.caffeine, bed)
  const settle = caffeineBelowAt(data.caffeine, CAFFEINE_BEDTIME_MG, now ?? 0)
  const total = today.reduce((a, d) => a + d.amount, 0)
  const aSeries = alcoholSeries(data.alcohol, 0, end)
  const aPeak = Math.max(...aSeries.map((p) => p.v), 0)
  const aTotal = todayAlc.reduce((a, d) => a + d.amount, 0)

  return (
    <section className="ft-card today-plan today-subst" aria-label="Substances in the body">
      {(today.length > 0 || carry >= 1) && (
        <>
          <div className="today-strip-head"><span style={{ color: 'var(--m-caffeine)' }}>CAFFEINE IN THE BODY</span><span>{total > 0 ? `${Math.round(total)} mg logged` : 'carried over'}</span></div>
          <div className="subst-head">
            {cNow != null && <span><b>{Math.round(cNow)}</b> mg now</span>}
            <span><b className={cBed > CAFFEINE_BEDTIME_MG ? 'subst-warn' : ''}>{Math.round(cBed)}</b> mg at {data.bedtime != null ? `usual bedtime ${clockLabel(bed)}` : 'midnight'}</span>
            {settle != null && cBed > CAFFEINE_BEDTIME_MG && <span>under {CAFFEINE_BEDTIME_MG} mg by {clockLabel(settle)}</span>}
          </div>
          <Curve pts={cSeries} color="var(--m-caffeine)" yMax={Math.ceil(cPeak / 50) * 50} now={now} bedtime={data.bedtime} line={CAFFEINE_BEDTIME_MG} label="Caffeine in the body, mg, through the day" />
        </>
      )}
      {(todayAlc.length > 0 || carryAlc >= 0.1) && (
        <>
          <div className="today-strip-head"><span style={{ color: 'var(--m-alcohol)' }}>ALCOHOL IN THE BODY</span><span>{aTotal > 0 ? `${Math.round(aTotal)} g logged` : 'carried over'}</span></div>
          <Curve pts={aSeries} color="var(--m-alcohol)" yMax={Math.max(10, Math.ceil(aPeak / 10) * 10)} now={now} bedtime={data.bedtime} line={null} label="Alcohol in the body, g, through the day" />
        </>
      )}
      <p className="today-hint">
        Estimated from logged drinks, so unlogged ones are missing. Caffeine: half-life {CAFFEINE_HALF_LIFE_H} h (people range 3 to 7 h), peak about 50 min after a dose; the dashed line is {CAFFEINE_BEDTIME_MG} mg, a working line for sleep.
        Alcohol: cleared at about 7 g an hour. {isToday ? 'The dashed part is the projection from what is logged so far. ' : ''}
        <a className="dash-link" href={metricHref('fuel.late_caffeine', { day })}>CAFFEINE HISTORY →</a>
      </p>
    </section>
  )
}
