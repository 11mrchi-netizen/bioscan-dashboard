// Run: node --test "supabase/functions/_shared/fuel-core/*.test.ts"
// Parity with the Android stored plans: the snapshot of a day or an event, its JSON text, the hash fuel_plans.inputs_hash holds, and the lines of what changed
// (fixtures/snapshots-day.json, snapshots-event.json, written by the Kotlin MealCodesParityTest and RaceFuelParityTest).
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import {
  codeMeals, daySnapshot, describeDayChanges, describeEventChanges, eventSnapshot, javaHashHex, planFuelDays, planRaceFuel, type PlannedDay, snapshotHash, snapshotText, sweatProfile, type WeekEnergy,
} from './index.ts'

const dir = new URL('./fixtures/', import.meta.url)
const read = (n: string) => JSON.parse(readFileSync(new URL(n, dir), 'utf8'))
const dayInput = read('day-input.json') as { name: string; weightKg: number; fatFreeMassKg: number | null; energy: WeekEnergy; days: PlannedDay[] }[]
const daySnaps = read('snapshots-day.json') as Record<string, { days: { snapshot: unknown; text: string; hash: string }[]; changes: string[][] }>
const raceInput = read('race-input.json')
const eventSnaps = read('snapshots-event.json')

for (const s of dayInput.filter((d) => daySnaps[d.name])) {
  test(`stored day plans: ${s.name}`, () => {
    const plans = planFuelDays(s.days, s.energy, s.weightKg, s.fatFreeMassKg)
    const snaps = s.days.map((d, i) => daySnapshot(plans[i], codeMeals(plans[i], d, s.days[i + 1] ?? null, s.weightKg, 'Asia/Taipei', undefined, false, s.energy.deficit === true), d.items))
    const want = daySnaps[s.name]
    snaps.forEach((sn, i) => {
      assert.deepEqual(JSON.parse(JSON.stringify(sn)), want.days[i].snapshot, `${s.name}[${i}] snapshot`)
      assert.equal(snapshotText(sn), want.days[i].text, `${s.name}[${i}] text`)
      assert.equal(snapshotHash(sn), want.days[i].hash, `${s.name}[${i}] hash`)
    })
    snaps.slice(1).forEach((b, i) => assert.deepEqual(describeDayChanges(snaps[i], b), want.changes[i], `${s.name}[${i}] changes`))
  })
}

test('stored event plans: snapshot, text, hash and changes', () => {
  const snaps = (raceInput.scenarios as any[]).map((s) => {
    const plan = planRaceFuel({
      title: s.title, type: s.type, intensity: s.intensity, start: s.start, durationMin: s.durationMin, durationLowMin: s.durationLowMin, durationHighMin: s.durationHighMin,
      durationBasis: s.durationBasis, course: s.course, distanceKm: s.distanceKm, aidStations: s.aidStations, weather: s.weather, sweat: sweatProfile(s.sweatTests, s.weightKg),
      weightKg: s.weightKg, settings: s.settings, products: s.products, history: s.history, loadingDays: s.loadingDays,
    })
    const sn = eventSnapshot(plan)
    const want = eventSnaps[s.name]
    assert.deepEqual(JSON.parse(JSON.stringify(sn)), want.snapshot, `${s.name} snapshot`)
    assert.equal(snapshotText(sn), want.text, `${s.name} text`)
    assert.equal(snapshotHash(sn), want.hash, `${s.name} hash`)
    return sn
  })
  snaps.slice(1).forEach((b, i) => assert.deepEqual(describeEventChanges(snaps[i], b), eventSnaps._changes[i], `changes ${i}`))
})

test('the hash is Java\'s: signed, in hex, over UTF-16 code units', () => {
  assert.equal(javaHashHex(''), '0')
  assert.equal(javaHashHex('a'), '61')
  assert.equal(javaHashHex('Aa'), javaHashHex('BB')) // the classic collision: 31 * 65 + 97 = 31 * 66 + 66
  assert.equal(javaHashHex('polygenelubricants'), '-80000000') // Integer.MIN_VALUE
  assert.equal(javaHashHex('😀'), (() => { let h = 0; for (const c of '😀') for (let i = 0; i < c.length; i++) h = (31 * h + c.charCodeAt(i)) | 0; return h < 0 ? `-${(-h >>> 0).toString(16)}` : h.toString(16) })())
  assert.equal(snapshotText({ a: 'x"y\n', b: [1, null], c: { d: true } }), '{"a":"x\\"y\\n","b":[1,null],"c":{"d":true}}')
})
