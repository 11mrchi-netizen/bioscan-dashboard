// Port of the week's energy the Android FuelPlanRepository builds (weekEnergyOf): the goal's calories, else maintenance; maintenance is
// the expenditure observed from logged intake and the weight trend (EnergyBalanceEvaluation, NutritionEvaluation and the weight EMA of
// BodyCompositionEvaluation) once that is confident, else Mifflin-St Jeor times an activity factor (NutritionTargets). Parity by fixture
// (WeekEnergyParityTest). Dates are local calendar dates ("YYYY-MM-DD"); the caller passes today.

import type { WeekEnergy } from './day.ts'

const dayNumber = (d: string) => Math.round(Date.UTC(Number(d.slice(0, 4)), Number(d.slice(5, 7)) - 1, Number(d.slice(8, 10))) / 86400000)
/** ChronoUnit.DAYS.between(a, b). */
const daysBetween = (a: string, b: string) => dayNumber(b) - dayNumber(a)
const plusDays = (d: string, n: number) => new Date((dayNumber(d) + n) * 86400000).toISOString().slice(0, 10)
const mean = (v: number[]) => (v.length === 0 ? 0 : v.reduce((a, b) => a + b, 0) / v.length)
const populationStdDev = (v: number[]) => {
  if (v.length === 0) return 0
  const m = mean(v)
  return Math.sqrt(v.reduce((a, x) => a + (x - m) * (x - m), 0) / v.length)
}
const median = (v: number[]) => {
  if (v.length === 0) return 0
  const s = [...v].sort((a, b) => a - b)
  const mid = Math.trunc(s.length / 2)
  return s.length % 2 === 0 ? (s[mid - 1] + s[mid]) / 2 : s[mid]
}

export interface Confidence { have: number; need: number }
const met = (c: Confidence) => c.have >= c.need

/** One logged meal row, as meals has it. */
export interface MealRow { logged_at: string; calories: number | null; protein_g: number | null; fat_g: number | null; carbs_g: number | null }

/** Meals summed by the calendar date of their (wall-clock) logged time, ascending (Nutrition.kt aggregateMealsByDay). */
export function aggregateMealsByDay(meals: MealRow[]): DailyNutrition[] {
  const byDate = new Map<string, MealRow[]>()
  for (const m of meals) { const d = m.logged_at.slice(0, 10); const l = byDate.get(d); if (l) l.push(m); else byDate.set(d, [m]) }
  const sum = (rows: MealRow[], f: (m: MealRow) => number | null) => rows.reduce((a, m) => a + (f(m) ?? 0), 0)
  return [...byDate.entries()]
    .map(([date, rows]) => ({ date, calories: sum(rows, (m) => m.calories), proteinG: sum(rows, (m) => m.protein_g), fatG: sum(rows, (m) => m.fat_g), carbsG: sum(rows, (m) => m.carbs_g), mealCount: rows.length }))
    .sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))
}

// ---- nutrition (NutritionEvaluation.kt) ----

export interface DailyNutrition { date: string; calories: number; proteinG: number; fatG: number; carbsG: number; mealCount: number }
type DayClass = 'Complete' | 'Partial' | 'Missing'
const COMPLETE_MEALS_MIN = 3
const MEDIAN_WINDOW_DAYS = 28
const ENERGY_TREND_DAYS = 14
const MIN_COMPLETE_IN_14 = 10

export function classifyNutritionDay(day: DailyNutrition, priorDays: DailyNutrition[]): DayClass {
  if (day.mealCount === 0) return 'Missing'
  if (day.mealCount < COMPLETE_MEALS_MIN) return 'Partial'
  const qualifying = priorDays.filter((d) => d.mealCount >= COMPLETE_MEALS_MIN)
  if (qualifying.length === 0) return 'Complete'
  return day.calories < 0.5 * median(qualifying.map((d) => d.calories)) ? 'Partial' : 'Complete'
}

