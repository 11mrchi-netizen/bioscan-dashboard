import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  adherenceLine, buildReport, convertUnit, foodStool, historyOf, labVerdict, lastCompleted, metricLine, microSection, parsePeriodKey,
  mealQuality, type MealProfileRead, periodKey, periodLabel, periodValue, plannedDoses, previousOf, REPORT_METRICS, regimenItem, specOn, suggestions, withHistory, type DailySeries, type MetricLine,
  type ReportPeriod,
} from './periodReport.ts'
import { addDays } from './seriesContract.ts'

const spec = (id: string) => REPORT_METRICS.find((m) => m.id === id)!
const fill = (start: string, days: number, f: (i: number) => number | null): DailySeries => {
  const m: DailySeries = new Map()
  for (let i = 0; i < days; i++) { const v = f(i); if (v !== null) m.set(addDays(start, i), v) }
  return m
}

test('periods: Monday-to-Sunday weeks and calendar months, keyed and labelled', () => {
  // 2026-11-25 is a Wednesday.
  assert.deepEqual(lastCompleted('2026-11-25', 'week'), { grain: 'week', start: '2026-11-16', end: '2026-11-22' })
  assert.deepEqual(lastCompleted('2026-11-23', 'week'), { grain: 'week', start: '2026-11-16', end: '2026-11-22' })
  assert.deepEqual(lastCompleted('2026-12-01', 'month'), { grain: 'month', start: '2026-11-01', end: '2026-11-30' })
  assert.deepEqual(lastCompleted('2026-03-15', 'month'), { grain: 'month', start: '2026-02-01', end: '2026-02-28' })
  const w = lastCompleted('2026-11-25', 'week')
  assert.equal(periodKey(w), 'week:2026-11-16')
  assert.deepEqual(parsePeriodKey('week:2026-11-16'), w)
  assert.equal(parsePeriodKey('week:2026-11-17'), null, 'a week key is a Monday')
  assert.deepEqual(parsePeriodKey('month:2026-02'), { grain: 'month', start: '2026-02-01', end: '2026-02-28' })
  assert.deepEqual(previousOf(w), { grain: 'week', start: '2026-11-09', end: '2026-11-15' })
  assert.equal(periodLabel(w), 'Week of 16 Nov 2026')
  assert.equal(periodLabel({ grain: 'month', start: '2026-11-01', end: '2026-11-30' }), 'November 2026')
  const h = historyOf(w)
  assert.equal(h.length, 8)
  assert.deepEqual(h.at(-1), previousOf(w))
  assert.equal(h[0].start, '2026-09-21')
})

test('a period value follows the contract: a mean of the days, a total per week for a month', () => {
  const week: ReportPeriod = { grain: 'week', start: '2026-11-16', end: '2026-11-22' }
  const s = fill('2026-11-16', 7, (i) => (i < 3 ? 10 : null))
  assert.deepEqual(periodValue(s, week, { period: 'mean' }), { value: 10, days: 3 })
  assert.deepEqual(periodValue(s, week, { period: 'sum' }), { value: 30, days: 3 })
  const month: ReportPeriod = { grain: 'month', start: '2026-11-01', end: '2026-11-30' }
  const runs = fill('2026-11-01', 30, (i) => (i % 3 === 0 ? 6 : null)) // 10 runs of 6 km = 60 km in 30 days
  assert.equal(periodValue(runs, month, { period: 'sum' }).value, 14)
  assert.equal(periodValue(new Map(), week, { period: 'sum' }).value, 0, 'no session is a total of zero, not missing')
})

