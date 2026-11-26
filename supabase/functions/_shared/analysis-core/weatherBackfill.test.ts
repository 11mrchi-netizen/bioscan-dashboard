import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fetchWeather, roundCoord, weatherDaysOf } from './weatherBackfill.ts'

test('weatherDaysOf maps the archive answer, converts daylight to hours and drops empty days', () => {
  const body = { daily: { time: ['2026-11-01', '2026-11-02', 'bad'], temperature_2m_max: [27.4, null, 30], relative_humidity_2m_mean: [78, null, 70], daylight_duration: [38520, null, 36000] } }
  assert.deepEqual(weatherDaysOf(body), [{ date: '2026-11-01', temp_max_c: 27.4, humidity_mean_pct: 78, daylight_hours: 10.7 }])
  assert.deepEqual(weatherDaysOf(null), [])
  assert.deepEqual(weatherDaysOf({ daily: {} }), [])
})

test('fetchWeather sends only a coordinate rounded to 0.1 degree and the date range', async () => {
  let seen = ''
  const days = await fetchWeather(25.04321, 121.56789, '2026-11-01', '2026-11-02', async (u) => { seen = u; return { daily: { time: ['2026-11-01'], temperature_2m_max: [25], relative_humidity_2m_mean: [60], daylight_duration: [39600] } } })
  assert.equal(days.length, 1)
  assert.match(seen, /latitude=25&longitude=121.6&/)
  assert.equal(roundCoord(25.04321), 25)
  assert.ok(!/25\.04|121\.56/.test(seen))
})
