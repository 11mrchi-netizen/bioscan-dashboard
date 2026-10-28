// Analysis hub phase A: the series contract (time, periods, aggregation, quality states) and the contract file itself.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import {
  aggregateDays,
  aggregatePeriods,
  classifyDay,
  combine,
  daySpan,
  grainFor,
  localDateOf,
  periodEnd,
  periodStart,
  periodsInRange,
  validateSeriesFile,
  type Observation,
  type SeriesContract,
  type SeriesFile,
  type TimeContract,
} from './seriesContract.ts'

const file = JSON.parse(readFileSync(new URL('../metric-registry/metric-series.json', import.meta.url), 'utf8')) as SeriesFile
const registry = JSON.parse(readFileSync(new URL('../metric-registry/metric-registry.json', import.meta.url), 'utf8')) as { metrics: { key: string }[] }

test('the contract file is valid against the methodology registry', () => {
  assert.deepEqual(validateSeriesFile(file, registry.metrics.map((m) => m.key)), [])
  assert.ok(file.series.length >= 30)
})

test('the validator catches the mistakes that matter', () => {
  const base = file.series.find((s) => s.id === 'steps')!
  const bad = (patch: Partial<SeriesContract>) => validateSeriesFile({ ...file, series: [{ ...base, ...patch }] }, registry.metrics.map((m) => m.key))
  assert.match(bad({ day: 'sum' }).join(), /must not sum rows within a day/)
  assert.match(bad({ metric: 'no_such_metric' }).join(), /not in metric-registry/)
  assert.match(bad({ plausible: [5, 1] }).join(), /plausible/)
  assert.match(bad({ id: 'Steps' }).join(), /snake_case/)
  assert.match(bad({ time: { column: 'date', kind: 'local_date', offsetColumn: 'tz' } }).join(), /offsetColumn only applies/)
  assert.match(validateSeriesFile({ ...file, series: [base, base] }, registry.metrics.map((m) => m.key)).join(), /duplicate id/)
})

test('weeks start on Monday and months are calendar months, across year and leap-day boundaries', () => {
  assert.equal(periodStart('2026-10-27', 'week'), '2026-10-26') // Tuesday -> Monday
  assert.equal(periodStart('2026-11-01', 'week'), '2026-10-26') // Sunday -> the Monday before
  assert.equal(periodStart('2026-10-26', 'week'), '2026-10-26')
  assert.equal(periodStart('2027-01-01', 'week'), '2026-12-28') // ISO week 53 of 2026 spans the year end
  assert.equal(periodEnd('2026-12-28', 'week'), '2027-01-03')
  assert.equal(periodStart('2028-02-29', 'month'), '2028-02-01')
  assert.equal(periodEnd('2028-02-01', 'month'), '2028-02-29')
  assert.equal(periodEnd('2027-02-01', 'month'), '2027-02-28')
  assert.equal(periodEnd('2026-12-01', 'month'), '2026-12-31')
})

test('periods cover the range exactly, clipped at both ends, with the right count', () => {
  const weeks = periodsInRange('2026-10-01', '2026-10-31', 'week') // Thursday .. Saturday
  assert.equal(weeks.length, 5)
  assert.deepEqual(weeks[0], { start: '2026-10-01', end: '2026-10-04', days: 4, clipped: true })
  assert.deepEqual(weeks[4], { start: '2026-10-26', end: '2026-10-31', days: 6, clipped: true })
  assert.equal(weeks.reduce((a, w) => a + w.days, 0), 31)
  const months = periodsInRange('2023-03-22', '2026-10-27', 'month')
  assert.equal(months.length, 44)
  assert.equal(months.reduce((a, m) => a + m.days, 0), daySpan('2023-03-22', '2026-10-27'))
  assert.equal(months.filter((m) => m.clipped).length, 2)
  assert.equal(periodsInRange('2026-10-27', '2026-10-27', 'day').length, 1)
  assert.equal(periodsInRange('2024-01-01', '2024-12-31', 'day').length, 366)
})

