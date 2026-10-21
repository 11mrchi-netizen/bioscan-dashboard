import assert from 'node:assert/strict'
import { test } from 'node:test'
import { deriveStatus, windowWords } from './healthFindings.ts'
import type { LedgerEntry } from './healthFindings.ts'
import { readTrial } from './healthTrials.ts'
import type { TrialInput } from './healthTrials.ts'
import { measureEpisodes } from './healthEpisodes.ts'
import { buildConcernSeries, shiftDate } from './healthSeries.ts'
import { concern, obs, sig } from './healthTestKit.ts'
import type { ExposureContext } from './healthExposures.ts'

const L = (as_of: string, verdict: string, n: number): LedgerEntry => ({ question_id: 'q', as_of, verdict, n })

test('the ladder: a first pass is a hint; it is shown once it repeats 14+ days later with more data', () => {
  assert.equal(deriveStatus('insufficient', 2, [], '2026-03-01').status, 'insufficient')
  assert.equal(deriveStatus('unclear', 8, [], '2026-03-01').status, 'no_pattern')
  assert.equal(deriveStatus('possible', 8, [], '2026-03-01').status, 'emerging')
  // repeated too soon, or without new data: still a hint
  assert.equal(deriveStatus('possible', 9, [L('2026-02-20', 'possible', 8)], '2026-03-01').status, 'emerging')
  assert.equal(deriveStatus('possible', 8, [L('2026-02-01', 'possible', 8)], '2026-03-01').status, 'emerging')
  // a failed re-check in between restarts the count
  assert.equal(deriveStatus('possible', 10, [L('2026-02-01', 'possible', 8), L('2026-02-10', 'unclear', 9)], '2026-03-01').status, 'emerging')
  const shown = deriveStatus('possible', 9, [L('2026-02-01', 'possible', 8)], '2026-03-01')
  assert.equal(shown.status, 'possible')
  assert.equal(shown.firstPossible, '2026-03-01')
  assert.equal(deriveStatus('persistent', 12, [L('2026-02-01', 'possible', 8), L('2026-03-01', 'possible', 9)], '2026-04-01').status, 'persistent')
})

test('a shown finding that fails one re-check with new data weakens; two fade it; no new data never fades', () => {
  const hist = [L('2026-01-01', 'possible', 6), L('2026-02-01', 'possible', 8)]
  const once = deriveStatus('unclear', 9, hist, '2026-03-01')
  assert.equal(once.status, 'possible')
  assert.equal(once.weakening, true)
  assert.equal(once.firstPossible, '2026-02-01')
  assert.equal(deriveStatus('unclear', 10, [...hist, L('2026-03-01', 'unclear', 9)], '2026-03-08').status, 'faded')
  assert.equal(deriveStatus('unclear', 8, [...hist, L('2026-03-01', 'unclear', 8)], '2026-03-08').status, 'possible')
  // after fading, it must repeat again before it is shown again
  const faded = [...hist, L('2026-03-01', 'unclear', 9), L('2026-03-08', 'unclear', 10)]
  assert.equal(deriveStatus('possible', 11, faded, '2026-04-01').status, 'emerging')
  assert.equal(deriveStatus('possible', 12, [...faded, L('2026-04-01', 'possible', 11)], '2026-04-20').status, 'possible')
})

test('window words', () => {
  assert.equal(windowWords([0, 0]), 'on the day')
  assert.equal(windowWords([0, 1]), 'on the day or the day before')
  assert.equal(windowWords([0, 2]), 'on the day or the 2 days before')
})

// ---- trials ----

function trialSetup(preFlares: number[], postFlares: number[], days = 120, startDay = 60) {
  const start = '2026-03-02'
  const dates = Array.from({ length: days }, (_, i) => shiftDate(start, i))
  const c = concern('k')
  const o = []
  const s = []
  const flares = [...preFlares, ...postFlares]
  for (let i = 0; i < days; i++) {
    const sick = flares.includes(i) // one-day flare-ups; 7 symptom-free days end each one
    const row = obs(0, { local_date: dates[i], observed_at: `${dates[i]}T20:00:00Z`, symptom_free: !sick })
    o.push(row)
    if (sick) s.push(sig(row, 5))
  }
  const cells = buildConcernSeries([c], o, s, dates[0], dates[days - 1]).series.get('k')!.cells
  const eps = measureEpisodes(c, cells, [], dates[days - 1])
  const nul = dates.map(() => null)
  const ctx: ExposureContext = {
    dates, sessions: dates.filter((_, i) => i % 2 === 0).map((d, i) => ({ id: i + 1, start_time: `${d}T07:00:00Z`, duration_min: 60, distance_km: 10, elevation_gain_m: 0, type: 'run', avg_hr: null, max_hr: null, details: {}, date: d })),
    histograms: new Map(), zeppSport: new Map(), library: [], robustMax: () => null, sleepHours: nul, hrvBelow: nul, hrvDeviation: nul,
    load: nul, alcohol: nul, stressHigh: nul, timeZone: nul,
  }
  const input = (today: string): TrialInput => ({
    interventionId: 't', name: 'Fewer trail runs', hypothesis: 'h', outcome: 'flare_rate_per_exposure', exposureUnit: 'run_km', preDays: 56,
    startedAt: dates[startDay], endedAt: null, evaluateOn: shiftDate(dates[startDay], 56), today, adherence: [], concurrent: [],
  })
  return { dates, cells, eps, ctx, input }
}

