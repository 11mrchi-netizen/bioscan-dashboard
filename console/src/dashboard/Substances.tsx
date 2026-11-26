// CAFFEINE and ALCOHOL in the body through the day, estimated from what was logged and when (lib/substanceCurves.ts holds
// the model and its assumptions). Drinks not logged cannot be counted, so every number here is "at least this much".
import { useState } from 'react'
import { useLoadEffect } from '../lib/useLoadEffect'
import { plusDays } from '../lib/dates'
import { supabase } from '../lib/supabase'
import { metricHref } from '../lib/metrics'
import {
  alcoholSeries, amountIn, caffeineAt, caffeineBelowAt, caffeineSeries, CAFFEINE_BEDTIME_MG, CAFFEINE_HALF_LIFE_H, clockLabel,
  type Compound, COMPOUNDS, ingredientAmount, isCaffeine, DEFAULT_TMAX_H, doseQty, kaFromTmax, lookbackDays, type Qty, qtyText, quantity, compoundFor, compoundSeries, type CurvePoint, type Dose, oneCompartmentAt, usualBedtimeMin, wallParts,
} from '../lib/substanceCurves'

/** A regimen item (medication, peptide, hormone, other) with a half-life: the person's own entry, else the FDA label's. */
interface Medication { name: string; kind: string; halfLifeH: number; tmaxH: number; tmaxAssumed: boolean; source: 'own' | 'label'; base: Qty['base']; doses: Dose[] }
interface RegimenCurves { items: Medication[]; /** Items taken in the last days that have no half-life yet. */ missing: string[] }
interface SubstanceData { caffeine: Dose[]; alcohol: Dose[]; supplements: Record<string, Dose[]>; regimen: RegimenCurves; bedtime: number | null }

const dayDiff = (a: string, b: string) => Math.round((Date.parse(`${a}T00:00:00Z`) - Date.parse(`${b}T00:00:00Z`)) / 86_400_000)