test('a metric line: better and worse by the metric direction, steady inside the usual swing, thin with too few days', () => {
  const week: ReportPeriod = { grain: 'week', start: '2026-11-16', end: '2026-11-22' }
  const hist = historyOf(week)
  // HRV: 50 every earlier week, 60 this week.
  const hrv = fill(hist[0].start, 63, (i) => (i < 56 ? 50 + (Math.floor(i / 7) % 2) : 60))
  const up = metricLine(spec('hrv'), { label: 'HRV', unit: 'ms', period: 'mean' }, hrv, week, hist)
  assert.equal(up.status, 'better')
  assert.equal(up.ref, 'series:hrv')
  assert.equal(up.days, 7)
  assert.ok(up.usualSwing !== null && up.usualSwing < 2)
  const rhr = metricLine(spec('rhr'), { label: 'RHR', unit: 'bpm', period: 'mean' }, hrv, week, hist)
  assert.equal(rhr.status, 'worse', 'up is worse for resting heart rate')
  // A wild series: swings of ±15 each week, so +10 is inside the usual swing.
  const wild = fill(hist[0].start, 63, (i) => (i < 56 ? (Math.floor(i / 7) % 2 ? 65 : 35) : 60))
  const steady = metricLine(spec('hrv'), { label: 'HRV', unit: 'ms', period: 'mean' }, wild, week, hist)
  assert.equal(steady.status, 'steady')
  const sparse = fill('2026-11-16', 7, (i) => (i < 2 ? 60 : null))
  assert.equal(metricLine(spec('hrv'), { label: 'HRV', unit: 'ms', period: 'mean' }, sparse, week, hist).status, 'thin')
  assert.equal(metricLine(spec('hrv'), { label: 'HRV', unit: 'ms', period: 'mean' }, new Map(), week, hist).status, 'no_data')
  // No direction: up or down, never better or worse.
  const w = metricLine(spec('body_weight'), { label: 'Weight', unit: 'kg', period: 'mean' }, fill(hist[0].start, 63, (i) => (i < 56 ? 80 : 81)), week, hist)
  assert.equal(w.status, 'up')
})

test('micronutrients: food and supplements, low under 70% of the need, high over the safe upper limit', () => {
  const week: ReportPeriod = { grain: 'week', start: '2026-11-16', end: '2026-11-22' }
  const prev = previousOf(week)
  const foodDays = new Set(['2026-11-16', '2026-11-17', '2026-11-18', '2026-11-19'])
  const food = new Map([
    ['magnesium', fill('2026-11-16', 3, () => 300)], // three days at 300, one food day without: 225 a day
    ['sodium', fill('2026-11-16', 4, () => 3000)],
    ['zinc', fill('2026-11-16', 4, () => 12)],
    ['vitamin_d', fill('2026-11-16', 4, () => 100)],
    ['iron', fill('2026-11-16', 4, () => 20)],
  ])
  const supps = new Map([
    ['zinc', fill('2026-11-16', 7, () => 50)], // 50 mg every day: 62 a day with food, over the 40 mg limit
    ['vitamin_d', fill('2026-11-16', 7, (i) => (i < 2 ? 5000 : null))], // two doses: 1,429 a day, over 4,000 on two days
    ['iron', fill('2026-11-16', 7, (i) => (i === 0 ? 36 : null))],
  ])
  const names = new Map([['zinc', new Set(['Zinc Picolinate'])]])
  const s = microSection(food, foodDays, supps, names, week, prev)
  assert.equal(s.foodDays, 4)
  assert.equal(s.days, 7)
  const line = (k: string) => s.lines.find((l) => l.key === k)!
  assert.equal(line('magnesium').food, 225)
  assert.equal(line('magnesium').status, 'low', '225 of 420 mg is 54% of the need')
  const zn = line('zinc')
  assert.equal(zn.food, 12)
  assert.equal(zn.supplements, 50)
  assert.equal(zn.value, 62)
  assert.equal(zn.status, 'high')
  assert.deepEqual(zn.fromSupplements, ['Zinc Picolinate'])
  const d = line('vitamin_d')
  assert.equal(d.value, 1528.57)
  assert.equal(d.daysOver, 2)
  assert.equal(d.status, 'high_days', 'under the limit on average, over it on the dose days')
  assert.equal(line('iron').status, 'high_days', '20 from food + 36 from the dose is over 45 mg on that day')
  assert.equal(line('sodium').status, 'high', '3,000 mg over the 2,300 mg limit')
  assert.equal(line('selenium').status, 'no_data', 'a nutrient no logged food carries is unknown, never short')
  assert.equal(convertUnit(420, 'mg', 'g'), 0.42)
  assert.equal(convertUnit(1, 'IU', 'mcg'), null)
  // Without food logged, a deficiency is not called; an excess from doses alone still is.
  const none = microSection(new Map(), new Set(), supps, names, week, prev)
  assert.equal(none.lines.find((l) => l.key === 'zinc')!.status, 'high')
  assert.equal(none.lines.find((l) => l.key === 'magnesium')!.status, 'no_data')
})

