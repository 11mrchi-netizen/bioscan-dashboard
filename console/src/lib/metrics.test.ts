import test from 'node:test'
import assert from 'node:assert/strict'
import { INPUT_SERIES, metricHref, parseMetricRef, refString, SERIES_HUE, seriesBehind, windowStats } from './metrics.ts'
import { SERIES } from './schema.ts'

test('old History refs still parse: a ring and an input', () => {
  assert.deepEqual(parseMetricRef('charge'), { kind: 'ring', key: 'charge' })
  assert.deepEqual(parseMetricRef('SLEEP'), { kind: 'ring', key: 'sleep' })
  assert.deepEqual(parseMetricRef('recovery.hrv'), { kind: 'input', key: 'recovery.hrv' })
  assert.deepEqual(parseMetricRef('gut.form_3d'), { kind: 'input', key: 'gut.form_3d' })
})

test('series and Me refs, and a bare series id', () => {
  assert.deepEqual(parseMetricRef('series:hrv'), { kind: 'series', key: 'hrv' })
  assert.deepEqual(parseMetricRef('hrv'), { kind: 'series', key: 'hrv' })
  assert.deepEqual(parseMetricRef('me:body:Body fat: DEXA'), { kind: 'me', tab: 'BODY', label: 'Body fat: DEXA' })
  assert.equal(parseMetricRef('series:nope'), null)
  assert.equal(parseMetricRef('nothing.here'), null)
  assert.equal(parseMetricRef(null), null)
})

test('a ref survives the round trip through the address', () => {
  for (const raw of ['charge', 'recovery.hrv', 'series:sleep_hours', 'me:BODY:Weight (7 d)']) {
    const ref = parseMetricRef(raw)!
    assert.equal(refString(ref), raw)
    const path = metricHref(raw).slice(4).split('?')[0]
    assert.equal(decodeURIComponent(path), raw)
  }
  assert.equal(metricHref('charge', { day: '2026-11-20', range: '90' }), '#/m/charge?day=2026-11-20&range=90')
})

test('every bridged input names a real series, and every series has a metric hue', () => {
  for (const [input, series] of Object.entries(INPUT_SERIES)) {
    assert.ok(parseMetricRef(input), input)
    assert.ok(SERIES.some((s) => s.id === series), `${input} -> ${series}`)
    assert.equal(seriesBehind(parseMetricRef(input)!), series)
  }
  for (const s of SERIES) assert.ok(SERIES_HUE[s.id], `no hue for series ${s.id}`)
})

test('window stats ignore gaps', () => {
  assert.equal(windowStats([{ day: 'a', value: null }]), null)
  const s = windowStats([{ day: '2026-11-01', value: 4 }, { day: '2026-11-02', value: null }, { day: '2026-11-03', value: 10 }, { day: '2026-11-04', value: 1 }])!
  assert.equal(s.readings, 3)
  assert.equal(s.average, 5)
  assert.deepEqual(s.lowest, { day: '2026-11-04', value: 1 })
  assert.deepEqual(s.highest, { day: '2026-11-03', value: 10 })
})
