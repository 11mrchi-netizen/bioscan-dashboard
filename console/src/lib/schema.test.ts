// Run: node --test console/src/lib/schema.test.ts
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CHART_SPECS } from './chartSpec.ts'
import { LIVE_COLUMNS, METRICS, TABLES_WITH_SOURCE, dayBounds, localDate } from './schema.ts'

test('every metric the console names exists in the live schema snapshot', () => {
  for (const [name, m] of Object.entries(METRICS)) {
    const cols = LIVE_COLUMNS[m.table]
    assert.ok(cols, `${name}: table ${m.table} missing from LIVE_COLUMNS`)
    assert.ok(cols.includes(m.column), `${name}: ${m.table}.${m.column} is not a live column`)
  }
})

test('the old guessed column names are gone', () => {
  const used = new Set(Object.values(METRICS).map((m) => m.column))
  for (const bad of ['hrv_rmssd_ms', 'resting_hr_bpm', 'total_sleep_minutes', 'sleep_efficiency_pct', 'energy_level', 'stress_level', 'muscle_soreness']) {
    assert.ok(!used.has(bad), bad)
  }
})

test('source is only selected from tables that have it', () => {
  for (const t of TABLES_WITH_SOURCE) {
    if (LIVE_COLUMNS[t]) assert.ok(LIVE_COLUMNS[t].includes('source'), t)
  }
  assert.ok(!LIVE_COLUMNS.wellbeing_daily.includes('source'))
})

test('timestamp helpers', () => {
  assert.equal(localDate('2026-10-12T08:30:00+00:00'), '2026-10-12')
  assert.equal(localDate(null), '')
  assert.deepEqual(dayBounds('2026-10-01', '2026-10-02'), { from: '2026-10-01T00:00:00', to: '2026-10-02T23:59:59' })
})

test('every metric a workspace charts has a chart spec under its live id', () => {
  for (const m of [METRICS.HRV, METRICS.RHR, METRICS.STEPS, METRICS.SLEEP_DURATION, METRICS.ENERGY_LEVEL, METRICS.MOOD, METRICS.BODY_WEIGHT]) {
    const id = `${m.table}.${m.column}`
    assert.ok(CHART_SPECS[id], `no CHART_SPECS entry for ${id}`)
    assert.equal(CHART_SPECS[id].metricId, id)
  }
  for (const id of Object.keys(CHART_SPECS)) {
    const [table, column] = id.split('.')
    assert.ok(LIVE_COLUMNS[table]?.includes(column), `${id} is not a live column`)
  }
})
