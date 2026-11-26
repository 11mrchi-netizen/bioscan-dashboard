// The per-meal view: opened from a meal row of THE DAY'S PLAN. The meal's own numbers (macros, the meal-profile indexes,
// foods and gut tags, micronutrients) and where each sits among the other meals of the last 90 days. Indexes stay
// separate, never one combined score; each is drawn in its metric's hue (CLAUDE.md, visual design).
import { useEffect, useState } from 'react'
import { useLoadEffect } from '../lib/useLoadEffect'
import { COMPARE_DAYS, loadMealDetail, type MealDetail, type ProfileData, PROTEIN_DOSE_G } from './mealView'
import { macroSplit, microName, standing } from './mealStats'
import { wallTime } from './todayLive'

const n0 = (x: number | null | undefined) => (x == null ? '–' : String(Math.round(x)))
const n1 = (x: number) => String(Math.round(x * 10) / 10)
const slotName = (s: string | null) => (s ? s.replace(/[_-]+/g, '-').replace(/^./, (c) => c.toUpperCase()) : 'Meal')

interface IndexSpec { key: keyof ProfileData; label: string; unit: string; hue: string; digits?: 0 | 1; info: string }
const INDEXES: IndexSpec[] = [
  { key: 'energy_density_kcal_g', label: 'Energy density', unit: 'kcal/g', hue: 'm-energy-density', digits: 1, info: 'Food kcal per gram of food, drinks left out.' },
  { key: 'protein_per_100kcal', label: 'Protein density', unit: 'g/100 kcal', hue: 'm-protein', digits: 1, info: 'Grams of protein per 100 kcal.' },
  { key: 'carbs_per_100kcal', label: 'Carb density', unit: 'g/100 kcal', hue: 'm-carbs', digits: 1, info: 'Grams of carbohydrate per 100 kcal.' },
  { key: 'fiber_per_1000kcal', label: 'Fibre', unit: 'g/1,000 kcal', hue: 'm-fiber', digits: 1, info: 'Grams of fibre per 1,000 kcal.' },
  { key: 'glycemic_load_est', label: 'Glycemic load', unit: 'est.', hue: 'm-glycemic-load', digits: 0, info: 'Estimated from typical glycemic index per food group.' },
  { key: 'fullness_factor_est', label: 'Fullness', unit: 'est.', hue: 'm-fullness', digits: 1, info: 'Fullness Factor per 100 g, for meals with grams and no drinks.' },
  { key: 'ultra_processed_pct', label: 'Processed share', unit: '% of kcal', hue: 'm-ultra-processed', digits: 0, info: 'Share of the meal\'s kcal from ultra-processed foods.' },
]

export function MealOverlay({ ids, day, onClose }: { ids: number[]; day: string; onClose: () => void }) {
  const [pick, setPick] = useState(0)
  const [detail, setDetail] = useState<MealDetail | null>(null)
  const id = ids[Math.min(pick, ids.length - 1)]
  const status = useLoadEffect(async (isCurrent) => {
    const d = await loadMealDetail(id, day)
    if (isCurrent()) setDetail(d)
  }, [id, day])
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [onClose])
  const shown = detail && detail.meal.id === id ? detail : null
  return (
    <div className="ov-scrim" onClick={onClose}>
      <div className="ov ft-card" role="dialog" aria-modal="true" aria-label="Meal" onClick={(e) => e.stopPropagation()}>
        <button className="ov-close" onClick={onClose} aria-label="Close">×</button>
        {ids.length > 1 && (
          <div className="meal-tabs">{ids.map((x, i) => <button key={x} className={`dash-link ${i === pick ? 'on' : ''}`} onClick={() => setPick(i)}>MEAL {i + 1}</button>)}</div>
        )}
        {shown ? <MealBody d={shown} /> : status.error ? <p className="ov-line">This meal could not be read: {status.error}</p> : <p className="today-hint">Reading the meal…</p>}
      </div>
    </div>
  )
}

