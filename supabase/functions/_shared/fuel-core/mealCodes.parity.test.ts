// Run: node --test "supabase/functions/_shared/fuel-core/*.test.ts"
// Parity with the Android coded meals: the days of fixtures/day-input.json are planned and coded under the settings of meals-input.json; the
// meals the Kotlin MealCodesParityTest records (meals-expected.json) must come out of the TypeScript exactly, reasons and times included.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { adherence, codeMeals, instant, mealForLog, type MealTimes, planFuelDays, type PlannedDay, type WeekEnergy, zdtString } from './index.ts'

const dir = new URL('./fixtures/', import.meta.url)
const days = JSON.parse(readFileSync(new URL('day-input.json', dir), 'utf8')) as { name: string; weightKg: number; fatFreeMassKg: number | null; energy: WeekEnergy; days: PlannedDay[] }[]
const { variants } = JSON.parse(readFileSync(new URL('meals-input.json', dir), 'utf8')) as {
  variants: { name: string; trainLow: boolean; deficit: boolean; tolerated: number; breakfast: string; lunch: string; snack: string | null; dinner: string }[]
}
const expected = JSON.parse(readFileSync(new URL('meals-expected.json', dir), 'utf8')) as Record<string, Record<string, unknown[]>>

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

for (const s of days.filter((d) => expected[d.name])) {
  test(`coded meals: ${s.name}`, () => {
    const plans = planFuelDays(s.days, s.energy, s.weightKg, s.fatFreeMassKg)
    for (const [name, want] of Object.entries(expected[s.name])) {
      const v = variants.find((x) => x.name === name)!
      const times: MealTimes = { breakfast: v.breakfast, lunch: v.lunch, snack: v.snack, dinner: v.dinner }
      const got = s.days.map((d, i) => codeMeals(plans[i], d, s.days[i + 1] ?? null, s.weightKg, 'Asia/Taipei', times, v.trainLow, v.deficit, v.tolerated))
      same(JSON.parse(JSON.stringify(got)), want, `${s.name}/${name}`)
    }
  })
}

test('the scenarios reach what they should', () => {
  const text = JSON.stringify(expected)
  for (const needle of [
    'Loading day: every meal carries carbohydrate.', 'top up for the work', 'refill (1-1.2 g/kg', "Tonight's meal is its fuel", 'training low, as you chose', 'Rest day: carbohydrate low',
    'Training day: a normal share.', '"kind":"PRE_SESSION"', '"kind":"RECOVERY"', '"kind":"NONE"', '"kind":"RINSE"', '"kind":"STEADY"', '"kind":"HIGH"', 'tolerated ceiling', 'glucose and fructose',
  ]) assert.ok(text.includes(needle), `no scenario reaches: ${needle}`)
})

test('a logged meal finds its coded meal within two hours, and its carbohydrate is judged against the range', () => {
  const logs = JSON.parse(readFileSync(new URL('logs-expected.json', dir), 'utf8')) as Record<string, { meal: string; at: string; hit: string | null; adherence: string[]; g: number[] }[][]>
  for (const s of days.filter((d) => logs[d.name])) {
    const plans = planFuelDays(s.days, s.energy, s.weightKg, s.fatFreeMassKg)
    s.days.forEach((d, i) => {
      const coded = codeMeals(plans[i], d, s.days[i + 1] ?? null, s.weightKg, 'Asia/Taipei', undefined, false, s.energy.deficit === true)
      const cases = logs[s.name][i]
      let k = 0
      for (const m of coded.meals) {
        for (const off of [-130, -121, -90, 0, 45, 119, 120, 121]) {
          const want = cases[k++]
          assert.equal(want.meal, m.kind)
          const at = instant(m.time) + off * 60000
          assert.equal(zdtString(at, 'Asia/Taipei'), want.at, `${s.name}[${i}] ${m.kind} ${off}: time`)
          assert.equal(mealForLog(coded.meals, at)?.kind ?? null, want.hit, `${s.name}[${i}] ${m.kind} ${off}: meal`)
          const g = [0.8 * m.carbLowG, m.carbLowG, (m.carbLowG + m.carbHighG) / 2, m.carbHighG, m.carbHighG + 1]
          assert.deepEqual([...g.map((x) => adherence(m, x)), adherence(m, null)], want.adherence, `${s.name}[${i}] ${m.kind}: adherence`)
        }
      }
      assert.equal(k, cases.length)
    })
  }
})
