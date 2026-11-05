import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ENGINE_GROUPS, enginesFor, GROUP_ORDER, needsFrame, parseGroup, timedEngine } from './runGroups.ts'

test('every engine belongs to exactly one group and `all` is their union in order', () => {
  const all = enginesFor('all')
  assert.equal(new Set(all).size, all.length, 'no engine appears twice')
  assert.deepEqual(all, GROUP_ORDER.flatMap((g) => [...ENGINE_GROUPS[g]]))
  for (const g of GROUP_ORDER) assert.ok(ENGINE_GROUPS[g].length > 0, `${g} is not empty`)
})

test('every engine the run publishes is placed in a group', () => {
  const published = ['breaks', 'integrity', 'relationships', 'typicality', 'expectedBands', 'evidence', 'experiments', 'interventionResponses', 'interventionIts', 'eventEvidence', 'fitnessFatigue', 'health']
  assert.deepEqual([...enginesFor('all')].sort(), [...published].sort())
})

test('breaks are written before the groups that restart at them', () => {
  assert.equal(GROUP_ORDER[0], 'core')
  assert.ok(ENGINE_GROUPS.core.includes('breaks'))
  assert.ok(GROUP_ORDER.indexOf('bands') > GROUP_ORDER.indexOf('core'))
})

test('parseGroup: missing means all, unknown is rejected', () => {
  assert.equal(parseGroup(undefined), 'all')
  assert.equal(parseGroup(null), 'all')
  assert.equal(parseGroup('all'), 'all')
  assert.equal(parseGroup('health'), 'health')
  assert.equal(parseGroup('nope'), null)
  assert.equal(parseGroup(3), null)
  assert.equal(parseGroup('constructor'), null)
})

test('only a group made entirely of own-read engines skips the frame', () => {
  assert.equal(needsFrame('core'), true, 'relationships needs the frame')
  assert.equal(needsFrame('health'), true)
  assert.equal(needsFrame('all'), true)
})

test('timedEngine records the time and turns a throw into that engine\'s error', async () => {
  const timings: Record<string, number> = {}
  let t = 0
  const clock = () => (t += 5)
  assert.deepEqual(await timedEngine('u', 'a', timings, async () => ({ ok: 1 }), clock), { ok: 1 })
  assert.deepEqual(await timedEngine('u', 'b', timings, async () => { throw new Error('boom') }, clock), { userId: 'u', error: 'Error: boom' })
  assert.deepEqual(timings, { a: 5, b: 5 })
})
