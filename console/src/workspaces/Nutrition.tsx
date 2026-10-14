// DAV-316: Nutrition workspace.
// Macros bar chart, fluid/caffeine, meals table, weight + TDEE overlay.

import { LoadBanner } from '../components/shared/LoadBanner'
import { useLoadEffect } from '../lib/useLoadEffect'
import { useState } from 'react'
import { supabase } from '../lib/supabase'
import { useRange, useInspect } from '../lib/analysisStore'
import { dayBounds, localDate, warnOnError } from '../lib/schema'
import { fetchMetric } from '../lib/metricAdapter'
import { FTChart } from '../components/shared/FTChart'
import type { MetricResult } from '../lib/metricAdapter'
import type { ChartSpec } from '../lib/chartSpec'
import './Nutrition.css'

interface MacroDay {
  date: string
  calories: number | null
  protein_g: number | null
  carbs_g: number | null
  fat_g: number | null
}

interface MealRow {
  id: string
  date: string
  meal_type: string | null
  description: string | null
  calories: number | null
  protein_g: number | null
}

interface MealQueryRow { id: unknown; logged_at: string; description: string | null; calories: number | null; protein_g: number | null; carbs_g: number | null; fat_g: number | null }

// All meals in [start, end], oldest first, read in pages of 1,000 (PostgREST's default cap) until a short page.
async function fetchMeals(start: string, end: string): Promise<MealQueryRow[]> {
  const { from, to } = dayBounds(start, end)
  const out: MealQueryRow[] = []
  for (let offset = 0; ; offset += 1000) {
    const { data, error } = await supabase.from('meals')
      .select('id, logged_at, description, calories, protein_g, carbs_g, fat_g')
      .gte('logged_at', from).lte('logged_at', to)
      .order('logged_at', { ascending: true })
      .range(offset, offset + 999)
    warnOnError('nutrition meals', error)
    if (error) throw new Error(error.message)
    const page = (data ?? []) as unknown as MealQueryRow[]
    out.push(...page)
    if (page.length < 1000) return out
  }
}

// Group by local date (meals have logged_at, not date); sums are the same as before.
function toMacroDays(rows: MealQueryRow[]): MacroDay[] {
  const map = new Map<string, MacroDay>()
  for (const r of rows) {
    const date = localDate(r.logged_at)
    const existing = map.get(date) ?? { date, calories: 0, protein_g: 0, carbs_g: 0, fat_g: 0 }
    existing.calories = (existing.calories ?? 0) + (r.calories ?? 0)
    existing.protein_g = (existing.protein_g ?? 0) + (r.protein_g ?? 0)
    existing.carbs_g = (existing.carbs_g ?? 0) + (r.carbs_g ?? 0)
    existing.fat_g = (existing.fat_g ?? 0) + (r.fat_g ?? 0)
    map.set(date, existing)
  }
  return [...map.values()].sort((a, b) => a.date.localeCompare(b.date))
}

// The 50 most recent meals, newest first.
function toMealRows(rows: MealQueryRow[]): MealRow[] {
  return rows.slice(-50).reverse().map(r => ({
    id: String(r.id), date: localDate(r.logged_at), meal_type: null,
    description: r.description, calories: r.calories, protein_g: r.protein_g,
  }))
}

function toResult(days: MacroDay[], field: keyof MacroDay, metricId: string): MetricResult {
  return {
    metricId, unit: null,
    points: days.map(d => ({
      date: d.date,
      value: (d[field] as number | null) ?? null,
      source: null, confidence: null, provenance: null,
    })),
    missingness: 'none', error: null,
  }
}

function makeSpec(id: string, title: string, unit: string, kind: ChartSpec['kind']): ChartSpec {
  return { specVersion: 1, metricId: id, title, unit, kind, smooth: false }
}

