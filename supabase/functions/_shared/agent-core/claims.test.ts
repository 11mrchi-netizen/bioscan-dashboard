import assert from 'node:assert/strict'
import { test } from 'node:test'
import { causalClaims, hasClearExperiment, unsupportedClaims } from './claims.ts'

const phrases = (text: string) => causalClaims(text).map((c) => c.phrase.toLowerCase())

test('causal wording is found in any case but capitals', () => {
  assert.deepEqual(phrases('Because of the wine, HRV dropped. Alcohol causes this and lowers your HRV.'), ['because of', 'causes', 'lowers your'])
  assert.deepEqual(phrases('Your trend reads IMPROVING.'), [])
})

test('a hedged suggestion to test is advice, not a claim', () => {
  assert.deepEqual(phrases('An earlier dinner could improve your sleep; a two-week test would show whether it helps.'), [])
  assert.deepEqual(phrases('Try a test to see if it improves your HRV.'), [])
})

test('association wording passes', () => {
  assert.deepEqual(phrases('Drinking nights went with lower HRV the next morning, and were linked with shorter sleep.'), [])
})

test('only a clear experiment allows causal wording', () => {
  const assoc = [{ evidenceClass: 'association', verdict: 'clear' }]
  const exp = [{ items: [{ evidenceClass: 'experiment', verdict: 'clear' }] }]
  const expUnclear = [{ evidenceClass: 'experiment', verdict: 'unclear' }]
  assert.equal(hasClearExperiment(assoc), false)
  assert.equal(hasClearExperiment(exp), true)
  assert.equal(hasClearExperiment(expUnclear), false)
  assert.equal(unsupportedClaims('Magnesium improved your sleep.', assoc).length, 1)
  assert.equal(unsupportedClaims('Magnesium improved your sleep.', exp).length, 0)
})