/** What was logged from the evening before `day` to the end of it, as doses on the day's own clock. */
async function loadSubstances(day: string): Promise<SubstanceData> {
  // Logged times are wall clock labelled +00 (CLAUDE.md, "Dates and times"): their digits are local.
  const lo = `${plusDays(day, -1)}T00:00:00+00:00`, hi = `${plusDays(day, 1)}T00:00:00+00:00`
  const [meals, others, sleep, taken] = await Promise.all([
    supabase.from('meals').select('logged_at,meal_items(caffeine_mg,alcohol_g)').gte('logged_at', lo).lt('logged_at', hi),
    // Caffeine and alcohol that did not come in as a meal item or a supplement (gels taken in training): single-counted in nutrient_intake.
    supabase.from('nutrient_intake').select('logged_at,nutrient,amount,unit,source_type').in('nutrient', ['caffeine', 'alcohol']).not('source_type', 'in', '(meal_item,supplement)').gte('logged_at', lo).lt('logged_at', hi),
    supabase.from('sleep_daily').select('bedtime,tz_offset_min').lte('date', day).order('date', { ascending: false }).limit(14),
    // What was taken and when (supplement_log.taken_at is the phone's wall clock); the doses are in nutrient_intake, one row per nutrient.
    supabase.from('supplement_log').select('id,taken_at,product_id,serving_count').gte('taken_at', lo).lt('taken_at', hi),
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
  const supplements: Record<string, Dose[]> = {}
  type TakenRow = { id: number; taken_at: string; product_id: number | null; serving_count: number | null }
  const rows = (taken.error ? [] : taken.data ?? []) as unknown as TakenRow[]
  // What a supplement holds comes from its product's ingredient rows (a label row named "Caffeine" has no nutrient key, so
  // nutrient_intake never got it): servings taken times the amount in a serving. Logs with no product fall back to nutrient_intake.
  const productIds = [...new Set(rows.map((r) => r.product_id).filter((x): x is number => x != null))]
  if (productIds.length > 0) {
    const links = await supabase.from('supplement_product_ingredients')
      .select('supplement_product_id,compound_amount,compound_unit,elemental_amount,elemental_unit,supplement_ingredients(name,nutrient_key,canonical_key)').in('supplement_product_id', productIds)
    type Link = { supplement_product_id: number; compound_amount: number | null; compound_unit: string | null; elemental_amount: number | null; elemental_unit: string | null; supplement_ingredients: { name: string; nutrient_key: string | null; canonical_key: string | null } | null }
    const byProduct = new Map<number, Link[]>()
    for (const l of (links.error ? [] : links.data ?? []) as unknown as Link[]) byProduct.set(l.supplement_product_id, [...(byProduct.get(l.supplement_product_id) ?? []), l])
    for (const r of rows) {
      const t = at(r.taken_at)
      if (t == null || r.product_id == null) continue
      for (const l of byProduct.get(r.product_id) ?? []) {
        const ing = l.supplement_ingredients, per = ingredientAmount(l)
        if (!ing || !per) continue
        const servings = r.serving_count != null ? Number(r.serving_count) : 1
        if (isCaffeine(ing.name, ing.nutrient_key, ing.canonical_key)) {
          const mg = amountIn({ key: 'caffeine', label: '', unit: 'mg', halfLifeH: 0, kaPerH: 0 }, per.amount * servings, per.unit)
          if (mg != null && mg > 0) caffeine.push({ at: t, amount: mg })
          continue
        }
        const c = compoundFor(ing.nutrient_key ?? '') ?? compoundFor(ing.canonical_key ?? '') ?? compoundFor(ing.name)
        const a = c ? amountIn(c, per.amount * servings, per.unit) : null
        if (c && a != null && a > 0) (supplements[c.key] ??= []).push({ at: t, amount: a })
      }
    }
  }
  const noProduct = new Map(rows.filter((r) => r.product_id == null).map((r) => [r.id, r.taken_at]))
  if (noProduct.size > 0) {
    // nutrient_intake.logged_at of a supplement is not the time it was taken: the time comes from supplement_log.
    const doses = await supabase.from('nutrient_intake').select('source_id,nutrient,amount,unit').eq('source_type', 'supplement').in('source_id', [...noProduct.keys()])
    if (!doses.error) {
      for (const r of (doses.data ?? []) as { source_id: number; nutrient: string; amount: number; unit: string | null }[]) {
        const iso = noProduct.get(r.source_id), t = iso ? at(iso) : null
        if (t == null || !(r.amount > 0)) continue
        if (r.nutrient === 'caffeine') { const mg = amountIn({ key: 'caffeine', label: '', unit: 'mg', halfLifeH: 0, kaPerH: 0 }, Number(r.amount), r.unit); if (mg != null && mg > 0) caffeine.push({ at: t, amount: mg }); continue }
        const c = compoundFor(r.nutrient), a = c ? amountIn(c, Number(r.amount), r.unit) : null
        if (c && a != null && a > 0) (supplements[c.key] ??= []).push({ at: t, amount: a })
      }
    }
  }
  const bedtime = sleep.error ? null : usualBedtimeMin((sleep.data ?? []) as { bedtime: string | null; tz_offset_min: number | null }[])
  const regimen = await loadRegimen(day, at).catch((e: unknown) => { console.error('[BioScan] regimen curves:', e); return { items: [], missing: [] } as RegimenCurves })
  return { caffeine, alcohol, supplements, regimen, bedtime }
}

/** The next 1, 2 or 5 times a power of ten above `v`, so the axis tops out just over the curve. */
function niceMax(v: number): number {
  if (!(v > 0)) return 1
  const p = 10 ** Math.floor(Math.log10(v))
  return ([1, 2, 5, 10].find((m) => m * p >= v) ?? 10) * p
}
const tickText = (v: number) => (v >= 10 || Number.isInteger(v) ? String(Math.round(v)) : v.toFixed(1))

/**
 * Active regimen items that are not supplements (medications, peptides, hormones, other) with a half-life: the person's own entry
 * (substance_kinetics) wins over the FDA label's (regimen_references). Each item's doses back to five half-lives; a dose logged
 * in tablets is the servings taken times what a serving holds (the person's own amount, else the product's ingredient row).
 */
async function loadRegimen(day: string, at: (iso: string) => number | null): Promise<RegimenCurves> {
  const meds = await supabase.from('supplements').select('id,name,kind,product_id').neq('kind', 'supplement').eq('status', 'active')
  if (meds.error || !meds.data?.length) return { items: [], missing: [] }
  const rows = meds.data as { id: number; name: string; kind: string; product_id: number | null }[]
  const ids = rows.map((r) => r.id)
  const [refs, own] = await Promise.all([
    supabase.from('regimen_references').select('supplement_id,half_life_hours').in('supplement_id', ids).not('half_life_hours', 'is', null),
    supabase.from('substance_kinetics').select('supplement_id,half_life_hours,tmax_hours,amount_per_serving,amount_unit').in('supplement_id', ids),
  ])
  const label = new Map<number, number>()
  for (const r of (refs.error ? [] : refs.data ?? []) as { supplement_id: number; half_life_hours: number }[]) if (r.half_life_hours > 0) label.set(r.supplement_id, Number(r.half_life_hours))
  type Own = { supplement_id: number; half_life_hours: number; tmax_hours: number | null; amount_per_serving: number | null; amount_unit: string | null }
  const mine = new Map(((own.error ? [] : own.data ?? []) as Own[]).map((r) => [r.supplement_id, r]))
  const products = rows.map((r) => r.product_id).filter((x): x is number => x != null)
  const links = products.length ? await supabase.from('supplement_product_ingredients').select('supplement_product_id,compound_amount,compound_unit,supplement_ingredients(name)').in('supplement_product_id', products) : null
  type Link = { supplement_product_id: number; compound_amount: number | null; compound_unit: string | null; supplement_ingredients: { name: string } | null }
  const items: Medication[] = [], missing: string[] = []
  for (const m of rows) {
    const o = mine.get(m.id)
    const hl = o ? Number(o.half_life_hours) : label.get(m.id)
    const since = `${plusDays(day, -lookbackDays(hl ?? 24))}T00:00:00+00:00`, hi = `${plusDays(day, 1)}T00:00:00+00:00`
    const logs = await supabase.from('supplement_log').select('taken_at,dose_value,dose_unit,serving_count').eq('supplement_id', m.id).gte('taken_at', since).lt('taken_at', hi)
    if (logs.error || !logs.data?.length) continue
    if (hl == null) { missing.push(m.name); continue }
    // What a serving holds: the person's own entry, else the product's ingredient named like the item (or its only ingredient).
    const ing = ((links?.error ? [] : links?.data ?? []) as unknown as Link[]).filter((l) => l.supplement_product_id === m.product_id)
    const named = ing.find((l) => (l.supplement_ingredients?.name ?? '').toLowerCase().includes(m.name.toLowerCase())) ?? (ing.length === 1 ? ing[0] : null)
    const perServing: Qty | null = o?.amount_per_serving != null ? quantity(Number(o.amount_per_serving), o.amount_unit)
      : named?.compound_amount != null ? quantity(Number(named.compound_amount), named.compound_unit) : null
    const qtys = (logs.data as { taken_at: string; dose_value: number | null; dose_unit: string | null; serving_count: number | null }[]).flatMap((l) => {
      const t = at(l.taken_at), q = doseQty(l, perServing)
      return t != null && q && q.v > 0 ? [{ t, q }] : []
    })
    if (qtys.length === 0) { missing.push(m.name); continue }
    const base = qtys[0].q.base // one unit per item: a dose logged in another family is left out
    const doses = qtys.filter((x) => x.q.base === base).map((x) => ({ at: x.t, amount: x.q.v }))
    items.push({ name: m.name, kind: m.kind, halfLifeH: hl, tmaxH: o?.tmax_hours != null ? Number(o.tmax_hours) : DEFAULT_TMAX_H, tmaxAssumed: o?.tmax_hours == null, source: o ? 'own' : 'label', base, doses })
  }
  return { items, missing }
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
      {[0, 0.5, 1].map((f) => <text key={f} x={PAD.l - 5} y={y(yMax * f) + 3} textAnchor="end" className="subst-tick">{tickText(yMax * f)}</text>)}
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

function CompoundRow({ c, doses, now, bed, end }: { c: Compound; doses: Dose[]; now: number | null; bed: number | null; end: number }) {
  const series = compoundSeries(c, doses, 0, end)
  const top = Math.max(...series.map((p) => p.v), 0)
  const nowV = now != null ? oneCompartmentAt(doses, now, c.halfLifeH, c.kaPerH) : null
  const taken = doses.filter((d) => d.at >= 0)
  const fmt = (v: number) => (c.unit === 'g' ? v.toFixed(1) : String(Math.round(v)))
  return (
    <>
      <div className="today-strip-head">
        <span style={{ color: 'var(--m-supplement)' }}>{c.label} IN THE BODY</span>
        <span>{taken.map((d) => `${fmt(d.amount)} ${c.unit} at ${clockLabel(d.at)}`).join(' · ')}</span>
      </div>
      {nowV != null && <div className="subst-head"><span><b>{fmt(nowV)}</b> {c.unit} now</span></div>}
      <Curve pts={series} color="var(--m-supplement)" yMax={niceMax(top)} now={now} bedtime={bed} line={null} label={`${c.label.toLowerCase()} in the body, ${c.unit}, through the day`} />
    </>
  )
}

function MedicationRow({ m, now, bed, end }: { m: Medication; now: number | null; bed: number | null; end: number }) {
  const ka = kaFromTmax(m.halfLifeH, m.tmaxH)
  const series: CurvePoint[] = []
  for (let t = 0; t <= end; t += 5) series.push({ t, v: oneCompartmentAt(m.doses, t, m.halfLifeH, ka) })
  const top = Math.max(...series.map((p) => p.v), 0)
  const nowV = now != null ? oneCompartmentAt(m.doses, now, m.halfLifeH, ka) : null
  const taken = m.doses.filter((d) => d.at >= 0)
  // Milligrams under 1 mg read as micrograms: one scale for the axis and the numbers.
  const scale = m.base === 'mg' && top < 1 ? 1000 : 1, unit = m.base === 'mg' && scale === 1000 ? 'mcg' : m.base
  const num = (v: number) => qtyText(v, m.base)
  return (
    <>
      <div className="today-strip-head">
        <span style={{ color: 'var(--m-supplement)' }}>{m.name.toUpperCase()} IN THE BODY</span>
        <span>{taken.length ? taken.map((d) => `${num(d.amount)} at ${clockLabel(d.at)}`).join(' · ') : 'carried over'}</span>
      </div>
      {nowV != null && <div className="subst-head"><span><b>{num(nowV).split(' ')[0]}</b> {unit} now</span></div>}
      <Curve pts={series.map((p) => ({ t: p.t, v: p.v * scale }))} color="var(--m-supplement)" yMax={niceMax(top * scale)} now={now} bedtime={bed} line={null} label={`${m.name} in the body, ${unit}, through the day`} />
    </>
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
  const shownCompounds = COMPOUNDS.filter((c) => (data.supplements[c.key] ?? []).some((d) => d.at >= 0))
  const meds = data.regimen.items.filter((m) => m.doses.some((d) => d.at >= 0) || oneCompartmentAt(m.doses, 0, m.halfLifeH, kaFromTmax(m.halfLifeH, m.tmaxH)) >= Math.max(...m.doses.map((d) => d.amount)) * 0.02)
  if (today.length === 0 && todayAlc.length === 0 && carry < 1 && carryAlc < 0.1 && shownCompounds.length === 0 && meds.length === 0 && data.regimen.missing.length === 0) return null

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
      {shownCompounds.map((c) => <CompoundRow key={c.key} c={c} doses={data.supplements[c.key]} now={now} bed={data.bedtime} end={end} />)}
      {data.regimen.missing.length > 0 && (
        <p className="today-hint">Taken but no curve yet, because no half-life is known: {data.regimen.missing.join(', ')}. Add one on the phone, in the item's reference card.</p>
      )}
      {meds.map((m) => <MedicationRow key={m.name} m={m} now={now} bed={data.bedtime} end={end} />)}
      <p className="today-hint">
        Estimated from logged drinks, so unlogged ones are missing. Caffeine: half-life {CAFFEINE_HALF_LIFE_H} h (people range 3 to 7 h), peak about 50 min after a dose; the dashed line is {CAFFEINE_BEDTIME_MG} mg, a working line for sleep.
        {meds.length > 0 ? `Regimen: ${meds.map((m) => `${m.name} half-life ${m.halfLifeH} h (${m.source === 'own' ? 'your entry' : 'FDA label'}), peak ${m.tmaxAssumed ? 'assumed ' : ''}${m.tmaxH} h`).join('; ')}; earlier days' doses are carried over. ` : ''}
        Alcohol: cleared at about 7 g an hour. Supplements: only the fast ones (melatonin, glycine, theanine, tyrosine) have a curve, with rough half-lives; magnesium, zinc, vitamin D, omega-3 and creatine do not move within a day. {isToday ? 'The dashed part is the projection from what is logged so far. ' : ''}
        <a className="dash-link" href={metricHref('fuel.late_caffeine', { day })}>CAFFEINE HISTORY →</a>
      </p>
    </section>
  )
}
