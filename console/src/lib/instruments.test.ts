import assert from 'node:assert/strict'
import { test } from 'node:test'
import { DEFS, dueOn, instrument, INSTRUMENTS, localToday, optionsOf, score } from './instruments.ts'

test('every published case scores as written in instruments.json', () => {
  assert.ok(DEFS.cases.length >= 10)
  for (const c of DEFS.cases) {
    const inst = instrument(c.instrument)!
    const s = score(inst, c.answers)
    assert.equal(s.score, c.score, `${c.instrument} ${c.answers}`)
    assert.equal(s.band?.label, c.band, `${c.instrument} ${c.answers}`)
    assert.deepEqual(s.flags.map((f) => f.id), c.flags, `${c.instrument} ${c.answers}`)
  }
})

test('the definitions are complete: options exist, bands cover the whole range without gaps', () => {
  for (const inst of INSTRUMENTS) {
    for (const item of inst.items) assert.ok(optionsOf(item).length >= 2, `${inst.id} item ${item.id}`)
    const sorted = [...inst.bands].sort((a, b) => a.min - b.min)
    assert.equal(sorted[0].min, inst.range[0], `${inst.id} starts at its minimum`)
    assert.equal(sorted.at(-1)!.max, inst.range[1], `${inst.id} ends at its maximum`)
    for (let i = 1; i < sorted.length; i++) assert.ok(sorted[i].min - sorted[i - 1].max <= 1 && sorted[i].min > sorted[i - 1].max, `${inst.id} band ${i} follows on`)
    for (const f of inst.flags) assert.ok(inst.items.some((it) => it.id === f.item), `${inst.id} flag ${f.id} names an item`)
  }
})

test('a wrong answer is refused, not scored', () => {
  assert.throws(() => score(instrument('phq9')!, [0, 0, 0]))
  assert.throws(() => score(instrument('phq9')!, [0, 0, 0, 0, 0, 0, 0, 0, 4]))
  assert.throws(() => score(instrument('work_hours')!, [200]))
})

test('the reversed CBI item counts the other way', () => {
  const cbi = instrument('cbi_work')!
  assert.equal(score(cbi, [0, 0, 0, 0, 0, 0, 100]).score, 0, 'plenty of energy for family is no burnout')
  assert.equal(score(cbi, [0, 0, 0, 0, 0, 0, 0]).score, round(100 / 7))
})

test('due dates follow each instrument\'s interval', () => {
  assert.equal(dueOn(instrument('phq9')!, '2026-11-01'), '2026-11-29')
  assert.equal(dueOn(instrument('cbi_work')!, '2026-11-01'), '2026-12-27')
  assert.equal(dueOn(instrument('phq9')!, null), null)
})

const round = (x: number) => Math.round(x * 100) / 100

test('a saved answer is dated by the local calendar, not the UTC date', () => {
  // 07:30 on 20 Nov in UTC+8 is still 19 Nov in UTC; the answer belongs to the 20th.
  const prev = process.env.TZ
  process.env.TZ = 'Asia/Taipei'
  try {
    assert.equal(localToday(new Date('2026-11-19T23:30:00Z')), '2026-11-20')
  } finally {
    process.env.TZ = prev
  }
})