function MealBody({ d }: { d: MealDetail }) {
  const p = d.profile
  const m = d.meal
  const kcal = p?.calories ?? null
  const protein = p?.protein_g ?? null
  const split = p ? macroSplit(p) : null
  const typical = macroSplit({
    protein_g: standing(1, d.others.map((o) => o.protein_g))?.median ?? null,
    carbs_g: standing(1, d.others.map((o) => o.carbs_g))?.median ?? null,
    fat_g: standing(1, d.others.map((o) => o.fat_g))?.median ?? null,
  })
  const groups = (p?.food_groups ?? []).map((g) => g.replace(/_/g, ' '))
  const tags = (p?.tags ?? []).map((g) => g.replace(/_/g, ' '))
  return (
    <>
      <div className="ov-eyebrow">{slotName(m.meal_slot)} · {wallTime(m.logged_at) ?? ''}</div>
      <div className="ov-head"><span className="ov-score">{n0(kcal)}</span><span className="ov-band">KCAL</span></div>
      {m.description && <p className="ov-line">{m.description}</p>}
      {p?.macro_mismatch && <p className="ov-warn">The kcal and the macros disagree: this entry is probably worth a look.</p>}

      <div className="ov-label">MACROS</div>
      <div className="meal-macros">
        <span style={{ color: 'var(--m-protein)' }}>Protein <b>{n0(p?.protein_g)} g</b></span>
        <span style={{ color: 'var(--m-carbs)' }}>Carbs <b>{n0(p?.carbs_g)} g</b></span>
        <span style={{ color: 'var(--m-fat)' }}>Fat <b>{n0(p?.fat_g)} g</b></span>
        {p?.fiber_g != null && <span style={{ color: 'var(--m-fiber)' }}>Fibre <b>{n1(p.fiber_g)} g</b></span>}
      </div>
      {split && <SplitBar label="THIS MEAL" s={split} />}
      {typical && <SplitBar label={`YOUR TYPICAL MEAL (${d.others.length})`} s={typical} />}
      {split && <p className="today-hint">Share of the meal's energy from each macro{typical ? ', against the middle of your other meals' : ''}.</p>}

      {protein != null && (
        <>
          <div className="ov-label">PROTEIN DOSE</div>
          <div className="meal-dose" role="img" aria-label={`${n0(protein)} g of a ${PROTEIN_DOSE_G} g dose`}>
            <i style={{ width: `${Math.min(100, (protein / PROTEIN_DOSE_G) * 100)}%`, background: 'var(--m-protein)' }} />
          </div>
          <p className="today-hint">{n0(protein)} g of the {PROTEIN_DOSE_G} g that counts as a full dose for muscle (0.3 g per kg of body weight; {PROTEIN_DOSE_G} g without one): {protein >= PROTEIN_DOSE_G ? 'reached' : protein >= PROTEIN_DOSE_G / 2 ? 'partly' : 'little'}.</p>
        </>
      )}

      <div className="ov-label">MEAL PROFILE · AGAINST YOUR LAST {COMPARE_DAYS} DAYS</div>
      {!p ? <p className="today-hint">This meal has no profile yet.</p> : INDEXES.map((spec) => {
        const v = p[spec.key] as number | null
        if (v == null) return null
        const s = standing(v, d.others.map((o) => o[spec.key] as number | null))
        return <IndexRow key={spec.key} spec={spec} value={v} s={s} />
      })}
      {(groups.length > 0 || tags.length > 0) && (
        <>
          <div className="ov-label">FOODS AND GUT TAGS</div>
          {groups.length > 0 && <p className="ov-line">Foods: {groups.join(', ')}</p>}
          {tags.length > 0 ? <p className="ov-line">Gut: {tags.join(', ')}</p> : <p className="today-hint">No gut tags.</p>}
        </>
      )}

      {d.items.length > 0 && (
        <>
          <div className="ov-label">WHAT WAS IN IT</div>
          {d.items.map((it, i) => (
            <div key={i} className="meal-item">
              <span>{it.description ?? 'Item'}{it.quantity != null && <span className="today-hint"> · {n1(it.quantity)} {it.quantity_unit ?? ''}</span>}</span>
              <span className="mono">{n0(it.calories)} kcal</span>
            </div>
          ))}
        </>
      )}

      <div className="ov-label">MICRONUTRIENTS</div>
      {d.micros.length === 0 ? <p className="today-hint">No micronutrient data for this meal: its foods were not matched to the food database.</p> : (
        <div className="meal-micros">
          {d.micros.map((x) => (
            <div key={x.nutrient} className="meal-micro">
              <span>{microName(x.nutrient)}</span>
              <span className="mono">{x.amount >= 100 ? Math.round(x.amount) : n1(x.amount)} {x.unit}</span>
              <span className="today-hint mono">{x.dayShare != null ? `${Math.round(x.dayShare * 100)}% of the day` : ''}</span>
            </div>
          ))}
        </div>
      )}
    </>
  )
}

function SplitBar({ label, s }: { label: string; s: { protein: number; carbs: number; fat: number } }) {
  const parts = [['protein', s.protein], ['carbs', s.carbs], ['fat', s.fat]] as const
  return (
    <div className="meal-split">
      <span className="meal-split-label mono">{label}</span>
      <div className="sofar-track" role="img" aria-label={parts.map(([k, v]) => `${k} ${Math.round(v * 100)}%`).join(', ')}>
        {parts.map(([k, v]) => <span key={k} style={{ width: `${v * 100}%`, background: `var(--m-${k})` }} />)}
      </div>
      <span className="today-hint mono">{parts.map(([k, v]) => `${k[0].toUpperCase()} ${Math.round(v * 100)}%`).join(' · ')}</span>
    </div>
  )
}

/** One index: its value, and a strip of the other meals (lowest to highest, the middle half boxed, the median ticked) with this meal marked. */
function IndexRow({ spec, value, s }: { spec: IndexSpec; value: number; s: ReturnType<typeof standing> }) {
  const fmt = (x: number) => (spec.digits === 0 ? n0(x) : n1(x))
  const pos = (x: number) => (s && s.max > s.min ? Math.max(0, Math.min(100, ((x - s.min) / (s.max - s.min)) * 100)) : 50)
  return (
    <div className="meal-index" title={spec.info}>
      <div className="meal-index-head">
        <span>{spec.label}</span>
        <span className="mono" style={{ color: `var(--${spec.hue})` }}>{fmt(value)} <small>{spec.unit}</small></span>
      </div>
      {s ? (
        <>
          <div className="meal-strip" role="img" aria-label={`${fmt(value)}, higher than ${s.pct}% of ${s.n} other meals`}>
            <i className="meal-strip-box" style={{ left: `${pos(s.q1)}%`, width: `${Math.max(1, pos(s.q3) - pos(s.q1))}%` }} />
            <i className="meal-strip-median" style={{ left: `${pos(s.median)}%` }} />
            <b style={{ left: `${pos(value)}%`, background: `var(--${spec.hue})` }} />
          </div>
          <div className="meal-index-sub today-hint mono"><span>{fmt(s.min)}</span><span>middle half {fmt(s.q1)}–{fmt(s.q3)} · median {fmt(s.median)}</span><span>{fmt(s.max)}</span></div>
          <span className="today-hint">{s.pct}% of your other meals are at or below this one.</span>
        </>
      ) : <span className="today-hint">Not enough other meals to compare.</span>}
    </div>
  )
}
