import assert from 'node:assert/strict'
import { test } from 'node:test'
import { bhQ, fisherGreater, foodTriggers, foodTriggerSuggestions } from './foodTriggers.ts'

test('Fisher exact (one-sided) and Benjamini-Hochberg', () => {
  // 3 of 3 exposed loose, 0 of 3 unexposed: p = 1 / C(6,3) = 0.05
  assert.ok(Math.abs(fisherGreater(3, 0, 0, 3) - 0.05) < 1e-9)
  assert.equal(fisherGreater(0, 3, 3, 0), 1)
  assert.deepEqual(bhQ([0.01, 0.04, 0.03]).map((q) => Math.round(q * 1000) / 1000), [0.03, 0.04, 0.04])
})

const day = (i: number) => `2026-10-${String(i).padStart(2, '0')}`

/** 30 stool days; the meal the evening before carries [groups] chosen per day. */
function fixture(pick: (i: number) => { loose: boolean; groups: string[]; tags?: string[] }) {
  const meals = [], stools = []
  for (let i = 1; i <= 30; i++) {
    const p = pick(i)
    const prev = new Date(Date.parse(day(i) + 'T00:00:00Z') - 86400000).toISOString().slice(0, 10)
    meals.push({ at: `${prev} 19:00:00+00`, groups: p.groups, tags: p.tags ?? [] })
    stools.push({ at: `${day(i)} 08:00:00+00`, bristol: p.loose ? 6 : 4 })
  }
  return { meals, stools }
}

test('a food on the loose days only, with enough days each side, is worth testing', () => {
  // dairy on 15 days, 12 of them loose; no dairy on 15 days, none loose
  const { meals, stools } = fixture((i) => ({ loose: i <= 12, groups: i <= 15 ? ['dairy', 'meat'] : ['meat'] }))
  // only 30 days: 15 each side is under the 20-day gate, so nothing speaks
  const thin = foodTriggers(meals, stools, day(1), day(30))
  assert.equal(thin.stoolDays, 30)
  assert.equal(thin.looseDays, 12)
  assert.equal(thin.lines.find((l) => l.key === 'dairy')!.state, 'not_enough')
  // double the days (another month, same pattern): now dairy is a candidate
  const more = fixture((i) => ({ loose: i <= 12, groups: i <= 15 ? ['dairy', 'meat'] : ['meat'] }))
  const shift = (s: string) => s.replace('2026-10-', '2026-11-').replace('2026-09-30', '2026-10-31')
  const all = foodTriggers(
    [...meals, ...more.meals.map((m) => ({ ...m, at: shift(m.at) }))],
    [...stools, ...more.stools.map((s) => ({ ...s, at: shift(s.at) }))],
    day(1), '2026-11-30')
  const dairy = all.lines.find((l) => l.key === 'dairy')!
  assert.equal(dairy.mode, 'any')
  // the window reaches back 48 h, so the day after the last dairy day still holds it: 16 days a month with it, 24 loose
  assert.deepEqual([dairy.withDays, dairy.withoutDays, dairy.withLoosePct, dairy.withoutLoosePct], [32, 28, 75, 0])
  assert.equal(dairy.state, 'candidate')
  assert.equal(all.lines[0].key, 'dairy')
  // meat is in every window: compared by how many meals carry it, and with one meal a day there is no high side
  const meat = all.lines.find((l) => l.key === 'meat')!
  assert.equal(meat.mode, 'high')
  assert.equal(meat.state, 'not_enough')
  const s = foodTriggerSuggestions(all)
  assert.equal(s[0].id, 'food_trigger')
  assert.match(s[0].text, /dairy went with more loose stools \(75% of days against 0%\)/)
})

test('no loose difference: no signal, and no suggestion', () => {
  const a = fixture((i) => ({ loose: i % 2 === 0, groups: i % 3 === 0 ? ['fruit'] : ['eggs'] }))
  const b = fixture((i) => ({ loose: i % 2 === 0, groups: i % 3 === 0 ? ['fruit'] : ['eggs'] }))
  const shift = (s: string) => s.replace('2026-10-', '2026-11-').replace('2026-09-30', '2026-10-31')
  const r = foodTriggers([...a.meals, ...b.meals.map((m) => ({ ...m, at: shift(m.at) }))], [...a.stools, ...b.stools.map((s) => ({ ...s, at: shift(s.at) }))], day(1), '2026-11-30')
  assert.equal(r.ready, true)
  assert.ok(r.lines.every((l) => l.state !== 'candidate'))
  assert.deepEqual(foodTriggerSuggestions(r), [])
})
