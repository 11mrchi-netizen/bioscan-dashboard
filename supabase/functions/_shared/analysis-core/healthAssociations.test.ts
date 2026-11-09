import assert from 'node:assert/strict'
import { test } from 'node:test'
import { caseCrossover, crossoverFrame, fisherGreater, poissonBinomialUpper, sessionLevel } from './healthAssociations.ts'
import { measureEpisodes } from './healthEpisodes.ts'
import { buildExposureSeries, inWindow, lookupStats, movementKey, musclesOf } from './healthExposures.ts'
import type { ExposureContext } from './healthExposures.ts'
import { buildConcernSeries, shiftDate } from './healthSeries.ts'
import { vocab } from './runHealth.ts'
import { concern, obs, sig } from './healthTestKit.ts'

test('Fisher exact one-sided p matches hand-computed values', () => {
  // [[3,1],[1,3]]: P(X>=3) with margins 4/4/4/4 = (16 + 1) / 70
  assert.ok(Math.abs(fisherGreater(3, 1, 1, 3) - 17 / 70) < 1e-12)
  assert.ok(Math.abs(fisherGreater(0, 5, 5, 0) - 1) < 1e-9)
})

test('Poisson-binomial upper tail equals the binomial when all probabilities are equal', () => {
  // X ~ Bin(4, 0.5): P(X >= 3) = 5/16
  assert.ok(Math.abs(poissonBinomialUpper([0.5, 0.5, 0.5, 0.5], 3) - 5 / 16) < 1e-12)
  assert.ok(Math.abs(poissonBinomialUpper([0.2, 0.9], 2) - 0.18) < 1e-12)
})

test('the exposure window reads 1 on any exposed day, 0 only when every day is known', () => {
  assert.equal(inWindow([0, 1, 0], 2, [0, 2]), 1)
  assert.equal(inWindow([0, 0, 0], 2, [0, 2]), 0)
  assert.equal(inWindow([null, 0, 0], 2, [0, 2]), null)
  assert.equal(inWindow([0, 0, 0], 1, [0, 2]), null) // runs off the start of the data
})

test('the movement key matches the Android normaliser on its own examples', () => {
  assert.equal(movementKey('Standing Barbell Press'), movementKey('Overhead Press'))
  assert.equal(movementKey('Weighted Pull Ups'), movementKey('Pull Up (Weighted)'))
  assert.equal(musclesOf('Barbell Squats', [{ name: 'Squat', primary_muscles: ['quadriceps'] }])?.[0], 'quadriceps')
  assert.equal(musclesOf('Unknown thing', [{ name: 'Squat', primary_muscles: ['quadriceps'] }]), null)
})

function ctxFor(days: number, runDays: number[], trailDays: number[]): ExposureContext {
  const start = '2026-01-05'
  const dates = Array.from({ length: days }, (_, i) => shiftDate(start, i))
  const sessions = runDays.map((d, i) => ({
    id: i + 1, start_time: `${dates[d]}T07:00:00Z`, duration_min: 50, distance_km: 10, elevation_gain_m: 50, type: 'run', avg_hr: 140, max_hr: 170,
    details: { route_type: trailDays.includes(d) ? 'trail' : 'road', run_type: 'easy' }, date: dates[d],
  }))
  const nul = dates.map(() => null)
  return {
    dates, sessions, histograms: new Map(), zeppSport: new Map(), library: [], robustMax: () => 190,
    sleepHours: dates.map(() => 7.5), hrvBelow: nul, hrvDeviation: nul, load: dates.map(() => 50), alcohol: nul, stressHigh: nul,
    timeZone: nul,
  }
}

test('case-crossover counts cases, references and the base rate; a trail run before every flare-up shows', () => {
  const days = 140
  // runs every other day; trail runs every 6th day; flare-ups start the day after six trail runs
  const runDays = Array.from({ length: days }, (_, i) => i).filter((i) => i % 2 === 0)
  const trailDays = runDays.filter((i) => i % 6 === 0)
  const ctx = ctxFor(days, runDays, trailDays)
  const c = concern('k')
  const flareStarts = [13, 37, 61, 85, 109, 127].map((x) => x)
  const o = []
  const s = []
  for (let i = 0; i < days; i++) {
    const inFlare = flareStarts.some((f) => i >= f && i < f + 3)
    const row = obs(0, { local_date: ctx.dates[i], observed_at: `${ctx.dates[i]}T20:00:00Z`, symptom_free: !inFlare })
    o.push(row)
    if (inFlare) s.push(sig(row, 5))
  }
  const cells = buildConcernSeries([c], o, s, ctx.dates[0], ctx.dates[days - 1]).series.get('k')!.cells
  const eps = measureEpisodes(c, cells, [], ctx.dates[days - 1])
  assert.equal(eps.length, 6)
  const { cases, referenceOk } = crossoverFrame(cells, eps)
  assert.equal(cases.length, 6)
  const series = buildExposureSeries(ctx, c, vocab.regions)
  const r = caseCrossover(ctx.dates, series.trail_run, cases, referenceOk, [0, 2])
  assert.equal(r.cases, 6)
  assert.equal(r.exposedCases, 6)
  assert.ok(r.referenceDays >= 20)
  assert.ok(r.referenceShare !== null && r.referenceShare > 0 && r.referenceShare < 0.8)
  assert.ok(r.p !== null && r.p < 0.05, `p = ${r.p}`)
  assert.ok(r.expectedExposed < 6)
  // any run: in a 3-day window nearly every day has one, so it cannot stand out
  const any = caseCrossover(ctx.dates, series.any_run, cases, referenceOk, [0, 2])
  assert.ok(any.p === null || any.p > 0.2, `any-run p = ${any.p}`)
})

