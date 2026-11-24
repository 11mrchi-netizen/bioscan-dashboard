// Port of the Android domain/fuel/RaceFuelPlan.kt and IntakeHistory.kt: a fuel and hydration plan for a race or a long session: what to take and
// when, by time and by aid station, what to carry between stations, what to do before and after. Built from the planned duration spread along the
// course, the weather at each hour and height, the person's sweat rate, their tolerated carbohydrate rate and products, and the days before.
// Parity by fixture (RaceFuelParityTest), text included.

import { type DayFuelPlan, fmt, type Intensity } from './day.ts'
import { elapsedAtKm, elevationAtKm, kmAtElapsed, type CoursePoint, timeAlongCourse } from './course.ts'
import { type HeatBand, heatBand, type HeatSummary, type HourWeather, seriesAt, summariseHeat, tempAtElevation, type WeatherSeries } from './heat.ts'
import { instant } from './plannedDay.ts'
import { roundToInt } from './plates.ts'
import { DEFAULT_TEST_TEMP_C, fluidPlan, MAX_REPLACEMENT_SHARE, predictSweatRate, type SweatEstimate, type SweatProfile } from './sweat.ts'
import { DEFAULT_TOLERATED_G_PER_H, MAX_G_PER_H, sessionFuel } from './mealCodes.ts'
import { fmtMin, fmtNum } from './text.ts'

export interface AidStation { name: string; km: number; water: boolean; food: boolean; dropBag: boolean; cutoffMin: number | null }
export interface FuelProductOption { id: number | null; name: string; kind: string; carbsG: number; fluidMl: number | null; sodiumMg: number | null; caffeineMg: number | null; fructoseG: number | null }
/** Used when the person has no products in their catalog: a typical 25 g gel, labelled generic. */
export const GENERIC_GEL: FuelProductOption = { id: null, name: 'gel (25 g, generic)', kind: 'gel', carbsG: 25, fluidMl: null, sodiumMg: 50, caffeineMg: null, fructoseG: null }

export interface RaceFuelSettings { toleratedGPerHour: number; caffeineOk: boolean; caffeineMgPerKg: number; saltySweater: boolean }
export const DEFAULT_RACE_SETTINGS: RaceFuelSettings = { toleratedGPerHour: DEFAULT_TOLERATED_G_PER_H, caffeineOk: false, caffeineMgPerKg: 3, saltySweater: false }

// ---- the days before (IntakeHistory.kt) ----

export interface LoggedDay { date: string; mealsLogged: number; carbG: number; kcal: number | null }
export type IntakeStatus = 'MET' | 'UNDER' | 'OVER' | 'NOT_LOGGED'
export interface DayIntakeCheck { date: string; plannedCarbG: number; loggedCarbG: number | null; status: IntakeStatus; loading: boolean }
export interface IntakeHistoryResult { days: DayIntakeCheck[]; loadingShortfallG: number; catchUpG: number; earlyFuelling: boolean; preHydrationBoost: boolean; notes: string[] }
export const CATCH_UP_MAX_G_PER_KG = 2
export const WEIGHT_DROP_FOR_FLUID = 0.01
export const LOG_COMPLETE_MEALS = 3
export const isFullyLogged = (d: LoggedDay | undefined) => d != null && d.mealsLogged >= LOG_COMPLETE_MEALS