test('a trial carries no verdict before its evaluation date', () => {
  const t = trialSetup([5, 20, 35, 50], [70])
  const r = readTrial(t.input(t.dates[90]), t.cells, t.eps, t.ctx)
  assert.equal(r.state, 'interim')
  assert.equal(r.word, null)
  assert.equal(r.effect, null)
})

test('a trial reads flare-ups per 100 km before and since, with its interval and the usual caveats', () => {
  const t = trialSetup([4, 12, 20, 28, 36, 44, 52], [])
  const r = readTrial(t.input(t.dates[119]), t.cells, t.eps, t.ctx)
  assert.equal(r.state, 'evaluated')
  assert.equal(r.pre.flares, 7)
  assert.equal(r.post.flares, 0)
  assert.equal(r.effect?.value, 0)
  assert.equal(r.word, 'lower')
  assert.equal(r.favourable, true)
  assert.ok(r.explanations.some((x) => x.includes('not what the change did')))
  assert.ok(r.explanations.some((x) => x.includes('No adherence logged')))
  const same = trialSetup([10, 40], [75, 100])
  assert.equal(readTrial(same.input(same.dates[119]), same.cells, same.eps, same.ctx).word, 'no_clear_change')
})

test('a trial without enough check-ins is insufficient, not a verdict', () => {
  const t = trialSetup([3, 10], [80])
  const sparse = t.cells.map((c, i) => (i % 3 === 0 ? c : { ...c, observed: false, severity: null, present: false }))
  const r = readTrial(t.input(t.dates[119]), sparse, t.eps, t.ctx)
  assert.equal(r.state, 'insufficient')
  assert.equal(r.word, 'insufficient')
})

test('findings never use cause words, at any status (contract R5)', async () => {
  const { simulateYear } = await import('./healthSimKit.ts')
  const { evaluateExposures } = await import('./healthAssociations.ts')
  const { summariseConcern } = await import('./healthEpisodes.ts')
  const { buildExposureSeries, exposuresFor } = await import('./healthExposures.ts')
  const { buildFindings } = await import('./healthFindings.ts')
  const { vocab } = await import('./runHealth.ts')
  const CAUSE = /\b(causes?|caused|causing|triggers?|triggered|leads? to|led to|prevents?|lowers|raises|improves|worsens)\b/i
  for (const seed of [9010, 9011, 9012]) {
    const y = simulateYear(seed, 6)
    const c = concern('k')
    const today = y.ctx.dates[y.ctx.dates.length - 1]
    const cells = buildConcernSeries([c], y.obs, y.sigs, y.ctx.dates[0], today).series.get('k')!.cells
    const summary = summariseConcern(c, cells, [], today)
    const series = buildExposureSeries(y.ctx, c, vocab.regions)
    const ev = evaluateExposures(y.ctx, cells, summary.episodes, exposuresFor(c, vocab.regions), series, new Set(['quadriceps']), summary.quality)
    const history = [{ question_id: 'health:k:trail_run', as_of: '2026-01-01', verdict: 'possible', n: 3 }, { question_id: 'health:k:trail_run', as_of: '2026-02-01', verdict: 'possible', n: 4 }]
    const fs = buildFindings({ concern: c, summary, evaluations: ev.evaluations, cases: ev.cases, referenceOk: ev.referenceOk, dates: y.ctx.dates, series, droppedCases: ev.dropped, history })
    for (const f of fs) {
      const text = [f.what, f.evidence.text, ...f.explanations, f.uncertainty, f.nextStep.text].join(' ')
      assert.ok(!CAUSE.test(text), `${f.questionId} (${f.status}): ${text.match(CAUSE)?.[0]}`)
      for (const k of ['what', 'uncertainty']) assert.ok((f as unknown as Record<string, string>)[k].length > 0)
    }
  }
})
