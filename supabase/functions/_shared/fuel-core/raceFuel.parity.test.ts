// Run: node --test "supabase/functions/_shared/fuel-core/*.test.ts"
// Parity with the Android race and long-session fuel plan: fixtures/race-input.json (scenarios with courses, aid stations, weather, sweat tests, products,
// settings), race-expected.json (what the Kotlin RaceFuelParityTest plans from them, every sentence included).
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import {
  type AidStation, checkIntakeHistory, climatology, type CoursePoint, type DayFuelPlan, type FuelProductOption, type HourWeather, type Intensity, type LoggedDay, planFuelDays, planRaceFuel,
  durationRange, estimateRunTime, type RecentRun, heatBand, heatIndexC, type PlannedDay, type RaceFuelSettings, type SweatTest, sweatProfile, type WeatherSeries, type WeekEnergy, withCatchUp, type IntakeHistoryResult,
} from './index.ts'

const dir = new URL('./fixtures/', import.meta.url)
const input = JSON.parse(readFileSync(new URL('race-input.json', dir), 'utf8'))
const expected = JSON.parse(readFileSync(new URL('race-expected.json', dir), 'utf8'))

function same(a: unknown, b: unknown, path = '$'): void {
  if (typeof a === 'number' && typeof b === 'number') { assert.ok(Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(b)), `${path}: ${a} vs ${b}`); return }
  if (Array.isArray(a) && Array.isArray(b)) { assert.equal(a.length, b.length, `${path}: length`); a.forEach((x, i) => same(x, b[i], `${path}[${i}]`)); return }
  if (a && b && typeof a === 'object' && typeof b === 'object') {
    assert.deepEqual(Object.keys(a).sort(), Object.keys(b).sort(), `${path}: keys`)
    for (const k of Object.keys(a)) same((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k], `${path}.${k}`)
    return
  }
  assert.equal(a, b, path)
}

interface Scenario {
  name: string; intensity: Intensity; type: string; durationMin: number; durationLowMin: number; durationHighMin: number; durationBasis: string; weather: WeatherSeries
  course: CoursePoint[]; distanceKm: number | null; aidStations: AidStation[]; products: FuelProductOption[]; sweatTests: SweatTest[]; settings: RaceFuelSettings
  history: IntakeHistoryResult | null; loadingDays: boolean; weightKg: number; start: string; title: string
}

for (const s of input.scenarios as Scenario[]) {
  test(`race fuel: ${s.name}`, () => {
    const plan = planRaceFuel({
      title: s.title, type: s.type, intensity: s.intensity, start: s.start, durationMin: s.durationMin, durationLowMin: s.durationLowMin, durationHighMin: s.durationHighMin,
      durationBasis: s.durationBasis, course: s.course, distanceKm: s.distanceKm, aidStations: s.aidStations, weather: s.weather, sweat: sweatProfile(s.sweatTests, s.weightKg),
      weightKg: s.weightKg, settings: s.settings, products: s.products, history: s.history, loadingDays: s.loadingDays,
    })
    same(JSON.parse(JSON.stringify(plan)), expected.plans[s.name], s.name)
  })
}

test('the days before', () => {
  const i = input.intake as { energy: WeekEnergy; weightKg: number; days: PlannedDay[]; cases: { name: string; logged: LoggedDay[]; raceSoon: boolean; morningWeightKg: number | null; usualWeightKg: number | null }[] }
  const plans = planFuelDays(i.days, i.energy, i.weightKg)
  for (const c of i.cases) {
    const r = checkIntakeHistory(plans.slice(0, 5), c.logged, 73.5, c.raceSoon, c.morningWeightKg, c.usualWeightKg)
    same(JSON.parse(JSON.stringify(r)), expected.intake[c.name].result, `${c.name}/result`)
    same(JSON.parse(JSON.stringify(withCatchUp(plans[4], r.catchUpG) as DayFuelPlan)), expected.intake[c.name].withCatchUp, `${c.name}/withCatchUp`)
  }
})

test('the typical weather of past years', () => {
  const c = input.climatology as { samples: HourWeather[]; targets: string[]; years: number }
  same(JSON.parse(JSON.stringify(climatology(c.samples, c.targets, c.years))), expected.climatology, 'climatology')
})

test('the heat index and its bands over a grid of temperature and humidity', () => {
  const cells = input.heat as { tempC: number; humidityPct: number | null }[]
  cells.forEach((c, i) => {
    const want = expected.heat[i]
    const got = c.humidityPct == null ? c.tempC : heatIndexC(c.tempC, c.humidityPct)
    assert.ok(Math.abs(got - want.index) <= 1e-9 * Math.max(1, Math.abs(want.index)), `index ${c.tempC}/${c.humidityPct}: ${got} vs ${want.index}`)
    assert.equal(heatBand(c.tempC, c.humidityPct), want.band, `band ${c.tempC}/${c.humidityPct}`)
  })
})

test('the duration from the person\'s runs, and its range', () => {
  const cases = input.estimates as { km: number; ascentM: number; runs: RecentRun[] }[]
  cases.forEach((c, i) => {
    const e = estimateRunTime(c.km, c.ascentM, c.runs)
    const want = expected.estimates[i]
    same(JSON.parse(JSON.stringify(e)), want.estimate, `estimate ${i}`)
    same(JSON.parse(JSON.stringify(durationRange(null, null, e))), want.history, `history ${i}`)
    same(JSON.parse(JSON.stringify(durationRange(210, 180, e))), want.target, `target ${i}`)
    same(JSON.parse(JSON.stringify(durationRange(null, 540, e))), want.expected, `expected ${i}`)
  })
})

test('the scenarios reach what they should', () => {
  const text = JSON.stringify(expected.plans)
  for (const needle of [
    'generic 25 g gels', 'No aid stations entered', 'after its', 'slow end of your range', 'With no sweat test yet', 'Even drinking to plan', 'glucose (or maltodextrin) with fructose', 'tolerated',
    'Pre-cool', 'Caffeine:', 'mg caffeine', '2 DAYS BEFORE', 'loading fell short', 'Weather: the forecast', 'Weather: Typical weather', 'Weather: unknown', 'Refill at', 'drop bag', 'Capped at your tolerated',
    'Hot: sweat losses rise', 'Cold: you sweat less', 'Warm: sweat losses', 'Strong sun', '1 year in 10 above',
  ]) assert.ok(text.includes(needle), `no scenario reaches: ${needle}`)
})
