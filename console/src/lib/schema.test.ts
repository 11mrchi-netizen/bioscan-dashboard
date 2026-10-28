// Run: node --test console/src/lib/schema.test.ts
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { chartSpecFor } from './chartSpec.ts'
import { LIVE_COLUMNS, SERIES, TABLES_WITH_SOURCE, dayBounds, localDate, seriesById } from './schema.ts'

test('every column a series contract reads exists in the live schema snapshot', () => {
  for (const s of SERIES) {
    const cols = LIVE_COLUMNS[s.table]
    assert.ok(cols, `${s.id}: table ${s.table} missing from LIVE_COLUMNS`)
    for (const c of [s.column, s.time.column, ...(s.time.offsetColumn ? [s.time.offsetColumn] : []), ...Object.keys(s.filters ?? {})]) {
      assert.ok(cols.includes(c), `${s.id}: ${s.table}.${c} is not a live column`)
    }
    assert.equal(s.hasSource, cols.includes('source'), `${s.id}: hasSource disagrees with the snapshot`)
  }
})

test('the old guessed column names are gone', () => {
  const used = new Set(SERIES.map((s) => s.column))
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

test('chart specs come from the contracts', () => {
  const hrv = chartSpecFor(seriesById('hrv')!)
  assert.deepEqual([hrv.kind, hrv.metricId, hrv.title, hrv.unit, hrv.aggregation], ['line', 'wearable_daily.hrv', 'HRV (RMSSD)', 'ms', 'mean'])
  assert.equal(chartSpecFor(seriesById('steps')!).kind, 'bar')
  assert.equal(chartSpecFor(seriesById('meal_protein')!).kind, 'bar')
  for (const s of SERIES) {
    const [table, column] = chartSpecFor(s).metricId.split('.')
    assert.ok(LIVE_COLUMNS[table]?.includes(column), `${s.id}: ${table}.${column} is not a live column`)
  }
})
