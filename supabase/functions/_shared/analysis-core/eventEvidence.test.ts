// Gate for the event evidence engine: ladder, windows (night and delayed), exclusions, ordinal outcomes, null effects.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { analyseEventEvidence, dayNumber, ladderFor, wallMinOf } from './eventEvidence.ts'
import type { EventInput, ExpectationInput, SeriesPoint } from './eventEvidence.ts'

const AS_OF = '2026-10-31'
const day = (offset: number) => new Date((dayNumber(AS_OF) + offset) * 86400000).toISOString().slice(0, 10)
const ev = (offset: number, hour: number, extra: Partial<EventInput> = {}): EventInput => ({
  id: `e${offset}`, wallMin: wallMinOf(day(offset), hour), localDate: day(offset), adhered: 'yes', confounders: [], feeling: null, assignedBy: 'self', ...extra,
})
const expectation = (extra: Partial<ExpectationInput> = {}): ExpectationInput => ({
  id: 'x1', outcomeKey: 'sleep_respiratory_rate', label: 'Respiratory rate', unit: 'br/min', role: 'primary', direction: 'down',
  windowStartH: 0, windowEndH: 12, aggregation: 'mean', exploratory: false, ...extra,
})
// one value per morning, anchored 04:00 on the morning the night ends
const nightly = (values: (offset: number) => number, from = -50, to = 0): SeriesPoint[] => {
  const out: SeriesPoint[] = []
  for (let o = from; o <= to; o++) out.push({ wallMin: wallMinOf(day(o), 4), value: values(o) })
  return out
}
// small deterministic wobble so the reference has a spread
const wobble = (o: number) => ((o * 7919) % 11) / 10 - 0.5

test('ladder thresholds', () => {
  assert.equal(ladderFor(0), 'anecdote')
  assert.equal(ladderFor(2), 'anecdote')
  assert.equal(ladderFor(3), 'early_signal')
  assert.equal(ladderFor(7), 'emerging')
  assert.equal(ladderFor(15), 'ready')
})

test('a night event reads the morning it ends, not the one before', () => {
  // events at 22:00 on offsets -10..-3; the value on the NEXT morning (offset+1) is lower by 1.5
  const eventOffsets = [-10, -9, -8, -7, -6, -5, -4, -3]
  const treated = new Set(eventOffsets.map((o) => o + 1))
  const events = eventOffsets.map((o) => ev(o, 22))
  const points = nightly((o) => 15 + wobble(o) - (treated.has(o) ? 1.5 : 0))
  const res = analyseEventEvidence({ interventionId: 'i', asOf: AS_OF, events, expectations: [expectation()], points: { sleep_respiratory_rate: points }, otherEventMins: [], confoundedDays: [] })
  const x = res.expectations[0]
  assert.equal(res.nUsed, 8)
  assert.equal(x.nWithData, 8)
  assert.ok(x.diff !== null && x.diff < -1, `diff ${x.diff}`)
  assert.equal(x.observed, 'as_expected')
  assert.equal(res.ladder, 'emerging')
  assert.equal(x.p, null, 'no p below the top rung')
})

test('the same data read in a window that misses the effect is not flagged', () => {
  const eventOffsets = [-10, -9, -8, -7, -6, -5, -4, -3]
  const treated = new Set(eventOffsets.map((o) => o + 1))
  const events = eventOffsets.map((o) => ev(o, 22))
  const points = nightly((o) => 15 + wobble(o) - (treated.has(o) ? 1.5 : 0))
  // a window of 14-26 h after 22:00 covers 12:00-24:00 next day, i.e. no 04:00 anchor at all
  const res = analyseEventEvidence({ interventionId: 'i', asOf: AS_OF, events, expectations: [expectation({ windowStartH: 14, windowEndH: 26 })], points: { sleep_respiratory_rate: points }, otherEventMins: [], confoundedDays: [] })
  assert.equal(res.expectations[0].nWithData, 0)
  assert.equal(res.expectations[0].diff, null)
})

test('two events are an anecdote and show no interval', () => {
  const events = [ev(-5, 22), ev(-4, 22)]
  const points = nightly((o) => 15 + wobble(o))
  const res = analyseEventEvidence({ interventionId: 'i', asOf: AS_OF, events, expectations: [expectation()], points: { sleep_respiratory_rate: points }, otherEventMins: [], confoundedDays: [] })
  assert.equal(res.ladder, 'anecdote')
  assert.equal(res.expectations[0].observed, 'too_few')
  assert.equal(res.expectations[0].lo, null)
})

