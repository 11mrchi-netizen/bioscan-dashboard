import assert from 'node:assert/strict'
import { test } from 'node:test'
import { triage } from './redFlags.ts'

const codes = (q: string) => triage(q)?.codes ?? []

test('urgent symptoms stop the model and give the local emergency number', () => {
  const t = triage('I have chest pain when I climb stairs, is my resting HR related?')
  assert.ok(t)
  assert.equal(t!.level, 'urgent')
  assert.equal(t!.callModel, false)
  assert.match(t!.text, /119/)
  assert.deepEqual(codes('I fainted after the interval session'), ['fainting'])
  assert.deepEqual(codes("my face is drooping and I've got slurred speech"), ['stroke'])
  assert.match(triage('sometimes I want to end my life')!.text, /1925/)
})

test('clinician symptoms put fixed text first and let the agent describe the data', () => {
  const t = triage('I had a fever of 39.4 last night, why is my HRV low?')
  assert.equal(t!.level, 'clinician')
  assert.equal(t!.callModel, true)
  assert.deepEqual(codes('my heart is racing at rest since yesterday'), ['heart_rhythm'])
  assert.deepEqual(codes('there was blood in my stool'), ['bleeding'])
})

test('urgent comes first when several match', () => {
  assert.deepEqual(codes('palpitations and then I passed out'), ['fainting', 'heart_rhythm'])
})

test('an explicit negation right before the symptom clears it; "but" does not', () => {
  assert.equal(triage('no chest pain, just tired legs'), null)
  assert.equal(triage('I did not faint, I just felt dizzy'), null)
  assert.deepEqual(codes('no fever but chest pain since this morning'), ['chest'])
})

test('data words that sound like symptoms are not flagged', () => {
  assert.equal(triage('my HRV collapsed this week, why?'), null)
  assert.equal(triage('is a temperature of 38 normal after a long run?'), null)
  assert.equal(triage('why is my resting heart rate higher than usual?'), null)
})
