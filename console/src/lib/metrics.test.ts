import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { washFor, rangeFor, INPUT_HUE, INPUT_SERIES, meRef, splitMeLabel, metricHref, parseMetricRef, refString, SERIES_HUE, seriesBehind, windowStats } from './metrics.ts'
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

test('every score input of the phone has a metric hue, and every hue is a token', () => {
  const kt = readFileSync(new URL('../../../android-app/app/src/main/java/com/bioscan/fieldterminal/domain/score/ScoreDomains.kt', import.meta.url), 'utf8')
  const keys = [...new Set([...kt.matchAll(/const val [A-Z_0-9]+ = "((?:sleep|recovery|load|fuel|gut|ans|mind|rhythm)\.[a-z0-9_]+)"/g)].map((m) => m[1]))]
  assert.ok(keys.length >= 38, `found ${keys.length} keys`)
  for (const k of keys) assert.ok(INPUT_HUE[k], `no hue for input ${k}`)
  const css = readFileSync(new URL('../../../design/tokens/tokens.css', import.meta.url), 'utf8')
  for (const t of new Set(Object.values(INPUT_HUE))) assert.ok(css.includes(`--${t}:`), `token --${t} is not in tokens.css`)
})

test('a Me ref keeps its section, and an older ref with the label alone still splits', () => {
  assert.deepEqual(splitMeLabel('WEIGHT::Average'), { section: 'WEIGHT', item: 'Average' })
  assert.deepEqual(splitMeLabel('Weight'), { section: null, item: 'Weight' })
  assert.deepEqual(parseMetricRef(meRef('BODY', 'WEIGHT', 'Average')), { kind: 'me', tab: 'BODY', label: 'WEIGHT::Average' })
})

test('a focused morning picks the smallest range that holds it, never less than 90 days', () => {
  assert.equal(rangeFor(null, '2026-11-23').id, '90')
  assert.equal(rangeFor('2026-11-20', '2026-11-23').id, '90')
  assert.equal(rangeFor('2026-08-01', '2026-11-23').id, '365') // 114 days back
  assert.equal(rangeFor('2025-09-01', '2026-11-23').id, 'all')
  assert.equal(rangeFor('junk', '2026-11-23').id, '90')
})

test('every metric has a domain wash that is a token', () => {
  const css = readFileSync(new URL('../../../design/tokens/tokens.css', import.meta.url), 'utf8')
  const refs = ['charge', 'sleep', 'recovery.hrv', 'gut.form_3d', 'series:sleep_hours', 'series:meal_energy', 'series:body_weight', 'me:BODY:Weight'].map((r) => parseMetricRef(r)!)
  for (const r of refs) assert.ok(css.includes(`--${washFor(r)}:`), `${refString(r)} -> ${washFor(r)}`)
  assert.equal(washFor(parseMetricRef('series:sleep_deep_min')!), 'ft-domain-sleep')
})