test('regimen: planned doses by the schedule, adherence capped per day, as-needed not scored', () => {
  const week: ReportPeriod = { grain: 'week', start: '2026-11-16', end: '2026-11-22' }
  const prev = previousOf(week)
  const daily = regimenItem({ id: 1, name: 'Creatine', time_of_day: 'morning', slots: ['morning'], schedule: { type: 'daily' }, start_date: '2026-06-01' })
  const thrice = regimenItem({ id: 2, name: 'Vitamin C', slots: ['night', 'morning', 'afternoon'], schedule: { type: 'daily' } })
  const everyThree = regimenItem({ id: 3, name: 'Iron', slots: ['morning'], every_n_days: 3, schedule: { type: 'every_n_days', n: 3 } })
  const weekdays = regimenItem({ id: 4, name: 'X', slots: ['morning'], schedule: { type: 'weekdays', days: [1, 3, 5] } })
  const cycle = regimenItem({ id: 5, name: 'Y', slots: ['morning'], schedule: { type: 'daily', cycle: { on_days: 5, off_days: 2, start: '2026-11-16' } } })
  const legacy = regimenItem({ id: 6, name: 'Z', time_of_day: 'as-needed', slots: [] })
  assert.deepEqual(thrice.slots, ['morning', 'afternoon', 'night'])
  assert.equal(plannedDoses(daily, week.start, week.end), 7)
  assert.equal(plannedDoses(thrice, week.start, week.end), 21)
  assert.equal(plannedDoses(everyThree, week.start, week.end), 3)
  assert.equal(plannedDoses(weekdays, week.start, week.end), 3)
  assert.equal(plannedDoses(cycle, week.start, week.end), 5)
  assert.equal(plannedDoses(legacy, week.start, week.end), 0)
  // Taken on 4 days, twice on one of them: 4 of 7.
  const takes = new Map([['2026-11-16', 2], ['2026-11-17', 1], ['2026-11-19', 1], ['2026-11-21', 1], ['2026-11-10', 1]])
  const a = adherenceLine(daily, takes, week, prev)
  assert.equal(a.taken, 4)
  assert.equal(a.rate, 0.571)
  assert.equal(a.status, 'not_followed')
  assert.equal(a.prevRate, 0.143)
  assert.equal(a.daysOn, 175)
  assert.equal(adherenceLine(legacy, takes, week, prev).status, 'as_needed')
})

test('lab verdicts: not followed wins, then working, worse, no effect and too early', () => {
  const r = (outcome: 'change' | 'none' | 'undecided', diff: number | null, metric = 'hrv') => ({ metric, label: metric.toUpperCase(), outcome, diff, unit: 'ms' })
  assert.equal(labVerdict([r('change', 5)], 0.5).verdict, 'not_followed')
  assert.equal(labVerdict([r('change', 5)], 0.9).verdict, 'working')
  assert.equal(labVerdict([r('change', -5)], null).verdict, 'worse')
  assert.equal(labVerdict([r('change', 3, 'rhr')], null).verdict, 'worse', 'a higher resting heart rate is worse')
  assert.equal(labVerdict([r('none', 0.5)], 0.95).verdict, 'no_effect')
  assert.equal(labVerdict([r('undecided', null)], 0.95).verdict, 'too_early')
  assert.equal(labVerdict([], null).verdict, 'too_early')
})

