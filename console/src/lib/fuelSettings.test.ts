// Run: node --test "src/lib/*.test.ts"
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { DEFAULT_ACCOUNT_FUEL_SETTINGS, fuelSettingsOf, homeOf, mealTimesText, raceSettingsOf } from './fuelSettings.ts'

// the JSON the phone writes (android-app FuelSettingsJsonTest.roundTripsEveryField)
const PHONE = '{"breakfast":"07:15","lunch":"13:00","snack":null,"dinner":"20:00","trainLow":true,"toleratedGPerHour":75.0,"caffeineOk":true,"caffeineMgPerKg":2.5,"saltySweater":true}'

test('the phone\'s settings are read field for field', () => {
  const r = fuelSettingsOf(JSON.parse(PHONE))
  assert.equal(r.fromPhone, true)
  assert.deepEqual(r.settings, { meals: { breakfast: '07:15', lunch: '13:00', snack: null, dinner: '20:00' }, trainLow: true, toleratedGPerHour: 75, caffeineOk: true, caffeineMgPerKg: 2.5, saltySweater: true })
  assert.deepEqual(raceSettingsOf(r.settings), { toleratedGPerHour: 75, caffeineOk: true, caffeineMgPerKg: 2.5, saltySweater: true })
  assert.equal(mealTimesText(r.settings), '07:15, 13:00, no snack, 20:00')
})

test('nothing sent is the defaults; a missing or bad key is its default', () => {
  assert.deepEqual(fuelSettingsOf(null), { settings: DEFAULT_ACCOUNT_FUEL_SETTINGS, fromPhone: false })
  assert.deepEqual(fuelSettingsOf([1]), { settings: DEFAULT_ACCOUNT_FUEL_SETTINGS, fromPhone: false })
  const r = fuelSettingsOf({ breakfast: '8:00', toleratedGPerHour: '90', caffeineMgPerKg: -1, trainLow: 'yes' })
  assert.deepEqual(r, { settings: DEFAULT_ACCOUNT_FUEL_SETTINGS, fromPhone: true })
  assert.equal(fuelSettingsOf({ lunch: '12:00' }).settings.meals.snack, DEFAULT_ACCOUNT_FUEL_SETTINGS.meals.snack)
  assert.equal(fuelSettingsOf({ breakfast: '06:45:00' }).settings.meals.breakfast, '06:45')
})

test('home is the oldest Home place still in use', () => {
  const places = [
    { lat: 1, lon: 2, category: 'work', stop_since: null, created_at: '2026-01-01' },
    { lat: 3, lon: 4, category: 'home', stop_since: null, created_at: '2026-03-01' },
    { lat: 5, lon: 6, category: 'home', stop_since: '2026-04-01', created_at: '2026-02-01' },
    { lat: 7, lon: 8, category: 'home', stop_since: null, created_at: '2026-05-01' },
  ]
  assert.deepEqual(homeOf(places), { lat: 3, lon: 4 })
  assert.equal(homeOf(places.slice(0, 1)), null)
})
