// The coin-flip draw: drawn 'do' days are read against drawn 'skip' days, and the compliance of the draws is reported.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { analyseEventEvidence, dayNumber } from './eventEvidence.ts'
import type { EventInput, ExpectationInput } from './eventEvidence.ts'

const TODAY = '2026-10-29'
const day = (offset: number) => new Date((dayNumber(TODAY) + offset) * 86400000).toISOString().slice(0, 10)
const exp: ExpectationInput = {
  id: 'x1', outcomeKey: 'resp', label: 'Respiratory rate', unit: 'br/min', role: 'primary', direction: 'down',
  windowStartH: 0, windowEndH: 12, aggregation: 'mean', exploratory: false,
}

// 20 days back, every other day "do" (event at 22:00, the night reads 13.0), the rest "skip" (the night reads 14.5)
function scenario(skipEvents = 0) {
  const draws: { localDate: string; result: 'do' | 'skip' }[] = []
  const events: EventInput[] = []
  const points: { wallMin: number; value: number }[] = []
  for (let i = 1; i <= 20; i++) {
    const d = day(-i)
    const doDay = i % 2 === 0
    draws.push({ localDate: d, result: doDay ? 'do' : 'skip' })
    const wall = dayNumber(d) * 1440
    const jitter = ((i * 37) % 7) / 20
    points.push({ wallMin: wall + 1440 + 4 * 60, value: (doDay ? 13 : 14.5) + jitter })
    if (doDay || i <= skipEvents * 2) {
      events.push({ id: `e${i}`, wallMin: wall + 22 * 60, localDate: d, adhered: 'yes', confounders: [], feeling: null, assignedBy: doDay ? 'drawn' : 'self' })
    }
  }
  return { draws, events, points }
}

test('drawn days are compared with drawn skip days and the draws are accounted for', () => {
  const { draws, events, points } = scenario()
  const res = analyseEventEvidence({ interventionId: 'i', asOf: TODAY, events, expectations: [exp], points: { resp: points }, otherEventMins: [], confoundedDays: [], draws })
  assert.equal(res.assignment, 'drawn')
  assert.deepEqual(res.draws, { nDo: 10, nSkip: 10, kept: 10, missed: 0, broken: 0 })
  const rz = res.expectations[0].randomised!
  assert.ok(rz.nDo >= 3 && rz.nSkip >= 3)
  assert.ok(rz.diff! < -1, `expected a clear drop, got ${rz.diff}`)
  assert.equal(rz.observed, 'as_expected')
})

test('a skip-day event and a missed do are counted, and no draws means no randomised block', () => {
  const { draws, events, points } = scenario()
  const broken = [...events.slice(1), { id: 'oops', wallMin: dayNumber(day(-1)) * 1440 + 22 * 60, localDate: day(-1), adhered: 'yes' as const, confounders: [], feeling: null, assignedBy: 'self' as const }]
  const res = analyseEventEvidence({ interventionId: 'i', asOf: TODAY, events: broken, expectations: [exp], points: { resp: points }, otherEventMins: [], confoundedDays: [], draws })
  assert.equal(res.draws!.broken, 1)
  assert.equal(res.draws!.missed, 1)

  const none = analyseEventEvidence({ interventionId: 'i', asOf: TODAY, events, expectations: [exp], points: { resp: points }, otherEventMins: [], confoundedDays: [] })
  assert.equal(none.draws, null)
  assert.equal(none.expectations[0].randomised, null)
})
