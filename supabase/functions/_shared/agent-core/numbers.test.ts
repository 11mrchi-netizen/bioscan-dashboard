import assert from 'node:assert/strict'
import { test } from 'node:test'
import { dropSentences, extractNumbers, groundingFrom, sentences, ungroundedNumbers } from './numbers.ts'

const raw = (text: string) => extractNumbers(text).map((t) => t.raw)

test('numbers, dates, times and number words are read; names with digits are not numbers', () => {
  assert.deepEqual(raw('HRV 38 ms on 2026-10-27 at 7:30, band 44–61; VO2max 52, vitamin D3, omega-3, R8'), ['38', '2026-10-27', '07:30', '44', '61', '52'])
  assert.deepEqual(raw('seven of the last twenty five nights, 1,234 steps'), ['seven', 'twenty five', '1,234'])
  assert.deepEqual(raw('the one before'), [], '"one" is a pronoun more often than a count')
})

test('a hyphen between numbers is a range, a leading minus is a sign', () => {
  const ts = extractNumbers('3-5 nights, a change of -4.2 ms')
  assert.deepEqual(ts.map((t) => (t.kind === 'number' ? t.value : null)), [3, 5, -4.2])
})

test('rounding at the stated precision, either sign, and fractions as percentages are grounded', () => {
  const g = groundingFrom({ hrv: 38.4, change: -4.17, share: 0.12, band: '44–61 ms', day: '2026-10-27', bed: '23:05' })
  assert.deepEqual(ungroundedNumbers('38 ms, down 4.2, 12% of nights, 44 to 61, on 2026-10-27 at 23:05', g), [])
  assert.deepEqual(ungroundedNumbers('38.4, or 38.40 written out', g), [])
})

test('a number the model worked out itself is ungrounded', () => {
  const g = groundingFrom({ this_week: 41.2, last_week: 45.6 })
  // the difference (4.4) and a made-up average are not in the results
  assert.deepEqual(ungroundedNumbers('HRV averaged 41.2 ms, 4.4 ms below last week; about 43 over both.', g).map((t) => t.raw), ['4.4', '43'])
})

test('dates and times must appear as such in the sources', () => {
  const g = groundingFrom({ as_of: '2026-10-27' })
  assert.deepEqual(ungroundedNumbers('on 2026-10-26', g).map((t) => t.raw), ['2026-10-26'])
  assert.deepEqual(ungroundedNumbers('at 06:40', g).map((t) => t.raw), ['06:40'])
})

test('sentences split on end punctuation, not on decimals or vs.', () => {
  assert.deepEqual(sentences('HRV was 38.4 ms. Low vs. usual! Next\nLast one.').map((s) => s.text), ['HRV was 38.4 ms.', 'Low vs. usual!', 'Next', 'Last one.'])
  assert.equal(dropSentences('A is 1. B is 2. C is 3.', new Set([1])), 'A is 1. C is 3.')
})
