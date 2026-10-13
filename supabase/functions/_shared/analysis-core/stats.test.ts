// Run: node --test supabase/functions/_shared/analysis-core/   (Node 22.6+ strips types; CI uses Node 24)
// Golden numbers below come from an independent Python implementation
// (docs/wellness-project-teardown/tools/replicate_pattern_engine.py), not from this code.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  alignToAxis, autocorr1, benjaminiHochberg, blockBootstrapCi, circularShiftP, dateAxis, effectiveN, gaussian, mean, median,
  minDetectableDifference, mulberry32, pairedLag, pearson, pFromR, ranks, sd, spearman, weekdayDemean, weekdayOf,
} from './stats.ts'

const close = (a: number, b: number, tol = 1e-6) => assert.ok(Math.abs(a - b) <= tol, `${a} vs ${b}`)

test('mulberry32 reproduces the canonical stream', () => {
  const r = mulberry32(1)
  close(r(), 0.6270739405881613, 1e-12)
  close(r(), 0.002735721180215478, 1e-12)
  close(r(), 0.5274470399599522, 1e-12)
})

test('gaussian has mean ~0 and sd ~1', () => {
  const r = mulberry32(5)
  const xs = Array.from({ length: 4000 }, () => gaussian(r))
  assert.ok(Math.abs(mean(xs)) < 0.06)
  assert.ok(Math.abs(sd(xs) - 1) < 0.06)
})

test('pearson, spearman and ranks', () => {
  const x = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
  const y = [2.1, 1.9, 3.5, 3.9, 5.2, 5.8, 7.7, 7.9, 9.4, 9.6]
  close(pearson(x, y), 0.9889610266211953, 1e-12)
  close(spearman(x, x.map((v) => v ** 3)), 1, 1e-12)
  assert.deepEqual(ranks([10, 20, 20, 30]), [1, 2.5, 2.5, 4])
  assert.equal(pearson([1, 1, 1], [1, 2, 3]), 0)
  assert.equal(median([3, 1, 2]), 2)
  assert.equal(median([4, 1, 3, 2]), 2.5)
})

test('t-distribution p-values match the independent implementation', () => {
  close(pFromR(0.253, 61), 0.049155, 1e-5)
  close(pFromR(-0.432, 60), 0.000568, 1e-5)
  close(pFromR(0.1, 30), 0.599048, 1e-5)
  assert.equal(pFromR(0.5, 2), 1)
  assert.equal(pFromR(1, 30), 0)
})

test('Benjamini-Hochberg matches the independent implementation', () => {
  const adj = benjaminiHochberg([0.01, 0.04, 0.03, 0.005, 0.2])
  const gold = [0.025, 0.05, 0.05, 0.025, 0.2]
  adj.forEach((v, i) => close(v, gold[i], 1e-12))
})

test('pairedLag drops nulls and honours lag', () => {
  const c = [1, null, 3, 4, 5]
  const t = [10, 20, null, 40, 50]
  const p0 = pairedLag(c, t, 0)
  assert.deepEqual(p0.x, [1, 4, 5])
  assert.deepEqual(p0.y, [10, 40, 50])
  const p1 = pairedLag(c, t, 1)
  assert.deepEqual(p1.idx, [0, 2, 3]) // cause[0]->target[1]=20, cause[2]->target[3]=40, cause[3]->target[4]=50
  assert.deepEqual(p1.y, [20, 40, 50])
})

test('weekday demeaning removes weekday means and keeps gaps', () => {
  const dates = dateAxis('2026-01-05', '2026-01-25') // 3 full weeks, starts Monday
  const values = dates.map((d) => 50 + weekdayOf(d) * 2)
  const dm = weekdayDemean(dates, values)
  dm.forEach((v) => close(v as number, 0, 1e-9))
  const gappy = values.map((v, i) => (i === 3 ? null : v))
  assert.equal(weekdayDemean(dates, gappy)[3], null)
})

test('alignToAxis leaves absent days null', () => {
  const axis = dateAxis('2026-02-01', '2026-02-04')
  assert.deepEqual(alignToAxis(axis, [{ date: '2026-02-02', value: 5 }, { date: '2026-02-04', value: null }]), [null, 5, null, null])
})

test('autocorrelation and effective n', () => {
  const r = mulberry32(3)
  const white = Array.from({ length: 400 }, () => gaussian(r))
  assert.ok(Math.abs(autocorr1(white)) < 0.12)
  let e = 0
  const ar = Array.from({ length: 400 }, () => (e = 0.7 * e + gaussian(r)))
  assert.ok(autocorr1(ar) > 0.5)
  assert.ok(effectiveN(100, 0.7, 0.7) < 70)
  assert.equal(effectiveN(100, 0, 0), 100)
  assert.equal(effectiveN(100, -0.5, 0.5), 100) // negative product never inflates n
})

test('circular-shift permutation p has the documented floor and detects a real link', () => {
  const r = mulberry32(21)
  const n = 61
  let e = 0
  const cause = Array.from({ length: n }, () => (e = 0.5 * e + gaussian(r)))
  const target = cause.map((c) => 2 * c + gaussian(r) * 0.3)
  const p = circularShiftP(cause, target, 0)
  assert.ok(p <= 1 / 40, `strong link should hit the floor, got ${p}`)
  close(p, 1 / (n - 14 + 1 + 1), 1e-12) // 47 shifts -> floor 1/48
  const indep = Array.from({ length: n }, () => gaussian(r))
  assert.ok(circularShiftP(cause, indep, 0) > 0.05)
})

test('block bootstrap interval is seeded, ordered and covers the estimate', () => {
  const r = mulberry32(8)
  const x = Array.from({ length: 80 }, () => gaussian(r))
  const y = x.map((v) => 0.6 * v + gaussian(r) * 0.8)
  const a = blockBootstrapCi(x, y, mulberry32(1), pearson, 500)
  const b = blockBootstrapCi(x, y, mulberry32(1), pearson, 500)
  assert.deepEqual(a, b)
  assert.ok(a[0] < pearson(x, y) && pearson(x, y) < a[1])
  assert.ok(Number.isNaN(blockBootstrapCi([1, 2], [1, 2], mulberry32(1))[0]))
})

test('minimum detectable difference scales with sigma and arm length', () => {
  close(minDetectableDifference(7, 14), 2.8 * 7 * Math.sqrt(2 / 14), 1e-12)
  assert.ok(minDetectableDifference(7, 28) < minDetectableDifference(7, 14))
})