export interface NutritionEvaluation { confidence: Confidence; energyTrend14d: number | null; energySd14d: number | null }

export function evaluateNutrition(dailyTotals: DailyNutrition[], asOf: string): NutritionEvaluation {
  const parsed = dailyTotals
    .filter((d) => /^\d{4}-\d{2}-\d{2}$/.test(d.date) && d.date <= asOf)
    .sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))
  if (parsed.length === 0) return { confidence: { have: 0, need: MIN_COMPLETE_IN_14 }, energyTrend14d: null, energySd14d: null }
  const classes = new Map<string, DayClass>()
  for (const day of parsed) {
    const windowStart = plusDays(day.date, -MEDIAN_WINDOW_DAYS)
    classes.set(day.date, classifyNutritionDay(day, parsed.filter((d) => d.date >= windowStart && d.date < day.date)))
  }
  const last14 = parsed.filter((d) => daysBetween(d.date, asOf) < ENERGY_TREND_DAYS)
  const completeInLast14 = last14.filter((d) => classes.get(d.date) === 'Complete').length
  if (completeInLast14 < MIN_COMPLETE_IN_14) return { confidence: { have: completeInLast14, need: MIN_COMPLETE_IN_14 }, energyTrend14d: null, energySd14d: null }
  const last14Complete = parsed.filter((d) => classes.get(d.date) === 'Complete' && daysBetween(d.date, asOf) < ENERGY_TREND_DAYS)
  return {
    confidence: { have: completeInLast14, need: MIN_COMPLETE_IN_14 },
    energyTrend14d: last14Complete.length ? mean(last14Complete.map((d) => d.calories)) : null,
    energySd14d: last14Complete.length >= 2 ? populationStdDev(last14Complete.map((d) => d.calories)) : null,
  }
}

// ---- weight trend (BodyCompositionEvaluation.kt) ----

const WEIGHT_MIN_MEASUREMENTS = 10
const WEIGHT_MIN_SPAN_DAYS = 14
export interface WeightEvaluation { confidence: Confidence; rateKgPerWeek: number | null }
const chooseWeightAlpha = (perWeek: number) => (perWeek >= 6.5 ? 0.1 : perWeek >= 3 ? 0.2 : 0.25)

export function evaluateWeightTrend(weightDaily: [string, number][], asOf: string): WeightEvaluation {
  const sorted = weightDaily.filter(([d]) => d <= asOf).sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
  if (sorted.length === 0) return { confidence: { have: 0, need: WEIGHT_MIN_MEASUREMENTS }, rateKgPerWeek: null }
  const span = daysBetween(sorted[0][0], sorted[sorted.length - 1][0])
  const conf = { have: sorted.length, need: WEIGHT_MIN_MEASUREMENTS }
  if (sorted.length < WEIGHT_MIN_MEASUREMENTS || span < WEIGHT_MIN_SPAN_DAYS) return { confidence: conf, rateKgPerWeek: null }
  const last30 = sorted.filter(([d]) => daysBetween(d, asOf) < 30)
  const perWeek = last30.length >= 2 ? (last30.length / Math.max(daysBetween(last30[0][0], last30[last30.length - 1][0]), 1)) * 7 : 2
  const alpha = chooseWeightAlpha(perWeek)
  let ema: number | null = null
  let lastDate = ''
  const series: [string, number][] = []
  for (const [date, w] of sorted) {
    if (ema === null) ema = w
    else {
      const dt = Math.max(daysBetween(lastDate, date), 1)
      const aEff = 1 - Math.pow(1 - alpha, dt)
      ema = aEff * w + (1 - aEff) * ema
    }
    lastDate = date
    series.push([date, ema])
  }
  const emaToday = series[series.length - 1][1]
  const cutoff = plusDays(asOf, -14)
  let two: number | null = null
  for (const [d, v] of series) if (d <= cutoff) two = v
  if (two === null) return { confidence: conf, rateKgPerWeek: null }
  return { confidence: conf, rateKgPerWeek: (emaToday - two) / 2 }
}

