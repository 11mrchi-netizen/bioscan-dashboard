import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compareRefs, seriesGroup, washFor, rangeFor, INPUT_HUE, INPUT_SERIES, OUTCOME_HUE, metricInk, meRef, splitMeLabel, metricHref, parseMetricRef, refString, SERIES_HUE, seriesBehind, windowStats } from './metrics.ts'
import { SERIES } from './schema.ts'
import { viewPath } from './savedViews.ts'
import { METRIC_INFO } from '../../../supabase/functions/_shared/analysis-core/experiment.ts'
import { DISCOVERY_OUTCOMES } from '../../../supabase/functions/_shared/analysis-core/discovery.ts'

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

test('the extra metrics of an address: known, distinct, not the page itself, at most three, no Me items', () => {
  const own = parseMetricRef('series:hrv')!
  const got = compareRefs(['series:rhr', 'series:hrv', 'nonsense', 'series:rhr', 'me:BODY:Weight', 'recovery.hrv', 'sleep', 'series:steps', 'charge'], own)
  assert.deepEqual(got.map(refString), ['series:rhr', 'recovery.hrv', 'sleep'])
  assert.deepEqual(compareRefs([], null), [])
})

test('a metric page is saved as a view with its metric and comparison', () => {
  assert.equal(viewPath('/m/series%3Ahrv', '?with=series%3Arhr&with=sleep'), '/m/series%3Ahrv?with=series%3Arhr&with=sleep')
  assert.equal(viewPath('/m', ''), null)
  assert.equal(viewPath('/lab/compare', '?view=periods&series=hrv'), '/lab/compare?view=periods&series=hrv')
  assert.equal(viewPath('/today', ''), null)
})

test('every series has a picker group', () => {
  for (const s of SERIES) assert.ok(['SLEEP', 'RECOVERY', 'LOAD', 'FUEL', 'BALANCE'].includes(seriesGroup(s.id)), s.id)
})

test('saved views: a Lab or Setup tab keeps its tab and query; the old route names are not savable', () => {
  assert.equal(viewPath('/lab/evidence', '?view=board'), '/lab/evidence?view=board')
  assert.equal(viewPath('/lab/compare', '?view=periods&series=hrv%2Crhr'), '/lab/compare?view=periods&series=hrv%2Crhr')
  assert.equal(viewPath('/lab', ''), null)
  assert.equal(viewPath('/setup/data', '?table=meals'), '/setup/data?table=meals')
  for (const old of ['/explore', '/evidence', '/models', '/data', '/integrity']) assert.equal(viewPath(old, '?x=1'), null) // the old names: no view was ever saved under them
})

test('every outcome of the Lab\'s charts has its metric\'s ink, and every ink is a token', () => {
  const css = readFileSync(new URL('../../../design/tokens/tokens.css', import.meta.url), 'utf8')
  // the catalogue's outcomes (hypotheses.ts), the experiments' metrics, the discovery screen's outcomes
  const keys = ['hrv', 'rhr', 'sleep', 'energy', 'recovery_score', 'sleep_score', ...Object.keys(METRIC_INFO), ...DISCOVERY_OUTCOMES]
  for (const k of keys) assert.ok(OUTCOME_HUE[k], `no ink for outcome ${k}`)
  for (const t of new Set(Object.values(OUTCOME_HUE))) assert.ok(css.includes(`--${t}:`), `token --${t} is not in tokens.css`)
  assert.equal(metricInk('rhr'), 'var(--m-resting-hr)')
  assert.equal(metricInk('steps'), 'var(--m-steps)') // a series id falls back to its series hue
  assert.equal(metricInk('nonsense'), 'var(--m-weight-ema)') // a chart that names no known metric is neutral
  assert.equal(metricInk(undefined), 'var(--m-weight-ema)')
})