test('a null effect is not read as as_expected', () => {
  const events = [-20, -18, -16, -14, -12, -10, -8, -6].map((o) => ev(o, 22))
  const points = nightly((o) => 15 + wobble(o))
  const res = analyseEventEvidence({ interventionId: 'i', asOf: AS_OF, events, expectations: [expectation()], points: { sleep_respiratory_rate: points }, otherEventMins: [], confoundedDays: [] })
  assert.notEqual(res.expectations[0].observed, 'as_expected')
  assert.notEqual(res.expectations[0].observed, 'opposite')
})

test('declined, confounded, stacked and still-open events are left out and counted', () => {
  const events = [
    ev(-12, 22), ev(-11, 22, { adhered: 'no' }), ev(-10, 22, { confounders: ['illness'] }), ev(-9, 22), ev(-8, 22), ev(0, 22),
  ]
  const stackedWith = wallMinOf(day(-9), 23)
  const res = analyseEventEvidence({ interventionId: 'i', asOf: AS_OF, events, expectations: [expectation()], points: { sleep_respiratory_rate: nightly(() => 15) }, otherEventMins: [stackedWith], confoundedDays: [] })
  assert.deepEqual(res.excluded, { notDone: 1, confounded: 1, stacked: 1, overlapping: 0, open: 1 })
  assert.equal(res.nUsed, 2)
})

test('events inside one another\'s window are not double counted', () => {
  // a 6-36 h window with events on consecutive days: the second starts before the first window closes
  const events = [ev(-10, 13), ev(-9, 13), ev(-4, 13)]
  const res = analyseEventEvidence({
    interventionId: 'i', asOf: AS_OF, events, otherEventMins: [], confoundedDays: [],
    expectations: [expectation({ outcomeKey: 'stool', windowStartH: 6, windowEndH: 36, aggregation: 'max' })],
    points: { stool: [] },
  })
  assert.equal(res.excluded.overlapping, 1)
  assert.equal(res.nUsed, 2)
})

test('a delayed effect is read in its declared window using an ordinal tracked outcome', () => {
  // lactose at 13:00 on every 3rd day; worst Bristol score 6-36 h later is 6, otherwise 4
  const eventDays = [-45, -42, -39, -36, -33, -30, -27, -24, -21, -18, -15, -12, -9, -6, -3]
  const events = eventDays.map((o) => ev(o, 13))
  const points: SeriesPoint[] = []
  for (let o = -50; o <= 0; o++) {
    const afterEvent = eventDays.includes(o - 1) // 13:00 + 20h = next day 09:00
    points.push({ wallMin: wallMinOf(day(o), 9), value: afterEvent ? 6 : 3 + ((o * 7919) % 3 === 0 ? 1 : 0) })
  }
  const res = analyseEventEvidence({
    interventionId: 'i', asOf: AS_OF, events, otherEventMins: [], confoundedDays: [],
    expectations: [expectation({ outcomeKey: 'stool', label: 'Stool consistency', unit: null, direction: 'up', windowStartH: 6, windowEndH: 36, aggregation: 'max' })],
    points: { stool: points },
  })
  const x = res.expectations[0]
  assert.equal(res.ladder, 'ready')
  assert.equal(x.observed, 'as_expected')
  assert.ok(x.diff !== null && x.diff >= 2, `diff ${x.diff}`)
  assert.ok(x.p !== null && x.p < 0.01, `p ${x.p}`)
})

test('secondary outcomes are corrected together and the primary is judged alone', () => {
  const eventOffsets = [-20, -18, -16, -14, -12, -10, -8, -6, -4, -2, -1]
  const events = eventOffsets.map((o) => ev(o, 22))
  const points = { a: nightly((o) => 10 + wobble(o)), b: nightly((o) => 5 + wobble(o + 3)), c: nightly((o) => 7 + wobble(o + 5)) }
  const res = analyseEventEvidence({
    interventionId: 'i', asOf: AS_OF, events, otherEventMins: [], confoundedDays: [],
    expectations: [expectation({ id: 'p', outcomeKey: 'a' }), expectation({ id: 's', outcomeKey: 'b', role: 'secondary' }), expectation({ id: 'w', outcomeKey: 'c', role: 'watch' })],
    points,
  })
  assert.equal(res.expectations.length, 3)
  assert.ok(res.feeling.n === 0)
})

test('feeling is summarised on its own', () => {
  const events = [ev(-5, 22, { feeling: 6 }), ev(-4, 22, { feeling: 8 }), ev(-3, 22)]
  const res = analyseEventEvidence({ interventionId: 'i', asOf: AS_OF, events, expectations: [expectation()], points: { sleep_respiratory_rate: [] }, otherEventMins: [], confoundedDays: [] })
  assert.deepEqual(res.feeling, { n: 2, mean: 7 })
})