// ---- observed expenditure (EnergyBalanceEvaluation.kt) ----

const KCAL_PER_KG_TISSUE_CHANGE = 7700

export function evaluateTdee(nutrition: NutritionEvaluation, weight: WeightEvaluation): number | null {
  const avg = nutrition.energyTrend14d
  const sd = nutrition.energySd14d
  const rate = weight.rateKgPerWeek
  if (!met(nutrition.confidence) || !met(weight.confidence) || avg == null || sd == null || rate == null) return null
  return avg - (rate / 7) * KCAL_PER_KG_TISSUE_CHANGE
}

// ---- maintenance from an equation (NutritionTargets.kt) ----

export const ACTIVITY_FACTOR: Record<string, number> = { sedentary: 1.2, light: 1.375, moderate: 1.55, very: 1.725, extra: 1.9 }
export function activityFromWeeklyTraining(minutes: number): string {
  return minutes < 30 ? 'sedentary' : minutes < 150 ? 'light' : minutes < 300 ? 'moderate' : minutes < 450 ? 'very' : 'extra'
}
/** Mifflin-St Jeor resting energy, kcal/day; null without sex, age, height or weight. */
export function restingEnergyKcal(sex: string | null, ageYears: number | null, heightCm: number | null, weightKg: number | null): number | null {
  if (ageYears == null || heightCm == null || weightKg == null || heightCm <= 0 || weightKg <= 0) return null
  const base = 10 * weightKg + 6.25 * heightCm - 5 * ageYears
  return sex === 'male' ? base + 5 : sex === 'female' ? base - 161 : null
}
export function maintenanceKcal(restingKcal: number | null, chosen: string | null, weeklyTrainingMin: number | null): number | null {
  if (restingKcal == null) return null
  const level = chosen != null && chosen in ACTIVITY_FACTOR ? chosen : weeklyTrainingMin != null ? activityFromWeeklyTraining(weeklyTrainingMin) : null
  return level == null ? null : restingKcal * ACTIVITY_FACTOR[level]
}
export const MAINTENANCE_BAND = 0.05
export type EnergyBalanceKind = 'DEFICIT' | 'MAINTENANCE' | 'SURPLUS'
export function energyBalanceKind(goalKcal: number, maintenance: number): EnergyBalanceKind {
  const diff = goalKcal - maintenance
  const share = diff / maintenance
  return Math.abs(share) <= MAINTENANCE_BAND ? 'MAINTENANCE' : diff < 0 ? 'DEFICIT' : 'SURPLUS'
}

// ---- the week ----

export interface NutritionGoals { caloriesKcal?: number | null; proteinG?: number | null; activityLevel?: string | null }
export interface MaintenanceInputs { sex?: string | null; ageYears?: number | null; heightCm?: number | null; weightKg?: number | null; weeklyTrainingMin?: number | null }

export function weekEnergyOf(goals: NutritionGoals, inputs: MaintenanceInputs, nutritionDays: DailyNutrition[] | null, weights: [string, number][], asOf: string): WeekEnergy {
  const resting = restingEnergyKcal(inputs.sex ?? null, inputs.ageYears ?? null, inputs.heightCm ?? null, inputs.weightKg ?? null)
  const observed = nutritionDays && nutritionDays.length ? evaluateTdee(evaluateNutrition(nutritionDays, asOf), evaluateWeightTrend(weights, asOf)) : null
  const maintenance = observed ?? maintenanceKcal(resting, goals.activityLevel ?? null, inputs.weeklyTrainingMin ?? null)
  const goalKcal = goals.caloriesKcal ?? null
  const deficit = goalKcal != null && maintenance != null && energyBalanceKind(goalKcal, maintenance) === 'DEFICIT'
  return { dailyKcal: goalKcal ?? maintenance, proteinG: goals.proteinG ?? null, fromGoal: goalKcal != null, deficit, observedMaintenance: observed != null }
}
