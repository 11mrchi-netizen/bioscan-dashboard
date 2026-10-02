// DAV-316: Nutrition workspace.
// Macros bar chart, fluid/caffeine, meals table, weight + TDEE overlay.

import { useEffect, useState } from 'react'
import { supabase } from '../lib/supabase'
import { useRange, useInspect } from '../lib/analysisStore'
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
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    const { start, end } = range
    Promise.all([
      // Daily macro summary from meals table
      supabase.from('meals')
        .select('date, calories, protein_g, carbs_g, fat_g')
        .gte('date', start).lte('date', end)
        .order('date', { ascending: true })
        .then(({ data }) => {
          // Group by date
          const map = new Map<string, MacroDay>()
          for (const r of (data ?? []) as MacroDay[]) {
            const existing = map.get(r.date) ?? { date: r.date, calories: 0, protein_g: 0, carbs_g: 0, fat_g: 0 }
            existing.calories = (existing.calories ?? 0) + (r.calories ?? 0)
            existing.protein_g = (existing.protein_g ?? 0) + (r.protein_g ?? 0)
            existing.carbs_g = (existing.carbs_g ?? 0) + (r.carbs_g ?? 0)
            existing.fat_g = (existing.fat_g ?? 0) + (r.fat_g ?? 0)
            map.set(r.date, existing)
          }
          return [...map.values()].sort((a, b) => a.date.localeCompare(b.date))
        }),
      // Recent meal rows
      supabase.from('meals')
        .select('id, date, meal_type, description, calories, protein_g')
        .gte('date', start).lte('date', end)
        .order('date', { ascending: false })
        .limit(50)
        .then(({ data }) => (data ?? []) as MealRow[]),
      // Weight for overlay
      fetchMetric({ table: 'body_metrics', column: 'weight_kg', range, aggregation: 'daily_avg' }),
    ]).then(([macroData, mealData, wt]) => {
      setMacros(macroData)
      setMeals(mealData)
      setWeightResult(wt)
      setLoading(false)
    })
  }, [range])

  if (loading) return <div className="state-loading mono">LOADING NUTRITION…</div>

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
