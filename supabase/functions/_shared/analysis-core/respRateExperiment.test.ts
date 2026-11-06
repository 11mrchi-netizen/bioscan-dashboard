// Sleep respiratory rate as a formal experiment outcome: the frame carries it and the experiment vocabulary knows it.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { DEFAULT_ZONE, METRIC_INFO } from './experiment.ts'
import { ALL_METRICS } from './runExperiments.ts'
import { buildFrame } from './runRelationships.ts'

test('buildFrame carries the night respiratory rate and drops implausible zeros', () => {
  const f = buildFrame({
    wearable: [], wellbeing: [], meals: [], sessions: [], recovery: [], illnesses: [],
    sleep: [
      { date: '2026-10-19', hours: 7, respiratory_rate: 14.2 },
      { date: '2026-10-20', hours: 7, respiratory_rate: 0 },
    ],
  }, '2026-10-21', 4)
  assert.equal(f.series.resp_rate.length, 4)
  assert.equal(f.series.resp_rate[f.dates.indexOf('2026-10-19')], 14.2)
  assert.equal(f.series.resp_rate[f.dates.indexOf('2026-10-20')], null)
})

test('resp_rate is a known experiment metric with its own unit and no-effect zone', () => {
  assert.equal(METRIC_INFO.resp_rate.unit, 'br/min')
  assert.equal(DEFAULT_ZONE.resp_rate.zone, 0.3)
  assert.ok(ALL_METRICS.includes('resp_rate'))
})