test('food and stool: shown only with eight days each side and a gap of 20 points', () => {
  const end = '2026-11-22'
  const start = addDays(end, -39)
  const fiber = fill(start, 40, (i) => (i % 2 ? 40 : 10))
  // The day after a high-fibre day is normal, after a low one not.
  const normal = fill(addDays(start, 1), 40, (i) => (i % 2 ? 100 : 0))
  const lines = foodStool([{ key: 'meal_fiber', label: 'Fibre', unit: 'g', series: fiber }], normal, end)
  assert.equal(lines.length, 1)
  assert.equal(lines[0].highPct, 100)
  assert.equal(lines[0].lowPct, 0)
  const flat = fill(addDays(start, 1), 40, () => 100)
  assert.equal(foodStool([{ key: 'meal_fiber', label: 'Fibre', unit: 'g', series: fiber }], flat, end).length, 0)
  const few = fill(start, 10, () => 30)
  assert.equal(foodStool([{ key: 'meal_fiber', label: 'Fibre', unit: 'g', series: few }], normal, end).length, 0)
})

const line = (id: string, value: number, prev: number, status: MetricLine['status']): MetricLine => ({
  id, ref: `series:${id}`, label: id, unit: '', section: 'sleep', better: 'up', value, prev, delta: value - prev, usualSwing: null,
  days: 7, prevDays: 7, status, perWeek: false,
})

test('suggestions: fixed rules, each naming its number, most pressing first', () => {
  const micro = { foodDays: 5, days: 7, lines: [] }
  const s = suggestions({
    metrics: [line('sleep_hours', 6.2, 6.9, 'worse'), line('hrv', 40, 48, 'worse'), line('meal_protein', 90, 95, 'steady'), line('steps', 9000, 8000, 'better')],
    micro, weightKg: 80, lab: [],
    regimen: [{ id: 3, name: 'Zinc', kind: null, asNeeded: false, planned: 7, taken: 3, rate: 0.43, prevRate: 1, daysOn: 100, status: 'not_followed' }],
    untested: [],
  })
  const ids = s.map((x) => x.id)
  assert.deepEqual(ids.slice(0, 2), ['sleep_short', 'worse_hrv'])
  assert.ok(!ids.includes('worse_sleep_hours'), 'short sleep is said once')
  assert.ok(ids.includes('protein_low'), '90 g is under 1.6 g/kg of 80 kg')
  assert.ok(ids.includes('regimen_3'))
  assert.ok(!ids.includes('steps_low'))
  assert.match(s[0].text, /6\.2 h/)
})

test('a report: summary lists and at most five suggestions; a tested item is not called untested', () => {
  const period: ReportPeriod = { grain: 'week', start: '2026-11-16', end: '2026-11-22' }
  const r = buildReport({
    period, today: '2026-11-23',
    metrics: [line('sleep_hours', 6, 7, 'worse'), line('hrv', 40, 48, 'worse'), line('rhr', 50, 50, 'steady'), line('steps', 3000, 3200, 'steady'), line('meal_fiber', 10, 12, 'steady'), line('water_intake', 1000, 1100, 'steady'), line('mood', 8, 6, 'better')],
    micro: { foodDays: 5, days: 7, lines: [] },
    regimen: [{ id: 4, name: 'Creatine', kind: null, asNeeded: false, planned: 7, taken: 7, rate: 1, prevRate: 1, daysOn: 170, status: 'followed' }],
    lab: [{ source: 'intervention', id: 'i1', name: 'Creatine test', verdict: 'no_effect', detail: 'No change found in HRV.', supplementId: 4, href: '#/lab' }],
    foodStool: [], weightKg: null,
  })
  assert.equal(r.key, 'week:2026-11-16')
  assert.equal(r.prevStart, '2026-11-09')
  assert.deepEqual(r.summary.better, ['mood'])
  assert.deepEqual(r.summary.worse, ['sleep_hours', 'hrv'])
  assert.deepEqual(r.summary.wasted, ['Creatine test'])
  assert.equal(r.suggestions.length, 5)
  assert.ok(!r.suggestions.some((x) => x.id === 'untested'))
})

