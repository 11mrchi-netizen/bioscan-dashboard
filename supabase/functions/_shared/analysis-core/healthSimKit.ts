// Health concerns: the synthetic years behind the simulation gates (healthSimulation.test.ts) and the measurements in
// docs/health-concerns/02-validation.md. Not a test file itself.
import { evaluateExposures, sessionKindFor, sessionLevel } from './healthAssociations.ts'
import { summariseConcern } from './healthEpisodes.ts'
import { buildExposureSeries, exposuresFor } from './healthExposures.ts'
import type { ExposureContext } from './healthExposures.ts'
import { buildFindings } from './healthFindings.ts'
import type { LedgerEntry } from './healthFindings.ts'
import { buildConcernSeries, shiftDate } from './healthSeries.ts'
import type { ObservationRow, SignalRow } from './healthSeries.ts'
import { mulberry32 } from './stats.ts'
import type { Num } from './stats.ts'
import { concern } from './healthTestKit.ts'
import { vocab } from './runHealth.ts'

export const DAYS = 365
const START = '2025-10-22'

export interface Year { ctx: ExposureContext; obs: ObservationRow[]; sigs: SignalRow[] }

export function simulateYear(seed: number, trailEffect: number, baseHazard = 0.02, days = DAYS): Year {
  const rng = mulberry32(seed)
  const dates = Array.from({ length: days }, (_, i) => shiftDate(START, i))
  const sessions: ExposureContext['sessions'] = []
  const trail = dates.map(() => false)
  let id = 1
  for (let i = 0; i < days; i++) {
    if (rng() < 0.55) {
      const isTrail = rng() < 0.3
      const long = rng() < 0.15
      trail[i] = isTrail
      sessions.push({ id: id++, start_time: `${dates[i]}T07:00:00Z`, duration_min: long ? 110 : 50, distance_km: long ? 20 : 9, elevation_gain_m: isTrail ? 400 : 40, type: 'run', avg_hr: 145, max_hr: 172, details: { route_type: isTrail ? 'trail' : 'road', run_type: rng() < 0.2 ? 'intervals' : 'easy' }, date: dates[i] })
    }
    if (rng() < 0.2) sessions.push({ id: id++, start_time: `${dates[i]}T18:00:00Z`, duration_min: 45, distance_km: null, elevation_gain_m: null, type: 'strength', avg_hr: 110, max_hr: 150, details: { exercises: [{ name: 'Squat' }] }, date: dates[i] })
  }
  const obs: ObservationRow[] = []
  const sigs: SignalRow[] = []
  let flareLeft = 0
  let freeRun = 30
  let sev = 0
  for (let i = 0; i < days; i++) {
    if (flareLeft === 0 && freeRun >= 7) {
      const recentTrail = trail[i] || (i > 0 && trail[i - 1]) || (i > 1 && trail[i - 2])
      const h = baseHazard * (recentTrail ? trailEffect : 1)
      if (rng() < h) { flareLeft = 3 + Math.floor(rng() * 6); sev = 3 + Math.floor(rng() * 4) }
    }
    const sick = flareLeft > 0
    if (sick) { flareLeft--; freeRun = 0 } else freeRun++
    if (rng() < (sick ? 0.9 : 0.5)) {
      const o: ObservationRow = {
        id: `s${seed}-${i}`, observed_at: `${dates[i]}T20:00:00Z`, local_date: dates[i], concern_id: 'k', scope: 'concern', symptom_free: !sick,
        recall: 'today', recall_from: null, function_impact: sick ? 1 : 0, change_vs_last: null, onset: false, session_id: null, timing: null,
        supersedes: null, superseded_at: null, retracted: false, source: 'app', created_at: `${dates[i]}T20:00:00Z`,
      }
      obs.push(o)
      if (sick) sigs.push({ observation_id: o.id, concern_id: 'k', symptom: 'sym.pain', region: 'knee', side: 'right', severity: sev })
    }
  }
  const sleep: Num[] = dates.map(() => Math.round((7.2 + (rng() - 0.5) * 2.4) * 10) / 10)
  const hrvBelow: Num[] = dates.map(() => (rng() < 0.2 ? 1 : 0))
  const load: Num[] = dates.map(() => Math.round(rng() * 120))
  const nul = dates.map(() => null)
  const ctx: ExposureContext = {
    dates, sessions, histograms: new Map(), zeppSport: new Map(), library: [{ name: 'Squat', primary_muscles: ['quadriceps'] }],
    robustMax: () => 190, sleepHours: sleep, hrvBelow, hrvDeviation: nul, load, alcohol: nul, stressHigh: nul, timeZone: nul,
  }
  return { ctx, obs, sigs }
}

function slice(ctx: ExposureContext, n: number): ExposureContext {
  const end = ctx.dates[n - 1]
  return {
    ...ctx, dates: ctx.dates.slice(0, n), sessions: ctx.sessions.filter((s) => s.date <= end),
    sleepHours: ctx.sleepHours.slice(0, n), hrvBelow: ctx.hrvBelow.slice(0, n), hrvDeviation: ctx.hrvDeviation.slice(0, n),
    load: ctx.load.slice(0, n), alcohol: ctx.alcohol.slice(0, n), stressHigh: ctx.stressHigh.slice(0, n), timeZone: ctx.timeZone.slice(0, n),
  }
}

/** Re-runs the engine every `step` days with its ledger; returns, per exposure, whether it was ever possible / persistent. */
export function replay(y: Year, step: number): Map<string, { possible: boolean; persistent: boolean }> {
  const c = concern('k', { onset_type: 'gradual' })
  const history: LedgerEntry[] = []
  const out = new Map<string, { possible: boolean; persistent: boolean }>()
  for (let n = 120; n <= y.ctx.dates.length; n += step) {
    const ctx = slice(y.ctx, n)
    const today = ctx.dates[n - 1]
    const cells = buildConcernSeries([c], y.obs.filter((o) => o.local_date <= today), y.sigs, ctx.dates[0], today).series.get('k')!.cells
    const summary = summariseConcern(c, cells, [], today)
    const series = buildExposureSeries(ctx, c, vocab.regions)
    const specs = exposuresFor(c, vocab.regions)
    const muscles = new Set(['quadriceps', 'hamstrings'])
    const ev = evaluateExposures(ctx, cells, summary.episodes, specs, series, muscles, summary.quality)
    const findings = buildFindings({
      concern: c, summary, evaluations: ev.evaluations, cases: ev.cases, referenceOk: ev.referenceOk, dates: ctx.dates, series, droppedCases: ev.dropped, history: [...history],
      sessionsSince: (spec, from) => { const k = sessionKindFor(spec, ctx, muscles); return k ? sessionLevel(ctx, cells, summary.episodes, k, spec.window, from) : null },
    })
    for (const f of findings) {
      if (!f.exposure) continue
      history.push({ question_id: f.questionId, as_of: today, verdict: f.rawVerdict, n: f.primaryN ?? 0 })
      const s = out.get(f.exposure) ?? { possible: false, persistent: false }
      if (f.status === 'possible' || f.status === 'persistent') s.possible = true
      if (f.status === 'persistent') s.persistent = true
      out.set(f.exposure, s)
    }
  }
  return out
}

