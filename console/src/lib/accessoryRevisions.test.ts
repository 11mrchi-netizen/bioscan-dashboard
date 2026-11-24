// Run: node --test "src/lib/*.test.ts"
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { type Accessory, ALL_SESSIONS, DEFAULT_EQUIPMENT } from '../../../supabase/functions/_shared/training-core/index.ts'
import { accessoryRevisions } from './accessoryRevisions.ts'

const curl: Accessory = { exercise: 'Curl', sets: 3, reps: { min: 10, max: 12 }, load: { kind: 'rpe', rpe: 8 }, restS: null, note: null }
const lift = (name: string, role: string) => ({ type: 'lift', exercise: name, role, slot: 1, sets: 3, reps: 5, load: { kind: 'unresolved' } })
const row = (id: number, title: string, prescription: unknown[], work_kind = 'progression') => ({ id, title, work_kind, prescription })
const idOf = (t: string) => (t.endsWith('D1') ? 'd1' : 'd2')

test('accessories go after the last lift; the session\'s own lines stay; a session that does not change is not revised', () => {
  const main = [lift('Squat', 'main'), lift('Press', 'main'), { type: 'note', text: 'warm up well' }]
  const rows = [row(1, 'Strength: D1', main), row(2, 'Strength: D2', main)]
  const rev = accessoryRevisions(rows, idOf, { d1: [curl] }, DEFAULT_EQUIPMENT, () => null)
  assert.deepEqual(rev.map((r) => r.id), [1]) // only D1 has accessory work
  const after = rev[0].after
  assert.deepEqual(after.map((e) => e.exercise ?? e.type), ['Squat', 'Press', 'Curl', 'note'])
  assert.equal(after[2].role, 'accessory')
  assert.deepEqual(after.slice(0, 2), main.slice(0, 2))
})

test('the accessories for every session reach all, and removing them takes the lines away again', () => {
  const withCurl = [lift('Squat', 'main'), lift('Curl', 'accessory')]
  const all = accessoryRevisions([row(1, 'S: D1', [lift('Squat', 'main')]), row(2, 'S: D2', [lift('Squat', 'main')])], idOf, { [ALL_SESSIONS]: [curl] }, DEFAULT_EQUIPMENT, () => null)
  assert.equal(all.length, 2)
  const none = accessoryRevisions([row(1, 'S: D1', withCurl)], idOf, {}, DEFAULT_EQUIPMENT, () => null)
  assert.deepEqual(none[0].after.map((e) => e.exercise), ['Squat'])
})

test('a deload week gets fewer sets, and an unchanged prescription gives no revision', () => {
  const base = [lift('Squat', 'main')]
  const normal = accessoryRevisions([row(1, 'S: D1', base)], idOf, { d1: [curl] }, DEFAULT_EQUIPMENT, () => null)[0].after
  const deload = accessoryRevisions([row(1, 'S: D1', base, 'deload')], idOf, { d1: [curl] }, DEFAULT_EQUIPMENT, () => null)[0].after
  assert.ok((deload[1].sets as number) < (normal[1].sets as number))
  assert.deepEqual(accessoryRevisions([row(1, 'S: D1', normal)], idOf, { d1: [curl] }, DEFAULT_EQUIPMENT, () => null), [])
})