test('session level: symptoms after trail runs against other runs, only from symptom-free days with follow-up', () => {
  const days = 60
  const runDays = Array.from({ length: days }, (_, i) => i).filter((i) => i % 3 === 0)
  const trailDays = runDays.filter((i) => i % 9 === 0)
  const ctx = ctxFor(days, runDays, trailDays)
  const c = concern('k')
  const o = []
  const s = []
  for (let i = 0; i < days; i++) {
    const sick = trailDays.includes(i - 1)
    const row = obs(0, { local_date: ctx.dates[i], observed_at: `${ctx.dates[i]}T20:00:00Z`, symptom_free: !sick })
    o.push(row)
    if (sick) s.push(sig(row, 4))
  }
  const cells = buildConcernSeries([c], o, s, ctx.dates[0], ctx.dates[days - 1]).series.get('k')!.cells
  const eps = measureEpisodes(c, cells, [], ctx.dates[days - 1])
  const r = sessionLevel(ctx, cells, eps, { sport: (x) => x.type === 'run', kind: (x) => x.details?.route_type === 'trail' }, [0, 2])
  assert.ok(r.exposedSessions >= 5)
  assert.equal(r.exposedFollowed, r.exposedSessions)
  assert.ok(r.p !== null && r.p < 0.01)
})

// The indexed lookup must return exactly what the original linear scan did (the scan recomputed movementKey for every
// library row on every miss, which made the nightly health run exceed the Edge CPU limit).
function musclesOfLinear(name: string, library: { name: string; primary_muscles: string[] | null }[]): string[] | null {
  const exact = library.find((l) => l.name.toLowerCase() === name.toLowerCase())
  if (exact) return exact.primary_muscles ?? []
  const key = movementKey(name)
  if (!key) return null
  const byKey = library.find((l) => movementKey(l.name) === key)
  return byKey ? byKey.primary_muscles ?? [] : null
}

test('musclesOf with the library index gives the linear scan\'s answer for hits, misses, duplicates and odd names', () => {
  const library = [
    { name: 'Back Squat', primary_muscles: ['quadriceps', 'glutes'] },
    { name: 'Squat (BB)', primary_muscles: ['quadriceps'] },
    { name: 'Lunge (BB)', primary_muscles: ['hamstrings'] }, // same movement key as the next row: the first must win
    { name: 'Lunge (DB)', primary_muscles: ['calves'] },
    { name: 'Bench Press', primary_muscles: ['chest'] },
    { name: 'bench press', primary_muscles: ['triceps'] }, // same lower-cased name: the first row must win
    { name: 'Pull-ups', primary_muscles: ['lats'] },
    { name: 'Plank', primary_muscles: null },
    { name: 'Overhead Press', primary_muscles: ['shoulders'] },
    { name: '(BB)', primary_muscles: ['nothing'] }, // movement key is empty
  ]
  const names = [
    'Back Squat', 'back squat', 'BACK SQUAT', 'Barbell Squats', 'Squat', 'squats', 'Bench Press', 'BENCH PRESS', 'Chest Press', 'chest press',
    'Pullups', 'Pull-ups', 'pull ups', 'chin ups', 'Plank', 'plank', 'Standing Barbell Press', 'Military Press', 'Shoulder Press',
    'Lunge', 'lunges', 'Unknown thing', '', '   ', '(BB)', 'the', 'Bent Over Row', 'Deadlift', 'inclined bench press',
  ]
  for (const n of names) assert.deepEqual(musclesOf(n, library), musclesOfLinear(n, library), JSON.stringify(n))
  assert.deepEqual(musclesOf('Squat', library), ['quadriceps'], 'no row is named Squat, so the first row with its key wins')
  assert.deepEqual(musclesOf('Lunge', library), ['hamstrings'], 'the first of two rows with the same key wins')
  assert.deepEqual(musclesOf('bench press', library), ['chest'], 'the first row with the name wins')
  assert.equal(musclesOf('Plank', library)?.length, 0, 'a known exercise without muscles is [] not null')
})

test('musclesOf re-indexes a library that changed between calls', () => {
  const library = [{ name: 'Squat', primary_muscles: ['quadriceps'] }]
  assert.equal(musclesOf('Lunge', library), null)
  library.push({ name: 'Lunge', primary_muscles: ['glutes'] })
  assert.deepEqual(musclesOf('Lunge', library), ['glutes'])
})

test('musclesOf computes each library movement key once, not once per lookup', () => {
  const library = Array.from({ length: 300 }, (_, i) => ({ name: `Exercise number ${i}`, primary_muscles: ['x'] }))
  musclesOf('warm up', library) // builds the index
  const before = lookupStats.movementKey
  for (let i = 0; i < 100; i++) musclesOf(`not in the library ${i}`, library)
  assert.equal(lookupStats.movementKey - before, 100, 'one key for the looked-up name, none for the library rows')
})