test('the default grain follows the range and the series', () => {
  const weekly = { longGrain: 'week' as const }
  const daily = { longGrain: 'day' as const }
  assert.equal(grainFor('2026-08-01', '2026-10-27', weekly), 'day') // 88 days
  assert.equal(grainFor('2026-04-01', '2026-10-27', weekly), 'week')
  assert.equal(grainFor('2026-04-01', '2026-10-27', daily), 'day')
  assert.equal(grainFor('2023-03-22', '2026-10-27', daily), 'month')
})

test('local dates: calendar dates and local wall times read as written; UTC instants need the offset', () => {
  const date: TimeContract = { column: 'date', kind: 'local_date' }
  const wall: TimeContract = { column: 'start_time', kind: 'local_wall' }
  const utc: TimeContract = { column: 'bedtime', kind: 'utc', offsetColumn: 'tz_offset_min' }
  assert.deepEqual(localDateOf('2026-10-27', date, null, 480), { date: '2026-10-27', assumedOffset: false })
  // a 23:30 local run stays on its day: no conversion is applied to local wall time
  assert.deepEqual(localDateOf('2026-10-27T23:30:00+00:00', wall, null, 480), { date: '2026-10-27', assumedOffset: false })
  // 16:30 UTC is 00:30 the next day in Taipei, 17:30 the same day at UTC+1
  assert.deepEqual(localDateOf('2026-10-27T16:30:00Z', utc, 480, 480), { date: '2026-10-28', assumedOffset: false })
  assert.deepEqual(localDateOf('2026-01-10T16:30:00Z', utc, 60, 480), { date: '2026-01-10', assumedOffset: false })
  assert.deepEqual(localDateOf('2026-10-27T16:30:00Z', utc, null, 480), { date: '2026-10-28', assumedOffset: true })
  assert.equal(localDateOf(null, wall, null, 480), null)
  assert.equal(localDateOf('not a time', utc, 0, 480), null)
})

test('the meals contract reads the backfill as UTC and live logging as local wall time', () => {
  const meals = file.series.find((s) => s.id === 'meal_energy')!
  // backfilled 2026-09-10 16:30 UTC -> 00:30 local on the 11th
  assert.deepEqual(localDateOf('2026-09-10T16:30:00+00:00', meals.time, null, file.defaultUtcOffsetMin), { date: '2026-09-11', assumedOffset: true })
  // live 2026-09-20 23:30 local wall time stays on the 20th
  assert.deepEqual(localDateOf('2026-09-20T23:30:00+00:00', meals.time, null, file.defaultUtcOffsetMin), { date: '2026-09-20', assumedOffset: false })
})

test('combine: null and non-finite values are skipped, and no values is null, never 0', () => {
  assert.deepEqual(combine([], 'sum'), { value: null, n: 0 })
  assert.deepEqual(combine([null, null], 'mean'), { value: null, n: 0 })
  assert.deepEqual(combine([2, null, 4], 'sum'), { value: 6, n: 2 })
  assert.deepEqual(combine([2, null, 4], 'mean'), { value: 3, n: 2 })
  assert.deepEqual(combine([2, 4, null], 'last'), { value: 4, n: 2 })
  assert.deepEqual(combine([Number.NaN, 5], 'mean'), { value: 5, n: 1 })
})

test('days: rows on one local day combine by the day method and keep their sources', () => {
  const obs: Observation[] = [
    { date: '2026-10-02', value: 300, source: 'manual' },
    { date: '2026-10-01', value: 500, source: 'manual' },
    { date: '2026-10-01', value: 700, source: 'estimate' },
    { date: '2026-10-03', value: null, source: 'manual' },
  ]
  const days = aggregateDays(obs, 'sum')
  assert.deepEqual(days.map((d) => [d.date, d.value, d.n]), [['2026-10-01', 1200, 2], ['2026-10-02', 300, 1], ['2026-10-03', null, 0]])
  assert.deepEqual(days[0].sources, ['manual', 'estimate'])
})