export function checkIntakeHistory(planned: DayFuelPlan[], logged: LoggedDay[], weightKg: number, raceSoon: boolean, morningWeightKg: number | null = null, usualWeightKg: number | null = null): IntakeHistoryResult {
  const byDate = new Map(logged.map((l) => [l.date, l]))
  const checks: DayIntakeCheck[] = planned.map((p) => {
    const l = byDate.get(p.date)
    if (!isFullyLogged(l)) return { date: p.date, plannedCarbG: p.carbG, loggedCarbG: null, status: 'NOT_LOGGED', loading: p.loading }
    const lo = p.carbG * 0.9
    const hi = p.carbG * 1.1
    const status: IntakeStatus = (l as LoggedDay).carbG < lo ? 'UNDER' : (l as LoggedDay).carbG > hi ? 'OVER' : 'MET'
    return { date: p.date, plannedCarbG: p.carbG, loggedCarbG: (l as LoggedDay).carbG, status, loading: p.loading }
  })
  const notes: string[] = []
  const shortfall = checks.filter((c) => c.loading && c.loggedCarbG != null).reduce((a, c) => a + Math.max(0, c.plannedCarbG - (c.loggedCarbG as number)), 0)
  const catchUp = raceSoon ? Math.min(shortfall, CATCH_UP_MAX_G_PER_KG * weightKg) : 0
  if (shortfall > 0 && raceSoon) notes.push(`Loading so far is ${roundToInt(shortfall)} g short of plan: today's meals carry ${roundToInt(catchUp)} g more, and fuelling starts in the first 20 minutes of the race.`)
  for (const c of checks.filter((x) => x.loading && x.status === 'NOT_LOGGED')) notes.push(`${c.date}: not logged, assumed loaded as planned.`)
  const dropped = morningWeightKg != null && usualWeightKg != null && usualWeightKg > 0 && (usualWeightKg - morningWeightKg) / usualWeightKg > WEIGHT_DROP_FOR_FLUID
  if (dropped) notes.push(`This morning's weight is ${fmt((usualWeightKg as number) - (morningWeightKg as number), 1)} kg under your usual: likely fluid. Drink a little more before the start.`)
  return { days: checks, loadingShortfallG: shortfall, catchUpG: catchUp, earlyFuelling: shortfall > 0 && raceSoon, preHydrationBoost: dropped, notes }
}

/** plan with extraG of carbohydrate added for catching up on loading (energy rises with it). */
export function withCatchUp(plan: DayFuelPlan, extraG: number): DayFuelPlan {
  if (extraG <= 0) return plan
  return {
    ...plan, carbG: plan.carbG + extraG, carbGPerKg: plan.carbGPerKg + extraG / Math.max(plan.carbG / plan.carbGPerKg, 1),
    kcal: plan.kcal == null ? null : plan.kcal + extraG * 4, notes: [...plan.notes, `Catching up on loading: +${roundToInt(extraG)} g today.`],
  }
}

// ---- the plan ----

export interface RaceFuelInput {
  title: string; type: string; intensity: Intensity
  /** a Kotlin ZonedDateTime.toString() */
  start: string
  durationMin: number; durationLowMin: number; durationHighMin: number; durationBasis: string
  course: CoursePoint[]; distanceKm: number | null; aidStations: AidStation[]; weather: WeatherSeries; sweat: SweatProfile; weightKg: number
  settings: RaceFuelSettings; products: FuelProductOption[]; history: IntakeHistoryResult | null; loadingDays: boolean
}
export interface StepPart { product: FuelProductOption; count: number }
export interface FuelStep {
  elapsedMin: number; km: number | null; tempC: number | null; carbsG: number; fluidMl: number; sodiumMg: number; caffeineMg: number; items: string[]
  aidStation: AidStation | null; parts: StepPart[]
}
export interface CarryLeg { from: string; to: string; minutes: number; carbsG: number; fluidMl: number; items: Record<string, number>; bottlesMl: number; note: string | null }
export interface PrepItem { minutesFromStart: number; whenLabel: string; text: string }
export interface RaceFuelPlan {
  title: string; durationMin: number; durationLowMin: number; durationHighMin: number; carbsPerHourLow: number; carbsPerHourHigh: number
  fluidMlPerHourLow: number; fluidMlPerHourHigh: number; sodiumMgPerL: number; sweat: SweatEstimate; weatherSource: string; heat: HeatSummary | null
  steps: FuelStep[]; legs: CarryLeg[]; before: PrepItem[]; after: PrepItem[]; totalCarbsG: number; totalFluidMl: number; totalSodiumMg: number; totalCaffeineMg: number
  expectedLossPct: number; assumptions: string[]; warnings: string[]
}

export const STEP_MIN = 20
export const CAFFEINE_CAP_MG = 400
const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0)

