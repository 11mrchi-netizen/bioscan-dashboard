import test from 'node:test'
import assert from 'node:assert/strict'
import { mondayOf, plusDays, weekWindow } from './dates.ts'

const dow = (d: string) => new Date(`${d}T12:00:00`).getDay() // 0 Sunday .. 6 Saturday

test('the Monday of a week, and days added by the local calendar', () => {
  assert.equal(mondayOf('2026-11-24'), '2026-11-23') // a Tuesday
  assert.equal(mondayOf('2026-11-23'), '2026-11-23') // a Monday
  assert.equal(mondayOf('2026-11-29'), '2026-11-23') // the Sunday
  assert.equal(mondayOf('2027-01-01'), '2026-12-28') // across the year
  assert.equal(plusDays('2026-11-23', 41), '2027-01-03')
  assert.equal(plusDays('2026-03-28', 2), '2026-03-30') // across the clocks going forward
  assert.equal(plusDays('2026-10-24', 2), '2026-10-26') // and back
})

test('the bug: on Tue 24 Nov 2026 NEXT 6 WEEKS reaches the Sunday, not the Monday after', () => {
  const w = weekWindow('ahead', '2026-11-24')
  assert.deepEqual(w, { from: '2026-11-24', to: '2027-01-03' }) // was today + 41 = Mon 4 Jan: the last week kept one session of seven
  assert.equal(dow(w.to), 0)
  assert.equal(mondayOf(w.to), '2026-12-28') // the 6th week is Mon 28 Dec - Sun 3 Jan, whole
})

test('LAST 6 WEEKS starts on a Monday and ends today; BOTH is whole weeks at both ends', () => {
  assert.deepEqual(weekWindow('recent', '2026-11-24'), { from: '2026-10-19', to: '2026-11-24' })
  assert.deepEqual(weekWindow('both', '2026-11-24'), { from: '2026-10-19', to: '2027-01-03' })
})

test('every weekday of a year: week edges, and six weeks counting this one', () => {
  for (let i = 0; i < 400; i++) {
    const today = plusDays('2026-01-01', i)
    for (const win of ['ahead', 'recent', 'both'] as const) {
      const w = weekWindow(win, today)
      if (win !== 'ahead') assert.equal(dow(w.from), 1, `${win} from ${today}`) // a Monday
      if (win !== 'recent') assert.equal(dow(w.to), 0, `${win} to ${today}`) // a Sunday
      if (win === 'recent') assert.equal(w.to, today)
      if (win === 'ahead') assert.equal(w.from, today)
    }
    const both = weekWindow('both', today)
    const weeks = new Set<string>()
    for (let d = both.from; d <= both.to; d = plusDays(d, 1)) weeks.add(mondayOf(d))
    assert.equal(weeks.size, 11, `both from ${today}`) // five weeks back, this week once, five ahead
    const ahead = new Set<string>()
    for (let d = weekWindow('ahead', today).from; d <= weekWindow('ahead', today).to; d = plusDays(d, 1)) ahead.add(mondayOf(d))
    assert.equal(ahead.size, 6, `ahead from ${today}`)
  }
})