test('periods: sparse and missing periods stay null with their coverage; sums are not extrapolated', () => {
  const days = aggregateDays([
    { date: '2026-10-05', value: 10, source: 'a' }, // Monday, week 1
    { date: '2026-10-07', value: 20, source: 'a' },
    { date: '2026-10-21', value: 30, source: 'b' }, // week 3; week 2 is empty
  ], 'last')
  const sums = aggregatePeriods(days, '2026-10-05', '2026-10-25', 'week', 'sum')
  assert.equal(sums.length, 3)
  assert.deepEqual(sums.map((p) => [p.start, p.value, p.daysObserved, p.days]), [['2026-10-05', 30, 2, 7], ['2026-10-12', null, 0, 7], ['2026-10-19', 30, 1, 7]])
  assert.equal(sums[0].coverage, 2 / 7)
  assert.equal(sums[1].coverage, 0)
  assert.equal(sums[1].min, null)
  const means = aggregatePeriods(days, '2026-10-05', '2026-10-25', 'week', 'mean')
  assert.equal(means[0].value, 15)
  assert.deepEqual([means[0].min, means[0].max], [10, 20])
  const last = aggregatePeriods(days, '2026-10-05', '2026-10-25', 'week', 'last')
  assert.equal(last[0].value, 20)
  // days outside the range do not leak into the clipped first period
  const clipped = aggregatePeriods(days, '2026-10-06', '2026-10-25', 'week', 'sum')
  assert.deepEqual([clipped[0].start, clipped[0].value, clipped[0].days, clipped[0].clipped], ['2026-10-06', 20, 6, true])
})

test('periods: a long daily history aggregates to months without dropping a day', () => {
  const obs: Observation[] = []
  for (let i = 0; i < 1000; i++) {
    const d = new Date(Date.UTC(2024, 0, 1) + i * 86_400_000).toISOString().slice(0, 10)
    obs.push({ date: d, value: 1, source: 'a' })
  }
  const months = aggregatePeriods(aggregateDays(obs, 'last'), '2024-01-01', '2026-09-26', 'month', 'sum')
  assert.equal(months.reduce((a, m) => a + (m.value ?? 0), 0), 1000)
  assert.ok(months.every((m) => m.coverage === 1))
})

test('quality states', () => {
  const daily = { cadence: 'daily' as const, plausible: [25, 120] as [number, number] }
  const events = { cadence: 'per_event' as const, plausible: null }
  const row = (value: number | null, source = 'zepp') => ({ value, source })
  assert.equal(classifyDay({ rows: [], expected: null, collectorAlive: null }, daily).state, 'unknown')
  assert.equal(classifyDay({ rows: [], expected: true, collectorAlive: true }, daily).state, 'missing')
  assert.match(classifyDay({ rows: [row(null)], expected: true, collectorAlive: false }, daily).reason, /not running/)
  assert.equal(classifyDay({ rows: [], expected: false, collectorAlive: null }, daily).state, 'valid')
  assert.equal(classifyDay({ rows: [row(140)], expected: true, collectorAlive: true }, daily).state, 'invalid')
  const conflict = classifyDay({ rows: [row(52), row(55, 'health_connect')], expected: true, collectorAlive: null }, daily)
  assert.deepEqual([conflict.state, conflict.duplicate], ['conflict', true])
  const agree = classifyDay({ rows: [row(52), row(52, 'health_connect')], expected: true, collectorAlive: null }, daily)
  assert.deepEqual([agree.state, agree.duplicate], ['valid', true])
  // several meals on a day are normal, not a conflict
  assert.deepEqual(classifyDay({ rows: [row(400), row(700)], expected: null, collectorAlive: null }, events), { state: 'valid', reason: 'checks passed', duplicate: false })
  // invalid outranks conflict
  assert.equal(classifyDay({ rows: [row(52), row(300)], expected: true, collectorAlive: null }, daily).state, 'invalid')
})