test('adherence follows the schedule in force each day, not today\'s', () => {
  const week: ReportPeriod = { grain: 'week', start: '2026-11-16', end: '2026-11-22' }
  const prev = previousOf(week)
  // Vitamin C once a day until it went to three times a day on Sunday 22 Nov.
  const now = regimenItem({ id: 2, name: 'Vitamin C', slots: ['morning', 'afternoon', 'night'], schedule: { type: 'daily' }, start_date: '2026-10-01' })
  const once = { slots: ['morning'], schedule: { type: 'daily' } }
  const thrice = { slots: ['morning', 'afternoon', 'night'], schedule: { type: 'daily' } }
  const it = withHistory(now, [
    { date: '2026-10-01', before: null, after: once },
    { date: '2026-11-22', before: once, after: thrice },
  ])
  assert.equal(specOn(it, '2026-11-22').slots.length, 1, 'the day of the change still runs on the old schedule')
  assert.equal(specOn(it, '2026-11-23').slots.length, 3)
  const takes = new Map<string, number>()
  for (let i = 0; i < 14; i++) takes.set(addDays(prev.start, i), 1)
  const line = adherenceLine(it, takes, week, prev)
  assert.equal(line.planned, 7)
  assert.equal(line.rate, 1)
  assert.equal(line.status, 'followed')
  assert.equal(adherenceLine(now, takes, week, prev).rate, 0.333, 'without the history it reads as a third')
  // The next week is judged three times a day.
  const next: ReportPeriod = { grain: 'week', start: '2026-11-23', end: '2026-11-29' }
  assert.equal(plannedDoses(it, next.start, next.end), 21)
  // A switch from every 3 days to daily mid-week counts each stretch by its own rule.
  const iron = withHistory(regimenItem({ id: 3, name: 'Iron', slots: ['morning'], schedule: { type: 'daily' } }), [
    { date: '2026-11-18', before: { slots: ['morning'], every_n_days: 3, schedule: { type: 'every_n_days', n: 3 } }, after: { slots: ['morning'], schedule: { type: 'daily' } } },
  ])
  assert.equal(plannedDoses(iron, week.start, week.end), 1 + 4, '16-18 every third day, then 19-22 daily')
})

test('meal quality: each index of the meal profile on its own line, against the week before', () => {
  const week: ReportPeriod = { grain: 'week', start: '2026-11-16', end: '2026-11-22' }
  const meal = (date: string, o: Partial<MealProfileRead>): MealProfileRead =>
    ({ date, calories: 500, proteinG: 30, fiberG: 5, energyDensity: 1.5, glycemicLoad: 20, ultraProcessedPct: 0, ...o })
  const meals = [
    meal('2026-11-16', { proteinG: 40, energyDensity: 1.2 }), meal('2026-11-16', { proteinG: 10, energyDensity: 3.0, ultraProcessedPct: 100 }),
    meal('2026-11-17', { proteinG: 25, energyDensity: null }), meal('2026-11-17', { calories: 150, proteinG: 2, glycemicLoad: 10 }),
    // the week before: one meal
    meal('2026-11-10', { proteinG: 10 }),
  ]
  const q = mealQuality(meals, week, 80)
  const by = Object.fromEntries(q.lines.map((l) => [l.key, l]))
  assert.equal(q.meals, 4)
  assert.deepEqual(q.lines.map((l) => l.key), ['protein_dose', 'energy_density', 'glycemic_load', 'fiber_density', 'ultra_processed'])
  // dose 24 g at 80 kg: 40 and 25 reach it, 10 does not; the 150 kcal snack is not a main meal
  assert.equal(by.protein_dose.value, 67)
  assert.equal(by.protein_dose.basis, '2 of 3 meals')
  assert.equal(by.protein_dose.prev, 0)
  assert.equal(by.energy_density.value, 1.5)
  assert.equal(by.glycemic_load.value, 35) // (40 + 30) over 2 days
  assert.equal(by.fiber_density.value, round1((1000 * 20) / 1650))
  assert.equal(by.ultra_processed.value, Math.round((100 * 500) / 1650))
  // nothing logged: every line says so
  const empty = mealQuality([], week, null)
  assert.ok(empty.lines.every((l) => l.value === null && l.basis === 'not enough logged'))
  assert.match(empty.lines[0].label, /25 g/)
})

const round1 = (v: number) => Math.round(v * 10) / 10