const calSpec  = makeSpec('nutrition.calories',  'Calories', 'kcal', 'bar')
const protSpec = makeSpec('nutrition.protein',   'Protein',  'g',    'bar')
const carbSpec = makeSpec('nutrition.carbs',     'Carbs',    'g',    'bar')
const fatSpec  = makeSpec('nutrition.fat',       'Fat',      'g',    'bar')
const wtSpec: ChartSpec = { specVersion: 1, metricId: 'body_metrics.weight_kg', title: 'Weight', unit: 'kg', kind: 'line', smooth: true }

export function Nutrition() {
  const range = useRange()
  const inspect = useInspect()
  const [macros, setMacros] = useState<MacroDay[]>([])
  const [meals, setMeals] = useState<MealRow[]>([])
  const [weightResult, setWeightResult] = useState<MetricResult | null>(null)

  const { loading, error, retry } = useLoadEffect(async (isCurrent) => {
    const { start, end } = range
    const [mealBundle, wt] = await Promise.all([
      // One paged read of the range's meals feeds both the daily macro sums and the latest-50 list (was two queries, the
      // first silently capped at 1,000 rows).
      fetchMeals(start, end).then(rows => ({ macros: toMacroDays(rows), meals: toMealRows(rows) })),
      // Weight for overlay
      fetchMetric({ table: 'body_metrics', column: 'weight_kg', range, aggregation: 'daily_avg' }),
    ])
    if (!isCurrent()) return
    setMacros(mealBundle.macros)
    setMeals(mealBundle.meals)
    setWeightResult(wt)
  }, [range])

  if (loading) return <div className="state-loading mono">LOADING NUTRITION…</div>
  if (error) return <LoadBanner error={error} onRetry={retry} />

  const calResult  = toResult(macros, 'calories',  'nutrition.calories')
  const protResult = toResult(macros, 'protein_g', 'nutrition.protein')
  const carbResult = toResult(macros, 'carbs_g',   'nutrition.carbs')
  const fatResult  = toResult(macros, 'fat_g',     'nutrition.fat')

  return (
    <div className="nutrition">
      <h2 className="nutrition-title">Nutrition</h2>

      <section className="nutrition-section">
        <div className="nutrition-section-label mono">MACROS</div>
        <div className="nutrition-macro-grid">
          <div className="card"><FTChart spec={calSpec}  result={calResult}  height={140} /></div>
          <div className="card"><FTChart spec={protSpec} result={protResult} height={140} /></div>
          <div className="card"><FTChart spec={carbSpec} result={carbResult} height={140} /></div>
          <div className="card"><FTChart spec={fatSpec}  result={fatResult}  height={140} /></div>
        </div>
      </section>

      {weightResult && (
        <section className="nutrition-section">
          <div className="nutrition-section-label mono">WEIGHT</div>
          <div className="card">
            <FTChart spec={wtSpec} result={weightResult} height={160} />
          </div>
        </section>
      )}

      <section className="nutrition-section">
        <div className="nutrition-section-label mono">MEALS</div>
        {meals.length === 0
          ? <div className="state-empty mono">NO MEALS IN RANGE</div>
          : (
            <div className="nutrition-meal-list">
              {meals.map(m => (
                <button
                  key={m.id}
                  className="nutrition-meal-row"
                  onClick={() => inspect({ kind: 'event', eventRef: { kind: 'meal', id: m.id } })}
                >
                  <span className="nutrition-meal-date mono">{m.date}</span>
                  <span className="nutrition-meal-type mono">{m.meal_type ?? '—'}</span>
                  <span className="nutrition-meal-desc">{m.description ?? '—'}</span>
                  {m.calories != null && (
                    <span className="nutrition-meal-stat mono">{m.calories} kcal</span>
                  )}
                  {m.protein_g != null && (
                    <span className="nutrition-meal-stat mono">{m.protein_g}g P</span>
                  )}
                </button>
              ))}
            </div>
          )
        }
      </section>
    </div>
  )
}

export default Nutrition
