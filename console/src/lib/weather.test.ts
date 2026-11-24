// Run: node --test "src/lib/*.test.ts"
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fetchHourly, hoursOf, minusYears } from './weather.ts'

const zone = 'Asia/Taipei'
const day = (date: string, base: number) => ({
  time: Array.from({ length: 24 }, (_, h) => `${date}T${String(h).padStart(2, '0')}:00`), temperature_2m: Array.from({ length: 24 }, (_, h) => base + h / 4),
  relative_humidity_2m: Array.from({ length: 24 }, () => 70), dew_point_2m: Array.from({ length: 24 }, () => 18), apparent_temperature: Array.from({ length: 24 }, () => null),
  shortwave_radiation: Array.from({ length: 24 }, (_, h) => h * 30), windspeed_10m: Array.from({ length: 24 }, () => 6), precipitation: Array.from({ length: 24 }, () => 0),
})

test('Open-Meteo hours become zoned hours', () => {
  const h = hoursOf(day('2026-12-05', 15), zone)
  assert.equal(h.length, 24)
  assert.equal(h[6].time, '2026-12-05T06:00+08:00[Asia/Taipei]')
  assert.equal(h[6].tempC, 16.5)
  assert.equal(h[6].apparentC, null)
})

test('a race within 16 days is the forecast, further out the typical weather of the same dates', async () => {
  const calls: string[] = []
  const fetcher = async (u: string) => {
    calls.push(u)
    const q = new URL(u).searchParams
    return { ok: true, status: 200, json: async () => ({ hourly: day(q.get('start_date') as string, 14) }) }
  }
  const start = Date.parse('2026-12-05T06:30:00+08:00')
  const soon = await fetchHourly(24, 121, start - 3600000, start + 5 * 3600000, zone, Date.parse('2026-11-24T08:00:00+08:00'), 10, fetcher)
  assert.equal(soon.source, 'FORECAST')
  assert.equal(calls.length, 1)
  assert.ok(calls[0].startsWith('https://api.open-meteo.com/v1/forecast?') && calls[0].includes('start_date=2026-12-05') && calls[0].includes('timezone=Asia%2FTaipei'))
  assert.equal(soon.hours.length, 7) // 05:00 to 11:00
  calls.length = 0
  const later = await fetchHourly(24, 121, Date.parse('2027-03-06T05:00:00+08:00'), Date.parse('2027-03-06T14:00:00+08:00'), zone, Date.parse('2026-11-24T08:00:00+08:00'), 10, fetcher)
  assert.equal(later.source, 'TYPICAL')
  assert.equal(calls.length, 10)
  assert.ok(calls[0].startsWith('https://archive-api.open-meteo.com/v1/archive?') && calls[0].includes('start_date=2026-03-03') && calls[0].includes('end_date=2026-03-09'))
  assert.ok(later.hours.length >= 9 && later.hours.every((x) => x.tempP90C != null))
  const none = await fetchHourly(24, 121, Date.parse('2027-03-06T05:00:00+08:00'), Date.parse('2027-03-06T14:00:00+08:00'), zone, Date.parse('2026-11-24T08:00:00+08:00'), 3, async () => ({ ok: false, status: 429, json: async () => ({}) }))
  assert.equal(none.source, 'NONE')
})

test('a leap day goes back to the 28th', () => {
  assert.equal(minusYears('2028-02-29', 1), '2027-02-28')
  assert.equal(minusYears('2028-02-29', 4), '2024-02-29')
})