export function planRaceFuel(input: RaceFuelInput): RaceFuelPlan {
  const kg = input.weightKg
  const duration = input.durationMin
  const times = timeAlongCourse(input.course, duration)
  const totalKm = input.distanceKm ?? (input.course.length ? input.course[input.course.length - 1].distanceM / 1000 : null)
  const startElevation = input.course.length ? input.course[0].elevationM : null
  const startMs = instant(input.start)
  const assumptions: string[] = []
  const warnings: string[] = []

  const sf = sessionFuel(duration, input.intensity, input.settings.toleratedGPerHour)
  const raceLike = input.intensity === 'RACE' || input.intensity === 'HARD'
  const gph = raceLike ? sf.gPerHourHigh : (sf.gPerHourLow + sf.gPerHourHigh) / 2
  const early = input.history?.earlyFuelling === true
  const firstIntake = early ? 15 : 20

  const weatherAt = (elapsed: number): [number | null, number | null] => {
    const h = seriesAt(input.weather, startMs + Math.trunc(elapsed * 60) * 1000)
    if (!h) return [null, null]
    const km = kmAtElapsed(input.course, times, elapsed, duration, totalKm)
    const climb = km != null && startElevation != null ? (elevationAtKm(input.course, km) ?? startElevation) - startElevation : 0
    return [tempAtElevation(h.tempC, climb), h.humidityPct ?? null]
  }
  const lo = startMs - 30 * 60000
  const hi = startMs + (Math.trunc(duration) + 30) * 60000
  const raceHours: HourWeather[] = input.weather.hours.filter((h) => !(instant(h.time) < lo) && !(instant(h.time) > hi))
  const heat = summariseHeat(raceHours, input.weather.source)
  const band: HeatBand = heat?.band ?? 'MILD'
  const meanTemp = raceHours.length ? sum(raceHours.map((h) => h.tempC)) / raceHours.length : DEFAULT_TEST_TEMP_C
  const sweatMean = predictSweatRate(input.sweat, input.intensity, input.type, meanTemp, kg)
  const fluidMean = fluidPlan(sweatMean, duration, kg, band, input.settings.saltySweater)

  const caffeineTotal = input.settings.caffeineOk && duration >= 60 ? Math.min(CAFFEINE_CAP_MG, input.settings.caffeineMgPerKg * kg) : 0
  const inRaceCaffeine: [number, number][] = caffeineTotal > 0 && duration > 90 ? [[0.4 * duration, caffeineTotal / 3], [0.7 * duration, (caffeineTotal * 2) / 3]] : []

  const stationTimes = [...input.aidStations].sort((a, b) => a.km - b.km).map((s) => [s, elapsedAtKm(input.course, times, s.km, duration, totalKm)] as [AidStation, number]).filter((x) => x[1] < duration)
  const stepTimes: number[] = []
  for (let t = firstIntake; t <= duration - 10; t += STEP_MIN) stepTimes.push(t)
  const products = input.products.filter((p) => p.carbsG > 0).length ? input.products.filter((p) => p.carbsG > 0) : [GENERIC_GEL]
  if (!input.products.some((p) => p.carbsG > 0)) assumptions.push('No products in your fuel catalog: shown as generic 25 g gels. Add yours under + › RUN FUEL.')
  const nonDrink = products.filter((p) => p.kind !== 'drink_mix')
  let solid = nonDrink.length ? nonDrink[0] : products[0]
  for (const p of nonDrink) if (p.carbsG > solid.carbsG) solid = p
  const drink = products.find((p) => p.kind === 'drink_mix' && (p.fluidMl ?? 0) > 0) ?? null

  let carbDebt = 0
  const steps: FuelStep[] = stepTimes.map((t, i) => {
    const span = i === 0 ? t - 0 : t - stepTimes[i - 1]
    const ramp = t <= 60 ? 0.75 : 1
    const need = (gph * ramp * span) / 60 + carbDebt
    const [temp, humidity] = weatherAt(t)
    const stepBand = temp != null ? heatBand(temp, humidity) : band
    const stepSweat = predictSweatRate(input.sweat, input.intensity, input.type, temp ?? meanTemp, kg)
    const stepFluid = fluidPlan(stepSweat, duration, kg, stepBand, input.settings.saltySweater)
    const fluid = ((stepFluid.mlPerHourLow + stepFluid.mlPerHourHigh) / 2) * span / 60
    const items: string[] = []
    const parts: StepPart[] = []
    let carbs = 0
    let sodium = (fluid / 1000) * stepFluid.sodiumMgPerL
    if (drink != null && fluid >= (drink.fluidMl as number) * 0.6) {
      const servings = Math.max(1, roundToInt(fluid / (drink.fluidMl as number)))
      carbs += servings * drink.carbsG
      items.push(`${servings} × ${drink.name}`)
      parts.push({ product: drink, count: servings })
    }
    const remaining = need - carbs
    const n = Math.max(Math.floor(remaining / solid.carbsG + 0.4), 0)
    if (n > 0) {
      carbs += n * solid.carbsG
      items.push(`${n} × ${solid.name}`)
      parts.push({ product: solid, count: n })
      sodium += n * (solid.sodiumMg ?? 0)
    }
    carbDebt = need - carbs
    if (fluid > 0) items.push(`${roundToInt(fluid / 10) * 10} ml to drink`)
    const caffeine = sum(inRaceCaffeine.filter(([at]) => at > t - span && at <= t).map((x) => x[1]))
    if (caffeine > 0) items.push(`${roundToInt(caffeine)} mg caffeine`)
    const station = stationTimes.find((x) => Math.abs(x[1] - t) <= STEP_MIN / 2)?.[0] ?? null
    return { elapsedMin: t, km: kmAtElapsed(input.course, times, t, duration, totalKm), tempC: temp, carbsG: carbs, fluidMl: fluid, sodiumMg: sodium, caffeineMg: caffeine, items, aidStation: station, parts }
  })

  const refills = stationTimes.filter((x) => x[0].water || x[0].food)
  const boundaries: [string, number][] = [['Start', 0], ...refills.map((x) => [x[0].name, x[1]] as [string, number]), ['Finish', duration]]
  const legs: CarryLeg[] = []
  for (let k = 0; k + 1 < boundaries.length; k++) {
    const [fromName, fromT] = boundaries[k]
    const [toName, toT] = boundaries[k + 1]
    const inLeg = steps.filter((s) => s.elapsedMin > fromT && s.elapsedMin <= toT)
    const counts: Record<string, number> = {}
    for (const s of inLeg.flatMap((x) => x.items)) {
      const m = /^(\d+) × (.+)$/.exec(s)
      if (m) counts[m[2]] = (counts[m[2]] ?? 0) + Number(m[1])
    }
    const fluid = sum(inLeg.map((s) => s.fluidMl))
    const refill = refills.find((x) => x[0].name === toName)?.[0]
    let note: string | null = null
    if (refill) {
      const what = [refill.water ? 'water' : null, refill.food ? 'food' : null, refill.dropBag ? 'drop bag' : null].filter((x) => x != null).join(', ')
      note = `Refill at ${toName}: ${what}.`
    }
    legs.push({ from: fromName, to: toName, minutes: toT - fromT, carbsG: sum(inLeg.map((s) => s.carbsG)), fluidMl: fluid, items: counts, bottlesMl: Math.trunc(Math.ceil(fluid / 250) * 250), note })
  }
  if (input.aidStations.length === 0 && duration > 90) assumptions.push('No aid stations entered: the plan assumes you carry everything. Add them on the event to split it.')

  for (const [s, t] of stationTimes) {
    const cutoff = s.cutoffMin
    if (cutoff == null) continue
    const slow = (t * input.durationHighMin) / duration
    if (t > cutoff) warnings.push(`At the planned pace you reach ${s.name} at ${fmtMin(t)}, after its ${fmtMin(cutoff)} cutoff.`)
    else if (slow > cutoff) warnings.push(`At the slow end of your range you reach ${s.name} at ${fmtMin(slow)}, after its ${fmtMin(cutoff)} cutoff.`)
  }

  let totalSweat = 0
  stepTimes.forEach((t, i) => {
    const span = i === 0 ? t : t - stepTimes[i - 1]
    const [temp] = weatherAt(t)
    totalSweat += (predictSweatRate(input.sweat, input.intensity, input.type, temp ?? meanTemp, kg).mlPerHour * span) / 60
  })
  totalSweat += (sweatMean.mlPerHour * Math.max(0, duration - (stepTimes.length ? stepTimes[stepTimes.length - 1] : 0))) / 60
  const totalFluid = sum(steps.map((s) => s.fluidMl))
  const lossPct = (Math.max(0, totalSweat - totalFluid) / (kg * 1000)) * 100
  if (lossPct > 3) {
    if (input.sweat.testCount === 0) {
      warnings.push(`With no sweat test yet the plan keeps drinking cautious (never more than you might sweat), so the estimated loss reaches about ${fmt(lossPct, 1)} % of body weight. Drink to thirst on top of the plan, up to about ${roundToInt(sweatMean.mlPerHour * MAX_REPLACEMENT_SHARE / 50) * 50} ml an hour, and do a sweat test in a long training session.`)
    } else if (band === 'HOT' || band === 'VERY_HOT') warnings.push(`Even drinking to plan you may lose about ${fmt(lossPct, 1)} % of body weight: in this heat start slower and pre-cool.`)
    else warnings.push(`Even drinking to plan you may lose about ${fmt(lossPct, 1)} % of body weight: drink to thirst on top of the plan, never more than you sweat.`)
  }
  if (gph > 60 && !products.some((p) => (p.fructoseG ?? 0) > 0)) warnings.push('Above 60 g an hour, use products that mix glucose (or maltodextrin) with fructose; check yours.')
  if (sf.kind === 'HIGH' && input.settings.toleratedGPerHour < MAX_G_PER_H) assumptions.push(`Capped at your tolerated ${roundToInt(input.settings.toleratedGPerHour)} g an hour; train the gut in long sessions to raise it.`)

  const before: PrepItem[] = []
  if (input.loadingDays) before.push({ minutesFromStart: -48 * 60, whenLabel: '2 DAYS BEFORE', text: `Carbohydrate loading: 10-12 g/kg a day (${roundToInt(10 * kg)}-${roundToInt(12 * kg)} g), as Today's fuel plans it.` })
  before.push({ minutesFromStart: -15 * 60, whenLabel: 'EVENING BEFORE', text: 'A familiar carbohydrate-rich meal, low in fibre and fat. Nothing new.' })
  const breakfastGPerKg = (input.history?.catchUpG ?? 0) > 0 ? 3 : 2
  before.push({ minutesFromStart: -180, whenLabel: '3 H BEFORE', text: `Breakfast: about ${roundToInt(breakfastGPerKg * kg)} g of carbohydrate (${roundToInt(breakfastGPerKg)} g/kg; 1-4 g/kg works), little fat and fibre.` })
  const preMlPerKg = 5 + (input.history?.preHydrationBoost === true || band === 'HOT' || band === 'VERY_HOT' ? 2 : 0)
  before.push({ minutesFromStart: -240, whenLabel: '4 H BEFORE', text: `Drink ${roundToInt((preMlPerKg * kg) / 50) * 50} ml (${roundToInt(preMlPerKg)} ml/kg) with something salty, then to thirst.` })
  if (band === 'HOT' || band === 'VERY_HOT') before.push({ minutesFromStart: -30, whenLabel: '30 MIN BEFORE', text: 'Pre-cool: an ice slurry or a cold drink (about 7 g/kg), and stay in the shade.' })
  before.push({ minutesFromStart: -15, whenLabel: '15 MIN BEFORE', text: early ? '150-250 ml of fluid and a first gel: loading fell short, so start topping up early.' : '150-250 ml of fluid.' })
  if (caffeineTotal > 0 && duration <= 90) before.push({ minutesFromStart: -50, whenLabel: '45-60 MIN BEFORE', text: `Caffeine: ${roundToInt(caffeineTotal)} mg (${fmtNum(input.settings.caffeineMgPerKg)} mg/kg), only if you have used it in training.` })
  before.sort((a, b) => a.minutesFromStart - b.minutesFromStart)

  const lossMl = Math.max(0, totalSweat - totalFluid)
  const after: PrepItem[] = [
    { minutesFromStart: duration + 15, whenLabel: 'FIRST 30 MIN', text: `Carbohydrate ${roundToInt(1.0 * kg)}-${roundToInt(1.2 * kg)} g an hour for 4 hours, with ${roundToInt(0.3 * kg)} g of protein soon.` },
    { minutesFromStart: duration + 30, whenLabel: 'NEXT HOURS', text: `Drink ${roundToInt((1.25 * lossMl) / 50) * 50}-${roundToInt((1.5 * lossMl) / 50) * 50} ml over the next hours (1.25-1.5 × the ~${roundToInt(lossMl / 50) * 50} ml you are likely to lose), with salty food.` },
    { minutesFromStart: duration + 5, whenLabel: 'AT THE FINISH', text: 'Weigh yourself before drinking: with your start weight and what you drank, the race becomes a sweat test.' },
  ].sort((a, b) => a.minutesFromStart - b.minutesFromStart)

  assumptions.push(`Duration: ${fmtMin(duration)} (${input.durationBasis}), likely ${fmtMin(input.durationLowMin)}-${fmtMin(input.durationHighMin)}.`)
  assumptions.push(input.weather.source === 'FORECAST' ? 'Weather: the forecast at the start.'
    : input.weather.source === 'TYPICAL' ? `Weather: ${input.weather.note ?? 'typical for the date'} Ranges are wider until the forecast reaches the race (16 days out).`
    : 'Weather: unknown (no place or no forecast); a mild 18 °C is assumed.')
  assumptions.push(`Sweat: about ${roundToInt(sweatMean.mlPerHour / 10) * 10} ml an hour (${roundToInt(sweatMean.lowMlPerHour / 10) * 10}-${roundToInt(sweatMean.highMlPerHour / 10) * 10}), from ${sweatMean.basis}.`)
  if (input.history) assumptions.push(...input.history.notes)

  return {
    title: input.title, durationMin: duration, durationLowMin: input.durationLowMin, durationHighMin: input.durationHighMin, carbsPerHourLow: sf.gPerHourLow, carbsPerHourHigh: sf.gPerHourHigh,
    fluidMlPerHourLow: fluidMean.mlPerHourLow, fluidMlPerHourHigh: fluidMean.mlPerHourHigh, sodiumMgPerL: fluidMean.sodiumMgPerL, sweat: sweatMean, weatherSource: input.weather.source, heat,
    steps, legs, before, after, totalCarbsG: sum(steps.map((s) => s.carbsG)), totalFluidMl: totalFluid, totalSodiumMg: sum(steps.map((s) => s.sodiumMg)), totalCaffeineMg: caffeineTotal,
    expectedLossPct: lossPct, assumptions, warnings,
  }
}

export interface TimeEstimateRange { minutes: number; lowMinutes: number; highMinutes: number; basis: string }
/** The duration and its range for a plan: a target time ±5 %, an expected time ±10 %, widened by a history estimate. */
export function durationRange(targetMin: number | null, expectedMin: number | null, estimate: TimeEstimateRange | null): { base: number; low: number; high: number; basis: string } | null {
  const base = targetMin ?? expectedMin ?? estimate?.minutes
  if (base == null) return null
  let lo: number
  let hi: number
  let basis: string
  if (targetMin != null) { lo = base * 0.95; hi = base * 1.05; basis = 'your target time' }
  else if (expectedMin != null) { lo = base * 0.9; hi = base * 1.1; basis = 'your expected time' }
  else { lo = (estimate as TimeEstimateRange).lowMinutes; hi = (estimate as TimeEstimateRange).highMinutes; basis = (estimate as TimeEstimateRange).basis }
  return { base, low: Math.min(lo, base), high: Math.max(hi, base), basis }
}
